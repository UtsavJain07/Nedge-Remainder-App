package app.nudge.core.designsystem.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.nudge.core.designsystem.theme.LocalReducedMotion
import app.nudge.core.designsystem.theme.NudgeMotion

/** A10: thin 3 dp bar; width animates with SpringGentle. Read in drawBehind (deferred, no recomposition). */
@Composable
fun ProgressBarThin(
    progress: Int,
    color: Color,
    modifier: Modifier = Modifier,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
) {
    val reduced = LocalReducedMotion.current
    val fraction by animateFloatAsState(
        targetValue = progress.coerceIn(0, 100) / 100f,
        animationSpec = if (reduced) tween(150) else spring(dampingRatio = 1f, stiffness = 300f),
        label = "progress",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(3.dp)
            .drawBehind {
                val radius = CornerRadius(size.height / 2, size.height / 2)
                drawRoundRect(trackColor, cornerRadius = radius)
                drawRoundRect(color, size = Size(size.width * fraction, size.height), cornerRadius = radius)
            },
    )
}

/** A10 / A15: ring sweep animates from 0 on first appearance (tween 600, EmphasizedDecelerate). */
@Composable
fun ProgressRing(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    stroke: Dp = 4.dp,
    trackColor: Color = color.copy(alpha = 0.18f),
    content: @Composable () -> Unit = {},
) {
    val reduced = LocalReducedMotion.current
    val anim = remember { Animatable(if (reduced) fraction else 0f) }
    LaunchedEffect(fraction) {
        anim.animateTo(fraction.coerceIn(0f, 1f), tween(if (reduced) 0 else 600, easing = NudgeMotion.EmphasizedDecelerate))
    }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val sw = stroke.toPx()
            val inset = sw / 2
            val arcSize = Size(this.size.width - sw, this.size.height - sw)
            drawArc(trackColor, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(sw))
            drawArc(color, -90f, 360f * anim.value, false, Offset(inset, inset), arcSize, style = Stroke(sw, cap = StrokeCap.Round))
        }
        content()
    }
}

/** A10: "40%" with a vertical slide counter. */
@Composable
fun AnimatedPercent(value: Int, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.labelMedium, color: Color = Color.Unspecified) {
    AnimatedContent(
        targetState = value,
        modifier = modifier,
        transitionSpec = {
            val up = targetState > initialState
            (slideInVertically { if (up) it else -it } + fadeIn()) togetherWith (slideOutVertically { if (up) -it else it } + fadeOut())
        },
        label = "percent",
    ) { v -> Text("$v%", style = style, color = color) }
}
