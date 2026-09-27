package app.nudge.core.reminders.receiver

import android.content.BroadcastReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import timber.log.Timber

/** Runs [block] off the main thread within the receiver's goAsync() window (07 §6). */
internal fun BroadcastReceiver.goAsync(scope: CoroutineScope, block: suspend () -> Unit) {
    val pending = goAsync()
    scope.launch {
        try {
            withTimeout(RECEIVER_TIMEOUT_MS) { block() }
        } catch (e: Exception) {
            Timber.e(e, "Receiver work failed")
        } finally {
            pending.finish()
        }
    }
}

private const val RECEIVER_TIMEOUT_MS = 9_000L
