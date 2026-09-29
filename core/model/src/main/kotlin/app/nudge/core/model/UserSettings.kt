package app.nudge.core.model

import java.time.Instant
import java.time.LocalTime

/** All user preferences (FR-100..FR-104, 06 §1.1). */
data class UserSettings(
    val onboardingDone: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val pureBlack: Boolean = false,
    val newTaskPosition: InsertPosition = InsertPosition.TOP,
    val defaultPriority: Priority = Priority.NONE,
    val haptics: Boolean = true,
    val confirmDelete: Boolean = false,
    val lastUsedListId: String? = null,
    val reminders: ReminderSettings = ReminderSettings(),
)

/** Reminder preferences (FR-61, FR-64, FR-67, FR-71). */
data class ReminderSettings(
    val defaultCadence: Map<Priority, ReminderCadence> = DEFAULT_CADENCE,
    val morningTime: LocalTime = LocalTime.of(8, 0),
    val eveningTime: LocalTime = LocalTime.of(21, 0),
    val quietHoursEnabled: Boolean = true,
    val quietStart: LocalTime = LocalTime.of(22, 0),
    val quietEnd: LocalTime = LocalTime.of(8, 0),
    val urgentIgnoresQuietHours: Boolean = false,
    val defaultSnoozeMinutes: Int = 60,
    /** Global pause. `Instant.MAX` = until resumed; null = not paused. */
    val pausedUntil: Instant? = null,
) {
    fun cadenceFor(priority: Priority): ReminderCadence =
        defaultCadence[priority] ?: DEFAULT_CADENCE.getValue(priority)

    fun isPaused(now: Instant): Boolean = pausedUntil != null && pausedUntil > now

    companion object {
        val DEFAULT_CADENCE: Map<Priority, ReminderCadence> = mapOf(
            Priority.URGENT to ReminderCadence.EVERY_10_MIN,
            Priority.HIGH to ReminderCadence.EVERY_1_H,
            Priority.MEDIUM to ReminderCadence.EVERY_3_H,
            Priority.LOW to ReminderCadence.TWICE_DAILY,
            Priority.NONE to ReminderCadence.OFF,
        )
    }
}
