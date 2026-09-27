package app.nudge.core.designsystem.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/** Raw motion tokens (04 §6.1). */
object NudgeMotion {
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    fun <T> bouncy(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.6f, stiffness = 500f)

    fun <T> snappy(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = 800f)

    fun <T> gentle(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 300f)

    fun <T> tweenFast(): FiniteAnimationSpec<T> = tween(150, easing = FastOutSlowInEasing)

    fun <T> tweenMedium(): FiniteAnimationSpec<T> = tween(300, easing = EmphasizedDecelerate)
}

/** Mirror of material3's MotionScheme contract (04 §6.1). */
interface NudgeMotionScheme {
    fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T>

    fun <T> fastSpatialSpec(): FiniteAnimationSpec<T>

    fun <T> slowSpatialSpec(): FiniteAnimationSpec<T>

    fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T>

    fun <T> fastEffectsSpec(): FiniteAnimationSpec<T>

    fun <T> slowEffectsSpec(): FiniteAnimationSpec<T>
}

/**
 * Expressive motion scheme (04 §6.1).
 *
 * NOTE: material3 1.4.0 (latest stable) keeps `MaterialExpressiveTheme` / `MotionScheme.expressive()`
 * internal, and 1.5.0-alpha drags the whole Compose stack to alpha. We reproduce the M3 Expressive
 * spring tokens here (spatial specs overshoot, effects don't) and provide them via
 * [LocalMotionScheme]. When material3 1.5 is stable, swap to `MaterialExpressiveTheme`.
 */
object NudgeExpressiveMotionScheme : NudgeMotionScheme {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = 380f)

    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.6f, stiffness = 800f)

    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = 200f)

    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 1600f)

    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 3800f)

    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = Spring.StiffnessLow)
}

/** True when the system "Remove animations" setting is on (04 §6.5). */
val LocalReducedMotion = staticCompositionLocalOf { false }

val LocalMotionScheme = staticCompositionLocalOf<NudgeMotionScheme> { NudgeExpressiveMotionScheme }

@Composable
fun rememberSystemReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** Decorative spec that collapses to snap() under reduced motion. */
fun <T> decorative(reduced: Boolean, spec: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> = if (reduced) snap() else spec
