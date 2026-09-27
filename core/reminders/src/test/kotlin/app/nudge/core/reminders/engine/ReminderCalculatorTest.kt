package app.nudge.core.reminders.engine

import app.nudge.core.model.NextReminder
import app.nudge.core.model.Priority
import app.nudge.core.model.ReminderCadence
import app.nudge.core.model.ReminderKind
import app.nudge.core.model.ReminderSettings
import app.nudge.core.testing.TaskBuilder
import app.nudge.core.testing.aTask
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** 07 §12 — every case C1–C25 must exist. Zone Asia/Kolkata unless noted. */
class ReminderCalculatorTest {
    private val calc = ReminderCalculator()
    private val zone = ZoneId.of("Asia/Kolkata")
    private val day = LocalDate.of(2026, 9, 27)
    private val defaults = ReminderSettings()

    private fun at(h: Int, m: Int, s: Int = 0, date: LocalDate = day, z: ZoneId = zone): Instant =
        ZonedDateTime.of(date, LocalTime.of(h, m, s), z).toInstant()

    private fun tomorrow(h: Int, m: Int) = at(h, m, date = day.plusDays(1))

    private fun next(
        now: Instant,
        settings: ReminderSettings = defaults,
        z: ZoneId = zone,
        block: TaskBuilder.() -> Unit,
    ): NextReminder? = calc.computeNext(aTask(block), settings, now, z)

    @Test fun c01_urgent_every_10_min() {
        assertThat(next(at(10, 0)) { priority = Priority.URGENT; anchorAt = at(10, 0) })
            .isEqualTo(NextReminder(at(10, 10), ReminderKind.INTERVAL))
    }

    @Test fun c02_high_every_hour() {
        assertThat(next(at(10, 30)) { priority = Priority.HIGH; anchorAt = at(10, 0) })
            .isEqualTo(NextReminder(at(11, 0), ReminderKind.INTERVAL))
    }

    @Test fun c03_medium_lands_in_quiet_hours_postponed_to_quiet_end() {
        assertThat(next(at(20, 0)) { priority = Priority.MEDIUM; anchorAt = at(20, 0) })
            .isEqualTo(NextReminder(tomorrow(8, 0), ReminderKind.INTERVAL))
    }

    @Test fun c04_urgent_ignores_quiet_hours_when_enabled() {
        val s = defaults.copy(urgentIgnoresQuietHours = true)
        assertThat(next(at(21, 55), s) { priority = Priority.URGENT; anchorAt = at(21, 55) })
            .isEqualTo(NextReminder(at(22, 5), ReminderKind.INTERVAL))
    }

    @Test fun c05_urgent_respects_quiet_hours_by_default() {
        assertThat(next(at(21, 55)) { priority = Priority.URGENT; anchorAt = at(21, 55) })
            .isEqualTo(NextReminder(tomorrow(8, 0), ReminderKind.INTERVAL))
    }

    @Test fun c06_low_twice_daily_morning() {
        assertThat(next(at(7, 59)) { priority = Priority.LOW })
            .isEqualTo(NextReminder(at(8, 0), ReminderKind.FIXED_TIME))
    }

    @Test fun c07_low_after_morning_goes_to_evening() {
        assertThat(next(at(8, 0, 30)) { priority = Priority.LOW })
            .isEqualTo(NextReminder(at(21, 0), ReminderKind.FIXED_TIME))
    }

    @Test fun c08_low_after_evening_goes_to_tomorrow_morning() {
        assertThat(next(at(21, 30)) { priority = Priority.LOW })
            .isEqualTo(NextReminder(tomorrow(8, 0), ReminderKind.FIXED_TIME))
    }

    @Test fun c09_none_without_due_is_null() {
        assertThat(next(at(12, 0)) { priority = Priority.NONE }).isNull()
    }

    @Test fun c10_none_with_due_date_and_time() {
        assertThat(next(at(12, 0)) { dueDate = day.plusDays(1); dueTime = LocalTime.of(17, 0) })
            .isEqualTo(NextReminder(tomorrow(17, 0), ReminderKind.DUE))
    }

    @Test fun c11_none_with_due_date_only_uses_morning_time() {
        assertThat(next(at(12, 0)) { dueDate = day.plusDays(1) })
            .isEqualTo(NextReminder(tomorrow(8, 0), ReminderKind.DUE))
    }

    @Test fun c12_due_earlier_than_interval_wins() {
        assertThat(
            next(at(10, 5)) {
                priority = Priority.HIGH; anchorAt = at(10, 0); dueDate = day; dueTime = LocalTime.of(10, 30)
            },
        ).isEqualTo(NextReminder(at(10, 30), ReminderKind.DUE))
    }

    @Test fun c13_tie_between_due_and_interval_due_wins() {
        assertThat(
            next(at(10, 5)) {
                priority = Priority.HIGH; anchorAt = at(10, 0); dueDate = day; dueTime = LocalTime.of(11, 0)
            },
        ).isEqualTo(NextReminder(at(11, 0), ReminderKind.DUE))
    }

    @Test fun c14_snooze_end_replaces_interval() {
        assertThat(next(at(12, 0)) { priority = Priority.HIGH; anchorAt = at(10, 0); snoozedUntil = at(15, 0) })
            .isEqualTo(NextReminder(at(15, 0), ReminderKind.INTERVAL))
    }

    @Test fun c15_missed_interval_catches_up_now() {
        assertThat(next(at(10, 0)) { priority = Priority.URGENT; anchorAt = at(9, 0) })
            .isEqualTo(NextReminder(at(10, 0), ReminderKind.INTERVAL))
    }

    @Test fun c16_completed_task_is_null() {
        assertThat(next(at(10, 0)) { priority = Priority.URGENT; isCompleted = true }).isNull()
    }

    @Test fun c17_global_pause_until_moves_floor() {
        val s = defaults.copy(pausedUntil = at(14, 0))
        assertThat(next(at(12, 30), s) { priority = Priority.HIGH; anchorAt = at(12, 0) })
            .isEqualTo(NextReminder(at(14, 0), ReminderKind.INTERVAL))
    }

    @Test fun c18_global_pause_forever_is_null() {
        val s = defaults.copy(pausedUntil = Instant.MAX)
        assertThat(next(at(12, 30), s) { priority = Priority.URGENT; dueDate = day.plusDays(1) }).isNull()
    }

    @Test fun c19_same_day_quiet_window() {
        val s = defaults.copy(quietStart = LocalTime.of(13, 0), quietEnd = LocalTime.of(15, 0))
        assertThat(next(at(12, 30), s) { priority = Priority.HIGH; anchorAt = at(12, 30) })
            .isEqualTo(NextReminder(at(15, 0), ReminderKind.INTERVAL))
    }

    @Test fun c20_quiet_hours_disabled() {
        val s = defaults.copy(quietHoursEnabled = false)
        assertThat(next(at(20, 0), s) { priority = Priority.MEDIUM; anchorAt = at(20, 0) })
            .isEqualTo(NextReminder(at(23, 0), ReminderKind.INTERVAL))
    }

    @Test fun c21_override_interval_on_low_task() {
        assertThat(
            next(at(10, 0)) { priority = Priority.LOW; cadenceOverride = ReminderCadence.EVERY_2_H; anchorAt = at(10, 0) },
        ).isEqualTo(NextReminder(at(12, 0), ReminderKind.INTERVAL))
    }

    @Test fun c22_override_off_on_urgent_task() {
        assertThat(next(at(10, 0)) { priority = Priority.URGENT; cadenceOverride = ReminderCadence.OFF }).isNull()
        assertThat(
            next(at(10, 0)) {
                priority = Priority.URGENT; cadenceOverride = ReminderCadence.OFF; dueDate = day; dueTime = LocalTime.of(18, 0)
            },
        ).isEqualTo(NextReminder(at(18, 0), ReminderKind.DUE))
    }

    @Test fun c23_dst_gap_shifts_fixed_time_forward() {
        val ny = ZoneId.of("America/New_York")
        val dstDay = LocalDate.of(2026, 3, 8) // spring forward 02:00 → 03:00
        val s = defaults.copy(morningTime = LocalTime.of(2, 30))
        val now = at(1, 30, date = dstDay, z = ny)
        val result = next(now, s, ny) { priority = Priority.LOW }
        assertThat(result).isEqualTo(NextReminder(Instant.parse("2026-03-08T07:30:00Z"), ReminderKind.FIXED_TIME))
        assertThat(result!!.at.atZone(ny).toLocalTime()).isEqualTo(LocalTime.of(3, 30))
    }

    @Test fun c24_fired_due_reminder_is_ignored() {
        assertThat(
            next(at(12, 0)) {
                priority = Priority.LOW; dueDate = day; dueTime = LocalTime.of(10, 0); dueReminderFired = true
            },
        ).isEqualTo(NextReminder(at(21, 0), ReminderKind.FIXED_TIME))
    }

    @Test fun c25_twice_daily_with_snooze_then_next_morning() {
        assertThat(next(at(12, 0)) { priority = Priority.LOW; snoozedUntil = at(22, 30) })
            .isEqualTo(NextReminder(at(22, 30), ReminderKind.INTERVAL))
        // After the snooze fires the dispatcher clears it; the next is the following morning.
        assertThat(next(at(22, 31)) { priority = Priority.LOW })
            .isEqualTo(NextReminder(tomorrow(8, 0), ReminderKind.FIXED_TIME))
    }

    @Test fun results_are_truncated_to_whole_seconds() {
        val r = next(at(10, 0).plusMillis(1234)) { priority = Priority.URGENT; anchorAt = at(10, 0).plusMillis(1234) }
        assertThat(r!!.at.nano).isEqualTo(0)
    }

    @Test fun deleted_task_is_null() {
        assertThat(next(at(10, 0)) { priority = Priority.URGENT; deletedAt = at(9, 0) }).isNull()
    }
}

/** 11 §3 QuietHoursTest: boundaries, midnight-crossing windows, DST. */
class QuietHoursTest {
    private val calc = ReminderCalculator()
    private val zone = ZoneId.of("Asia/Kolkata")
    private val s = ReminderSettings()
    private fun at(h: Int, m: Int, d: LocalDate = LocalDate.of(2026, 9, 27), z: ZoneId = zone) =
        ZonedDateTime.of(d, LocalTime.of(h, m), z).toInstant()

    @Test fun start_is_inclusive_end_is_exclusive() {
        assertThat(calc.isInQuietHours(at(22, 0), s, zone)).isTrue()
        assertThat(calc.isInQuietHours(at(21, 59), s, zone)).isFalse()
        assertThat(calc.isInQuietHours(at(8, 0), s, zone)).isFalse()
        assertThat(calc.isInQuietHours(at(7, 59), s, zone)).isTrue()
    }

    @Test fun crossing_midnight() {
        assertThat(calc.isInQuietHours(at(0, 0), s, zone)).isTrue()
        assertThat(calc.isInQuietHours(at(3, 0), s, zone)).isTrue()
        assertThat(calc.isInQuietHours(at(12, 0), s, zone)).isFalse()
    }

    @Test fun same_day_window() {
        val w = s.copy(quietStart = LocalTime.of(13, 0), quietEnd = LocalTime.of(15, 0))
        assertThat(calc.isInQuietHours(at(13, 0), w, zone)).isTrue()
        assertThat(calc.isInQuietHours(at(14, 59), w, zone)).isTrue()
        assertThat(calc.isInQuietHours(at(15, 0), w, zone)).isFalse()
        assertThat(calc.isInQuietHours(at(23, 0), w, zone)).isFalse()
    }

    @Test fun empty_window_never_quiet() {
        val w = s.copy(quietStart = LocalTime.of(9, 0), quietEnd = LocalTime.of(9, 0))
        assertThat(calc.isInQuietHours(at(9, 0), w, zone)).isFalse()
    }

    @Test fun next_quiet_end_before_midnight_is_next_day() {
        assertThat(calc.nextQuietEnd(at(23, 0), s, zone)).isEqualTo(at(8, 0, LocalDate.of(2026, 9, 28)))
    }

    @Test fun next_quiet_end_after_midnight_is_same_day() {
        assertThat(calc.nextQuietEnd(at(2, 0), s, zone)).isEqualTo(at(8, 0))
    }

    @Test fun dst_overlap_quiet_end_resolves_to_earlier_offset() {
        val ny = ZoneId.of("America/New_York")
        val fallBack = LocalDate.of(2026, 11, 1) // 02:00 EDT → 01:00 EST
        val w = s.copy(quietStart = LocalTime.of(0, 0), quietEnd = LocalTime.of(1, 30))
        val end = calc.nextQuietEnd(at(0, 30, fallBack, ny), w, ny)
        assertThat(end.atZone(ny).toLocalTime()).isEqualTo(LocalTime.of(1, 30))
        assertThat(end).isEqualTo(Instant.parse("2026-11-01T05:30:00Z")) // first (EDT) occurrence
    }
}
