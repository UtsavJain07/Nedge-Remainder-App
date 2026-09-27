package app.nudge.core.common

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only source of "now" in the app (05 §12). Never call `System.currentTimeMillis()` directly.
 */
interface Clock {
    fun now(): Instant

    fun zone(): ZoneId
}

/**
 * Wall clock. [offset] is only ever changed by the debug "time travel" tool (07 §11); it stays zero
 * in release builds.
 */
@Singleton
class SystemClock @Inject constructor() : Clock {
    @Volatile
    var offset: java.time.Duration = java.time.Duration.ZERO

    override fun now(): Instant = Instant.now().plus(offset).truncatedTo(ChronoUnit.MILLIS)

    override fun zone(): ZoneId = ZoneId.systemDefault()
}
