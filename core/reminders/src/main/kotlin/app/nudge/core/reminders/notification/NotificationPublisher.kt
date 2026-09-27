package app.nudge.core.reminders.notification

import app.nudge.core.model.ReminderKind
import app.nudge.core.model.Task
import app.nudge.core.model.TaskList
import java.time.Instant

/** Everything needed to render one reminder notification (07 §7.2). */
data class ReminderContent(
    val task: Task,
    val list: TaskList,
    val kind: ReminderKind,
    val count: Int,
    val subtasksDone: Int,
    val subtasksTotal: Int,
    /** Effective progress (computed from subtasks for a parent, FR-42). */
    val progress: Int,
    val snoozeMinutes: Int,
    val postedAt: Instant,
)

/** Posts, updates and cancels reminder notifications (FR-65, FR-66, FR-69). */
interface NotificationPublisher {
    fun ensureChannels()

    /** Posts or replaces (same id) the task's notification. No-op if notifications are disabled. */
    fun post(content: ReminderContent)

    fun cancel(taskId: String)

    fun cancelAllReminders()

    /** Posts the group summary when ≥ 2 reminders are showing, removes it otherwise. */
    fun updateGroupSummary()

    fun postTestNudge()

    companion object {
        const val SUMMARY_ID = 1
        const val TEST_ID = 2

        /** Stable per-task id; 1–9 are reserved (07 §7.2). */
        fun notificationIdFor(taskId: String): Int = (taskId.hashCode() and 0x7FFFFFFF).coerceAtLeast(10)
    }
}
