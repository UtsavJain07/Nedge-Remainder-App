package app.nudge.core.designsystem.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import app.nudge.core.designsystem.theme.LocalReducedMotion

/**
 * A1 step 3 / A2: the strike line draws left → right across every line over 250 ms (retracts in
 * 150 ms), and text alpha animates 1 → 0.5.
 */
@Composable
fun StrikethroughText(
    text: AnnotatedString,
    struck: Boolean,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
    maxLines: Int = 2,
) {
    val reduced = LocalReducedMotion.current
    val fraction by animateFloatAsState(
        targetValue = if (struck) 1f else 0f,
        animationSpec = tween(if (reduced) 0 else if (struck) 250 else 150),
        label = "strike",
    )
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { layout = it },
        modifier = modifier
            .graphicsLayer { alpha = 1f - 0.5f * fraction }
            .drawWithContent {
                drawContent()
                val l = layout ?: return@drawWithContent
                if (fraction <= 0f) return@drawWithContent
                val widths = (0 until l.lineCount).map { l.getLineRight(it) - l.getLineLeft(it) }
                var remaining = widths.sum() * fraction
                for (i in 0 until l.lineCount) {
                    if (remaining <= 0f) break
                    val w = minOf(widths[i], remaining)
                    val y = (l.getLineTop(i) + l.getLineBottom(i)) / 2f
                    val x0 = l.getLineLeft(i)
                    drawLine(color, Offset(x0, y), Offset(x0 + w, y), strokeWidth = 1.5f * density)
                    remaining -= w
                }
            },
    )
}

@Composable
fun StrikethroughText(
    text: String,
    struck: Boolean,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
    maxLines: Int = 2,
) = StrikethroughText(AnnotatedString(text), struck, style, modifier, color, maxLines)
