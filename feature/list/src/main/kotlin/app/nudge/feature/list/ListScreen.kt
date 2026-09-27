package app.nudge.feature.list

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.nudge.core.designsystem.component.ListIcon
import app.nudge.core.designsystem.component.pressScale
import app.nudge.core.designsystem.theme.NudgeSeededTheme
import app.nudge.core.designsystem.theme.Spacing
import app.nudge.core.model.ListColors
import app.nudge.core.model.ListSortMode
import app.nudge.core.model.TaskList
import app.nudge.core.ui.list.ListEditorSheet
import app.nudge.core.ui.nav.LocalNavAnimatedVisibilityScope
import app.nudge.core.ui.nav.SharedKeys
import app.nudge.core.ui.nav.sharedElementOrNone
import app.nudge.core.ui.snackbar.LocalSnackbar
import app.nudge.core.ui.snackbar.SnackbarDispatcher
import app.nudge.feature.quickadd.QuickAddSheet
import app.nudge.feature.taskdetail.TaskDetailSheet
import kotlinx.serialization.Serializable

/** List screen route (03 §2). [openTaskId] auto-opens Task Detail (deep link, 03 §4.6). */
@Serializable
data class ListRoute(val listId: String, val highlightTaskId: String? = null, val openTaskId: String? = null)

fun NavController.navigateToList(listId: String, highlightTaskId: String? = null, openTaskId: String? = null) =
    navigate(ListRoute(listId, highlightTaskId, openTaskId)) { launchSingleTop = true }

fun NavGraphBuilder.listScreen(onBack: () -> Unit) {
    composable<ListRoute> {
        androidx.compose.runtime.CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
            ListScreen(onBack = onBack)
        }
    }
}

private data class QuickAddTarget(val listId: String, val parentId: String?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(onBack: () -> Unit, viewModel: ListScreenViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lists = state.lists
    if (!state.loaded) return
    if (lists.isEmpty()) return
    val initialPage = remember(state.loaded) { lists.indexOfFirst { it.id == state.initialListId }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = initialPage) { lists.size }
    val current = lists.getOrElse(pager.currentPage) { lists.first() }
    val seed by animateColorAsState(Color(current.colorArgb), tween(300), label = "seed") // A16

    NudgeSeededTheme(seed = seed) {
        ListScreenContent(lists, current, pager, viewModel, onBack)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListScreenContent(
    lists: List<TaskList>,
    current: TaskList,
    pager: androidx.compose.foundation.pager.PagerState,
    screenVm: ListScreenViewModel,
    onBack: () -> Unit,
) {
    val snackbar = LocalSnackbar.current
    val resources = LocalResources.current
    val currentVm: ListPageViewModel = hiltViewModel(key = "list-page-${current.id}")
    var dragging by remember { mutableStateOf(false) }
    var quickAdd by remember { mutableStateOf<QuickAddTarget?>(null) }
    var detailTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var openedFromRoute by rememberSaveable { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }
    var editList by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDeleteCompleted by remember { mutableStateOf(false) }
    val undo = stringResource(R.string.action_undo)
    val route = screenVm.route

    LaunchedEffect(route.openTaskId) {
        if (!openedFromRoute && route.openTaskId != null) {
            openedFromRoute = true
            detailTaskId = route.openTaskId
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar.hostState) },
        topBar = {
            Column(
                Modifier
                    .sharedElementOrNone(SharedKeys.listContainer(current.id), bounds = true)
                    .background(MaterialTheme.colorScheme.primaryContainer),
            ) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ListIcon(
                                current.name,
                                current.emoji,
                                Color(current.colorArgb),
                                size = 32.dp,
                                modifier = Modifier.sharedElementOrNone(SharedKeys.listIcon(current.id)),
                            )
                            Spacer(Modifier.width(Spacing.m))
                            Text(
                                current.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.sharedElementOrNone(SharedKeys.listName(current.id)),
                            )
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { showSort = true }) {
                                Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = stringResource(R.string.list_sort))
                            }
                            SortMenu(showSort, current.sortMode, onSelect = currentVm::onSortMode, onDismiss = { showSort = false })
                        }
                        Box {
                            IconButton(onClick = { showOverflow = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.list_more))
                            }
                            DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.list_rename_color)) },
                                    leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                                    onClick = {
                                        showOverflow = false
                                        editList = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.list_delete_all_completed)) },
                                    leadingIcon = { Icon(Icons.Rounded.DeleteSweep, null) },
                                    onClick = {
                                        showOverflow = false
                                        confirmDeleteCompleted = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.list_delete), color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                    enabled = lists.size > 1,
                                    onClick = {
                                        showOverflow = false
                                        confirmDelete = true
                                    },
                                )
                            }
                        }
                    },
                )
                if (lists.size > 1) ListDots(lists, pager.currentPage, onSelect = { /* dots are indicators; swipe to change */ })
            }
        },
        floatingActionButton = {
            val interaction = remember { MutableInteractionSource() }
            FloatingActionButton(
                onClick = { quickAdd = QuickAddTarget(current.id, null) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = MaterialTheme.shapes.extraLarge,
                interactionSource = interaction,
                modifier = Modifier.pressScale(interaction, 0.9f).testTag("list_fab"),
            ) { Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.list_add_task)) }
        },
    ) { padding ->
        HorizontalPager(
            state = pager,
            userScrollEnabled = !dragging, // DD-10
            key = { lists[it].id },
            beyondViewportPageCount = 0,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val list = lists[page]
            val vm: ListPageViewModel = hiltViewModel(key = "list-page-${list.id}")
            ListPage(
                listId = list.id,
                vm = vm,
                isCurrent = page == pager.currentPage,
                highlightTaskId = if (list.id == route.listId) route.highlightTaskId else null,
                onOpenDetails = { detailTaskId = it },
                onAddSubtask = { quickAdd = QuickAddTarget(list.id, it) },
                onDraggingChange = { dragging = it },
                onListDeleted = { snapshot, name ->
                    onBack()
                    snackbar.show(resources.getString(R.string.list_deleted_list, name), undo, SnackbarDispatcher.DELETE_MS) {
                        vm.undo(snapshot)
                    }
                },
                contentPadding = padding,
            )
        }
    }

    quickAdd?.let { target ->
        QuickAddSheet(listId = target.listId, parentId = target.parentId, onDismiss = { quickAdd = null })
    }
    detailTaskId?.let { id -> TaskDetailSheet(taskId = id, onDismiss = { detailTaskId = null }) }
    if (editList) {
        ListEditorSheet(
            existing = current,
            defaultColor = ListColors.DEFAULT,
            onSave = { name, color, emoji -> currentVm.onEditList(name, color, emoji) },
            onDismiss = { editList = false },
        )
    }
    if (confirmDelete) {
        val count = currentVm.state.value.model?.let { it.tree.open.size + it.tree.completed.size } ?: 0
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(androidx.compose.ui.res.pluralStringResource(R.plurals.list_delete_confirm, count, current.name, count)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    currentVm.onDeleteList()
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (confirmDeleteCompleted) {
        AlertDialog(
            onDismissRequest = { confirmDeleteCompleted = false },
            title = { Text(stringResource(R.string.list_delete_completed_title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteCompleted = false
                    currentVm.onDeleteCompleted()
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteCompleted = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun SortMenu(expanded: Boolean, current: ListSortMode, onSelect: (ListSortMode) -> Unit, onDismiss: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        listOf(
            ListSortMode.MY_ORDER to R.string.sort_my_order,
            ListSortMode.PRIORITY to R.string.sort_priority,
            ListSortMode.DUE_DATE to R.string.sort_due_date,
        ).forEach { (mode, label) ->
            DropdownMenuItem(
                text = { Text(stringResource(label)) },
                trailingIcon = { if (mode == current) Icon(Icons.Rounded.Check, null) },
                onClick = {
                    onSelect(mode)
                    onDismiss()
                },
            )
        }
    }
}

/** FR-85: pager indicator dots in each list's color; the current one stretches into a pill. */
@Composable
private fun ListDots(lists: List<TaskList>, current: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 64.dp, bottom = Spacing.s),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        lists.forEachIndexed { i, l ->
            val selected = i == current
            val width by animateDpAsState(if (selected) 20.dp else 8.dp, spring(0.6f, 500f), label = "dot")
            Box(
                Modifier
                    .height(8.dp)
                    .width(width)
                    .clip(CircleShape)
                    .background(Color(l.colorArgb).copy(alpha = if (selected) 1f else 0.5f))
                    .clickable(role = Role.Tab) { onSelect(i) }
                    .semantics {
                        contentDescription = l.name
                        this.selected = selected
                    },
            )
        }
    }
}
