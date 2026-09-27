package app.nudge.core.common

import java.security.SecureRandom
import java.util.UUID
import javax.inject.Inject

/** Generates sync-safe string ids (06 intro). */
fun interface IdGenerator {
    fun newId(): String
}

/**
 * UUID v7 (time-ordered, RFC 9562): 48-bit unix millis, version/variant bits, the rest random.
 * Time-ordering keeps B-tree inserts local and makes ids roughly sortable by creation.
 */
class UuidV7Generator @Inject constructor(private val clock: Clock) : IdGenerator {
    private val random = SecureRandom()

    override fun newId(): String {
        val millis = clock.now().toEpochMilli()
        val randA = random.nextInt(1 shl 12).toLong()
        val msb = (millis shl 16) or (0x7L shl 12) or randA
        val lsb = (random.nextLong() and 0x3FFFFFFFFFFFFFFFL) or Long.MIN_VALUE
        return UUID(msb, lsb).toString()
    }
}
