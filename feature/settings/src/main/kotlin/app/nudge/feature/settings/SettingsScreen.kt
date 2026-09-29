package app.nudge.feature.settings

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nudge.core.designsystem.component.PriorityFlag
import app.nudge.core.designsystem.component.priorityLabel
import app.nudge.core.designsystem.theme.Spacing
import app.nudge.core.designsystem.theme.centeredMaxWidth
import app.nudge.core.domain.repository.ImportMode
import app.nudge.core.model.InsertPosition
import app.nudge.core.model.Priority
import app.nudge.core.model.ThemeMode
import app.nudge.core.model.UserSettings
import app.nudge.core.ui.snackbar.LocalSnackbar

private enum class SettingsDialog { NONE, NEW_TASK_POSITION, DEFAULT_PRIORITY, DELETE_ALL, LICENSES }

/** Settings root (FR-100..FR-104, 03 §3.9). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    versionName: String,
    showDebug: Boolean,
    onBack: () -> Unit,
    onOpenReminders: () -> Unit,
    onOpenDebug: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val importDialog by viewModel.importDialog.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val context = LocalContext.current
    val resources = LocalResources.current
    var dialog by rememberSaveable { mutableStateOf(SettingsDialog.NONE) }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()

    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.show(it.resolve(resources)) } }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) viewModel.export(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.onImportPicked(uri)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar.hostState) },
        topBar = { SettingsTopBar(stringResource(R.string.settings_title), onBack, scroll) },
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
    ) { padding ->
        val s = settings ?: return@Scaffold
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .centeredMaxWidth()
                .padding(bottom = Spacing.xxl)
                .testTag("settings_list"),
        ) {
            AppearanceSection(s, viewModel)

            SettingsSectionHeader(stringResource(R.string.settings_section_reminders))
            SettingsItem(
                title = stringResource(R.string.settings_reminders),
                summary = stringResource(R.string.settings_reminders_summary),
                leading = { Icon(Icons.Rounded.Notifications, contentDescription = null) },
                trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
                onClick = onOpenReminders,
                modifier = Modifier.testTag("settings_reminders"),
            )

            SettingsSectionHeader(stringResource(R.string.settings_section_behavior))
            SettingsItem(
                title = stringResource(R.string.settings_new_tasks_at),
                summary = positionLabel(s.newTaskPosition),
                onClick = { dialog = SettingsDialog.NEW_TASK_POSITION },
            )
            SettingsItem(
                title = stringResource(R.string.settings_default_priority),
                summary = priorityLabel(s.defaultPriority),
                trailing = { PriorityFlag(s.defaultPriority) },
                onClick = { dialog = SettingsDialog.DEFAULT_PRIORITY },
            )
            SwitchItem(stringResource(R.string.settings_haptics), s.haptics, { on -> viewModel.update { it.copy(haptics = on) } })
            SwitchItem(stringResource(R.string.settings_confirm_delete), s.confirmDelete, { on -> viewModel.update { it.copy(confirmDelete = on) } })

            SettingsSectionHeader(stringResource(R.string.settings_section_data))
            SettingsItem(
                title = stringResource(R.string.settings_export),
                summary = stringResource(R.string.settings_export_summary),
                leading = { Icon(Icons.Rounded.Upload, contentDescription = null) },
                onClick = { exportLauncher.launch(viewModel.backupFileName()) },
            )
            SettingsItem(
                title = stringResource(R.string.settings_import),
                summary = stringResource(R.string.settings_import_summary),
                leading = { Icon(Icons.Rounded.Download, contentDescription = null) },
                onClick = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
            )
            SettingsItem(
                title = stringResource(R.string.settings_delete_all),
                leading = { Icon(Icons.Rounded.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                onClick = { dialog = SettingsDialog.DELETE_ALL },
            )

            SettingsSectionHeader(stringResource(R.string.settings_section_about))
            SettingsItem(
                title = stringResource(R.string.settings_version),
                summary = versionName,
                leading = { Icon(Icons.Rounded.Info, contentDescription = null) },
            )
            SettingsItem(
                title = stringResource(R.string.settings_licenses),
                leading = { Icon(Icons.Rounded.Description, contentDescription = null) },
                onClick = { dialog = SettingsDialog.LICENSES },
            )
            SettingsItem(
                title = stringResource(R.string.settings_privacy),
                summary = stringResource(R.string.settings_privacy_statement),
                leading = { Icon(Icons.Rounded.Lock, contentDescription = null) },
            )
            if (showDebug) {
                SettingsItem(
                    title = stringResource(R.string.debug_title),
                    leading = { Icon(Icons.Rounded.BugReport, contentDescription = null) },
                    trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
                    onClick = onOpenDebug,
                )
            }
        }

        val dismiss = { dialog = SettingsDialog.NONE }
        when (dialog) {
            SettingsDialog.NEW_TASK_POSITION -> ChoiceDialog(
                title = stringResource(R.string.settings_new_tasks_at),
                options = InsertPosition.entries,
                selected = s.newTaskPosition,
                label = { positionLabel(it) },
                onSelect = { v -> viewModel.update { it.copy(newTaskPosition = v) } },
                onDismiss = dismiss,
            )
            SettingsDialog.DEFAULT_PRIORITY -> ChoiceDialog(
                title = stringResource(R.string.settings_default_priority),
                options = Priority.chipOrder + Priority.NONE,
                selected = s.defaultPriority,
                label = { priorityLabel(it) },
                onSelect = { v -> viewModel.update { it.copy(defaultPriority = v) } },
                onDismiss = dismiss,
            )
            SettingsDialog.DELETE_ALL -> DeleteAllDialog(onConfirm = viewModel::deleteAll, onDismiss = dismiss)
            SettingsDialog.LICENSES -> LicensesDialog(onDismiss = dismiss)
            SettingsDialog.NONE -> Unit
        }
    }

    when (importDialog) {
        ImportDialog.CHOOSE_MODE -> AlertDialog(
            onDismissRequest = viewModel::dismissImportDialog,
            title = { Text(stringResource(R.string.settings_import_title)) },
            text = { Text(stringResource(R.string.settings_import_body)) },
            confirmButton = { TextButton(onClick = { viewModel.import(ImportMode.REPLACE) }) { Text(stringResource(R.string.settings_import_replace)) } },
            dismissButton = { TextButton(onClick = { viewModel.import(ImportMode.MERGE) }) { Text(stringResource(R.string.settings_import_merge)) } },
        )
        ImportDialog.INVALID_FILE -> AlertDialog(
            onDismissRequest = viewModel::dismissImportDialog,
            title = { Text(stringResource(R.string.settings_import_invalid)) },
            confirmButton = { TextButton(onClick = viewModel::dismissImportDialog) { Text(stringResource(R.string.action_ok)) } },
        )
        ImportDialog.NONE -> Unit
    }
}

/** FR-100: theme, dynamic color (API 31+ only), pure black. */
@Composable
private fun AppearanceSection(s: UserSettings, viewModel: SettingsViewModel) {
    SettingsSectionHeader(stringResource(R.string.settings_section_appearance))
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_theme)) },
        supportingContent = {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = Spacing.s)) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = s.theme == mode,
                        onClick = { viewModel.update { it.copy(theme = mode) } },
                        shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                        label = { Text(themeLabel(mode)) },
                    )
                }
            }
        },
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        SwitchItem(
            title = stringResource(R.string.settings_dynamic_color),
            summary = stringResource(R.string.settings_dynamic_color_summary),
            checked = s.dynamicColor,
            onCheckedChange = { on -> viewModel.update { it.copy(dynamicColor = on) } },
        )
    }
    SwitchItem(
        title = stringResource(R.string.settings_pure_black),
        summary = stringResource(R.string.settings_pure_black_summary),
        checked = s.pureBlack,
        onCheckedChange = { on -> viewModel.update { it.copy(pureBlack = on) } },
    )
}

/** FR-103: typed confirmation "DELETE". */
@Composable
private fun DeleteAllDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val word = stringResource(R.string.settings_delete_all_word)
    var typed by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_delete_all_title)) },
        text = {
            Column {
                Text(stringResource(R.string.settings_delete_all_body, word))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.settings_delete_all_hint, word)) },
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.m).testTag("delete_all_field"),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = typed.trim() == word,
                onClick = {
                    onConfirm()
                    onDismiss()
                },
            ) { Text(stringResource(R.string.settings_delete_all_confirm), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** FR-104: the main open-source components and their licenses. */
@Composable
private fun LicensesDialog(onDismiss: () -> Unit) {
    val apache = stringResource(R.string.license_apache)
    val licenses = listOf(
        stringResource(R.string.lib_androidx) to apache,
        stringResource(R.string.lib_compose) to apache,
        stringResource(R.string.lib_material3) to apache,
        stringResource(R.string.lib_room) to apache,
        stringResource(R.string.lib_datastore) to apache,
        stringResource(R.string.lib_workmanager) to apache,
        stringResource(R.string.lib_hilt) to apache,
        stringResource(R.string.lib_kotlin) to apache,
        stringResource(R.string.lib_materialkolor) to stringResource(R.string.license_mit),
        stringResource(R.string.lib_reorderable) to apache,
        stringResource(R.string.lib_timber) to apache,
        stringResource(R.string.lib_font) to stringResource(R.string.license_ofl),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_licenses)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                licenses.forEach { (name, license) ->
                    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.s)) {
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                        Text(license, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun themeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.SYSTEM -> R.string.settings_theme_system
        ThemeMode.LIGHT -> R.string.settings_theme_light
        ThemeMode.DARK -> R.string.settings_theme_dark
    },
)

@Composable
private fun positionLabel(position: InsertPosition): String = stringResource(
    when (position) {
        InsertPosition.TOP -> R.string.settings_position_top
        InsertPosition.BOTTOM -> R.string.settings_position_bottom
    },
)
