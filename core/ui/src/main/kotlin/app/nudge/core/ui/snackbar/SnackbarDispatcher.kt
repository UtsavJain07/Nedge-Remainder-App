package app.nudge.core.ui.snackbar

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * App-wide snackbar (03 §6): one at a time — a new one replaces the old one (whose action is then
 * committed, i.e. simply not undone). Durations: complete 4 s, delete 5 s, move 4 s.
 */
@Stable
class SnackbarDispatcher(val hostState: SnackbarHostState, private val scope: CoroutineScope) {
    private var job: Job? = null

    fun show(message: String, actionLabel: String? = null, durationMs: Long = COMPLETE_MS, onAction: () -> Unit = {}) {
        job?.cancel()
        hostState.currentSnackbarData?.dismiss()
        job = scope.launch {
            val result = withTimeoutOrNull(durationMs) {
                hostState.showSnackbar(message, actionLabel, withDismissAction = false, duration = SnackbarDuration.Indefinite)
            }
            if (result == SnackbarResult.ActionPerformed) onAction()
        }
    }

    companion object {
        const val COMPLETE_MS = 4_000L
        const val DELETE_MS = 5_000L
        const val MOVE_MS = 4_000L
        const val INFO_MS = 3_000L
    }
}

val LocalSnackbar = staticCompositionLocalOf<SnackbarDispatcher> { error("SnackbarDispatcher not provided") }
