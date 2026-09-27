package app.nudge.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath
import app.nudge.core.designsystem.R
import app.nudge.core.designsystem.theme.LocalReducedMotion
import app.nudge.core.designsystem.theme.contentColorOn
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Shared polygons for the circle → soft-burst morph (04 §4). NOTE: built on graphics-shapes; MaterialShapes is not stable. */
internal object CheckboxShapes {
    val circle: RoundedPolygon = RoundedPolygon.circle(numVertices = 10)
    val softBurst: RoundedPolygon = RoundedPolygon.star(
        numVerticesPerRadius = 10,
        radius = 1f,
        innerRadius = 0.84f,
        rounding = CornerRounding(0.38f),
        innerRounding = CornerRounding(0.38f),
    )
    val morph = Morph(circle, softBurst)
}

/**
 * The completion checkbox (A1 / A2). 24 dp visual inside a 48 dp touch target.
 *
 * @param color ring + fill color (the priority color; for None the caller passes the list/primary color).
 * @param ringColor ring color while open (outline for None).
 * @param onToggle null makes it decorative (e.g. when the whole row handles the tap).
 */
@Composable
fun NudgeCheckbox(
    checked: Boolean,
    color: Color,
    onToggle: (() -> Unit)?,
    modifier: Modifier = Modifier,
    ringColor: Color = color,
    size: Dp = 24.dp,
    label: String? = null,
) {
    val reduced = LocalReducedMotion.current
    val fill = remember { Animatable(if (checked) 1f else 0f) }
    val check = remember { Animatable(if (checked) 1f else 0f) }
    val burst = remember { Animatable(1f) }
    var initialized by remember { mutableStateOf(false) }

    LaunchedEffect(checked) {
        if (!initialized) {
            initialized = true
            return@LaunchedEffect
        }
        if (checked) {
            if (!reduced) {
                burst.snapTo(0f)
                coroutineScope {
                    launch { fill.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 500f)) }
                    launch { check.animateTo(1f, tween(250, delayMillis = 60, easing = FastOutSlowInEasing)) }
                    launch { burst.animateTo(1f, tween(400, easing = LinearEasing)) }
                }
            } else {
                fill.snapTo(1f)
                check.snapTo(1f)
            }
        } else {
            if (!reduced) {
                check.animateTo(0f, tween(150))
                fill.animateTo(0f, tween(150))
            } else {
                check.snapTo(0f)
                fill.snapTo(0f)
            }
        }
    }

    val animatedRing by animateColorAsState(if (checked) color else ringColor, if (reduced) snap() else tween(200), label = "ring")
    val checkColor = contentColorOn(color)
    val density = LocalDensity.current
    val stateText = stringResource(if (checked) R.string.state_completed else R.string.state_not_completed)
    val toggleMod = if (onToggle != null) {
        Modifier.toggleable(
            value = checked,
            onValueChange = { onToggle() },
            role = Role.Checkbox,
            interactionSource = null,
            indication = ripple(bounded = false, radius = 24.dp),
        )
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .size(48.dp)
            .then(toggleMod)
            .semantics {
                stateDescription = stateText
                if (label != null) contentDescription = label
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            val r = this.size.minDimension / 2f
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val stroke = with(density) { 2.dp.toPx() }
            val f = fill.value.coerceIn(0f, 1.2f)
            // Ring (fades as the fill takes over).
            if (f < 1f) {
                drawCircle(animatedRing, radius = r - stroke / 2, center = center, style = Stroke(stroke))
            }
            // Fill: scales 0 → 1 with overshoot while the outline morphs circle → soft burst.
            if (f > 0f) {
                val morphed = CheckboxShapes.morph.toPath(progress = f.coerceIn(0f, 1f)).asComposePath()
                val scale = r * f
                morphed.transform(Matrix().apply { translate(center.x, center.y); scale(scale, scale) })
                drawPath(morphed, color)
            }
            if (check.value > 0f) drawCheck(check.value, checkColor, r, center)
            // Particle burst: 8 dots fly outward 16–28 dp and fade (A1 step 2).
            if (burst.value < 1f) drawParticles(burst.value, color, center, with(density) { 16.dp.toPx() }, with(density) { 28.dp.toPx() })
        }
    }
}

private fun DrawScope.drawCheck(progress: Float, color: Color, r: Float, c: Offset) {
    val path = Path().apply {
        moveTo(c.x - r * 0.46f, c.y + r * 0.02f)
        lineTo(c.x - r * 0.12f, c.y + r * 0.36f)
        lineTo(c.x + r * 0.48f, c.y - r * 0.32f)
    }
    val measure = PathMeasure().apply { setPath(path, false) }
    val partial = Path()
    measure.getSegment(0f, measure.length * progress.coerceIn(0f, 1f), partial, true)
    drawPath(partial, color, style = Stroke(width = r * 0.22f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.drawParticles(t: Float, color: Color, c: Offset, minDist: Float, maxDist: Float) {
    val eased = 1f - (1f - t) * (1f - t)
    repeat(8) { i ->
        val angle = (i * 45.0 + 22.5) * PI / 180.0
        val dist = (if (i % 2 == 0) maxDist else minDist) * eased
        val p = Offset(c.x + (cos(angle) * dist).toFloat(), c.y + (sin(angle) * dist).toFloat())
        drawCircle(color.copy(alpha = (1f - t).coerceIn(0f, 1f)), radius = (1f - t * 0.5f) * 2.5f * density, center = p)
    }
}
