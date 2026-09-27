package app.nudge.core.ui.picker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.nudge.core.ui.R
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Due date flow (03 §3.5): Material 3 DatePicker, then an optional TimePicker ("No time" keeps it a
 * date-only due, FR-68).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DueDateTimeDialogs(
    initialDate: LocalDate?,
    initialTime: LocalTime?,
    onConfirm: (LocalDate, LocalTime?) -> Unit,
    onDismiss: () -> Unit,
    requireTime: Boolean = false,
    minDate: LocalDate? = null,
) {
    var pickedDate by rememberSaveable { mutableStateOf<Long?>(null) }
    val date = pickedDate
    if (date == null) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (initialDate ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val d = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                    return minDate == null || !d.isBefore(minDate)
                }
            },
        )
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = { pickedDate = state.selectedDateMillis }, enabled = state.selectedDateMillis != null) {
                    Text(stringResource(R.string.action_next))
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        ) { DatePicker(state) }
    } else {
        val localDate = Instant.ofEpochMilli(date).atZone(ZoneOffset.UTC).toLocalDate()
        TimeDialog(
            initial = initialTime ?: LocalTime.of(9, 0),
            onConfirm = { onConfirm(localDate, it) },
            onSkip = if (requireTime) null else ({ onConfirm(localDate, null) }),
            onDismiss = onDismiss,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeDialog(
    initial: LocalTime,
    onConfirm: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
    onSkip: (() -> Unit)? = null,
    title: String? = null,
) {
    val is24 = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    val state = rememberTimePickerState(initial.hour, initial.minute, is24)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title ?: stringResource(R.string.pick_time)) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                TimePicker(state)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = {
            if (onSkip != null) {
                TextButton(onClick = onSkip) { Text(stringResource(R.string.action_no_time)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

