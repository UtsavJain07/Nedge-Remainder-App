package app.nudge.core.domain.usecase

import app.nudge.core.common.Clock
import app.nudge.core.domain.reminder.ReminderScheduler
import app.nudge.core.domain.repository.SettingsRepository
import app.nudge.core.model.UserSettings
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import javax.inject.Inject

/**
 * Updates settings; any reminder-setting change reschedules everything (06 §7). Ending a pause
 * resets anchors (07 §9).
 */
class UpdateSettingsUseCase @Inject constructor(
    private val settings: SettingsRepository,
    private val scheduler: ReminderScheduler,
) {
    suspend operator fun invoke(transform: (UserSettings) -> UserSettings) {
        val before = settings.settings.first()
        settings.update(transform)
        val after = settings.settings.first()
        if (before.reminders != after.reminders) {
            val resumed = before.reminders.pausedUntil != null && after.reminders.pausedUntil == null
            scheduler.rescheduleAll(resetAnchors = resumed)
        }
    }
}

enum class PauseDuration { ONE_HOUR, UNTIL_TOMORROW, UNTIL_RESUMED }

/** FR-71: pause all reminders. */
class PauseRemindersUseCase @Inject constructor(
    private val update: UpdateSettingsUseCase,
    private val settings: SettingsRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(duration: PauseDuration) {
        val now = clock.now()
        val morning = settings.settings.first().reminders.morningTime
        val until = when (duration) {
            PauseDuration.ONE_HOUR -> now.plus(Duration.ofHours(1))
            PauseDuration.UNTIL_TOMORROW -> SnoozeOptions.nextMorning(now, morning, clock)
            PauseDuration.UNTIL_RESUMED -> Instant.MAX
        }
        update { it.copy(reminders = it.reminders.copy(pausedUntil = until)) }
    }

    suspend fun resume() = update { it.copy(reminders = it.reminders.copy(pausedUntil = null)) }
}

/** Snooze presets (FR-67). */
object SnoozeOptions {
    val presetMinutes: List<Long> = listOf(15, 60, 180)

    /** "Tomorrow" = the next day at the morning time (07 §8). */
    fun nextMorning(now: Instant, morning: java.time.LocalTime, clock: Clock): Instant {
        val today = now.atZone(clock.zone()).toLocalDate()
        return ZonedDateTime.of(today.plusDays(1), morning, clock.zone()).toInstant()
    }
}

/** FR-103: import then reschedule every reminder (06 §10). */
class ImportBackupUseCase @Inject constructor(
    private val backup: app.nudge.core.domain.repository.BackupRepository,
    private val scheduler: ReminderScheduler,
) {
    suspend operator fun invoke(json: String, mode: app.nudge.core.domain.repository.ImportMode):
        app.nudge.core.domain.repository.ImportResult {
        val result = backup.importJson(json, mode)
        if (result is app.nudge.core.domain.repository.ImportResult.Success) scheduler.rescheduleAll(resetAnchors = true)
        return result
    }
}

/** FR-103: delete all data, then clear every alarm/notification. */
class DeleteAllDataUseCase @Inject constructor(
    private val backup: app.nudge.core.domain.repository.BackupRepository,
    private val scheduler: ReminderScheduler,
) {
    suspend operator fun invoke() {
        backup.deleteAllData()
        scheduler.rescheduleAll()
    }
}
