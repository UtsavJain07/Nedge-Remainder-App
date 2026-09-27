package app.nudge.core.testing

import app.nudge.core.common.Clock
import app.nudge.core.common.IdGenerator
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Controllable clock for tests (11 §1). */
class TestClock(
    var now: Instant = Instant.parse("2026-09-27T04:30:00Z"),
    var zone: ZoneId = ZoneId.of("Asia/Kolkata"),
) : Clock {
    override fun now(): Instant = now

    override fun zone(): ZoneId = zone

    fun advance(d: Duration) {
        now = now.plus(d)
    }

    fun set(date: LocalDate, time: LocalTime) {
        now = ZonedDateTime.of(date, time, zone).toInstant()
    }

    fun at(date: LocalDate, time: LocalTime): Instant = ZonedDateTime.of(date, time, zone).toInstant()
}

class SequentialIdGenerator(private val prefix: String = "id") : IdGenerator {
    private var n = 0

    override fun newId(): String = "$prefix-${++n}"
}
