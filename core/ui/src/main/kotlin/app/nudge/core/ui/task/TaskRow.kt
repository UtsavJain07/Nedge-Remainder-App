package app.nudge.core.ui.task

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AlarmOn
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.nudge.core.designsystem.component.AnimatedPercent
import app.nudge.core.designsystem.component.NudgeCheckbox
import app.nudge.core.designsystem.component.PriorityFlag
import app.nudge.core.designsystem.component.ProgressBarThin
import app.nudge.core.designsystem.component.StrikethroughText
import app.nudge.core.designsystem.component.cadenceShortLabel
import app.nudge.core.designsystem.component.priorityColor
import app.nudge.core.designsystem.component.priorityLabel
import app.nudge.core.designsystem.component.shake
import app.nudge.core.designsystem.theme.LocalNudgeColors
import app.nudge.core.designsystem.theme.Spacing
import app.nudge.core.model.Priority
import app.nudge.core.model.ReminderCadence
import app.nudge.core.model.Task
import app.nudge.core.ui.R
import app.nudge.core.ui.format.formatDue
import app.nudge.core.ui.format.formatInstant
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Everything a row renders (stateless; 04 §7 TaskRow). */
@Immutable
data class TaskRowUi(
    val task: Task,
    val depth: Int,
    val isParent: Boolean,
    val progress: Int,
    val childCounter: String?,
    val childCount: Int,
    val effectiveCadence: ReminderCadence,
    /** Checked immediately on tap while the 600 ms completion hold runs (A1 step 4). */
    val completing: Boolean = false,
    val parentTitle: String? = null,
    val showParentContext: Boolean = false,
) {
    val checked: Boolean get() = task.isCompleted || completing
}

/** Visual drag state for a row (A5, A6, A7). */
@Immutable
data class RowDragVisual(
    val isDragging: Boolean = false,
    val isNestTarget: Boolean = false,
    val rejectTick: Int = 0,
    val hiddenChildCount: Int = 0,
    val dx: Float = 0f,
    val dy: Float = 0f,
) {
    companion object {
        val NONE = RowDragVisual()
    }
}

/**
 * The central task row (03 §3.3). Tap = complete (or open details), › opens details, chevron toggles
 * subtasks, + adds a subtask. Gesture modifiers (drag, swipe) are applied by the caller.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskRow(
    ui: TaskRowUi,
    today: LocalDate,
    now: Instant,
    zone: ZoneId,
    onTap: () -> Unit,
    onToggleComplete: () -> Unit,
    onOpenDetails: () -> Unit,
    modifier: Modifier = Modifier,
    listColor: Color = MaterialTheme.colorScheme.primary,
    showAddSubtask: Boolean = false,
    onAddSubtask: () -> Unit = {},
    onToggleExpanded: () -> Unit = {},
    drag: RowDragVisual = RowDragVisual.NONE,
    accessibilityActions: List<CustomAccessibilityAction> = emptyList(),
) {
    val task = ui.task
    val isSub = ui.depth > 0
    val ringColor = if (task.priority == Priority.NONE) MaterialTheme.colorScheme.outline else priorityColor(task.priority)
    val fillColor = if (task.priority == Priority.NONE) listColor else priorityColor(task.priority)

    // A5 lift / A6 nest target.
    val elevation by animateDpAsState(if (drag.isDragging) 8.dp else 0.dp, spring(0.8f, 800f), label = "elev")
    val scale by animateFloatAsState(
        when {
            drag.isDragging -> 1.03f
            drag.isNestTarget -> 1.02f
            else -> 1f
        },
        spring(0.8f, 800f),
        label = "scale",
    )
    val corner by animateDpAsState(if (drag.isDragging || drag.isNestTarget) 12.dp else 0.dp, spring(0.8f, 800f), label = "corner")
    val container by animateColorAsState(
        when {
            drag.isDragging -> MaterialTheme.colorScheme.surfaceContainerHighest
            drag.isNestTarget -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
            else -> Color.Transparent
        },
        label = "container",
    )
    val indent by animateDpAsState(if (isSub) Spacing.subtaskIndent else 0.dp, spring(0.8f, 800f), label = "indent")

    val a11y = rowDescription(ui, today)
    val completeLabel = stringResource(if (ui.checked) R.string.a11y_mark_not_completed else R.string.a11y_mark_completed)
    val stateText = stringResource(if (ui.checked) R.string.state_completed else R.string.state_not_completed)
    // Springs overshoot (A5); negative corner / elevation values crash, so clamp.
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(corner.coerceAtLeast(0.dp))

    Surface(
        color = container,
        shape = shape,
        shadowElevation = elevation.coerceAtLeast(0.dp),
        modifier = modifier
            .fillMaxWidth()
            .shake(if (drag.rejectTick > 0) drag.rejectTick else 0)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationY = drag.dy
                translationX = drag.dx
            }
            .then(if (drag.isNestTarget) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .clip(shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (isSub) Spacing.subtaskRowMinHeight else Spacing.rowMinHeight)
                .semantics(mergeDescendants = true) {
                    contentDescription = a11y
                    stateDescription = stateText
                    onClick(label = completeLabel) {
                        onTap()
                        true
                    }
                    if (accessibilityActions.isNotEmpty()) customActions = accessibilityActions
                }
                .clickable(onClick = onTap)
                .padding(start = indent.coerceAtLeast(0.dp) + Spacing.xs, end = Spacing.xs, top = Spacing.xxs, bottom = Spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NudgeCheckbox(
                checked = ui.checked,
                color = fillColor,
                ringColor = ringColor,
                onToggle = onToggleComplete,
                size = if (isSub) 20.dp else 24.dp,
                modifier = Modifier.clearAndSetSemantics { },
            )
            Column(Modifier.weight(1f).padding(vertical = Spacing.s)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PriorityFlag(task.priority, Modifier.padding(end = Spacing.xs))
                    StrikethroughText(
                        text = task.title,
                        struck = ui.checked,
                        style = if (isSub) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 3,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (drag.hiddenChildCount > 0) {
                        Text(
                            "+${drag.hiddenChildCount}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = Spacing.s),
                        )
                    }
                }
                MetaLine(ui, today, now, zone)
                AnimatedVisibility(ui.progress in 1..99 && !ui.checked, enter = fadeIn(), exit = fadeOut()) {
                    ProgressBarThin(ui.progress, fillColor, Modifier.padding(top = Spacing.xs, end = Spacing.s))
                }
            }
            if (ui.progress in 1..99 && !ui.checked) {
                AnimatedPercent(ui.progress, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Spacing.xs))
            }
            if (ui.isParent) {
                val rotation by animateFloatAsState(if (task.isExpanded) 180f else 0f, spring(0.8f, 800f), label = "chev")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(MaterialTheme.shapes.small)
                        .clickable(onClick = onToggleExpanded)
                        .padding(horizontal = Spacing.xs),
                ) {
                    Text(ui.childCounter.orEmpty(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(
                        Icons.Rounded.ExpandMore,
                        contentDescription = stringResource(if (task.isExpanded) R.string.a11y_collapse else R.string.a11y_expand),
                        modifier = Modifier.graphicsLayer { rotationZ = rotation },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            AnimatedVisibility(showAddSubtask && !isSub && !task.isCompleted, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                IconButton(onClick = onAddSubtask, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.a11y_add_subtask, task.title))
                }
            }
            IconButton(onClick = onOpenDetails, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.a11y_open_details, task.title),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetaLine(ui: TaskRowUi, today: LocalDate, now: Instant, zone: ZoneId) {
    val task = ui.task
    val due = task.dueDate
    val snoozed = task.reminder.snoozedUntil?.takeIf { it.isAfter(now) }
    val showCadence = !task.isCompleted && ui.effectiveCadence != ReminderCadence.OFF
    val showParent = ui.showParentContext && ui.parentTitle != null
    if (due == null && snoozed == null && !showCadence && task.notes.isBlank() && !showParent) return
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.padding(top = Spacing.xxs)) {
        if (showParent) MetaItem(null, "↳ ${ui.parentTitle}", muted)
        if (due != null) {
            val nowLocal = now.atZone(zone)
            val overdue = !task.isCompleted && (due < today || (due == today && task.dueTime?.let { it < nowLocal.toLocalTime() } == true))
            MetaItem(Icons.Rounded.CalendarToday, formatDue(due, task.dueTime, today), if (overdue) MaterialTheme.colorScheme.error else muted)
        }
        if (snoozed != null) {
            MetaItem(Icons.Rounded.Snooze, stringResource(R.string.meta_snoozed_until, formatInstant(snoozed, zone, today)), muted)
        } else if (showCadence) {
            MetaItem(Icons.Rounded.AlarmOn, cadenceShortLabel(ui.effectiveCadence), muted)
        }
        if (task.notes.isNotBlank()) MetaItem(Icons.AutoMirrored.Rounded.Notes, null, muted)
    }
}

@Composable
private fun MetaItem(icon: androidx.compose.ui.graphics.vector.ImageVector?, text: String?, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
        if (icon != null && text != null) Spacer(Modifier.width(Spacing.xxs))
        if (text != null) Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/** 03 §9: "Fix prod bug, Urgent priority, 40 percent, due today, 2 of 5 subtasks". */
@Composable
private fun rowDescription(ui: TaskRowUi, today: LocalDate): String {
    val parts = mutableListOf(ui.task.title)
    if (ui.task.priority != Priority.NONE) parts += stringResource(R.string.a11y_priority, priorityLabel(ui.task.priority))
    if (ui.progress in 1..99) parts += stringResource(R.string.a11y_percent, ui.progress)
    ui.task.dueDate?.let { parts += stringResource(R.string.a11y_due, formatDue(it, ui.task.dueTime, today)) }
    if (ui.isParent) {
        val done = ui.childCounter?.substringBefore('/')?.toIntOrNull() ?: 0
        parts += pluralStringResource(R.plurals.a11y_subtasks, ui.childCount, done, ui.childCount)
    }
    return parts.joinToString(", ")
}
