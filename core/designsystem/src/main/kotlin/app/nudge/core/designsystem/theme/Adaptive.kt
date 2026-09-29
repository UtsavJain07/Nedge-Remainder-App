package app.nudge.core.designsystem.theme

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Responsive layout tokens (03 §8). Phones use the full width; on tablets, foldables and landscape the
 * reading content is capped and centered so rows and forms keep a comfortable line length.
 */
object AdaptiveWidth {
    /** Lists of tasks, settings, search, forms. */
    val content: Dp = 720.dp

    /** Home dashboard (grid of list cards). */
    val dashboard: Dp = 1040.dp
}

/** Current window width in dp. */
@Composable
@ReadOnlyComposable
fun windowWidth(): Dp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }

/**
 * Extra horizontal padding that centers content inside [maxWidth]. Use it in a lazy list's
 * contentPadding so the whole screen still scrolls, not just the centered column.
 */
@Composable
@ReadOnlyComposable
fun centeringPadding(maxWidth: Dp = AdaptiveWidth.content): Dp = ((windowWidth() - maxWidth) / 2).coerceAtLeast(0.dp)

/** Caps a non-lazy container at [maxWidth] and centers it. */
fun Modifier.centeredMaxWidth(maxWidth: Dp = AdaptiveWidth.content): Modifier =
    fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = maxWidth)
