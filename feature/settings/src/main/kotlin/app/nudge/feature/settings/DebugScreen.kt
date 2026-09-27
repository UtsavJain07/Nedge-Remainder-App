package app.nudge.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.nudge.core.common.Clock
import app.nudge.core.designsystem.theme.Spacing
import app.nudge.core.domain.reminder.ReminderDebugTools
import app.nudge.core.domain.reminder.ScheduledReminder
import app.nudge.core.ui.format.formatInstant
import app.nudge.core.ui.snackbar.LocalSnackbar
import app.nudge.core.ui.text.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

data class DebugUiState(
    val offsetMinutes: Long = 0,
    val nextAlarmAt: Instant? = null,
    val scheduled: List<ScheduledReminder>? = null,
)

/** Debug reminder tools (07 §11); only reachable when the debug destination is registered. */
@HiltViewModel
class DebugViewModel @Inject constructor(
    private val tools: ReminderDebugTools,
    private val seeder: DemoDataSeeder,
    val clock: Clock,
) : ViewModel() {
    private val _state = MutableStateFlow(DebugUiState(offsetMinutes = tools.timeOffsetMinutes()))
    val state: StateFlow<DebugUiState> = _state.asStateFlow()
    private val _messages = Channel<UiText>(Channel.BUFFERED)
    val messages: Flow<UiText> = _messages.receiveAsFlow()

    init {
        refreshNextAlarm()
    }

    fun dispatchNow() = perform { UiText.Res(R.string.debug_dispatched, listOf(tools.dispatchNow())) }

    fun showScheduled() {
        viewModelScope.launch { _state.update { it.copy(scheduled = tools.scheduled()) } }
    }

    fun timeTravel(minutes: Long) = perform {
        tools.timeTravel(minutes)
        UiText.Res(R.string.debug_time_offset, listOf(tools.timeOffsetMinutes()))
    }

    fun resetTime() = perform {
        tools.resetTime()
        UiText.Res(R.string.debug_time_reset)
    }

    fun resetAnchors() = perform {
        tools.resetAllAnchors()
        UiText.Res(R.string.debug_anchors_reset)
    }

    fun loadDemoData() = perform { UiText.Res(R.string.debug_demo_loaded, listOf(seeder.seed())) }

    /** Runs [action], refreshes the derived debug state, and reports [action]'s message. */
    private fun perform(action: suspend () -> UiText) {
        viewModelScope.launch {
            val message = runCatching { action() }.getOrElse { UiText.Res(R.string.settings_error) }
            val scheduled = if (_state.value.scheduled != null) tools.scheduled() else null
            _state.update { it.copy(offsetMinutes = tools.timeOffsetMinutes(), nextAlarmAt = tools.nextAlarmAt(), scheduled = scheduled) }
            _messages.send(message)
        }
    }

    private fun refreshNextAlarm() {
        viewModelScope.launch { _state.update { it.copy(nextAlarmAt = tools.nextAlarmAt()) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DebugScreen(onBack: () -> Unit, viewModel: DebugViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val context = LocalContext.current
    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.show(it.resolve(context)) } }
    val zone = viewModel.clock.zone()
    val today = viewModel.clock.now().atZone(zone).toLocalDate()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar.hostState) },
        topBar = { SettingsTopBar(stringResource(R.string.debug_title), onBack) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = Spacing.xxl),
        ) {
            SettingsSectionHeader(stringResource(R.string.debug_section_engine))
            SettingsItem(stringResource(R.string.debug_dispatch_now), onClick = viewModel::dispatchNow)
            SettingsItem(
                stringResource(R.string.debug_next_alarm),
                summary = state.nextAlarmAt?.let { formatInstant(it, zone, today) } ?: stringResource(R.string.debug_none),
            )
            SettingsItem(stringResource(R.string.debug_show_scheduled), onClick = viewModel::showScheduled)
            state.scheduled?.let { ScheduledTable(it, zone, today) }
            SettingsItem(stringResource(R.string.debug_reset_anchors), onClick = viewModel::resetAnchors)

            SettingsSectionHeader(stringResource(R.string.debug_section_time))
            SettingsItem(
                stringResource(R.string.debug_current_offset),
                summary = stringResource(R.string.debug_offset_value, state.offsetMinutes),
            )
            SettingsItem(stringResource(R.string.debug_travel_10m), onClick = { viewModel.timeTravel(10) })
            SettingsItem(stringResource(R.string.debug_travel_1h), onClick = { viewModel.timeTravel(60) })
            SettingsItem(stringResource(R.string.debug_reset_time), onClick = viewModel::resetTime)

            SettingsSectionHeader(stringResource(R.string.debug_section_data))
            SettingsItem(
                stringResource(R.string.debug_demo_data),
                summary = stringResource(R.string.debug_demo_data_summary),
                onClick = viewModel::loadDemoData,
            )
        }
    }
}

/** Title · next at · kind · count (07 §11). */
@Composable
private fun ScheduledTable(rows: List<ScheduledReminder>, zone: java.time.ZoneId, today: java.time.LocalDate) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.xs),
    ) {
        Column(Modifier.padding(Spacing.m)) {
            TableRow(
                stringResource(R.string.debug_col_title),
                stringResource(R.string.debug_col_next),
                stringResource(R.string.debug_col_kind),
                stringResource(R.string.debug_col_count),
                header = true,
            )
            if (rows.isEmpty()) {
                Text(stringResource(R.string.debug_none), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Spacing.s))
            }
            rows.forEach { r ->
                TableRow(r.title, formatInstant(r.nextAt, zone, today), r.kind?.name.orEmpty(), r.count.toString())
            }
        }
    }
}

@Composable
private fun TableRow(title: String, next: String, kind: String, count: String, header: Boolean = false) {
    val style = MaterialTheme.typography.bodySmall.let { if (header) it.copy(fontWeight = FontWeight.Bold) else it }
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xxs)) {
        Text(title, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.4f))
        Text(next, style = style, maxLines = 1, modifier = Modifier.weight(0.3f))
        Text(kind, style = style, maxLines = 1, modifier = Modifier.weight(0.2f))
        Text(count, style = style, maxLines = 1, modifier = Modifier.weight(0.1f))
    }
}
