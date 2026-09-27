package app.nudge.feature.smartview

import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.nudge.core.designsystem.component.EmptyIllustration
import app.nudge.core.designsystem.component.EmptyState
import app.nudge.core.designsystem.component.HapticEvent
import app.nudge.core.designsystem.component.ListIcon
import app.nudge.core.designsystem.component.rememberNudgeHaptics
import app.nudge.core.designsystem.theme.Spacing
import app.nudge.core.domain.usecase.TaskGroup
import app.nudge.core.model.SmartViewType
import app.nudge.core.model.TapAction
import app.nudge.core.model.effectiveCadence
import app.nudge.core.ui.nav.LocalNavAnimatedVisibilityScope
import app.nudge.core.ui.snackbar.LocalSnackbar
import app.nudge.core.ui.snackbar.SnackbarDispatcher
import app.nudge.core.ui.task.TaskRow
import app.nudge.core.ui.task.TaskRowUi
import app.nudge.feature.taskdetail.TaskDetailSheet
import kotlinx.serialization.Serializable

/** Smart view route; [type] is a [SmartViewType] name (03 §2). */
@Serializable
data class SmartViewRoute(val type: String)

fun NavController.navigateToSmartView(type: SmartViewType) = navigate(SmartViewRoute(type.name)) { launchSingleTop = true }

fun NavGraphBuilder.smartViewScreen(onBack: () -> Unit, onOpenList: (listId: String, taskId: String) -> Unit) {
    composable<SmartViewRoute> {
        CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
            SmartViewScreen(onBack = onBack, onOpenList = onOpenList)
        }
    }
}

/** Today / All tasks (FR-81, FR-82, 03 §3.7). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartViewScreen(
    onBack: () -> Unit,
    onOpenList: (listId: String, taskId: String) -> Unit,
    viewModel: SmartViewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val resources = LocalResources.current
    val haptics = rememberNudgeHaptics()
    val undo = stringResource(R.string.action_undo)
    val completeLabel = stringResource(R.string.action_complete)
    var detailTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    val isToday = viewModel.type == SmartViewType.TODAY

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { e ->
            when (e) {
                is SmartViewEffect.Undo -> snackbar.show(e.message.resolve(resources), undo, SnackbarDispatcher.COMPLETE_MS) { viewModel.undo(e.snapshot) }
                is SmartViewEffect.AllSubtasksDone -> snackbar.show(
                    resources.getString(R.string.smart_all_subtasks_done, e.parentTitle),
                    completeLabel,
                    SnackbarDispatcher.COMPLETE_MS,
                ) { viewModel.completeParent(e.parentId) }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar.hostState) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { Text(stringResource(if (isToday) R.string.smart_today else R.string.smart_all)) },
            )
        },
    ) { padding ->
        val now = viewModel.now()
        val zone = viewModel.clock.zone()
        val today = now.atZone(zone).toLocalDate()
        val reminders = state.settings.reminders
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (state.loaded && state.groups.isEmpty()) {
                EmptyState(
                    illustration = if (isToday) EmptyIllustration.NOTHING_DUE else EmptyIllustration.EMPTY_LIST,
                    title = stringResource(if (isToday) R.string.smart_empty_today else R.string.smart_empty_all),
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            LazyColumn(
                contentPadding = PaddingValues(bottom = Spacing.xxl),
                modifier = Modifier.fillMaxSize().testTag("smart_view_list"),
            ) {
                state.groups.forEach { group ->
                    val groupKey = if (group.isOverdue) "overdue" else group.list?.id.orEmpty()
                    stickyHeader(key = "header-$groupKey", contentType = "header") {
                        GroupHeader(
                            group = group,
                            onClick = group.list?.let { l -> { onOpenList(l.id, group.tasks.first().task.id) } },
                        )
                    }
                    items(group.tasks, key = { "$groupKey-${it.task.id}" }, contentType = { "task" }) { item ->
                        val task = item.task
                        val tap = {
                            if (state.settings.tapAction == TapAction.OPEN_DETAILS) {
                                detailTaskId = task.id
                            } else {
                                haptics.perform(HapticEvent.COMPLETE)
                                viewModel.onComplete(task.id, task.title)
                            }
                        }
                        TaskRow(
                            ui = TaskRowUi(
                                task = task,
                                depth = 0,
                                isParent = false,
                                progress = task.progress,
                                childCounter = null,
                                childCount = 0,
                                effectiveCadence = task.effectiveCadence(reminders),
                                completing = task.id in state.completing,
                                parentTitle = item.parentTitle,
                                showParentContext = true,
                            ),
                            today = today,
                            now = now,
                            zone = zone,
                            onTap = tap,
                            onToggleComplete = {
                                haptics.perform(HapticEvent.COMPLETE)
                                viewModel.onComplete(task.id, task.title)
                            },
                            onOpenDetails = { detailTaskId = task.id },
                            listColor = Color(item.list.colorArgb),
                            modifier = Modifier.animateItem(fadeInSpec = tween(220), placementSpec = spring(0.6f, 500f), fadeOutSpec = tween(200)),
                        )
                    }
                }
            }
        }
    }

    detailTaskId?.let { TaskDetailSheet(taskId = it, onDismiss = { detailTaskId = null }) }
}

/** Sticky group header: "💼 Work · 3" in the list color, or "Overdue · 2" in error color (03 §3.7). */
@Composable
private fun GroupHeader(group: TaskGroup, onClick: (() -> Unit)?) {
    val list = group.list
    val color = if (group.isOverdue || list == null) MaterialTheme.colorScheme.error else Color(list.colorArgb)
    val title = if (group.isOverdue || list == null) stringResource(R.string.smart_overdue) else list.name
    val openLabel = list?.let { stringResource(R.string.smart_open_list, it.name) }
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = openLabel, onClick = onClick) else Modifier)
                .semantics { heading() }
                .padding(horizontal = Spacing.l, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (list == null || group.isOverdue) {
                Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
            } else {
                ListIcon(list.name, list.emoji, color, size = 24.dp)
            }
            Spacer(Modifier.width(Spacing.s))
            Text(
                stringResource(R.string.smart_group_header, title, group.tasks.size),
                style = MaterialTheme.typography.titleSmall,
                color = color,
            )
        }
    }
}
