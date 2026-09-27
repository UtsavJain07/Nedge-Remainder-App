package app.nudge.feature.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.NotificationsPaused
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.nudge.core.common.DayPart
import app.nudge.core.common.dayPartOf
import app.nudge.core.designsystem.component.EmptyIllustration
import app.nudge.core.designsystem.component.FocusCard
import app.nudge.core.designsystem.component.HealthBanner
import app.nudge.core.designsystem.component.Illustration
import app.nudge.core.designsystem.component.ListCard
import app.nudge.core.designsystem.component.NewListCard
import app.nudge.core.designsystem.component.pressScale
import app.nudge.core.designsystem.theme.EmphasizedType
import app.nudge.core.designsystem.theme.LocalReducedMotion
import app.nudge.core.designsystem.theme.Spacing
import app.nudge.core.domain.usecase.ListWithStats
import app.nudge.core.model.SmartViewType
import app.nudge.core.model.TaskList
import app.nudge.core.ui.format.currentLocale
import app.nudge.core.ui.format.formatInstant
import app.nudge.core.ui.list.ListEditorSheet
import app.nudge.core.ui.nav.LocalNavAnimatedVisibilityScope
import app.nudge.core.ui.nav.SharedKeys
import app.nudge.core.ui.nav.sharedElementOrNone
import app.nudge.core.ui.snackbar.LocalSnackbar
import app.nudge.feature.quickadd.QuickAddSheet
import app.nudge.feature.taskdetail.TaskDetailSheet
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

@Serializable
data object HomeRoute

/** Navigation callbacks out of Home (features never depend on each other, 05 §3). */
data class HomeNavigation(
    val openList: (String) -> Unit,
    val openSmartView: (SmartViewType) -> Unit,
    val openSearch: () -> Unit,
    val openSettings: () -> Unit,
    val openHealth: () -> Unit,
)

fun NavGraphBuilder.homeScreen(nav: HomeNavigation) {
    composable<HomeRoute> {
        androidx.compose.runtime.CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
            HomeScreen(nav)
        }
    }
}

@Composable
fun HomeScreen(nav: HomeNavigation, viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val resources = LocalResources.current
    val undo = stringResource(R.string.action_undo)
    var showQuickAdd by rememberSaveable { mutableStateOf(false) }
    var detailTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var editor by remember { mutableStateOf<EditorTarget?>(null) }
    var confirmDelete by remember { mutableStateOf<TaskList?>(null) }
    val gridState = rememberLazyGridState()

    LifecycleResumeEffect(Unit) {
        viewModel.refreshHealth()
        onPauseOrDispose { }
    }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { e ->
            when (e) {
                is HomeEffect.Undo -> snackbar.show(e.message.resolve(resources), undo, e.durationMs) { viewModel.undo(e.snapshot) }
                is HomeEffect.Message -> snackbar.show(e.message.resolve(resources))
            }
        }
    }

    val model = state.model
    // Local order for list reordering (FR-04), synced from the DB when not dragging.
    var order by remember { mutableStateOf<List<ListWithStats>>(emptyList()) }
    var reordering by remember { mutableStateOf(false) }
    LaunchedEffect(model?.lists) { if (!reordering) order = model?.lists.orEmpty() }
    var movedId by remember { mutableStateOf<String?>(null) }
    val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
        val fromKey = from.key as? String ?: return@rememberReorderableLazyGridState
        val toKey = to.key as? String ?: return@rememberReorderableLazyGridState
        val fi = order.indexOfFirst { it.list.id == fromKey }
        val ti = order.indexOfFirst { it.list.id == toKey }
        if (fi < 0 || ti < 0) return@rememberReorderableLazyGridState
        order = order.toMutableList().apply { add(ti, removeAt(fi)) }
        movedId = fromKey
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar.hostState) },
        floatingActionButton = {
            val interaction = remember { MutableInteractionSource() }
            ExtendedFloatingActionButton(
                onClick = { showQuickAdd = true },
                expanded = gridState.firstVisibleItemIndex == 0,
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text(stringResource(R.string.home_new_task)) },
                shape = MaterialTheme.shapes.large,
                interactionSource = interaction,
                modifier = Modifier.pressScale(interaction, 0.9f).testTag("home_fab"),
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            state = gridState,
            contentPadding = PaddingValues(
                start = Spacing.screenPadding,
                end = Spacing.screenPadding,
                bottom = padding.calculateBottomPadding() + 104.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(Spacing.cardGap),
            verticalArrangement = Arrangement.spacedBy(Spacing.cardGap),
            modifier = Modifier.fillMaxSize().testTag("home_grid"),
        ) {
            item(key = "header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                Header(state.now, viewModel, nav)
            }
            if (state.health.isDegraded) {
                item(key = "health", span = { GridItemSpan(maxLineSpan) }, contentType = "banner") {
                    HealthBanner(
                        message = stringResource(if (!state.health.notificationsEnabled) R.string.home_health_notifications else R.string.home_health_delayed),
                        onFix = nav.openHealth,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            val paused = model?.settings?.reminders?.pausedUntil
            if (paused != null && paused.isAfter(state.now)) {
                item(key = "paused", span = { GridItemSpan(maxLineSpan) }, contentType = "banner") {
                    PausedChip(paused, state, onResume = viewModel::onResume, modifier = Modifier.animateItem())
                }
            }
            if (model != null && model.focus.isNotEmpty()) {
                item(key = "focus", span = { GridItemSpan(maxLineSpan) }, contentType = "focus") {
                    Column(Modifier.animateItem()) {
                        SectionTitle(stringResource(R.string.home_focus_now))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                            itemsIndexed(model.focus, key = { _, it -> it.task.id }) { index, f ->
                                StaggerIn(index) {
                                    FocusCard(
                                        task = f.task,
                                        listName = f.list.name,
                                        listColor = Color(f.list.colorArgb),
                                        progress = f.task.progress,
                                        completing = f.task.id in state.completing,
                                        onOpen = { detailTaskId = f.task.id },
                                        onComplete = { viewModel.completeFocus(f.task.id, f.task.title) },
                                        modifier = Modifier.animateItem(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (model != null) {
                item(key = "smart-today", contentType = "smart") {
                    SmartViewCard(Icons.Rounded.Today, stringResource(R.string.home_today), model.todayCount) { nav.openSmartView(SmartViewType.TODAY) }
                }
                item(key = "smart-all", contentType = "smart") {
                    SmartViewCard(Icons.AutoMirrored.Rounded.ListAlt, stringResource(R.string.home_all_tasks), model.allCount) { nav.openSmartView(SmartViewType.ALL) }
                }
                item(key = "lists-title", span = { GridItemSpan(maxLineSpan) }, contentType = "title") {
                    SectionTitle(stringResource(R.string.home_my_lists))
                }
                items(order, key = { it.list.id }, contentType = { "list" }) { lws ->
                    ReorderableItem(reorderState, key = lws.list.id) { isDragging ->
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            ListCard(
                                list = lws.list,
                                stats = lws.stats,
                                onClick = { nav.openList(lws.list.id) },
                                elevated = isDragging,
                                containerModifier = Modifier.sharedElementOrNone(SharedKeys.listContainer(lws.list.id), bounds = true),
                                iconModifier = Modifier.sharedElementOrNone(SharedKeys.listIcon(lws.list.id)),
                                nameModifier = Modifier.sharedElementOrNone(SharedKeys.listName(lws.list.id)),
                                modifier = Modifier.longPressDraggableHandle(
                                    onDragStarted = {
                                        reordering = true
                                        movedId = null
                                    },
                                    onDragStopped = {
                                        reordering = false
                                        val moved = movedId
                                        if (moved != null) {
                                            viewModel.onReorder(order.map { it.list.id }, moved)
                                        } else {
                                            menu = true // released without moving → menu (03 §3.2)
                                        }
                                    },
                                ),
                            )
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.home_edit_list)) },
                                    leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                                    onClick = {
                                        menu = false
                                        editor = EditorTarget(lws.list)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.home_delete_list), color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                    enabled = order.size > 1,
                                    onClick = {
                                        menu = false
                                        confirmDelete = lws.list
                                    },
                                )
                            }
                        }
                    }
                }
                item(key = "new-list", contentType = "new") {
                    NewListCard(onClick = { editor = EditorTarget(null) }, modifier = Modifier.animateItem())
                }
                if (model.totalTaskCount == 0 && model.lists.size <= 1) {
                    item(key = "empty", span = { GridItemSpan(maxLineSpan) }, contentType = "empty") {
                        HomeEmptyState()
                    }
                }
            }
        }
    }

    if (showQuickAdd) QuickAddSheet(listId = null, parentId = null, showListPicker = true, onDismiss = { showQuickAdd = false })
    detailTaskId?.let { TaskDetailSheet(taskId = it, onDismiss = { detailTaskId = null }) }
    editor?.let { target ->
        ListEditorSheet(
            existing = target.list,
            defaultColor = viewModel.nextColor(),
            onSave = { name, color, emoji ->
                if (target.list == null) viewModel.onCreateList(name, color, emoji) else viewModel.onEditList(target.list.id, name, color, emoji)
            },
            onDismiss = { editor = null },
        )
    }
    confirmDelete?.let { list ->
        val count = model?.lists?.firstOrNull { it.list.id == list.id }?.stats?.total ?: 0
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(pluralStringResource(R.plurals.home_delete_confirm, count, list.name, count)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    viewModel.onDeleteList(list)
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private data class EditorTarget(val list: TaskList?)

/** Greeting + date with search/settings (A14). */
@Composable
private fun Header(now: Instant, vm: HomeViewModel, nav: HomeNavigation) {
    val zoned = now.atZone(vm.clock.zone())
    val part = dayPartOf(zoned.hour)
    val reduced = LocalReducedMotion.current
    val enter = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(300)) }
    val rise = with(LocalDensity.current) { 16.dp.toPx() }
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = Spacing.l, bottom = Spacing.s),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            Modifier
                .weight(1f)
                .graphicsLayer {
                    alpha = enter.value
                    translationY = (1f - enter.value) * rise
                },
        ) {
            AnimatedContent(part, transitionSpec = { (fadeIn() + slideInVertically { it / 2 }) togetherWith fadeOut() }, label = "greeting") { p ->
                Text(
                    stringResource(
                        when (p) {
                            DayPart.MORNING -> R.string.greeting_morning
                            DayPart.AFTERNOON -> R.string.greeting_afternoon
                            DayPart.EVENING -> R.string.greeting_evening
                            DayPart.NIGHT -> R.string.greeting_night
                        },
                    ),
                    style = EmphasizedType.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                DateTimeFormatter.ofPattern("EEEE, d MMMM", currentLocale()).format(zoned),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = nav.openSearch, modifier = Modifier.testTag("home_search")) {
            Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.home_search))
        }
        IconButton(onClick = nav.openSettings, modifier = Modifier.testTag("home_settings")) {
            Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.home_settings))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = Spacing.s, bottom = Spacing.s),
    )
}

/** A14: Focus cards stagger in (40 ms apart, SpringBouncy). */
@Composable
private fun StaggerIn(index: Int, content: @Composable () -> Unit) {
    val reduced = LocalReducedMotion.current
    val p = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        delay(120L + index * 40L)
        p.animateTo(1f, spring(0.6f, 500f))
    }
    Box(
        Modifier.graphicsLayer {
            alpha = p.value.coerceIn(0f, 1f)
            val s = 0.9f + 0.1f * p.value
            scaleX = s
            scaleY = s
        },
    ) { content() }
}

@Composable
private fun SmartViewCard(icon: ImageVector, title: String, count: Int, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .pressScale(interaction)
            .clip(MaterialTheme.shapes.medium)
            .clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$title, $count" },
    ) {
        Row(Modifier.padding(Spacing.l).heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(Spacing.m))
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.weight(1f))
            Text("$count", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

/** FR-71: "Reminders paused until 3:00 PM [Resume]". */
@Composable
private fun PausedChip(until: Instant, state: HomeUiState, onResume: () -> Unit, modifier: Modifier = Modifier) {
    val zone = java.time.ZoneId.systemDefault()
    val today = state.now.atZone(zone).toLocalDate()
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.medium, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = Spacing.l, end = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.NotificationsPaused, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.width(Spacing.m))
            Text(
                if (until == Instant.MAX) stringResource(R.string.home_paused_forever) else stringResource(R.string.home_paused_until, formatInstant(until, zone, today)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onResume) { Text(stringResource(R.string.home_resume)) }
        }
    }
}

/** 03 §3.2 empty state with an arrow animating toward the FAB. */
@Composable
private fun HomeEmptyState() {
    val reduced = LocalReducedMotion.current
    val bob = if (reduced) {
        0f
    } else {
        val t = rememberInfiniteTransition(label = "arrow")
        val v by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "bob")
        v
    }
    val px = with(LocalDensity.current) { 8.dp.toPx() }
    Column(Modifier.fillMaxWidth().padding(top = Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally) {
        Illustration(EmptyIllustration.WELCOME, size = 140.dp)
        Text(
            stringResource(R.string.home_empty),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(Spacing.l),
        )
        Text(
            "↘",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.End)
                .padding(end = Spacing.xxl)
                .graphicsLayer {
                    translationX = bob * px
                    translationY = bob * px
                },
        )
    }
}

