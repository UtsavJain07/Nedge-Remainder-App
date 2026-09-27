package app.nudge.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * A to-do item (FR-12). `parentId == null` means top-level; subtasks never have children (FR-20).
 */
data class Task(
    val id: String,
    val listId: String,
    val parentId: String?,
    val title: String,
    val notes: String,
    val priority: Priority,
    val cadenceOverride: ReminderCadence?,
    val progress: Int,
    val progressBeforeComplete: Int?,
    val isCompleted: Boolean,
    val completedAt: Instant?,
    val dueDate: LocalDate?,
    val dueTime: LocalTime?,
    val sortOrder: Double,
    val isExpanded: Boolean,
    val reminder: ReminderState,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
) {
    val isSubtask: Boolean get() = parentId != null

    companion object {
        const val TITLE_MAX = 200
        const val NOTES_MAX = 2000
    }
}

/** Device-local reminder runtime state (07 §3). Not user data; never synced or exported. */
data class ReminderState(
    val anchorAt: Instant,
    val nextAt: Instant?,
    val nextKind: ReminderKind?,
    val lastRemindedAt: Instant?,
    val count: Int,
    val snoozedUntil: Instant?,
    val dueReminderFired: Boolean,
) {
    companion object {
        fun fresh(anchorAt: Instant) = ReminderState(
            anchorAt = anchorAt,
            nextAt = null,
            nextKind = null,
            lastRemindedAt = null,
            count = 0,
            snoozedUntil = null,
            dueReminderFired = false,
        )
    }
}

/** Effective cadence = override ?: settings default for priority (07 §2). */
fun Task.effectiveCadence(settings: ReminderSettings): ReminderCadence =
    cadenceOverride ?: settings.cadenceFor(priority)

/** Result of the pure reminder calculation. */
data class NextReminder(val at: Instant, val kind: ReminderKind)

/** A reminder-state write: new runtime state plus the next reminder (or null for none). */
data class ReminderStateUpdate(
    val taskId: String,
    val state: ReminderState,
    val next: NextReminder?,
)
