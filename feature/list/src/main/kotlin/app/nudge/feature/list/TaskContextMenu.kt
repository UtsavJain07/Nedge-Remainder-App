package app.nudge.feature.list

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material.icons.rounded.SubdirectoryArrowRight
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import app.nudge.core.designsystem.component.priorityColor
import app.nudge.core.designsystem.component.priorityLabel
import app.nudge.core.domain.tree.TaskItemUi
import app.nudge.core.model.MoveOperation
import app.nudge.core.model.Priority

private enum class MenuPage { MAIN, PRIORITY, SNOOZE, NEST }

/**
 * Long-press-without-moving menu (01 §4.4 #2, FR-26): Edit, Priority ›, Snooze ›, Make subtask of ›
 * / Move to top level, Delete. Move to list lives in Task Detail.
 */
@Composable
internal fun TaskContextMenu(
    expanded: Boolean,
    item: TaskItemUi,
    openTopLevel: List<TaskItemUi>,
    vm: ListPageViewModel,
    onDismiss: () -> Unit,
    onOpenDetails: () -> Unit,
) {
    var page by remember(expanded) { mutableStateOf(MenuPage.MAIN) }
    val task = item.task
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        when (page) {
            MenuPage.MAIN -> {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_edit)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null) },
                    onClick = {
                        onDismiss()
                        onOpenDetails()
                    },
                )
                if (!task.isCompleted) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_priority)) },
                        leadingIcon = { Icon(Icons.Rounded.Flag, null) },
                        trailingIcon = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                        onClick = { page = MenuPage.PRIORITY },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_snooze)) },
                        leadingIcon = { Icon(Icons.Rounded.Snooze, null) },
                        trailingIcon = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                        onClick = { page = MenuPage.SNOOZE },
                    )
                    if (task.parentId == null && !item.isParent) {
                        val candidates = openTopLevel.filter { it.key != item.key }
                        if (candidates.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_make_subtask)) },
                                leadingIcon = { Icon(Icons.Rounded.SubdirectoryArrowRight, null) },
                                trailingIcon = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                                onClick = { page = MenuPage.NEST },
                            )
                        }
                    }
                    if (task.parentId != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_top_level)) },
                            leadingIcon = { Icon(Icons.Rounded.VerticalAlignTop, null) },
                            onClick = {
                                onDismiss()
                                vm.onMove(MoveOperation.ToTopLevel(task.id))
                            },
                        )
                    }
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    onClick = {
                        onDismiss()
                        vm.onDelete(task.id, task.title)
                    },
                )
            }
            MenuPage.PRIORITY -> {
                BackItem { page = MenuPage.MAIN }
                (Priority.chipOrder + Priority.NONE).forEach { p ->
                    DropdownMenuItem(
                        text = { Text(priorityLabel(p)) },
                        leadingIcon = { Icon(Icons.Rounded.Flag, null, tint = priorityColor(p)) },
                        onClick = {
                            onDismiss()
                            vm.onPriority(task.id, p)
                        },
                    )
                }
            }
            MenuPage.SNOOZE -> {
                BackItem { page = MenuPage.MAIN }
                listOf(15L to R.string.snooze_15m, 60L to R.string.snooze_1h, 180L to R.string.snooze_3h).forEach { (m, label) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(label)) },
                        onClick = {
                            onDismiss()
                            vm.onSnoozeMinutes(task.id, m)
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.snooze_tomorrow)) },
                    onClick = {
                        onDismiss()
                        vm.onSnoozeTomorrow(task.id)
                    },
                )
            }
            MenuPage.NEST -> {
                BackItem { page = MenuPage.MAIN }
                openTopLevel.filter { it.key != item.key }.forEach { target ->
                    DropdownMenuItem(
                        text = { Text(target.task.title, maxLines = 1) },
                        onClick = {
                            onDismiss()
                            vm.onMove(MoveOperation.Nest(task.id, target.key), target.task.title)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun BackItem(onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.menu_back)) },
        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) },
        onClick = onClick,
    )
}
