package app.nudge.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nudge.core.designsystem.R
import app.nudge.core.designsystem.theme.LocalNudgeColors
import app.nudge.core.designsystem.theme.Spacing
import app.nudge.core.model.Task

/** "Focus now" card (FR-80): tap opens details, the check completes. */
@Composable
fun FocusCard(
    task: Task,
    listName: String,
    listColor: Color,
    progress: Int,
    completing: Boolean,
    onOpen: () -> Unit,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pc = LocalNudgeColors.current.forPriority(task.priority)
    val interaction = remember { MutableInteractionSource() }
    val a11y = stringResource(R.string.focus_card_a11y, task.title, priorityLabel(task.priority), listName)
    Surface(
        color = pc.container,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
            .width(168.dp)
            .pressScale(interaction)
            .clip(MaterialTheme.shapes.medium)
            .clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), onClick = onOpen)
            .semantics { contentDescription = a11y },
    ) {
        Column(Modifier.padding(start = Spacing.xs, end = Spacing.m, top = Spacing.xs, bottom = Spacing.m).heightIn(min = 112.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NudgeCheckbox(checked = completing, color = pc.color, onToggle = onComplete, label = task.title)
                PriorityFlag(task.priority)
            }
            StrikethroughText(
                task.title,
                struck = completing,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = Spacing.m),
            )
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(Spacing.s))
            Row(Modifier.padding(start = Spacing.m), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listName,
                    style = MaterialTheme.typography.labelSmall,
                    color = listColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (progress > 0) {
                    Text(" · $progress%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
