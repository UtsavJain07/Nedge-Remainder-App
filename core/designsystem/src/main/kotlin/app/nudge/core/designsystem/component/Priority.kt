package app.nudge.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.OutlinedFlag
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.nudge.core.designsystem.R
import app.nudge.core.designsystem.theme.LocalNudgeColors
import app.nudge.core.designsystem.theme.Spacing
import app.nudge.core.model.Priority

/** Priority color; None falls back to [noneColor]. */
@Composable
@ReadOnlyComposable
fun priorityColor(priority: Priority, noneColor: Color = MaterialTheme.colorScheme.onSurfaceVariant): Color =
    if (priority == Priority.NONE) noneColor else LocalNudgeColors.current.forPriority(priority).color

@Composable
@ReadOnlyComposable
fun priorityLabel(priority: Priority): String = stringResource(
    when (priority) {
        Priority.URGENT -> R.string.priority_urgent
        Priority.HIGH -> R.string.priority_high
        Priority.MEDIUM -> R.string.priority_medium
        Priority.LOW -> R.string.priority_low
        Priority.NONE -> R.string.priority_none
    },
)

/** FR-52: a colored flag next to the title; hidden for None. */
@Composable
fun PriorityFlag(priority: Priority, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    if (priority == Priority.NONE) return
    Icon(
        imageVector = Icons.Rounded.Flag,
        contentDescription = null, // the row's merged semantics carry the priority label
        tint = priorityColor(priority),
        modifier = modifier.size(size),
    )
}

/**
 * FR-51: color-coded priority chips. Without [includeNone], tapping the selected chip toggles back to
 * None (Quick Add); with it, None is an explicit fifth chip (Task Detail).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PriorityChipRow(
    selected: Priority,
    onSelect: (Priority) -> Unit,
    modifier: Modifier = Modifier,
    includeNone: Boolean = false,
) {
    val colors = LocalNudgeColors.current
    val options = if (includeNone) Priority.chipOrder + Priority.NONE else Priority.chipOrder
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        options.forEach { p ->
            val isSelected = p == selected
            val pc = colors.forPriority(p)
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(if (isSelected && !includeNone) Priority.NONE else p) },
                label = { Text(priorityLabel(p)) },
                leadingIcon = {
                    Icon(
                        if (p == Priority.NONE) Icons.Rounded.OutlinedFlag else Icons.Rounded.Flag,
                        contentDescription = null,
                        tint = pc.color,
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = pc.container,
                    selectedLabelColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        }
    }
}
