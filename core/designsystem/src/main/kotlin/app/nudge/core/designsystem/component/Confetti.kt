package app.nudge.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import app.nudge.core.designsystem.theme.LocalReducedMotion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private class Particle(
    val angle: Double,
    val speed: Float,
    val size: Float,
    val color: Color,
    val isCircle: Boolean,
    val spin: Float,
)

/**
 * A13: 80 particles launched from the bottom center (−60°..−120°), 900–1600 dp/s, gravity 2200 dp/s²,
 * 1800 ms lifetime fading in the last 400 ms. One Canvas, driven by withFrameNanos. Plays once per
 * new [trigger] value (> 0). Skipped under reduced motion.
 */
@Composable
fun ConfettiOverlay(trigger: Int, colors: List<Color>, modifier: Modifier = Modifier) {
    if (LocalReducedMotion.current || trigger <= 0 || colors.isEmpty()) return
    val density = LocalDensity.current.density
    val particles = remember(trigger) {
        val rnd = Random(trigger)
        List(80) {
            Particle(
                angle = (-60.0 - rnd.nextDouble() * 60.0) * PI / 180.0,
                speed = (900f + rnd.nextFloat() * 700f) * density,
                size = (6f + rnd.nextFloat() * 6f) * density,
                color = colors[it % colors.size],
                isCircle = rnd.nextBoolean(),
                spin = rnd.nextFloat() * 720f - 360f,
            )
        }
    }
    var elapsed by remember(trigger) { mutableLongStateOf(0L) }
    var running by remember(trigger) { mutableStateOf(true) }
    LaunchedEffect(trigger) {
        val start = withFrameNanos { it }
        while (running) {
            withFrameNanos { now ->
                elapsed = (now - start) / 1_000_000
                if (elapsed >= LIFETIME_MS) running = false
            }
        }
    }
    if (!running) return
    Canvas(modifier.fillMaxSize()) {
        val t = elapsed / 1000f
        val gravity = 2200f * density
        val origin = Offset(size.width / 2, size.height)
        val alpha = if (elapsed > LIFETIME_MS - 400) ((LIFETIME_MS - elapsed) / 400f).coerceIn(0f, 1f) else 1f
        particles.forEach { p ->
            val x = origin.x + (cos(p.angle) * p.speed * t).toFloat()
            val y = origin.y + (sin(p.angle) * p.speed * t).toFloat() + 0.5f * gravity * t * t
            val c = p.color.copy(alpha = alpha)
            if (p.isCircle) {
                drawCircle(c, radius = p.size / 2, center = Offset(x, y))
            } else {
                rotate(p.spin * t, Offset(x, y)) {
                    drawRect(c, topLeft = Offset(x - p.size / 2, y - p.size / 4), size = Size(p.size, p.size / 2))
                }
            }
        }
    }
}

private const val LIFETIME_MS = 1800L
