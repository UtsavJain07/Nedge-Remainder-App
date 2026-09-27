package app.nudge.core.common

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Persisted pause value meaning "until I resume" (06 §7). */
const val PAUSE_FOREVER_MILLIS: Long = Long.MAX_VALUE

fun Instant?.toEpochMilliOrNull(): Long? = this?.let { if (it == Instant.MAX) PAUSE_FOREVER_MILLIS else it.toEpochMilli() }

fun Long?.toInstantOrNull(): Instant? = this?.let { if (it == PAUSE_FOREVER_MILLIS) Instant.MAX else Instant.ofEpochMilli(it) }

fun LocalTime.minuteOfDay(): Int = hour * 60 + minute

fun minuteOfDayToLocalTime(minute: Int): LocalTime = LocalTime.of((minute / 60).coerceIn(0, 23), minute % 60)

/** The instant of a wall-clock due date (+ optional time, else [defaultTime]) in [zone]. */
fun dueInstant(date: LocalDate, time: LocalTime?, defaultTime: LocalTime, zone: ZoneId): Instant =
    ZonedDateTime.of(date, time ?: defaultTime, zone).toInstant()

/** Greeting bucket per 03 §3.2. */
enum class DayPart { MORNING, AFTERNOON, EVENING, NIGHT }

fun dayPartOf(hour: Int): DayPart = when (hour) {
    in 5..11 -> DayPart.MORNING
    in 12..16 -> DayPart.AFTERNOON
    in 17..21 -> DayPart.EVENING
    else -> DayPart.NIGHT
}
