package app.nudge.core.domain.usecase

import app.nudge.core.model.Priority
import app.nudge.core.model.ReminderCadence
import app.nudge.core.model.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime

class UpdateSettingsUseCaseTest {

    private val h = Harness()
    private val update = UpdateSettingsUseCase(h.settings, h.scheduler)
    private val pause = PauseRemindersUseCase(update, h.settings, h.clock)

    @Test
    fun `reminder setting change reschedules all without resetting anchors`() = runTest {
        update {
            it.copy(
                reminders = it.reminders.copy(
                    defaultCadence = it.reminders.defaultCadence + (Priority.URGENT to ReminderCadence.EVERY_30_MIN),
                ),
            )
        }
        assertThat(h.scheduler.rescheduleAllCalls).isEqualTo(1)
        assertThat(h.scheduler.lastResetAnchors).isFalse()
        assertThat(h.settings.state.value.reminders.cadenceFor(Priority.URGENT)).isEqualTo(ReminderCadence.EVERY_30_MIN)
    }

    @Test
    fun `quiet hours change reschedules`() = runTest {
        update { it.copy(reminders = it.reminders.copy(quietStart = LocalTime.of(23, 0))) }
        assertThat(h.scheduler.rescheduleAllCalls).isEqualTo(1)
    }

    @Test
    fun `non-reminder change does not reschedule`() = runTest {
        update { it.copy(theme = ThemeMode.DARK, haptics = false) }
        assertThat(h.scheduler.rescheduleAllCalls).isEqualTo(0)
        assertThat(h.settings.state.value.theme).isEqualTo(ThemeMode.DARK)
    }

    @Test
    fun `no-op reminder change does not reschedule`() = runTest {
        update { it.copy(reminders = it.reminders.copy(quietStart = it.reminders.quietStart)) }
        assertThat(h.scheduler.rescheduleAllCalls).isEqualTo(0)
    }

    @Test
    fun `pausing reschedules without resetting anchors`() = runTest {
        pause(PauseDuration.ONE_HOUR)
        assertThat(h.settings.state.value.reminders.pausedUntil).isEqualTo(h.clock.now.plus(Duration.ofHours(1)))
        assertThat(h.scheduler.rescheduleAllCalls).isEqualTo(1)
        assertThat(h.scheduler.lastResetAnchors).isFalse()
    }

    @Test
    fun `resume from pause resets anchors`() = runTest {
        pause(PauseDuration.UNTIL_RESUMED)
        assertThat(h.settings.state.value.reminders.pausedUntil).isEqualTo(Instant.MAX)

        pause.resume()

        assertThat(h.settings.state.value.reminders.pausedUntil).isNull()
        assertThat(h.scheduler.rescheduleAllCalls).isEqualTo(2)
        assertThat(h.scheduler.lastResetAnchors).isTrue()
    }

    @Test
    fun `changing the pause length is not a resume`() = runTest {
        pause(PauseDuration.ONE_HOUR)
        pause(PauseDuration.UNTIL_RESUMED)
        assertThat(h.scheduler.lastResetAnchors).isFalse()
    }

    @Test
    fun `pause until tomorrow ends at the next morning time`() = runTest {
        pause(PauseDuration.UNTIL_TOMORROW)
        val expected = ZonedDateTime.of(
            h.clock.now.atZone(h.clock.zone).toLocalDate().plusDays(1),
            LocalTime.of(8, 0),
            h.clock.zone,
        ).toInstant()
        assertThat(h.settings.state.value.reminders.pausedUntil).isEqualTo(expected)
    }
}
