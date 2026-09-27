package app.nudge.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.nudge.core.designsystem.theme.LocalNudgeColors
import app.nudge.core.designsystem.theme.LocalReducedMotion
import kotlinx.coroutines.launch

enum class EmptyIllustration { EMPTY_LIST, ALL_DONE, NOTHING_DUE, NO_RESULTS, WELCOME }

/** Self-contained Canvas illustrations (no Lottie, 05 §2). */
@Composable
fun Illustration(kind: EmptyIllustration, modifier: Modifier = Modifier, size: Dp = 160.dp) {
    val reduced = LocalReducedMotion.current
    val scheme = MaterialTheme.colorScheme
    val colors = LocalNudgeColors.current
    val float = if (reduced) {
        0f
    } else {
        val t = rememberInfiniteTransition(label = "float")
        val v by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Reverse), label = "f")
        v
    }
    Canvas(modifier.size(size)) {
        when (kind) {
            EmptyIllustration.EMPTY_LIST, EmptyIllustration.WELCOME -> drawChecklist(scheme.surfaceContainerHigh, scheme.outlineVariant, scheme.primary, float)
            EmptyIllustration.ALL_DONE -> drawBadge(scheme.primaryContainer, scheme.primary, float, listOf(colors.urgent.color, colors.high.color, colors.medium.color, colors.low.color))
            EmptyIllustration.NOTHING_DUE -> drawCup(scheme.surfaceContainerHigh, scheme.primary, scheme.outlineVariant, float)
            EmptyIllustration.NO_RESULTS -> drawMagnifier(scheme.surfaceContainerHigh, scheme.outline, float)
        }
    }
}

private fun DrawScope.drawChecklist(card: Color, line: Color, accent: Color, t: Float) {
    val w = size.width
    val h = size.height
    drawRoundRect(card, Offset(w * 0.18f, h * 0.14f), Size(w * 0.56f, h * 0.72f), CornerRadius(w * 0.08f))
    repeat(3) { i ->
        val y = h * (0.3f + i * 0.17f)
        drawCircle(line, radius = w * 0.035f, center = Offset(w * 0.3f, y), style = Stroke(w * 0.014f))
        drawRoundRect(line, Offset(w * 0.38f, y - w * 0.015f), Size(w * (0.26f - i * 0.04f), w * 0.03f), CornerRadius(w * 0.015f))
    }
    // Floating check bubble.
    val cy = h * (0.22f - 0.04f * t)
    drawCircle(accent, radius = w * 0.13f, center = Offset(w * 0.76f, cy))
    val p = Path().apply {
        moveTo(w * 0.705f, cy)
        lineTo(w * 0.745f, cy + w * 0.04f)
        lineTo(w * 0.82f, cy - w * 0.045f)
    }
    drawPath(p, Color.White, style = Stroke(w * 0.03f, cap = StrokeCap.Round))
}

private fun DrawScope.drawBadge(container: Color, accent: Color, t: Float, confetti: List<Color>) {
    val c = Offset(size.width / 2, size.height / 2)
    val r = size.minDimension * 0.3f
    drawCircle(container, radius = r * (1.05f + 0.05f * t), center = c)
    drawCircle(accent, radius = r * 0.72f, center = c)
    val p = Path().apply {
        moveTo(c.x - r * 0.32f, c.y)
        lineTo(c.x - r * 0.08f, c.y + r * 0.24f)
        lineTo(c.x + r * 0.36f, c.y - r * 0.22f)
    }
    drawPath(p, Color.White, style = Stroke(r * 0.14f, cap = StrokeCap.Round))
    confetti.forEachIndexed { i, col ->
        val a = i * 90f + 45f + 20f * t
        rotate(a, c) { drawRoundRect(col, Offset(c.x - 4f, c.y - r * 1.45f), Size(size.width * 0.03f, size.width * 0.06f), CornerRadius(4f)) }
    }
}

private fun DrawScope.drawCup(body: Color, accent: Color, steam: Color, t: Float) {
    val w = size.width
    val h = size.height
    drawRoundRect(body, Offset(w * 0.26f, h * 0.42f), Size(w * 0.4f, h * 0.36f), CornerRadius(w * 0.06f))
    drawCircle(accent, radius = w * 0.08f, center = Offset(w * 0.7f, h * 0.58f), style = Stroke(w * 0.035f))
    drawRoundRect(accent, Offset(w * 0.2f, h * 0.8f), Size(w * 0.52f, h * 0.04f), CornerRadius(w * 0.02f))
    repeat(3) { i ->
        val x = w * (0.36f + i * 0.1f)
        val p = Path().apply {
            moveTo(x, h * 0.36f)
            cubicTo(x - w * 0.04f, h * (0.3f - 0.02f * t), x + w * 0.04f, h * 0.24f, x, h * (0.16f + 0.02f * t))
        }
        drawPath(p, steam, style = Stroke(w * 0.02f, cap = StrokeCap.Round))
    }
}

private fun DrawScope.drawMagnifier(body: Color, stroke: Color, t: Float) {
    val c = Offset(size.width * (0.44f + 0.02f * t), size.height * 0.42f)
    val r = size.minDimension * 0.22f
    drawCircle(body, radius = r, center = c)
    drawCircle(stroke, radius = r, center = c, style = Stroke(size.width * 0.04f))
    drawLine(stroke, Offset(c.x + r * 0.72f, c.y + r * 0.72f), Offset(c.x + r * 1.5f, c.y + r * 1.5f), strokeWidth = size.width * 0.06f, cap = StrokeCap.Round)
}

/** Animated check path used by onboarding and "All done" (A20). */
@Composable
fun AnimatedCheckMark(color: Color, modifier: Modifier = Modifier, delayMillis: Int = 0) {
    val reduced = LocalReducedMotion.current
    val progress = remember { Animatable(if (reduced) 1f else 0f) }
    val scale = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        launch { scale.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 500f)) }
        progress.animateTo(1f, tween(450, delayMillis = delayMillis + 150))
    }
    Canvas(modifier) {
        val r = size.minDimension / 2 * scale.value
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(color, radius = r, center = c)
        val p = Path().apply {
            moveTo(c.x - r * 0.42f, c.y)
            lineTo(c.x - r * 0.1f, c.y + r * 0.32f)
            lineTo(c.x + r * 0.45f, c.y - r * 0.3f)
        }
        val m = PathMeasure().apply { setPath(p, false) }
        val seg = Path()
        m.getSegment(0f, m.length * progress.value, seg, true)
        drawPath(seg, Color.White, style = Stroke(r * 0.18f, cap = StrokeCap.Round))
    }
}
