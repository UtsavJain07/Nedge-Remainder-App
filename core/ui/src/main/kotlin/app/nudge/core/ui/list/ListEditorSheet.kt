package app.nudge.core.ui.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import app.nudge.core.designsystem.component.ColorSwatchPicker
import app.nudge.core.designsystem.component.ListIcon
import app.nudge.core.designsystem.theme.LocalIsDarkTheme
import app.nudge.core.designsystem.theme.Spacing
import app.nudge.core.designsystem.theme.listContainerColor
import app.nudge.core.model.ListColors
import app.nudge.core.model.TaskList
import app.nudge.core.ui.R
import app.nudge.core.ui.sheet.MinimizableBottomSheet
import app.nudge.core.ui.sheet.rememberMinimizableSheetController

/**
 * Create / Edit list sheet (03 §3.6, FR-01, FR-03): name with a live preview card and 12 color swatches.
 * Dragging it down minimizes it instead of discarding the input (v1.1). [existing] null = create.
 */
@Composable
fun ListEditorSheet(
    existing: TaskList?,
    defaultColor: Int,
    onSave: (name: String, color: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // State lives above the minimize animation so it survives minimizing.
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var color by rememberSaveable { mutableIntStateOf(existing?.colorArgb ?: defaultColor) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val controller = rememberMinimizableSheetController()
    val hasChanges = if (existing == null) name.isNotBlank() else name != existing.name || color != existing.colorArgb
    val valid = name.isNotBlank() && name.trim().length <= TaskList.NAME_MAX
    val save = {
        if (valid) {
            onSave(name.trim(), color)
            controller.close()
        }
    }
    val title = stringResource(if (existing == null) R.string.list_editor_new_title else R.string.list_editor_edit_title)

    MinimizableBottomSheet(
        controller = controller,
        peekTitle = name.ifBlank { title },
        onCloseRequest = {
            if (hasChanges) confirmDiscard = true
            !hasChanges
        },
        onDismissed = onDismiss,
        modifier = Modifier.testTag("list_editor_sheet"),
    ) {
        val focus = remember { FocusRequester() }
        // Focus the name on open and whenever the sheet is restored from its minimized bar.
        LaunchedEffect(controller.minimized) { if (!controller.minimized) runCatching { focus.requestFocus() } }
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.sheetPadding)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            // Live preview card.
            val c = Color(color)
            Surface(
                color = listContainerColor(c, MaterialTheme.colorScheme.surfaceContainer, LocalIsDarkTheme.current),
                shape = MaterialTheme.shapes.large,
            ) {
                Row(Modifier.fillMaxWidth().padding(Spacing.l), verticalAlignment = Alignment.CenterVertically) {
                    ListIcon(name.ifBlank { "?" }, existing?.emoji, c)
                    Spacer(Modifier.width(Spacing.m))
                    Text(
                        name.ifBlank { stringResource(R.string.list_editor_placeholder) },
                        style = MaterialTheme.typography.titleMedium,
                        color = if (name.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(TaskList.NAME_MAX) },
                label = { Text(stringResource(R.string.list_editor_name)) },
                singleLine = true,
                supportingText = { Text("${name.length}/${TaskList.NAME_MAX}") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("list_name"),
            )
            Text(stringResource(R.string.list_editor_color), style = MaterialTheme.typography.titleSmall)
            ColorSwatchPicker(ListColors.presets, ListColors.names, color, onSelect = { color = it })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = save, enabled = valid, modifier = Modifier.testTag("list_save")) {
                    Text(stringResource(if (existing == null) R.string.list_editor_create else R.string.list_editor_save))
                }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(if (existing == null) R.string.list_editor_discard_new else R.string.list_editor_discard_changes)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    controller.close()
                }) { Text(stringResource(R.string.list_editor_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.list_editor_keep_editing)) }
            },
        )
    }
}
