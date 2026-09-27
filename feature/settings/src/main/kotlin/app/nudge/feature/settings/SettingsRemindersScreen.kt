package app.nudge.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.NotificationsPaused
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nudge.core.designsystem.component.CadencePickerDialog
import app.nudge.core.designsystem.component.PriorityFlag
import app.nudge.core.designsystem.component.cadenceLabel
import app.nudge.core.designsystem.component.priorityLabel
import app.nudge.core.designsystem.theme.LocalNudgeColors
import app.nudge.core.designsystem.theme.Spacing
import app.nudge.core.domain.usecase.PauseDuration
import app.nudge.core.model.Priority
import app.nudge.core.model.ReminderSettings
import app.nudge.core.ui.format.formatInstant
import app.nudge.core.ui.format.formatTime
import app.nudge.core.ui.picker.TimeDialog
import app.nudge.core.ui.snackbar.LocalSnackbar
import java.time.Instant
import java.time.LocalTime

/** Which picker is open on the Reminders screen. */
private sealed interface RemindersDialog {
    data class Cadence(val priority: Priority) : RemindersDialog

    data class Time(val field: TimeField) : RemindersDialog

    data object Snooze : RemindersDialog

    data object Pause : RemindersDialog
}

private enum class TimeField { MORNING, EVENING, QUIET_START, QUIET_END }

private val SNOOZE_OPTIONS = listOf(15, 30, 60, 120, 180)

/** Settings › Reminders (FR-101, FR-71, 07 §9). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsRemindersScreen(onBack: () -> Unit, onOpenHealth: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val health by viewModel.health.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<RemindersDialog?>(null) }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()

    LifecycleResumeEffect(Unit) {
        viewModel.refreshHealth()
        onPauseOrDispose { }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbar.current.hostState) },
        topBar = { SettingsTopBar(stringResource(R.string.settings_reminders), onBack, scroll) },
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
    ) { padding ->
        val r = settings?.reminders ?: return@Scaffold
        val now = viewModel.clock.now()
        val zone = viewModel.clock.zone()
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = Spacing.xxl),
        ) {
            SettingsSectionHeader(stringResource(R.string.settings_section_cadence))
            listOf(Priority.URGENT, Priority.HIGH, Priority.MEDIUM, Priority.LOW, Priority.NONE).forEach { p ->
                SettingsItem(
                    title = priorityLabel(p),
                    summary = cadenceLabel(r.cadenceFor(p)),
                    leading = { PriorityFlag(p, size = 20.dp) },
                    onClick = { dialog = RemindersDialog.Cadence(p) },
                )
            }

            SettingsSectionHeader(stringResource(R.string.settings_section_checkins))
            SettingsItem(stringResource(R.string.settings_morning), summary = formatTime(r.morningTime), onClick = { dialog = RemindersDialog.Time(TimeField.MORNING) })
            SettingsItem(stringResource(R.string.settings_evening), summary = formatTime(r.eveningTime), onClick = { dialog = RemindersDialog.Time(TimeField.EVENING) })

            SettingsSectionHeader(stringResource(R.string.settings_section_quiet))
            SwitchItem(
                title = stringResource(R.string.settings_quiet_hours),
                summary = stringResource(R.string.settings_quiet_hours_summary),
                checked = r.quietHoursEnabled,
                onCheckedChange = { on -> viewModel.updateReminders { it.copy(quietHoursEnabled = on) } },
            )
            SettingsItem(
                stringResource(R.string.settings_quiet_start),
                summary = formatTime(r.quietStart),
                enabled = r.quietHoursEnabled,
                onClick = { dialog = RemindersDialog.Time(TimeField.QUIET_START) },
            )
            SettingsItem(
                stringResource(R.string.settings_quiet_end),
                summary = formatTime(r.quietEnd),
                enabled = r.quietHoursEnabled,
                onClick = { dialog = RemindersDialog.Time(TimeField.QUIET_END) },
            )
            SwitchItem(
                title = stringResource(R.string.settings_urgent_ignores_quiet),
                checked = r.urgentIgnoresQuietHours,
                enabled = r.quietHoursEnabled,
                onCheckedChange = { on -> viewModel.updateReminders { it.copy(urgentIgnoresQuietHours = on) } },
            )

            SettingsSectionHeader(stringResource(R.string.settings_section_snooze_pause))
            SettingsItem(
                stringResource(R.string.settings_default_snooze),
                summary = snoozeLabel(r.defaultSnoozeMinutes),
                onClick = { dialog = RemindersDialog.Snooze },
            )
            val pausedUntil = r.pausedUntil
            if (r.isPaused(now) && pausedUntil != null) {
                SettingsItem(
                    title = if (pausedUntil == Instant.MAX) {
                        stringResource(R.string.settings_paused_forever)
                    } else {
                        stringResource(R.string.settings_paused_until, formatInstant(pausedUntil, zone, now.atZone(zone).toLocalDate()))
                    },
                    leading = { Icon(Icons.Rounded.NotificationsPaused, contentDescription = null) },
                    trailing = { TextButton(onClick = viewModel::resumeReminders) { Text(stringResource(R.string.settings_resume)) } },
                )
            } else {
                SettingsItem(
                    stringResource(R.string.settings_pause_all),
                    summary = stringResource(R.string.settings_pause_all_summary),
                    leading = { Icon(Icons.Rounded.NotificationsPaused, contentDescription = null) },
                    onClick = { dialog = RemindersDialog.Pause },
                )
            }

            SettingsSectionHeader(stringResource(R.string.settings_section_reliability))
            val issues = health.issueCount
            SettingsItem(
                title = stringResource(R.string.health_title),
                summary = if (issues == 0) stringResource(R.string.health_all_good) else pluralStringResource(R.plurals.health_issues, issues, issues),
                leading = {
                    if (issues == 0) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = LocalNudgeColors.current.low.color)
                    } else {
                        Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    }
                },
                trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
                onClick = onOpenHealth,
            )
            SettingsItem(
                title = stringResource(R.string.settings_sound),
                summary = stringResource(R.string.settings_sound_summary),
                leading = { Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = null) },
                onClick = { SystemIntents.launch(context, SystemIntents.appNotificationSettings(context)) },
            )
        }

        val dismiss = { dialog = null }
        when (val d = dialog) {
            is RemindersDialog.Cadence -> CadencePickerDialog(
                selected = r.cadenceFor(d.priority),
                defaultCadence = r.cadenceFor(d.priority),
                onSelect = { c -> if (c != null) viewModel.updateReminders { it.copy(defaultCadence = it.defaultCadence + (d.priority to c)) } },
                onDismiss = dismiss,
                includeDefault = false,
            )
            is RemindersDialog.Time -> TimeDialog(
                initial = r.time(d.field),
                title = stringResource(d.field.titleRes),
                onConfirm = { t ->
                    viewModel.updateReminders { it.withTime(d.field, t) }
                    dismiss()
                },
                onDismiss = dismiss,
            )
            RemindersDialog.Snooze -> ChoiceDialog(
                title = stringResource(R.string.settings_default_snooze),
                options = SNOOZE_OPTIONS,
                selected = r.defaultSnoozeMinutes,
                label = { snoozeLabel(it) },
                onSelect = { m -> viewModel.updateReminders { it.copy(defaultSnoozeMinutes = m) } },
                onDismiss = dismiss,
            )
            RemindersDialog.Pause -> ChoiceDialog(
                title = stringResource(R.string.settings_pause_all),
                options = PauseDuration.entries,
                selected = null,
                label = { pauseLabel(it) },
                onSelect = viewModel::pauseReminders,
                onDismiss = dismiss,
            )
            null -> Unit
        }
    }
}

private val TimeField.titleRes: Int
    get() = when (this) {
        TimeField.MORNING -> R.string.settings_morning
        TimeField.EVENING -> R.string.settings_evening
        TimeField.QUIET_START -> R.string.settings_quiet_start
        TimeField.QUIET_END -> R.string.settings_quiet_end
    }

private fun ReminderSettings.time(field: TimeField): LocalTime = when (field) {
    TimeField.MORNING -> morningTime
    TimeField.EVENING -> eveningTime
    TimeField.QUIET_START -> quietStart
    TimeField.QUIET_END -> quietEnd
}

private fun ReminderSettings.withTime(field: TimeField, t: LocalTime): ReminderSettings = when (field) {
    TimeField.MORNING -> copy(morningTime = t)
    TimeField.EVENING -> copy(eveningTime = t)
    TimeField.QUIET_START -> copy(quietStart = t)
    TimeField.QUIET_END -> copy(quietEnd = t)
}

@Composable
private fun snoozeLabel(minutes: Int): String =
    if (minutes < 60) {
        stringResource(R.string.settings_snooze_minutes, minutes)
    } else {
        pluralStringResource(R.plurals.settings_snooze_hours, minutes / 60, minutes / 60)
    }

@Composable
private fun pauseLabel(duration: PauseDuration): String = stringResource(
    when (duration) {
        PauseDuration.ONE_HOUR -> R.string.settings_pause_1h
        PauseDuration.UNTIL_TOMORROW -> R.string.settings_pause_tomorrow
        PauseDuration.UNTIL_RESUMED -> R.string.settings_pause_resume
    },
)
