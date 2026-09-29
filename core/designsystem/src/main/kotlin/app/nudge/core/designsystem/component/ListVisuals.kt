package app.nudge.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nudge.core.designsystem.R
import app.nudge.core.designsystem.theme.LocalIsDarkTheme
import app.nudge.core.designsystem.theme.LocalNudgeColors
import app.nudge.core.designsystem.theme.Spacing
import app.nudge.core.designsystem.theme.contentColorOn
import app.nudge.core.designsystem.theme.listContainerColor
import app.nudge.core.model.ListStats
import app.nudge.core.model.TaskList

/** The list's icon: its emoji, or its first letter in a filled circle of the list color (FR-02). */
@Composable
fun ListIcon(name: String, emoji: String?, color: Color, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        if (!emoji.isNullOrBlank()) {
            Text(emoji, fontSize = (size.value * 0.5f).sp)
        } else {
            Text(
                text = name.trim().take(1).uppercase(),
                color = contentColorOn(color),
                style = MaterialTheme.typography.titleMedium.copy(fontSize = (size.value * 0.45f).sp),
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * Home list card (FR-06, A15). The container / icon / name modifiers let the caller attach
 * shared-element transitions (A8) without this component knowing about navigation.
 */
@Composable
fun ListCard(
    list: TaskList,
    stats: ListStats,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    containerModifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
    nameModifier: Modifier = Modifier,
    elevated: Boolean = false,
) {
    val color = Color(list.colorArgb)
    val container = listContainerColor(color, MaterialTheme.colorScheme.surfaceContainer, LocalIsDarkTheme.current)
    val interaction = remember { MutableInteractionSource() }
    val openText = pluralStringResource(R.plurals.open_count, stats.openCount, stats.openCount)
    val a11y = stringResource(R.string.list_card_a11y, list.name, openText, (stats.completedFraction * 100).toInt())
    Surface(
        color = container,
        shape = MaterialTheme.shapes.large,
        shadowElevation = if (elevated) 8.dp else 0.dp,
        modifier = modifier
            .then(containerModifier)
            .pressScale(interaction)
            .clip(MaterialTheme.shapes.large)
            .combinedClickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), onClick = onClick, onLongClick = onLongClick)
            .semantics(mergeDescendants = true) { contentDescription = a11y },
    ) {
        Column(Modifier.padding(Spacing.l).heightIn(min = 112.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                ListIcon(list.name, list.emoji, color, iconModifier)
                Spacer(Modifier.weight(1f))
                ProgressRing(fraction = stats.completedFraction, color = color, size = 36.dp, stroke = 4.dp) {
                    Text(
                        "${(stats.completedFraction * 100).toInt()}",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(Spacing.m))
            Text(
                list.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = nameModifier,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(openText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (stats.hasUrgent) {
                    Spacer(Modifier.width(Spacing.s))
                    Box(Modifier.size(8.dp).clip(CircleShape).background(LocalNudgeColors.current.urgent.color))
                }
            }
        }
    }
}

/** FR-83: dashed "New list" card. */
@Composable
fun NewListCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val outline = MaterialTheme.colorScheme.outline
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .heightIn(min = 144.dp)
            .pressScale(interaction)
            .clip(MaterialTheme.shapes.large)
            .drawBehind {
                drawRoundRect(
                    color = outline,
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))),
                    cornerRadius = CornerRadius(24.dp.toPx()),
                )
            }
            .clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(Spacing.s))
            Text(stringResource(R.string.new_list), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** 12-color picker in a 6×2 grid; the selected swatch gets a check and an outline ring (03 §3.6). */
@Composable
fun ColorSwatchPicker(colors: List<Int>, names: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        colors.chunked(6).forEachIndexed { rowIndex, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                row.forEachIndexed { i, argb ->
                    val c = Color(argb)
                    val isSelected = argb == selected
                    val ringWidth by androidx.compose.animation.core.animateDpAsState(
                        if (isSelected) 3.dp else 0.dp,
                        androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 500f),
                        label = "ring",
                    )
                    Box(
                        Modifier
                            .size(48.dp)
                            .border(ringWidth.coerceAtLeast(0.dp), c, CircleShape)
                            .padding(5.dp)
                            .clip(CircleShape)
                            .background(c)
                            .clickable(role = Role.RadioButton) { onSelect(argb) }
                            .semantics {
                                contentDescription = names.getOrElse(rowIndex * 6 + i) { "" }
                                this.selected = isSelected
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSelected) Icon(Icons.Rounded.Check, contentDescription = null, tint = contentColorOn(c))
                    }
                }
            }
        }
    }
}

