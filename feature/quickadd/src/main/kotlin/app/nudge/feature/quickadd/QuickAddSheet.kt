package app.nudge.feature.quickadd

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.AlarmOn
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nudge.core.designsystem.component.CadencePickerDialog
import app.nudge.core.designsystem.component.ListIcon
import app.nudge.core.designsystem.component.PriorityChipRow
import app.nudge.core.designsystem.component.cadenceLabel
import app.nudge.core.designsystem.theme.LocalReducedMotion
import app.nudge.core.designsystem.theme.Spacing
import app.nudge.core.ui.format.formatDue
import app.nudge.core.ui.picker.DueDateTimeDialogs
import app.nudge.core.ui.snackbar.LocalSnackbar
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Quick Add bottom sheet (03 §3.4). Enter saves and keeps the sheet open for rapid entry (FR-10).
 * [listId] null + [showListPicker] = Home entry with a list chip (FR-80). [parentId] = add subtask (FR-11).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuickAddSheet(
    listId: String?,
    parentId: String?,
    onDismiss: () -> Unit,
    showListPicker: Boolean = false,
    viewModel: QuickAddViewModel = hiltViewModel(key = "quickadd-$listId-$parentId"),
) {
    LaunchedEffect(listId, parentId) { viewModel.start(listId, parentId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val form = state.form
    val snackbar = LocalSnackbar.current
    val errorText = stringResource(R.string.quickadd_error)
    var confirmDiscard by remember { mutableStateOf(false) }
    val hasText by rememberUpdatedState(form.title.isNotBlank())
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { value ->
            if (value == SheetValue.Hidden && hasText) {
                confirmDiscard = true
                false
            } else {
                true
            }
        },
    )
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    var flyText by remember { mutableStateOf<String?>(null) }
    var flyKey by remember { mutableIntStateOf(0) }
    var showDate by rememberSaveable { mutableStateOf(false) }
    var showCadence by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { e ->
            when (e) {
                is QuickAddEffect.Added -> {
                    flyText = e.title
                    flyKey++
                }
                QuickAddEffect.Error -> snackbar.show(errorText)
            }
        }
    }
    LaunchedEffect(state.ready) { if (state.ready) runCatching { focus.requestFocus() } }

    fun dismiss() {
        if (form.title.isNotBlank()) {
            confirmDiscard = true
        } else {
            scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("quick_add_sheet"),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .padding(horizontal = Spacing.sheetPadding)
                .padding(bottom = Spacing.l)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showListPicker && parentId == null) {
                    ListPickerChip(state, viewModel::onList)
                    Spacer(Modifier.size(Spacing.s))
                }
                state.parentTitle?.let {
                    Text(
                        stringResource(R.string.quickadd_subtask_of, it),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Box {
                TextField(
                    value = form.title,
                    onValueChange = viewModel::onTitle,
                    placeholder = { Text(stringResource(R.string.quickadd_placeholder)) },
                    singleLine = false,
                    maxLines = 4,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { viewModel.save() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .testTag("quick_add_title"),
                )
                FlyingTitle(flyText, flyKey)
            }
            Staggered(0) { PriorityChipRow(selected = form.priority, onSelect = viewModel::onPriority) }
            Staggered(1) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    val due = form.dueDate
                    if (due == null) {
                        AssistChip(
                            onClick = { showDate = true },
                            label = { Text(stringResource(R.string.quickadd_date)) },
                            leadingIcon = { Icon(Icons.Rounded.CalendarToday, null, Modifier.size(AssistChipDefaults.IconSize)) },
                        )
                    } else {
                        InputChip(
                            selected = true,
                            onClick = { showDate = true },
                            label = { Text(formatDue(due, form.dueTime, LocalDate.now())) },
                            leadingIcon = { Icon(Icons.Rounded.CalendarToday, null, Modifier.size(AssistChipDefaults.IconSize)) },
                            trailingIcon = {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.quickadd_clear_date),
                                    modifier = Modifier.size(18.dp).clickable { viewModel.onDue(null, null) },
                                )
                            },
                        )
                    }
                    AssistChip(
                        onClick = { showCadence = true },
                        label = { Text(stringResource(R.string.quickadd_reminder_fmt, cadenceLabel(state.effectiveCadence))) },
                        leadingIcon = { Icon(Icons.Rounded.AlarmOn, null, Modifier.size(AssistChipDefaults.IconSize)) },
                    )
                    AssistChip(
                        onClick = viewModel::toggleNotes,
                        label = { Text(stringResource(R.string.quickadd_notes)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Notes, null, Modifier.size(AssistChipDefaults.IconSize)) },
                    )
                }
            }
            if (form.showNotes) {
                OutlinedTextField(
                    value = form.notes,
                    onValueChange = viewModel::onNotes,
                    placeholder = { Text(stringResource(R.string.quickadd_notes_placeholder)) },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = viewModel::save, enabled = form.canSave, modifier = Modifier.testTag("quick_add_save")) {
                    Text(stringResource(R.string.quickadd_save))
                }
            }
        }
    }

    if (showDate) {
        DueDateTimeDialogs(
            initialDate = form.dueDate,
            initialTime = form.dueTime,
            onConfirm = { d, t ->
                viewModel.onDue(d, t)
                showDate = false
            },
            onDismiss = { showDate = false },
        )
    }
    if (showCadence) {
        CadencePickerDialog(
            selected = form.cadenceOverride,
            defaultCadence = state.reminderSettings.cadenceFor(form.priority),
            onSelect = viewModel::onCadence,
            onDismiss = { showCadence = false },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.quickadd_discard_title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    viewModel.onTitle("")
                    scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                }) { Text(stringResource(R.string.quickadd_discard)) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.quickadd_keep_editing)) } },
        )
    }
}

@Composable
private fun ListPickerChip(state: QuickAddUiState, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val list = state.selectedList ?: return
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(list.name) },
            leadingIcon = { ListIcon(list.name, list.emoji, Color(list.colorArgb), size = 20.dp) },
            trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.lists.forEach { l ->
                DropdownMenuItem(
                    text = { Text(l.name) },
                    leadingIcon = { ListIcon(l.name, l.emoji, Color(l.colorArgb), size = 24.dp) },
                    onClick = {
                        onSelect(l.id)
                        open = false
                    },
                )
            }
        }
    }
}

/** A9: chips stagger in (30 ms apart, alpha 0 → 1, translationY 8 → 0 dp). */
@Composable
private fun Staggered(index: Int, content: @Composable () -> Unit) {
    val reduced = LocalReducedMotion.current
    val progress = remember { Animatable(if (reduced) 1f else 0f) }
    val offset = with(LocalDensity.current) { 8.dp.toPx() }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(220, delayMillis = 60 + index * 30)) }
    Box(
        Modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * offset
        },
    ) { content() }
}

/** A4: the saved title scales 1 → 0.6, rises and fades over 350 ms. */
@Composable
private fun FlyingTitle(text: String?, key: Int) {
    if (text == null || LocalReducedMotion.current) return
    val t = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { t.animateTo(1f, tween(350)) }
    if (t.value >= 1f) return
    val rise = with(LocalDensity.current) { 120.dp.toPx() }
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = Spacing.l, top = Spacing.l)
            .graphicsLayer {
                val s = 1f - 0.4f * t.value
                scaleX = s
                scaleY = s
                translationY = -rise * t.value
                alpha = 1f - t.value
            },
    )
}
