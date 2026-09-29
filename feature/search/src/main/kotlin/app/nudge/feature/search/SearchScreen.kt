package app.nudge.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.nudge.core.designsystem.component.EmptyIllustration
import app.nudge.core.designsystem.component.EmptyState
import app.nudge.core.designsystem.component.ListIcon
import app.nudge.core.designsystem.component.PriorityFlag
import app.nudge.core.designsystem.theme.Spacing
import app.nudge.core.designsystem.theme.centeringPadding
import app.nudge.core.domain.usecase.TaskWithList
import app.nudge.core.model.TaskList
import app.nudge.core.ui.nav.LocalNavAnimatedVisibilityScope
import app.nudge.core.ui.snackbar.LocalSnackbar
import kotlinx.serialization.Serializable

@Serializable
data object SearchRoute

fun NavController.navigateToSearch() = navigate(SearchRoute) { launchSingleTop = true }

fun NavGraphBuilder.searchScreen(onBack: () -> Unit, onOpenResult: (listId: String, taskId: String) -> Unit) {
    composable<SearchRoute> {
        CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
            SearchScreen(onBack = onBack, onOpenResult = onOpenResult)
        }
    }
}

/** Full-screen search (FR-84, 03 §3.8). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenResult: (listId: String, taskId: String) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val results by viewModel.results.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf(viewModel.query.value) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbar.current.hostState) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = {
                    TextField(
                        value = text,
                        onValueChange = {
                            text = it
                            viewModel.onQueryChange(it)
                        },
                        placeholder = { Text(stringResource(R.string.search_placeholder)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("search_field"),
                    )
                },
                actions = {
                    if (text.isNotEmpty()) {
                        IconButton(onClick = {
                            text = ""
                            viewModel.onQueryChange("")
                            focus.requestFocus()
                        }) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.search_clear))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                results.query.isEmpty() -> SearchHint(Modifier.align(Alignment.Center))
                results.groups.isEmpty() -> EmptyState(
                    EmptyIllustration.NO_RESULTS,
                    title = stringResource(R.string.search_no_results, results.query),
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            LazyColumn(
                contentPadding = PaddingValues(start = centeringPadding(), end = centeringPadding(), bottom = Spacing.xxl),
                modifier = Modifier.fillMaxSize().testTag("search_results"),
            ) {
                results.groups.forEach { group ->
                    val list = group.list ?: return@forEach
                    stickyHeader(key = "header-${list.id}", contentType = "header") { GroupHeader(list) }
                    items(group.tasks, key = { it.task.id }, contentType = { "result" }) { item ->
                        ResultRow(
                            item = item,
                            query = results.query,
                            onClick = {
                                keyboard?.hide()
                                onOpenResult(item.list.id, item.task.id)
                            },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchHint(modifier: Modifier = Modifier) {
    Column(modifier.padding(Spacing.xxl), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Spacing.s))
        Text(
            stringResource(R.string.search_hint),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GroupHeader(list: TaskList) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .heightIn(min = 48.dp)
                .semantics(mergeDescendants = true) { heading() }
                .padding(horizontal = Spacing.l, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ListIcon(list.name, list.emoji, Color(list.colorArgb), size = 24.dp)
            Spacer(Modifier.width(Spacing.s))
            Text(list.name, style = MaterialTheme.typography.titleSmall, color = Color(list.colorArgb))
        }
    }
}

@Composable
private fun ResultRow(item: TaskWithList, query: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val task = item.task
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val snippet = remember(task.notes, query) { notesSnippet(task.notes, query) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.search_open_in_list), onClick = onClick)
            .padding(start = Spacing.l, end = Spacing.s, top = Spacing.s, bottom = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PriorityFlag(task.priority, Modifier.padding(end = Spacing.s))
        Column(Modifier.weight(1f)) {
            Text(
                highlight(task.title, query),
                style = MaterialTheme.typography.bodyLarge.copy(
                    textDecoration = if (task.isCompleted) TextDecoration.LineThrough else null,
                ),
                color = if (task.isCompleted) muted else MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (snippet != null) {
                Text(
                    highlight(snippet, query),
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (task.isCompleted) {
                Text(stringResource(R.string.search_completed), style = MaterialTheme.typography.labelSmall, color = muted)
            }
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = muted)
    }
}

/** Bolds every case-insensitive occurrence of [query] in [text] (03 §3.8). */
internal fun highlight(text: String, query: String): AnnotatedString = buildAnnotatedString {
    append(text)
    if (query.isEmpty()) return@buildAnnotatedString
    var start = text.indexOf(query, ignoreCase = true)
    while (start >= 0) {
        addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, start + query.length)
        start = text.indexOf(query, start + query.length, ignoreCase = true)
    }
}

/** A single-line excerpt of [notes] around the first match, or null when the notes don't match. */
internal fun notesSnippet(notes: String, query: String): String? {
    if (query.isEmpty()) return null
    val at = notes.indexOf(query, ignoreCase = true)
    if (at < 0) return null
    val from = (at - SNIPPET_BEFORE).coerceAtLeast(0)
    val to = (at + query.length + SNIPPET_AFTER).coerceAtMost(notes.length)
    val body = notes.substring(from, to).replace('\n', ' ').trim()
    return buildString {
        if (from > 0) append("…")
        append(body)
        if (to < notes.length) append("…")
    }
}

private const val SNIPPET_BEFORE = 30
private const val SNIPPET_AFTER = 60
