package app.nudge.core.designsystem.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.nudge.core.designsystem.R
import app.nudge.core.designsystem.theme.Spacing
import app.nudge.core.model.ReminderCadence

/** Short label: "10 min", "1 h", "8 AM & 9 PM", "Off". */
@Composable
@ReadOnlyComposable
fun cadenceShortLabel(cadence: ReminderCadence): String = when (cadence) {
    ReminderCadence.EVERY_10_MIN -> stringResource(R.string.cadence_short_min, 10)
    ReminderCadence.EVERY_30_MIN -> stringResource(R.string.cadence_short_min, 30)
    ReminderCadence.EVERY_1_H -> stringResource(R.string.cadence_short_h, 1)
    ReminderCadence.EVERY_2_H -> stringResource(R.string.cadence_short_h, 2)
    ReminderCadence.EVERY_3_H -> stringResource(R.string.cadence_short_h, 3)
    ReminderCadence.EVERY_5_H -> stringResource(R.string.cadence_short_h, 5)
    ReminderCadence.TWICE_DAILY -> stringResource(R.string.cadence_twice_daily)
    ReminderCadence.OFF -> stringResource(R.string.cadence_off)
}

/** Long label: "Every 10 min", "Twice daily", "Off". */
@Composable
@ReadOnlyComposable
fun cadenceLabel(cadence: ReminderCadence): String = when (cadence) {
    ReminderCadence.TWICE_DAILY, ReminderCadence.OFF -> cadenceShortLabel(cadence)
    else -> stringResource(R.string.cadence_every_fmt, cadenceShortLabel(cadence))
}

/**
 * FR-62 cadence picker: Default (follows priority), 10 m, 30 m, 1 h, 2 h, 3 h, 5 h, twice daily, off.
 * [selected] null = Default.
 */
@Composable
fun CadencePickerDialog(
    selected: ReminderCadence?,
    defaultCadence: ReminderCadence,
    onSelect: (ReminderCadence?) -> Unit,
    onDismiss: () -> Unit,
    includeDefault: Boolean = true,
) {
    val options: List<ReminderCadence?> = (if (includeDefault) listOf<ReminderCadence?>(null) else emptyList()) + ReminderCadence.entries
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cadence_picker_title)) },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                options.forEach { option ->
                    val label = if (option == null) {
                        stringResource(R.string.cadence_default_fmt, cadenceLabel(defaultCadence))
                    } else {
                        cadenceLabel(option)
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = option == selected, role = Role.RadioButton) {
                                onSelect(option)
                                onDismiss()
                            }
                            .padding(horizontal = Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = Spacing.m))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
