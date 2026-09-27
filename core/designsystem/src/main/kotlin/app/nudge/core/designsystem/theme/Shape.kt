package app.nudge.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** 04 §4. */
val NudgeShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** 4 dp grid spacing tokens (04 §5). */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp

    val screenPadding = 16.dp
    val cardGap = 12.dp
    val sheetPadding = 24.dp
    val subtaskIndent = 40.dp
    val rowMinHeight = 56.dp
    val subtaskRowMinHeight = 48.dp
    val minTouchTarget = 48.dp
}
