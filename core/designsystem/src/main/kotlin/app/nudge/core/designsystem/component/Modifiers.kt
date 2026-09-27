package app.nudge.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import app.nudge.core.designsystem.theme.LocalReducedMotion

/** A15 / A9: scale to [pressed] while pressed (SpringSnappy). */
fun Modifier.pressScale(interaction: MutableInteractionSource, pressed: Float = 0.96f): Modifier = composed {
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) pressed else 1f, spring(dampingRatio = 0.8f, stiffness = 800f), label = "press")
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** A7: horizontal shake 0, -8, 8, -6, 6, -3, 0 dp over 360 ms whenever [trigger] changes to a new non-zero value. */
fun Modifier.shake(trigger: Int): Modifier = composed {
    val reduced = LocalReducedMotion.current
    val offset = remember { Animatable(0f) }
    val px = with(LocalDensity.current) { 1f * density }
    LaunchedEffect(trigger) {
        if (trigger == 0 || reduced) return@LaunchedEffect
        offset.animateTo(
            0f,
            keyframes {
                durationMillis = 360
                0f at 0
                -8f at 50
                8f at 110
                -6f at 170
                6f at 230
                -3f at 290
                0f at 360
            },
        )
    }
    graphicsLayer { translationX = offset.value * px }
}

/**
 * A19: row background pulses the list color alpha 0 → 0.3 → 0 twice (2 × 600 ms). With reduced
 * motion it shows a static highlight for 1.5 s.
 */
fun Modifier.highlightPulse(active: Boolean, color: Color, onFinished: () -> Unit = {}): Modifier = composed {
    val reduced = LocalReducedMotion.current
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        if (reduced) {
            alpha.snapTo(0.3f)
            kotlinx.coroutines.delay(1500)
            alpha.snapTo(0f)
        } else {
            repeat(2) {
                alpha.animateTo(0.3f, tween(300))
                alpha.animateTo(0f, tween(300))
            }
        }
        onFinished()
    }
    drawBehind { if (alpha.value > 0f) drawRect(color.copy(alpha = alpha.value)) }
}
