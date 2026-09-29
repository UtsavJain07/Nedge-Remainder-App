package app.nudge.feature.list

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.nudge.core.designsystem.component.ConfettiOverlay
import app.nudge.core.designsystem.component.EmptyIllustration
import app.nudge.core.designsystem.component.EmptyState
import app.nudge.core.designsystem.component.HapticEvent
import app.nudge.core.designsystem.component.SectionHeader
import app.nudge.core.designsystem.component.highlightPulse
import app.nudge.core.designsystem.component.rememberNudgeHaptics
import app.nudge.core.designsystem.theme.LocalReducedMotion
import app.nudge.core.designsystem.theme.Spacing
import app.nudge.core.designsystem.theme.centeringPadding
import app.nudge.core.domain.tree.CompletedHeaderUi
import app.nudge.core.domain.tree.TaskItemUi
import app.nudge.core.model.ListSortMode
import app.nudge.core.model.MoveOperation
import app.nudge.core.model.effectiveCadence
import app.nudge.core.ui.dnd.DndRow
import app.nudge.core.ui.dnd.DragDropState
import app.nudge.core.ui.dnd.dragToReorder
import app.nudge.core.ui.dnd.rememberDragDropState
import app.nudge.core.ui.snackbar.LocalSnackbar
import app.nudge.core.ui.snackbar.SnackbarDispatcher
import app.nudge.core.ui.task.RowDragVisual
import app.nudge.core.ui.task.TaskRow
import app.nudge.core.ui.task.TaskRowUi
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** One page of the list pager (03 §3.3). */
@Composable
internal fun ListPage(
    listId: String,
    vm: ListPageViewModel,
    isCurrent: Boolean,
    highlightTaskId: String?,
    onOpenDetails: (String) -> Unit,
    onAddSubtask: (String) -> Unit,
    onDraggingChange: (Boolean) -> Unit,
    onListDeleted: (app.nudge.core.model.UndoSnapshot, String) -> Unit,
    contentPadding: PaddingValues,
) {
    LaunchedEffect(listId) { vm.start(listId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val model = state.model
    val resources = LocalResources.current
    val snackbar = LocalSnackbar.current
    val haptics = rememberNudgeHaptics()
    val undo = stringResource(R.string.action_undo)
    val completeLabel = stringResource(R.string.action_complete)
    var confetti by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val dnd = rememberDragDropState(listState) { op -> vm.onMove(op) }
    LaunchedEffect(dnd.isDragging) { onDraggingChange(dnd.isDragging) }

    LaunchedEffect(vm) {
        vm.effects.collect { e ->
            when (e) {
                is ListEffect.Undo -> snackbar.show(e.message.resolve(resources), undo, e.durationMs) { vm.undo(e.snapshot) }
                is ListEffect.AllSubtasksDone -> snackbar.show(
                    resources.getString(R.string.list_all_subtasks_done, e.parentTitle),
                    completeLabel,
                    SnackbarDispatcher.COMPLETE_MS,
                ) { vm.completeParent(e.parentId) }
                is ListEffect.Message -> snackbar.show(e.message.resolve(resources))
                ListEffect.AllDone -> confetti++
                is ListEffect.ListDeleted -> onListDeleted(e.snapshot, e.name)
            }
        }
    }

    if (model == null) {
        Box(Modifier.fillMaxSize())
        return
    }
    val settings = model.settings
    val reminders = settings.reminders
    val openItems = remember(model.items) { model.items.takeWhile { it !is CompletedHeaderUi }.filterIsInstance<TaskItemUi>() }
    val completedHeader = model.items.firstOrNull { it is CompletedHeaderUi } as CompletedHeaderUi?
    val completedItems = remember(model.items) { model.items.dropWhile { it !is CompletedHeaderUi }.filterIsInstance<TaskItemUi>() }
    val dbRows = remember(openItems) {
        openItems.map { DndRow(it.key, it.depth, it.task.parentId, it.isParent, it.task.isExpanded, it.task.isCompleted) }
    }
    LaunchedEffect(dbRows) { dnd.onDbRows(dbRows) }
    val byKey = remember(openItems) { openItems.associateBy { it.key } }
    val rendered: List<Pair<TaskItemUi, Int>> = if (dnd.showPreview) {
        dnd.previewRows.mapNotNull { r -> byKey[r.key]?.let { it to r.depth } }
    } else {
        openItems.map { it to it.depth }
    }
    val dragEnabled = model.list.sortMode == ListSortMode.MY_ORDER
    val now = vm.now()
    val zone = vm.clock.zone()
    val today = now.atZone(zone).toLocalDate()
    var focusedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var menuKey by remember { mutableStateOf<String?>(null) }
    var confirmDeleteCompleted by remember { mutableStateOf(false) }
    var highlightKey by rememberSaveable(highlightTaskId) { mutableStateOf(highlightTaskId) }
    val sortTip = stringResource(R.string.list_sort_tip)

    // Scroll to and pulse the highlighted task (FR-84, A19).
    LaunchedEffect(highlightKey, model.items.size) {
        val key = highlightKey ?: return@LaunchedEffect
        val idx = model.items.indexOfFirst { it.key == key }
        if (idx >= 0) listState.animateScrollToItem(idx)
    }

    // A3: rows that appear after the first frame animate in.
    val seen = remember { mutableSetOf<String>() }
    var firstFrame by remember { mutableStateOf(true) }
    SideEffect {
        seen += model.items.map { it.key }
        firstFrame = false
    }

    val layoutDirection = LocalLayoutDirection.current
    val side = centeringPadding()
    Box(Modifier.fillMaxSize()) {
        if (openItems.isEmpty() && completedItems.isEmpty() && completedHeader == null) {
            EmptyState(
                EmptyIllustration.EMPTY_LIST,
                title = stringResource(R.string.list_empty_title),
                body = stringResource(R.string.list_empty_body),
                modifier = Modifier.align(Alignment.Center).padding(contentPadding),
            )
        }
        LazyColumn(
            state = listState,
            // Wide screens: rows stay at a readable width, centered (03 §8); side insets respected in landscape.
            contentPadding = PaddingValues(
                start = contentPadding.calculateStartPadding(layoutDirection) + side,
                end = contentPadding.calculateEndPadding(layoutDirection) + side,
                top = contentPadding.calculateTopPadding() + Spacing.xs,
                bottom = contentPadding.calculateBottomPadding() + 96.dp,
            ),
            modifier = Modifier.fillMaxSize().testTag("task_list"),
        ) {
            if (openItems.isEmpty() && completedHeader != null) {
                item(key = "all-done", contentType = "header") {
                    EmptyState(
                        EmptyIllustration.ALL_DONE,
                        title = stringResource(R.string.list_all_done),
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            items(rendered, key = { it.first.key }, contentType = { if (it.second == 0) "task" else "subtask" }) { (item, depth) ->
                val task = item.task
                val isDragging = dnd.isDragging(item.key)
                val isNew = remember(item.key) { !firstFrame && item.key !in seen }
                val rowUi = TaskRowUi(
                    task = task,
                    depth = depth,
                    isParent = item.isParent,
                    progress = item.effectiveProgress,
                    childCounter = item.childCounter,
                    childCount = item.childCount,
                    effectiveCadence = task.effectiveCadence(reminders),
                    completing = task.id in state.completing,
                )
                val a11y = accessibilityActions(item, rendered.map { it.first }, dragEnabled, vm, onDelete = { vm.onDelete(task.id, task.title) }, resources)
                // Tapping a row opens its details; only the circle completes it.
                val toggle = {
                    focusedKey = item.key
                    if (!task.isCompleted) haptics.perform(HapticEvent.COMPLETE)
                    vm.onToggle(task.id, task.title, task.isCompleted)
                }
                val open = {
                    focusedKey = item.key
                    onOpenDetails(task.id)
                }
                val placement = Modifier.then(
                    if (isDragging) {
                        Modifier.zIndex(1f)
                    } else {
                        Modifier.animateItem(fadeInSpec = tween(220), placementSpec = spring(0.6f, 500f), fadeOutSpec = tween(200))
                    },
                )
                Box(placement.then(insertAnimation(isNew)).highlightPulse(highlightKey == item.key, MaterialTheme.colorScheme.primary) { highlightKey = null }) {
                    SwipeRow(
                        enabled = !dnd.isDragging && !task.isCompleted,
                        onComplete = {
                            haptics.perform(HapticEvent.COMPLETE)
                            vm.completeNow(task.id, task.title)
                        },
                        onDelete = {
                            haptics.perform(HapticEvent.DELETE)
                            vm.onDelete(task.id, task.title)
                        },
                    ) {
                        TaskRow(
                            ui = rowUi,
                            today = today,
                            now = now,
                            zone = zone,
                            onTap = open,
                            onToggleComplete = toggle,
                            onOpenDetails = { onOpenDetails(task.id) },
                            showAddSubtask = !task.isCompleted && depth == 0 && (item.isParent || focusedKey == item.key),
                            onAddSubtask = { onAddSubtask(task.id) },
                            onToggleExpanded = { vm.onToggleExpanded(task.id, !task.isExpanded) },
                            drag = dragVisual(dnd, item.key),
                            accessibilityActions = a11y,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.surface)
                                .dragToReorder(
                                    state = dnd,
                                    key = item.key,
                                    enabled = dragEnabled && !task.isCompleted && isCurrent,
                                    rows = { dbRows },
                                    onLongPressMenu = { menuKey = item.key },
                                    onDisabledLongPress = {
                                        if (!dragEnabled) snackbar.show(sortTip)
                                        menuKey = item.key
                                    },
                                ),
                        )
                    }
                    TaskContextMenu(
                        expanded = menuKey == item.key,
                        item = item,
                        openTopLevel = openItems.filter { it.depth == 0 && !it.task.isCompleted },
                        vm = vm,
                        onDismiss = { menuKey = null },
                        onOpenDetails = { onOpenDetails(task.id) },
                    )
                }
                // A6 ghost placeholder under the armed nest target's last child.
                if (dnd.nestTargetKey != null && isLastOfGroup(item, rendered.map { it.first }, dnd.nestTargetKey!!)) {
                    NestGhost()
                }
            }
            if (completedHeader != null) {
                item(key = CompletedHeaderUi.KEY, contentType = "header") {
                    Box(Modifier.animateItem()) {
                        var headerMenu by remember { mutableStateOf(false) }
                        SectionHeader(
                            title = stringResource(R.string.list_completed_header, completedHeader.count),
                            expanded = completedHeader.expanded,
                            onToggle = vm::onToggleCompletedSection,
                        ) {
                            Box {
                                IconButton(onClick = { headerMenu = true }) {
                                    Icon(Icons.Rounded.MoreVert, stringResource(R.string.list_completed_menu))
                                }
                                DropdownMenu(expanded = headerMenu, onDismissRequest = { headerMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.list_delete_all_completed)) },
                                        leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                                        onClick = {
                                            headerMenu = false
                                            confirmDeleteCompleted = true
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                completedSection(completedItems, reminders, today, now, zone, vm, onOpenDetails)
            }
        }
        ConfettiOverlay(confetti, listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.secondary, Color(0xFFFFB300), Color(0xFFEC407A)))
    }

    if (confirmDeleteCompleted) {
        AlertDialog(
            onDismissRequest = { confirmDeleteCompleted = false },
            title = { Text(stringResource(R.string.list_delete_completed_title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteCompleted = false
                    vm.onDeleteCompleted()
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteCompleted = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private fun LazyListScope.completedSection(
    items: List<TaskItemUi>,
    reminders: app.nudge.core.model.ReminderSettings,
    today: java.time.LocalDate,
    now: java.time.Instant,
    zone: java.time.ZoneId,
    vm: ListPageViewModel,
    onOpenDetails: (String) -> Unit,
) {
    items(items, key = { it.key }, contentType = { if (it.depth == 0) "task" else "subtask" }) { item ->
        val task = item.task
        Box(Modifier.animateItem(fadeInSpec = tween(220), fadeOutSpec = tween(200))) {
            TaskRow(
                ui = TaskRowUi(task, item.depth, item.isParent, item.effectiveProgress, item.childCounter, item.childCount, task.effectiveCadence(reminders)),
                today = today,
                now = now,
                zone = zone,
                onTap = { onOpenDetails(task.id) },
                onToggleComplete = { vm.onToggle(task.id, task.title, task.isCompleted) },
                onOpenDetails = { onOpenDetails(task.id) },
                onToggleExpanded = { vm.onToggleExpanded(task.id, !task.isExpanded) },
            )
        }
    }
}

/** A5/A6/A7 visual state for a row. */
private fun dragVisual(dnd: DragDropState, key: String): RowDragVisual {
    val dragging = dnd.isDragging(key)
    return RowDragVisual(
        isDragging = dragging,
        isNestTarget = dnd.nestTargetKey == key,
        rejectTick = if (dnd.rejectTargetKey == key) dnd.rejectTick else 0,
        hiddenChildCount = if (dragging) dnd.hiddenChildCount else 0,
        dy = if (dragging) dnd.dragDelta.y else 0f,
        dx = 0f,
    )
}

/** A3: scaleY 0.8 → 1 and translationY −12 dp → 0 (SpringBouncy) for newly inserted rows. */
@Composable
private fun insertAnimation(isNew: Boolean): Modifier {
    if (!isNew || LocalReducedMotion.current) return Modifier
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, spring(0.6f, 500f)) }
    val px = with(LocalDensity.current) { 12.dp.toPx() }
    return Modifier.graphicsLayer {
        scaleY = 0.8f + 0.2f * progress.value
        translationY = -px * (1f - progress.value)
    }
}

/** Swipe right = complete (list color + ✓), left = delete (error + 🗑); threshold 35% (A18). */
@Composable
private fun SwipeRow(enabled: Boolean, onComplete: () -> Unit, onDelete: () -> Unit, content: @Composable () -> Unit) {
    val haptics = rememberNudgeHaptics()
    val state = rememberSwipeToDismissBoxState(positionalThreshold = { it * 0.35f })
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = enabled,
        enableDismissFromEndToStart = enabled,
        gesturesEnabled = enabled,
        onDismiss = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> onComplete()
                SwipeToDismissBoxValue.EndToStart -> onDelete()
                SwipeToDismissBoxValue.Settled -> Unit
            }
        },
        backgroundContent = {
            val direction = state.dismissDirection
            val progress = state.progress
            val armed = state.targetValue != SwipeToDismissBoxValue.Settled
            LaunchedEffect(armed) { if (armed) haptics.perform(HapticEvent.THRESHOLD) }
            val scale by animateFloatAsState(if (armed) 1.2f else 0.6f + 0.4f * progress, spring(0.6f, 500f), label = "swipeIcon")
            val (color, icon, align) = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> Triple(MaterialTheme.colorScheme.primary, Icons.Rounded.Check, Alignment.CenterStart)
                SwipeToDismissBoxValue.EndToStart -> Triple(MaterialTheme.colorScheme.error, Icons.Rounded.Delete, Alignment.CenterEnd)
                SwipeToDismissBoxValue.Settled -> Triple(Color.Transparent, null, Alignment.Center)
            }
            Box(Modifier.fillMaxSize().background(color).padding(horizontal = Spacing.xl), contentAlignment = align) {
                if (icon != null) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = if (direction == SwipeToDismissBoxValue.EndToStart) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        },
                    )
                }
            }
        },
    ) { content() }
}

@Composable
private fun NestGhost() {
    val color = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = Spacing.subtaskIndent + Spacing.l, end = Spacing.l, top = Spacing.xs, bottom = Spacing.xs)
            .animateContentSize()
            .height(48.dp)
            .graphicsLayer { alpha = 0.5f }
            .drawBehind {
                drawRoundRect(
                    color,
                    style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                    cornerRadius = CornerRadius(12.dp.toPx()),
                )
            },
    )
}

private fun isLastOfGroup(item: TaskItemUi, rows: List<TaskItemUi>, targetKey: String): Boolean {
    val groupKeys = rows.filter { it.key == targetKey || it.task.parentId == targetKey }.map { it.key }
    return groupKeys.lastOrNull() == item.key
}

/** 08 §6: custom actions mirroring every drag gesture (FR-26, NFR-05). */
private fun accessibilityActions(
    item: TaskItemUi,
    rows: List<TaskItemUi>,
    dragEnabled: Boolean,
    vm: ListPageViewModel,
    onDelete: () -> Unit,
    resources: android.content.res.Resources,
): List<CustomAccessibilityAction> {
    val task = item.task
    val actions = mutableListOf<CustomAccessibilityAction>()
    if (!task.isCompleted && dragEnabled) {
        val siblings = rows.filter { it.task.parentId == task.parentId && !it.task.isCompleted }
        val i = siblings.indexOfFirst { it.key == item.key }
        if (i > 0) {
            actions += CustomAccessibilityAction(resources.getString(R.string.a11y_move_up)) {
                vm.onMove(MoveOperation.Reorder(task.id, task.parentId, siblings.getOrNull(i - 2)?.key, siblings[i - 1].key))
                true
            }
        }
        if (i in 0 until siblings.lastIndex) {
            actions += CustomAccessibilityAction(resources.getString(R.string.a11y_move_down)) {
                vm.onMove(MoveOperation.Reorder(task.id, task.parentId, siblings[i + 1].key, siblings.getOrNull(i + 2)?.key))
                true
            }
        }
        if (task.parentId == null && !item.isParent && i > 0) {
            val prev = siblings[i - 1]
            actions += CustomAccessibilityAction(resources.getString(R.string.a11y_make_subtask_of, prev.task.title)) {
                vm.onMove(MoveOperation.Nest(task.id, prev.key), prev.task.title)
                true
            }
        }
        if (task.parentId != null) {
            actions += CustomAccessibilityAction(resources.getString(R.string.a11y_move_top_level)) {
                vm.onMove(MoveOperation.ToTopLevel(task.id))
                true
            }
        }
    }
    actions += CustomAccessibilityAction(resources.getString(R.string.action_delete)) {
        onDelete()
        true
    }
    return actions
}
