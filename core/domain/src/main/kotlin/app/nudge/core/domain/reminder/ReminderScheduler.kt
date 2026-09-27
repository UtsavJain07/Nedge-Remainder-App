package app.nudge.core.domain.reminder

/**
 * Re-arms the single dispatcher alarm after writes (07 §5.3). Lives in the domain so use cases can
 * call it; implemented in :core:reminders.
 */
interface ReminderScheduler {
    suspend fun onTasksChanged(taskIds: Collection<String>)

    /** Boot, time change, settings change, reconciler. [resetAnchors] after a global pause ends. */
    suspend fun rescheduleAll(resetAnchors: Boolean = false)

    suspend fun cancelNotification(taskId: String)
}

/** Reminder reliability status (FR-91, 07 §10). */
data class ReminderHealth(
    val notificationsEnabled: Boolean,
    val blockedChannels: List<String>,
    val exactAlarmsAllowed: Boolean,
    val ignoringBatteryOptimizations: Boolean,
    val oemVendor: String?,
) {
    val isDegraded: Boolean get() = !notificationsEnabled || !exactAlarmsAllowed || blockedChannels.isNotEmpty()

    val issueCount: Int
        get() = listOf(!notificationsEnabled, !exactAlarmsAllowed, blockedChannels.isNotEmpty(), !ignoringBatteryOptimizations)
            .count { it }

    companion object {
        val HEALTHY = ReminderHealth(true, emptyList(), true, true, null)
    }
}

/** Observes reminder reliability for the Home banner and Settings › Reminder health (FR-91). */
interface ReminderHealthMonitor {
    val health: kotlinx.coroutines.flow.StateFlow<ReminderHealth>

    /** Re-checks (call on every activity resume). */
    fun refresh()

    /** Schedules a test notification in [delaySeconds] seconds (07 §10). */
    fun sendTestNudge(delaySeconds: Long = 10)
}

/** A scheduled reminder row for the debug tools (07 §11). */
data class ScheduledReminder(
    val taskId: String,
    val title: String,
    val nextAt: java.time.Instant,
    val kind: app.nudge.core.model.ReminderKind?,
    val count: Int,
)

/** Debug-only reminder tools (07 §11). The release build binds a no-op variant through the UI gate. */
interface ReminderDebugTools {
    suspend fun dispatchNow(): Int

    suspend fun scheduled(): List<ScheduledReminder>

    suspend fun nextAlarmAt(): java.time.Instant?

    /** Advances the app clock by [minutes] and dispatches anything now due. */
    suspend fun timeTravel(minutes: Long)

    fun timeOffsetMinutes(): Long

    suspend fun resetTime()

    suspend fun resetAllAnchors()
}
