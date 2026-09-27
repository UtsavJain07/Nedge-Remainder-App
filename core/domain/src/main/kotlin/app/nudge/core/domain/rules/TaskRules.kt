package app.nudge.core.domain.rules

import app.nudge.core.common.dueInstant
import app.nudge.core.model.Patch
import app.nudge.core.model.ReminderState
import app.nudge.core.model.Task
import app.nudge.core.model.TaskDraft
import app.nudge.core.model.TaskList
import app.nudge.core.model.TaskPatch
import app.nudge.core.model.valueOr
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Pure task mutation rules. Repositories load rows, call these, and write the result in one
 * transaction, so every rule here is unit-testable without Android.
 *
 * Covers FR-12, FR-14, FR-34, FR-43, 06 §5 integrity rules and the anchor reset rules in 07 §3.1.
 */
object TaskRules {

    /** Trims and validates a task title (FR-12). */
    fun validateTitle(raw: String): String {
        val t = raw.trim()
        require(t.isNotEmpty()) { "Title must not be blank" }
        require(t.length <= Task.TITLE_MAX) { "Title longer than ${Task.TITLE_MAX}" }
        return t
    }

    /** Trims and validates a list name (FR-01). */
    fun validateListName(raw: String): String {
        val n = raw.trim()
        require(n.isNotEmpty()) { "List name must not be blank" }
        require(n.length <= TaskList.NAME_MAX) { "List name longer than ${TaskList.NAME_MAX}" }
        return n
    }

    fun clampNotes(notes: String): String = notes.take(Task.NOTES_MAX)

    /** 07 §3.1: a new anchor restarts the interval count. */
    fun ReminderState.resetAnchor(now: Instant): ReminderState =
        copy(anchorAt = now, count = 0, lastRemindedAt = null)

    /** 07 §3.1: never fire a due reminder for a date the user just typed in the past. */
    fun dueAlreadyPassed(date: LocalDate?, time: LocalTime?, morning: LocalTime, now: Instant, zone: ZoneId): Boolean =
        date != null && !dueInstant(date, time, morning, zone).isAfter(now)

    fun newTask(
        id: String,
        draft: TaskDraft,
        sortOrder: Double,
        now: Instant,
        zone: ZoneId,
        morning: LocalTime,
    ): Task {
        val title = validateTitle(draft.title)
        val dueTime = if (draft.dueDate == null) null else draft.dueTime
        return Task(
            id = id,
            listId = draft.listId,
            parentId = draft.parentId,
            title = title,
            notes = clampNotes(draft.notes),
            priority = draft.priority,
            cadenceOverride = draft.cadenceOverride,
            progress = 0,
            progressBeforeComplete = null,
            isCompleted = false,
            completedAt = null,
            dueDate = draft.dueDate,
            dueTime = dueTime,
            sortOrder = sortOrder,
            isExpanded = true,
            reminder = ReminderState.fresh(now).copy(
                dueReminderFired = dueAlreadyPassed(draft.dueDate, dueTime, morning, now, zone),
            ),
            createdAt = now,
            updatedAt = now,
        )
    }

    /**
     * Applies a user edit (FR-14). [hasChildren] rejects manual progress on parents (FR-42).
     */
    fun applyPatch(
        task: Task,
        patch: TaskPatch,
        hasChildren: Boolean,
        now: Instant,
        zone: ZoneId,
        morning: LocalTime,
    ): Task {
        var reminder = task.reminder
        val title = patch.title?.let(::validateTitle) ?: task.title
        val notes = patch.notes?.let(::clampNotes) ?: task.notes
        val priority = patch.priority ?: task.priority
        val cadence = patch.cadenceOverride.valueOr(task.cadenceOverride)
        if (priority != task.priority || cadence != task.cadenceOverride) reminder = reminder.resetAnchor(now)

        val progress = patch.progress?.let {
            require(!hasChildren) { "Progress of a parent is calculated from its subtasks" }
            it.coerceIn(0, 100)
        } ?: task.progress

        val dueDate = patch.dueDate.valueOr(task.dueDate)
        val dueTime = if (dueDate == null) null else patch.dueTime.valueOr(task.dueTime)
        if (dueDate != task.dueDate || dueTime != task.dueTime) {
            reminder = reminder.copy(dueReminderFired = dueAlreadyPassed(dueDate, dueTime, morning, now, zone))
        }

        if (patch.snoozedUntil is Patch.Set) {
            val until = (patch.snoozedUntil as Patch.Set<Instant?>).value
            reminder = if (until == null) {
                // 07 §8: cancelling a snooze restarts the interval from now.
                if (reminder.snoozedUntil != null) reminder.copy(snoozedUntil = null, anchorAt = now) else reminder
            } else {
                require(until.isAfter(now)) { "Snooze must end in the future" }
                reminder.copy(snoozedUntil = until)
            }
        }

        val updated = task.copy(
            title = title,
            notes = notes,
            priority = priority,
            cadenceOverride = cadence,
            progress = progress,
            dueDate = dueDate,
            dueTime = dueTime,
            isExpanded = patch.isExpanded ?: task.isExpanded,
            reminder = reminder,
        )
        return if (updated == task) task else updated.copy(updatedAt = now)
    }

    /**
     * Completes [task] and its open [children] with the same completedAt (FR-34, 06 §5 rule 5).
     * Returns only the rows that changed.
     */
    fun complete(task: Task, children: List<Task>, now: Instant, progressBefore: Int? = null): List<Task> {
        if (task.isCompleted) return emptyList()
        val done = task.copy(
            isCompleted = true,
            completedAt = now,
            progressBeforeComplete = progressBefore ?: task.progress,
            progress = 100,
            updatedAt = now,
        )
        val kids = children.filter { !it.isCompleted && it.deletedAt == null }.map {
            it.copy(isCompleted = true, completedAt = now, progressBeforeComplete = it.progress, progress = 100, updatedAt = now)
        }
        return listOf(done) + kids
    }

    /**
     * Un-completes [task] (FR-33). Children are NOT un-completed. If [task] is a subtask of a
     * completed [parent], the parent is un-completed too (06 §5 rule 6).
     */
    fun uncomplete(task: Task, parent: Task?, now: Instant): List<Task> {
        if (!task.isCompleted) return emptyList()
        val result = mutableListOf(task.reopen(now))
        if (parent != null && parent.isCompleted) result += parent.reopen(now)
        return result
    }

    private fun Task.reopen(now: Instant) = copy(
        isCompleted = false,
        completedAt = null,
        progress = progressBeforeComplete ?: if (progress == 100) 0 else progress,
        progressBeforeComplete = null,
        reminder = reminder.resetAnchor(now),
        updatedAt = now,
    )

    /** Soft-deletes [task] and its children (FR-15, 06 §5 rule 4). */
    fun softDelete(task: Task, children: List<Task>, now: Instant): List<Task> =
        (listOf(task) + children.filter { it.deletedAt == null }).map { it.copy(deletedAt = now, updatedAt = now) }
}
