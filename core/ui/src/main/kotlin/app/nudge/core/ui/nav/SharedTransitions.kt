package app.nudge.core.ui.nav

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect

/** Provided by the app's SharedTransitionLayout / NavHost destinations for A8. */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

val LocalNavAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** A8: shared element with the spec'd bounds spring (0.8, 380). No-op when scopes are absent. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedElementOrNone(key: String, bounds: Boolean = false): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val visibility = LocalNavAnimatedVisibilityScope.current ?: return this
    return with(shared) {
        val state = rememberSharedContentState(key)
        val transform = BoundsTransform { _: Rect, _: Rect -> spring<Rect>(dampingRatio = 0.8f, stiffness = 380f) }
        if (bounds) {
            this@sharedElementOrNone.sharedBounds(state, visibility, boundsTransform = transform)
        } else {
            this@sharedElementOrNone.sharedElement(state, visibility, boundsTransform = transform)
        }
    }
}

object SharedKeys {
    fun listContainer(id: String) = "list-$id"

    fun listIcon(id: String) = "list-icon-$id"

    fun listName(id: String) = "list-name-$id"
}
