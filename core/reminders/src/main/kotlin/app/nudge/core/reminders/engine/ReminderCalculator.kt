package app.nudge.core.reminders.engine

import app.nudge.core.model.NextReminder
import app.nudge.core.model.Priority
import app.nudge.core.model.ReminderCadence
import app.nudge.core.model.ReminderKind
import app.nudge.core.model.ReminderSettings
import app.nudge.core.model.Task
import app.nudge.core.model.effectiveCadence
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/**
 * Pure next-reminder calculation (07 §4). No Android dependencies; 100% unit tested (C1–C25).
 *
 * Implements FR-60..FR-64, FR-67, FR-68, FR-71.
 */
class ReminderCalculator @Inject constructor() {

    fun computeNext(task: Task, settings: ReminderSettings, now: Instant, zone: ZoneId): NextReminder? {
        if (task.isCompleted || task.deletedAt != null) return null
        val paused = settings.pausedUntil
        if (paused == Instant.MAX) return null

        val snoozedUntil = task.reminder.snoozedUntil
        // Earliest moment anything may fire.
        val floor = maxOf(now, snoozedUntil ?: Instant.MIN, paused ?: Instant.MIN)
        val candidates = mutableListOf<NextReminder>()
        val cadence = task.effectiveCadence(settings)
        val bypassQuiet = settings.urgentIgnoresQuietHours && task.priority == Priority.URGENT

        // (a) Snooze end: fires exactly at snoozedUntil (if in the future), regardless of cadence/quiet hours.
        if (snoozedUntil != null && snoozedUntil > now) {
            candidates += NextReminder(maxOf(snoozedUntil, paused ?: Instant.MIN), ReminderKind.INTERVAL)
        }

        // (b) Interval cadence.
        val interval = cadence.intervalMinutes
        if (interval != null && (snoozedUntil == null || snoozedUntil <= now)) {
            var t = task.reminder.anchorAt.plus(Duration.ofMinutes(interval.toLong()))
            if (t < floor) t = floor // missed (device off / inexact) → catch up once, ASAP
            if (settings.quietHoursEnabled && !bypassQuiet && isInQuietHours(t, settings, zone)) {
                t = nextQuietEnd(t, settings, zone)
            }
            candidates += NextReminder(t, ReminderKind.INTERVAL)
        }

        // (c) Twice daily (fixed times): ignores quiet hours; missed occurrences are skipped.
        if (cadence == ReminderCadence.TWICE_DAILY) {
            candidates += NextReminder(
                nextFixedTime(floor, listOf(settings.morningTime, settings.eveningTime), zone),
                ReminderKind.FIXED_TIME,
            )
        }

        // (d) One-off due reminder: ignores quiet hours and cadence (fires even if cadence OFF).
        val dueDate = task.dueDate
        if (dueDate != null && !task.reminder.dueReminderFired) {
            val dueAt = ZonedDateTime.of(dueDate, task.dueTime ?: settings.morningTime, zone).toInstant()
            candidates += NextReminder(maxOf(dueAt, floor), ReminderKind.DUE)
        }

        // Earliest wins; tie-break DUE > INTERVAL > FIXED_TIME.
        return candidates
            .map { it.copy(at = it.at.truncatedTo(ChronoUnit.SECONDS)) }
            .minWithOrNull(compareBy<NextReminder> { it.at }.thenBy { kindRank(it.kind) })
    }

    private fun kindRank(k: ReminderKind) = when (k) {
        ReminderKind.DUE -> 0
        ReminderKind.INTERVAL -> 1
        ReminderKind.FIXED_TIME -> 2
    }

    /** Start inclusive, end exclusive; handles windows that cross midnight (default 22:00–08:00). */
    fun isInQuietHours(t: Instant, s: ReminderSettings, zone: ZoneId): Boolean {
        val lt = t.atZone(zone).toLocalTime()
        return when {
            s.quietStart == s.quietEnd -> false
            s.quietStart < s.quietEnd -> lt >= s.quietStart && lt < s.quietEnd
            else -> lt >= s.quietStart || lt < s.quietEnd
        }
    }

    /** First instant after [t] at which local time == quietEnd. DST gaps shift forward (java.time). */
    fun nextQuietEnd(t: Instant, s: ReminderSettings, zone: ZoneId): Instant {
        val z = t.atZone(zone)
        var c = ZonedDateTime.of(z.toLocalDate(), s.quietEnd, zone)
        if (!c.toInstant().isAfter(t)) c = ZonedDateTime.of(z.toLocalDate().plusDays(1), s.quietEnd, zone)
        return c.toInstant()
    }

    /** First instant strictly after [after] whose local time is one of [times]. */
    fun nextFixedTime(after: Instant, times: List<LocalTime>, zone: ZoneId): Instant {
        val d = after.atZone(zone).toLocalDate()
        return (0..2L).asSequence()
            .flatMap { off -> times.asSequence().map { ZonedDateTime.of(d.plusDays(off), it, zone).toInstant() } }
            .filter { it.isAfter(after) }
            .min()
    }
}
