package app.nudge.core.ui.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.nudge.core.designsystem.component.ColorSwatchPicker
import app.nudge.core.designsystem.component.EmojiPicker
import app.nudge.core.designsystem.component.ListIcon
import app.nudge.core.designsystem.theme.LocalIsDarkTheme
import app.nudge.core.designsystem.theme.Spacing
import app.nudge.core.designsystem.theme.listContainerColor
import app.nudge.core.model.ListColors
import app.nudge.core.model.TaskList
import app.nudge.core.ui.R

/**
 * Create / Edit list sheet (03 §3.6, FR-01..FR-03): name with a live preview card, 12 swatches, emoji.
 * [existing] null = create.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListEditorSheet(
    existing: TaskList?,
    defaultColor: Int,
    onSave: (name: String, color: Int, emoji: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var color by rememberSaveable { mutableIntStateOf(existing?.colorArgb ?: defaultColor) }
    var emoji by rememberSaveable { mutableStateOf(existing?.emoji) }
    val focus = remember { FocusRequester() }
    val valid = name.isNotBlank() && name.trim().length <= TaskList.NAME_MAX
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.sheetPadding)
                .padding(bottom = Spacing.xl)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            // Live preview card.
            val c = Color(color)
            Surface(
                color = listContainerColor(c, MaterialTheme.colorScheme.surfaceContainer, LocalIsDarkTheme.current),
                shape = MaterialTheme.shapes.large,
            ) {
                Row(Modifier.fillMaxWidth().padding(Spacing.l), verticalAlignment = Alignment.CenterVertically) {
                    ListIcon(name.ifBlank { "?" }, emoji, c)
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
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("list_name"),
            )
            Text(stringResource(R.string.list_editor_color), style = MaterialTheme.typography.titleSmall)
            ColorSwatchPicker(ListColors.presets, ListColors.names, color, onSelect = { color = it })
            Text(stringResource(R.string.list_editor_icon), style = MaterialTheme.typography.titleSmall)
            EmojiPicker(emoji, onSelect = { emoji = it })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = {
                        onSave(name.trim(), color, emoji)
                        onDismiss()
                    },
                    enabled = valid,
                    modifier = Modifier.testTag("list_save"),
                ) {
                    Text(stringResource(if (existing == null) R.string.list_editor_create else R.string.list_editor_save))
                }
            }
        }
    }
}
