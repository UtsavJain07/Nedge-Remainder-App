package app.nudge.core.domain.usecase

import app.nudge.core.domain.reminder.ReminderScheduler
import app.nudge.core.domain.repository.SettingsRepository
import app.nudge.core.domain.repository.TaskRepository
import app.nudge.core.model.MoveOperation
import app.nudge.core.model.Patch
import app.nudge.core.model.Task
import app.nudge.core.model.TaskDraft
import app.nudge.core.model.TaskPatch
import app.nudge.core.model.UndoSnapshot
import java.time.Instant
import javax.inject.Inject

/** FR-10, FR-11, FR-13. Creates a task, remembers the list, schedules its reminder (US-1). */
class CreateTaskUseCase @Inject constructor(
    private val tasks: TaskRepository,
    private val settings: SettingsRepository,
    private val scheduler: ReminderScheduler,
) {
    suspend operator fun invoke(draft: TaskDraft): Task {
        val task = tasks.create(draft)
        if (draft.parentId == null) settings.update { it.copy(lastUsedListId = draft.listId) }
        scheduler.onTasksChanged(listOf(task.id))
        return task
    }
}

/** FR-14 autosave. */
class UpdateTaskUseCase @Inject constructor(
    private val tasks: TaskRepository,
    private val scheduler: ReminderScheduler,
) {
    suspend operator fun invoke(id: String, patch: TaskPatch): Task {
        val task = tasks.update(id, patch)
        scheduler.onTasksChanged(listOf(id))
        return task
    }
}

/** Result of a completion toggle; [allSubtasksDoneParent] drives the FR-36 prompt. */
data class ToggleResult(
    val snapshot: UndoSnapshot,
    val completed: Boolean,
    val task: Task,
    val allSubtasksDoneParent: Task? = null,
)

/** FR-30, FR-33, FR-34, FR-36. */
class ToggleCompleteUseCase @Inject constructor(
    private val tasks: TaskRepository,
    private val scheduler: ReminderScheduler,
) {
    /** Toggles, or forces [completed] when given (notification "Done"). Returns null if the task is gone. */
    suspend operator fun invoke(id: String, completed: Boolean? = null, progressBefore: Int? = null): ToggleResult? {
        val task = tasks.get(id)?.takeIf { it.deletedAt == null } ?: return null
        val target = completed ?: !task.isCompleted
        val snapshot = tasks.setCompleted(id, target, progressBefore)
        scheduler.onTasksChanged(snapshot.affectedTaskIds + id)
        var prompt: Task? = null
        val parentId = task.parentId
        if (target && parentId != null) {
            val parent = tasks.get(parentId)
            if (parent != null && !parent.isCompleted && parent.deletedAt == null) {
                val siblings = tasks.children(parent.id)
                if (siblings.isNotEmpty() && siblings.all { it.isCompleted }) prompt = parent
            }
        }
        return ToggleResult(snapshot, target, task, prompt)
    }
}

/** FR-40, FR-43: releasing the slider at 100 completes the task. */
class SetProgressUseCase @Inject constructor(
    private val tasks: TaskRepository,
    private val toggle: ToggleCompleteUseCase,
) {
    /** Returns a snapshot when the task was completed (for Undo), else null. */
    suspend operator fun invoke(id: String, value: Int, valueBefore: Int): UndoSnapshot? {
        return if (value >= 100) {
            toggle(id, completed = true, progressBefore = valueBefore)?.snapshot
        } else {
            tasks.update(id, TaskPatch(progress = value))
            null
        }
    }
}

/** FR-15. */
class DeleteTaskUseCase @Inject constructor(
    private val tasks: TaskRepository,
    private val scheduler: ReminderScheduler,
) {
    suspend operator fun invoke(id: String): UndoSnapshot {
        val snapshot = tasks.softDelete(id)
        scheduler.onTasksChanged(snapshot.affectedTaskIds)
        return snapshot
    }
}

/** FR-37. */
class DeleteCompletedUseCase @Inject constructor(
    private val tasks: TaskRepository,
    private val scheduler: ReminderScheduler,
) {
    suspend operator fun invoke(listId: String): UndoSnapshot {
        val snapshot = tasks.deleteCompleted(listId)
        scheduler.onTasksChanged(snapshot.affectedTaskIds)
        return snapshot
    }
}

/** FR-16, FR-22..FR-26 (08 §5.2). */
class MoveTaskUseCase @Inject constructor(
    private val tasks: TaskRepository,
    private val scheduler: ReminderScheduler,
) {
    suspend operator fun invoke(op: MoveOperation): UndoSnapshot {
        val snapshot = tasks.move(op)
        // Reminders don't depend on order; list changes affect notification content only.
        if (op is MoveOperation.ToList) scheduler.onTasksChanged(snapshot.affectedTaskIds)
        return snapshot
    }
}

/** FR-67 (07 §8). `until == null` cancels the snooze. */
class SnoozeUseCase @Inject constructor(
    private val tasks: TaskRepository,
    private val scheduler: ReminderScheduler,
) {
    suspend operator fun invoke(id: String, until: Instant?): Task? {
        if (tasks.get(id)?.takeIf { it.deletedAt == null && !it.isCompleted } == null) return null
        val task = tasks.update(id, TaskPatch(snoozedUntil = Patch.Set(until)))
        scheduler.onTasksChanged(listOf(id))
        return task
    }
}
