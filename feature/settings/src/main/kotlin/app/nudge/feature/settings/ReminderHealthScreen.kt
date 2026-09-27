package app.nudge.feature.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.nudge.core.ui.format.currentLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nudge.core.designsystem.theme.EmphasizedType
import app.nudge.core.designsystem.theme.LocalNudgeColors
import app.nudge.core.designsystem.theme.Spacing
import app.nudge.core.domain.reminder.ReminderHealth
import app.nudge.core.domain.reminder.ReminderHealthMonitor
import app.nudge.core.ui.snackbar.LocalSnackbar
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject

/** FR-91, 07 §10. */
@HiltViewModel
class ReminderHealthViewModel @Inject constructor(private val monitor: ReminderHealthMonitor) : ViewModel() {
    val health = monitor.health

    fun refresh() = monitor.refresh()

    fun sendTestNudge() = monitor.sendTestNudge(TEST_DELAY_SECONDS)

    companion object {
        const val TEST_DELAY_SECONDS = 10L
    }
}

/** Reminder health checklist with a Fix for each failing item (03 §3.9, 07 §6, §10). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReminderHealthScreen(onBack: () -> Unit, viewModel: ReminderHealthViewModel = hiltViewModel()) {
    val health by viewModel.health.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current
    val snackbar = LocalSnackbar.current
    val testSent = stringResource(R.string.health_test_sent, ReminderHealthViewModel.TEST_DELAY_SECONDS.toInt())

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.refresh()
        // No dialog shown and no rationale → permanently denied: go to the system screen (07 §6).
        val permanentlyDenied = !granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
        if (permanentlyDenied) SystemIntents.launch(context, SystemIntents.appNotificationSettings(context))
    }
    val fixNotifications = {
        val canRequest = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (canRequest) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            SystemIntents.launch(context, SystemIntents.appNotificationSettings(context))
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar.hostState) },
        topBar = { SettingsTopBar(stringResource(R.string.health_title), onBack) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screenPadding, vertical = Spacing.s),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            HealthHeader(health)
            HealthRow(
                ok = health.notificationsEnabled,
                okText = stringResource(R.string.health_notifications_ok),
                issueText = stringResource(R.string.health_notifications_issue),
                onFix = fixNotifications,
            )
            HealthRow(
                ok = health.exactAlarmsAllowed,
                okText = stringResource(R.string.health_exact_ok),
                issueText = stringResource(R.string.health_exact_issue),
                onFix = if (SystemIntents.supportsExactAlarmSettings) {
                    { SystemIntents.launch(context, SystemIntents.exactAlarmSettings(context)) }
                } else {
                    null
                },
            )
            HealthRow(
                ok = health.blockedChannels.isEmpty(),
                okText = stringResource(R.string.health_channels_ok),
                issueText = stringResource(R.string.health_channels_issue, health.blockedChannels.map { channelLabel(it) }.joinToString()),
                onFix = { SystemIntents.launch(context, SystemIntents.appNotificationSettings(context)) },
            )
            HealthRow(
                ok = health.ignoringBatteryOptimizations,
                okText = stringResource(R.string.health_battery_ok),
                issueText = stringResource(R.string.health_battery_issue),
                onFix = { SystemIntents.launch(context, SystemIntents.batteryOptimizationSettings()) },
            )
            health.oemVendor?.let { vendor ->
                OemTip(vendor, onHow = { SystemIntents.launch(context, SystemIntents.dontKillMyApp(vendor)) })
            }
            Spacer(Modifier.height(Spacing.s))
            Button(
                onClick = {
                    viewModel.sendTestNudge()
                    snackbar.show(testSent)
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("health_test_nudge"),
            ) {
                Icon(Icons.Rounded.NotificationsActive, contentDescription = null)
                Spacer(Modifier.width(Spacing.s))
                Text(stringResource(R.string.health_test_nudge, ReminderHealthViewModel.TEST_DELAY_SECONDS.toInt()))
            }
        }
    }
}

@Composable
private fun HealthHeader(health: ReminderHealth) {
    val issues = health.issueCount
    Text(
        if (issues == 0) stringResource(R.string.health_header_ok) else pluralStringResource(R.plurals.health_header_issues, issues, issues),
        style = EmphasizedType.headlineMedium,
        color = if (issues == 0) LocalNudgeColors.current.low.color else MaterialTheme.colorScheme.error,
        modifier = Modifier
            .padding(vertical = Spacing.m)
            .semantics {
                heading()
                liveRegion = LiveRegionMode.Polite
            },
    )
}

@Composable
private fun HealthRow(ok: Boolean, okText: String, issueText: String, onFix: (() -> Unit)?) {
    val okColor = LocalNudgeColors.current.low.color
    Surface(
        color = if (ok) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.errorContainer,
        contentColor = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.heightIn(min = 56.dp).padding(start = Spacing.l, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = if (ok) okColor else MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(Spacing.m))
            Text(if (ok) okText else issueText, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(vertical = Spacing.s))
            if (!ok && onFix != null) {
                TextButton(onClick = onFix) { Text(stringResource(R.string.action_fix)) }
            }
        }
    }
}

/** OEM background-killer tip linking to dontkillmyapp.com (07 §6). */
@Composable
private fun OemTip(vendor: String, onHow: () -> Unit) {
    val name = vendor.replaceFirstChar { if (it.isLowerCase()) it.titlecase(currentLocale()) else it.toString() }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.heightIn(min = 56.dp).padding(start = Spacing.l, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(Spacing.m))
            Text(
                stringResource(R.string.health_oem_tip, name),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f).padding(vertical = Spacing.s),
            )
            TextButton(onClick = onHow) { Text(stringResource(R.string.health_oem_how)) }
        }
    }
}

/** Human names for the reminder channel ids (07 §7.1). */
@Composable
private fun channelLabel(id: String): String = when (id) {
    "reminders_urgent" -> stringResource(R.string.channel_urgent)
    "reminders_high" -> stringResource(R.string.channel_high)
    "reminders_medium" -> stringResource(R.string.channel_medium)
    "reminders_low" -> stringResource(R.string.channel_low)
    "reminders_due" -> stringResource(R.string.channel_due)
    else -> id
}
