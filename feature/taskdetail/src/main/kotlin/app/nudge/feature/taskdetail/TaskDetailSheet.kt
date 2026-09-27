package app.nudge.feature.taskdetail

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AlarmOn
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nudge.core.designsystem.component.AnimatedPercent
import app.nudge.core.designsystem.component.CadencePickerDialog
import app.nudge.core.designsystem.component.HapticEvent
import app.nudge.core.designsystem.component.ListIcon
import app.nudge.core.designsystem.component.NudgeCheckbox
import app.nudge.core.designsystem.component.PriorityChipRow
import app.nudge.core.designsystem.component.ProgressRing
import app.nudge.core.designsystem.component.StrikethroughText
import app.nudge.core.designsystem.component.cadenceLabel
import app.nudge.core.designsystem.component.priorityColor
import app.nudge.core.designsystem.component.priorityLabel
import app.nudge.core.designsystem.component.rememberNudgeHaptics
import app.nudge.core.designsystem.theme.Spacing
import app.nudge.core.model.Priority
import app.nudge.core.model.Task
import app.nudge.core.ui.format.formatDue
import app.nudge.core.ui.format.formatInstant
import app.nudge.core.ui.format.formatRelative
import app.nudge.core.ui.picker.DueDateTimeDialogs
import app.nudge.core.ui.snackbar.LocalSnackbar
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

/**
 * Task Detail bottom sheet (03 §3.5). Opens at ~60% height; dragging up makes it full screen.
 * Every edit autosaves (FR-14); there is no Save button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailSheet(
    taskId: String,
    onDismiss: () -> Unit,
    viewModel: TaskDetailViewModel = hiltViewModel(key = "detail"),
) {
    var currentId by rememberSaveable { mutableStateOf(taskId) }
    LaunchedEffect(taskId) { currentId = taskId }
    LaunchedEffect(currentId) { viewModel.start(currentId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scope = rememberCoroutineScope()
    val undoLabel = stringResource(R.string.action_undo)
    val completeLabel = stringResource(R.string.action_complete)

    fun close() {
        viewModel.flushDrafts()
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { e ->
            when (e) {
                is TaskDetailEffect.Undo -> snackbar.show(e.message.resolve(context), undoLabel, e.durationMs) { viewModel.undo(e.snapshot) }
                is TaskDetailEffect.AllSubtasksDone -> snackbar.show(
                    context.getString(R.string.detail_all_subtasks_done, e.parent.title),
                    completeLabel,
                ) { viewModel.completeParent(e.parent.id) }
                is TaskDetailEffect.Message -> snackbar.show(e.message.resolve(context))
                TaskDetailEffect.Dismiss -> close()
            }
        }
    }
    LaunchedEffect(state.loaded, state.task) { if (state.loaded && state.task == null) onDismiss() }

    ModalBottomSheet(
        onDismissRequest = {
            viewModel.flushDrafts()
            onDismiss()
        },
        sheetState = sheetState,
        modifier = Modifier.testTag("task_detail_sheet"),
    ) {
        val task = state.task
        if (task == null) {
            Box(Modifier.fillMaxWidth().heightIn(min = 200.dp))
        } else {
            DetailContent(state, task, viewModel, onOpenSubtask = { currentId = it }, onOpenParent = { currentId = it })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailContent(
    state: TaskDetailUiState,
    task: Task,
    vm: TaskDetailViewModel,
    onOpenSubtask: (String) -> Unit,
    onOpenParent: (String) -> Unit,
) {
    val title = vm.titleDraft
    val notes = vm.notesDraft
    val listColor = state.list?.let { Color(it.colorArgb) } ?: MaterialTheme.colorScheme.primary
    var showMenu by remember { mutableStateOf(false) }
    var showMovePicker by remember { mutableStateOf(false) }
    var showCadence by remember { mutableStateOf(false) }
    var showDue by remember { mutableStateOf(false) }
    var showSnoozePicker by remember { mutableStateOf(false) }
    val today = state.now.atZone(state.zone).toLocalDate()

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.sheetPadding)
            .padding(bottom = Spacing.xxl)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        // 1. Header: large checkbox, editable title, overflow.
        Row(verticalAlignment = Alignment.Top) {
            val fill = if (task.priority == Priority.NONE) listColor else priorityColor(task.priority)
            NudgeCheckbox(
                checked = task.isCompleted,
                color = fill,
                ringColor = if (task.priority == Priority.NONE) MaterialTheme.colorScheme.outline else fill,
                onToggle = vm::onToggleComplete,
                size = 28.dp,
                label = task.title,
            )
            TextField(
                value = title,
                onValueChange = vm::onTitle,
                textStyle = MaterialTheme.typography.headlineSmall,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f).testTag("detail_title"),
            )
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.detail_more))
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.detail_move_to)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.DriveFileMove, null) },
                        onClick = {
                            showMenu = false
                            showMovePicker = true
                        },
                    )
                    if (!task.isCompleted) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.detail_snooze)) },
                            leadingIcon = { Icon(Icons.Rounded.Snooze, null) },
                            onClick = {
                                showMenu = false
                                vm.onSnoozeMinutes(state.settings.reminders.defaultSnoozeMinutes.toLong())
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.detail_delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            showMenu = false
                            vm.onDelete()
                        },
                    )
                }
            }
        }

        // 2. Breadcrumb.
        Row(verticalAlignment = Alignment.CenterVertically) {
            state.list?.let { l ->
                AssistChip(
                    onClick = { showMovePicker = true },
                    label = { Text(l.name) },
                    leadingIcon = { ListIcon(l.name, l.emoji, Color(l.colorArgb), size = 20.dp) },
                )
            }
            state.parent?.let { p ->
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { onOpenParent(p.id) }) { Text(p.title, maxLines = 1) }
            }
        }

        // 3. Priority.
        Section(stringResource(R.string.detail_priority)) {
            PriorityChipRow(selected = task.priority, onSelect = vm::onPriority, includeNone = true)
        }

        // 4. Progress.
        Section(stringResource(R.string.detail_progress)) { ProgressSection(state, task, listColor, vm) }

        // 5. Reminders card.
        RemindersCard(state, task, onCadence = { showCadence = true }, vm = vm, onPickSnooze = { showSnoozePicker = true })

        // 6. Due date.
        Section(stringResource(R.string.detail_due)) {
            val due = task.dueDate
            if (due == null) {
                AssistChip(
                    onClick = { showDue = true },
                    label = { Text(stringResource(R.string.detail_add_due)) },
                    leadingIcon = { Icon(Icons.Rounded.CalendarToday, null, Modifier.size(AssistChipDefaults.IconSize)) },
                )
            } else {
                InputChip(
                    selected = true,
                    onClick = { showDue = true },
                    label = { Text(formatDue(due, task.dueTime, today)) },
                    leadingIcon = { Icon(Icons.Rounded.CalendarToday, null, Modifier.size(AssistChipDefaults.IconSize)) },
                    trailingIcon = {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.detail_clear_due),
                            modifier = Modifier.size(18.dp).clickable { vm.onDue(null, null) },
                        )
                    },
                )
            }
        }

        // 7. Subtasks (top-level tasks only).
        if (task.parentId == null) {
            Section(stringResource(R.string.detail_subtasks)) {
                SubtaskList(state, listColor, vm, onOpenSubtask)
            }
        }

        // 8. Notes.
        Section(stringResource(R.string.detail_notes)) {
            OutlinedTextField(
                value = notes,
                onValueChange = vm::onNotes,
                placeholder = { Text(stringResource(R.string.detail_add_notes)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth().testTag("detail_notes"),
            )
        }

        // 9. Footer.
        val fmt = remember { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT) }
        Text(
            stringResource(
                R.string.detail_footer,
                fmt.format(task.createdAt.atZone(state.zone)),
                formatAgo(Duration.between(task.updatedAt, state.now)),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showMovePicker) {
        ListPickerDialog(state, onPick = { vm.onMoveToList(it) }, onDismiss = { showMovePicker = false })
    }
    if (showCadence) {
        CadencePickerDialog(
            selected = task.cadenceOverride,
            defaultCadence = state.defaultCadence,
            onSelect = vm::onCadence,
            onDismiss = { showCadence = false },
        )
    }
    if (showDue) {
        DueDateTimeDialogs(
            initialDate = task.dueDate,
            initialTime = task.dueTime,
            onConfirm = { d, t ->
                vm.onDue(d, t)
                showDue = false
            },
            onDismiss = { showDue = false },
        )
    }
    if (showSnoozePicker) {
        DueDateTimeDialogs(
            initialDate = today,
            initialTime = null,
            requireTime = true,
            minDate = today,
            onConfirm = { d, t ->
                if (t != null) vm.onSnoozeAt(d, t)
                showSnoozePicker = false
            },
            onDismiss = { showSnoozePicker = false },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
private fun ProgressSection(state: TaskDetailUiState, task: Task, listColor: Color, vm: TaskDetailViewModel) {
    val haptics = rememberNudgeHaptics()
    if (state.hasChildren) {
        // FR-42: computed ring, slider disabled.
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(state.effectiveProgress / 100f, listColor, size = 48.dp, stroke = 5.dp) {
                AnimatedPercent(state.effectiveProgress, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.width(Spacing.l))
            Text(stringResource(R.string.detail_progress_calculated), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        var dragging by remember(task.id) { mutableStateOf(false) }
        var value by remember(task.id) { mutableFloatStateOf(task.progress.toFloat()) }
        var before by remember(task.id) { mutableIntStateOf(task.progress) }
        LaunchedEffect(task.progress, task.isCompleted) {
            if (!dragging) value = if (task.isCompleted) 100f else task.progress.toFloat()
        }
        val percentLabel = stringResource(R.string.detail_progress_a11y, value.roundToInt())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = value,
                onValueChange = {
                    if (!dragging) {
                        dragging = true
                        before = task.progress
                    }
                    if (it.roundToInt() != value.roundToInt()) haptics.perform(HapticEvent.SLIDER_STEP)
                    value = it
                },
                onValueChangeFinished = {
                    dragging = false
                    vm.onProgressCommit(value.roundToInt(), before)
                },
                valueRange = 0f..100f,
                steps = 19, // step 5 (FR-40)
                enabled = !task.isCompleted,
                modifier = Modifier.weight(1f).semantics { stateDescription = percentLabel }.testTag("detail_progress"),
            )
            Spacer(Modifier.width(Spacing.m))
            AnimatedPercent(value.roundToInt(), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RemindersCard(
    state: TaskDetailUiState,
    task: Task,
    onCadence: () -> Unit,
    vm: TaskDetailViewModel,
    onPickSnooze: () -> Unit,
) {
    val today = state.now.atZone(state.zone).toLocalDate()
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onCadence),
            ) {
                Icon(Icons.Rounded.AlarmOn, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(Spacing.m))
                val repeats = if (task.cadenceOverride == null && task.priority != Priority.NONE) {
                    stringResource(R.string.detail_repeats_default, cadenceLabel(state.effectiveCadence), priorityLabel(task.priority))
                } else {
                    stringResource(R.string.detail_repeats, cadenceLabel(state.effectiveCadence))
                }
                Text(repeats, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val next = task.reminder.nextAt
            val nextText = when {
                task.isCompleted -> stringResource(R.string.detail_next_none_done)
                state.isPaused -> stringResource(R.string.detail_next_paused)
                next == null -> stringResource(R.string.detail_next_none)
                else -> stringResource(R.string.detail_next_nudge, formatRelative(state.now, next), formatInstant(next, state.zone, today))
            }
            Text(nextText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!task.isCompleted) {
                if (state.isSnoozed) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Snooze, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.s))
                        Text(
                            stringResource(R.string.detail_snoozed_until, formatInstant(task.reminder.snoozedUntil!!, state.zone, today)),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = vm::onCancelSnooze) { Text(stringResource(R.string.action_cancel)) }
                    }
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        SuggestionChip(onClick = { vm.onSnoozeMinutes(15) }, label = { Text(stringResource(R.string.snooze_15m)) })
                        SuggestionChip(onClick = { vm.onSnoozeMinutes(60) }, label = { Text(stringResource(R.string.snooze_1h)) })
                        SuggestionChip(onClick = { vm.onSnoozeMinutes(180) }, label = { Text(stringResource(R.string.snooze_3h)) })
                        SuggestionChip(onClick = vm::onSnoozeTomorrow, label = { Text(stringResource(R.string.snooze_tomorrow)) })
                        SuggestionChip(onClick = onPickSnooze, label = { Text(stringResource(R.string.snooze_pick)) })
                    }
                }
            }
            if (task.reminder.count > 0) {
                val fmt = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }
                Text(
                    pluralStringResource(
                        R.plurals.detail_nudged,
                        task.reminder.count,
                        task.reminder.count,
                        fmt.format(task.reminder.anchorAt.atZone(state.zone)),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SubtaskList(state: TaskDetailUiState, listColor: Color, vm: TaskDetailViewModel, onOpen: (String) -> Unit) {
    var newTitle by rememberSaveable { mutableStateOf("") }
    Column {
        state.children.forEach { c ->
            val fill = if (c.priority == Priority.NONE) listColor else priorityColor(c.priority)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onOpen(c.id) },
            ) {
                NudgeCheckbox(
                    checked = c.isCompleted,
                    color = fill,
                    ringColor = if (c.priority == Priority.NONE) MaterialTheme.colorScheme.outline else fill,
                    onToggle = { vm.onToggleSubtask(c.id) },
                    size = 20.dp,
                    label = c.title,
                )
                StrikethroughText(c.title, struck = c.isCompleted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (c.progress in 1..99 && !c.isCompleted) {
                    Text("${c.progress}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (state.task?.isCompleted == true) return@Column
        HorizontalDivider(Modifier.padding(vertical = Spacing.xs), color = MaterialTheme.colorScheme.outlineVariant)
        TextField(
            value = newTitle,
            onValueChange = { newTitle = it },
            placeholder = { Text(stringResource(R.string.detail_add_subtask)) },
            leadingIcon = { Icon(Icons.Rounded.Add, null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                vm.onAddSubtask(newTitle)
                newTitle = "" // Enter adds another (03 §3.5)
            }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth().testTag("detail_add_subtask"),
        )
    }
}

@Composable
private fun ListPickerDialog(state: TaskDetailUiState, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.detail_move_to)) },
        text = {
            Column {
                state.lists.forEach { l ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable(enabled = l.id != state.task?.listId) {
                                onPick(l.id)
                                onDismiss()
                            },
                    ) {
                        ListIcon(l.name, l.emoji, Color(l.colorArgb), size = 28.dp)
                        Spacer(Modifier.width(Spacing.m))
                        Text(
                            l.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (l.id == state.task?.listId) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun formatAgo(d: Duration): String {
    val minutes = d.toMinutes().coerceAtLeast(0)
    return when {
        minutes < 1 -> stringResource(R.string.ago_just_now)
        minutes < 60 -> stringResource(R.string.ago_min, minutes)
        minutes < 24 * 60 -> stringResource(R.string.ago_h, minutes / 60)
        else -> stringResource(R.string.ago_days, d.toDays())
    }
}
