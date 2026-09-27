package app.nudge.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/** Semantic haptic events (03 §7). */
enum class HapticEvent { COMPLETE, DRAG_START, SLOT_CHANGE, NEST_ARMED, REJECT, SLIDER_STEP, DELETE, THRESHOLD }

/** Whether the user enabled haptics (Settings › Behavior). */
val LocalHapticsEnabled = staticCompositionLocalOf { true }

/** Maps [HapticEvent]s to platform feedback, gated by the setting (03 §7). */
@Stable
class NudgeHaptics(private val feedback: HapticFeedback, private val enabled: Boolean) {
    fun perform(event: HapticEvent) {
        if (!enabled) return
        feedback.performHapticFeedback(
            when (event) {
                HapticEvent.COMPLETE -> HapticFeedbackType.Confirm
                HapticEvent.DRAG_START -> HapticFeedbackType.LongPress
                HapticEvent.SLOT_CHANGE -> HapticFeedbackType.SegmentFrequentTick
                HapticEvent.NEST_ARMED -> HapticFeedbackType.Confirm
                HapticEvent.REJECT -> HapticFeedbackType.Reject
                HapticEvent.SLIDER_STEP -> HapticFeedbackType.SegmentTick
                HapticEvent.DELETE -> HapticFeedbackType.ContextClick
                HapticEvent.THRESHOLD -> HapticFeedbackType.GestureThresholdActivate
            },
        )
    }
}

@Composable
fun rememberNudgeHaptics(): NudgeHaptics {
    val feedback = LocalHapticFeedback.current
    val enabled = LocalHapticsEnabled.current
    return remember(feedback, enabled) { NudgeHaptics(feedback, enabled) }
}
