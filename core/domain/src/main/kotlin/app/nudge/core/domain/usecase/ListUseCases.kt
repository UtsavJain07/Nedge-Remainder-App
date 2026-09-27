package app.nudge.core.domain.usecase

import app.nudge.core.domain.reminder.ReminderScheduler
import app.nudge.core.domain.repository.EmojiChange
import app.nudge.core.domain.repository.ListRepository
import app.nudge.core.domain.repository.TaskRepository
import app.nudge.core.domain.rules.TaskRules
import app.nudge.core.model.ListColors
import app.nudge.core.model.TaskList
import app.nudge.core.model.UndoSnapshot
import javax.inject.Inject

/** FR-01, FR-02. */
class CreateListUseCase @Inject constructor(private val lists: ListRepository) {
    suspend operator fun invoke(name: String, color: Int?, emoji: String?): TaskList {
        val validName = TaskRules.validateListName(name)
        val chosen = color ?: ListColors.nextUnused(lists.lists().map { it.colorArgb })
        return lists.create(validName, chosen, emoji?.takeIf { it.isNotBlank() })
    }
}

/** FR-03. */
class UpdateListUseCase @Inject constructor(private val lists: ListRepository) {
    suspend operator fun invoke(id: String, name: String?, color: Int?, emoji: EmojiChange) {
        lists.update(id, name?.let(TaskRules::validateListName), color, emoji)
    }
}

/** FR-03, FR-05: soft-deletes a list with its tasks; the last list can't be deleted. */
class DeleteListUseCase @Inject constructor(
    private val lists: ListRepository,
    private val scheduler: ReminderScheduler,
) {
    suspend operator fun invoke(id: String): UndoSnapshot {
        val snapshot = lists.softDelete(id)
        scheduler.onTasksChanged(snapshot.affectedTaskIds)
        return snapshot
    }
}

/** Restores any snapshot (task or list mutation) and re-arms reminders (06 §6). */
class RestoreSnapshotUseCase @Inject constructor(
    private val tasks: TaskRepository,
    private val lists: ListRepository,
    private val scheduler: ReminderScheduler,
) {
    suspend operator fun invoke(snapshot: UndoSnapshot) {
        if (snapshot.listsBefore.isNotEmpty()) lists.restore(snapshot) else tasks.restore(snapshot)
        scheduler.onTasksChanged(snapshot.affectedTaskIds)
    }
}

/** FR-04: move list [id] so it sits right before [beforeTargetId] (or last when null) in Home order. */
class ReorderListsUseCase @Inject constructor(private val lists: ListRepository) {
    suspend operator fun invoke(orderedIds: List<String>, movedId: String) {
        val idx = orderedIds.indexOf(movedId)
        if (idx < 0) return
        lists.reorder(movedId, orderedIds.getOrNull(idx - 1), orderedIds.getOrNull(idx + 1))
    }
}

/**
 * Restores undo snapshots on the application scope, so Undo still works after the screen that made
 * the change is gone (e.g. "List deleted — Undo" shown on Home, 03 §4.8).
 */
interface UndoRunner {
    fun restore(snapshot: UndoSnapshot)
}
