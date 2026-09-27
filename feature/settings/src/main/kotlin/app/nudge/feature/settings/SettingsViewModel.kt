package app.nudge.feature.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nudge.core.common.Clock
import app.nudge.core.domain.reminder.ReminderHealth
import app.nudge.core.domain.reminder.ReminderHealthMonitor
import app.nudge.core.domain.repository.BackupRepository
import app.nudge.core.domain.repository.ImportMode
import app.nudge.core.domain.repository.ImportResult
import app.nudge.core.domain.repository.SettingsRepository
import app.nudge.core.domain.usecase.DeleteAllDataUseCase
import app.nudge.core.domain.usecase.ImportBackupUseCase
import app.nudge.core.domain.usecase.PauseDuration
import app.nudge.core.domain.usecase.PauseRemindersUseCase
import app.nudge.core.domain.usecase.UpdateSettingsUseCase
import app.nudge.core.model.ReminderSettings
import app.nudge.core.model.UserSettings
import app.nudge.core.ui.text.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** Dialogs driven by the import flow (FR-103, 03 §5). */
enum class ImportDialog { NONE, CHOOSE_MODE, INVALID_FILE }

/**
 * Settings and Settings › Reminders (FR-100..FR-104, 07 §9). Every change goes through
 * [UpdateSettingsUseCase], which reschedules reminders when reminder settings change.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    settingsRepository: SettingsRepository,
    private val updateSettings: UpdateSettingsUseCase,
    private val pause: PauseRemindersUseCase,
    private val backup: BackupRepository,
    private val importBackup: ImportBackupUseCase,
    private val deleteAllData: DeleteAllDataUseCase,
    private val healthMonitor: ReminderHealthMonitor,
    val clock: Clock,
) : ViewModel() {
    val settings: StateFlow<UserSettings?> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val health: StateFlow<ReminderHealth> = healthMonitor.health

    private val _importDialog = MutableStateFlow(ImportDialog.NONE)
    val importDialog: StateFlow<ImportDialog> = _importDialog.asStateFlow()
    private var pendingImport: String? = null

    private val _messages = Channel<UiText>(Channel.BUFFERED)
    val messages: Flow<UiText> = _messages.receiveAsFlow()

    fun update(transform: (UserSettings) -> UserSettings) {
        viewModelScope.launch { updateSettings(transform) }
    }

    fun updateReminders(transform: (ReminderSettings) -> ReminderSettings) = update { it.copy(reminders = transform(it.reminders)) }

    fun pauseReminders(duration: PauseDuration) {
        viewModelScope.launch { pause(duration) }
    }

    fun resumeReminders() {
        viewModelScope.launch { pause.resume() }
    }

    fun refreshHealth() = healthMonitor.refresh()

    /** `nudge-backup-YYYYMMDD-HHmm.json` (06 §10). */
    fun backupFileName(): String = "nudge-backup-${FILE_STAMP.format(clock.now().atZone(clock.zone()))}.json"

    fun export(uri: Uri) {
        viewModelScope.launch {
            val ok = runCatching {
                val json = backup.exportJson()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                        ?: error("No output stream")
                }
            }.isSuccess
            _messages.send(UiText.Res(if (ok) R.string.settings_export_done else R.string.settings_error))
        }
    }

    /** Reads the picked file, then asks Replace / Merge. */
    fun onImportPicked(uri: Uri) {
        viewModelScope.launch {
            val text = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                }
            }.getOrNull()
            if (text.isNullOrBlank()) {
                _importDialog.value = ImportDialog.INVALID_FILE
            } else {
                pendingImport = text
                _importDialog.value = ImportDialog.CHOOSE_MODE
            }
        }
    }

    fun import(mode: ImportMode) {
        val json = pendingImport ?: return
        pendingImport = null
        _importDialog.value = ImportDialog.NONE
        viewModelScope.launch {
            when (val result = runCatching { importBackup(json, mode) }.getOrElse { ImportResult.InvalidFile }) {
                is ImportResult.Success -> _messages.send(UiText.Res(R.string.settings_import_done, listOf(result.lists, result.tasks)))
                ImportResult.InvalidFile -> _importDialog.value = ImportDialog.INVALID_FILE
                is ImportResult.UnsupportedVersion -> _messages.send(UiText.Res(R.string.settings_import_unsupported))
            }
        }
    }

    fun dismissImportDialog() {
        pendingImport = null
        _importDialog.value = ImportDialog.NONE
    }

    fun deleteAll() {
        viewModelScope.launch {
            val ok = runCatching { deleteAllData() }.isSuccess
            _messages.send(UiText.Res(if (ok) R.string.settings_delete_all_done else R.string.settings_error))
        }
    }

    private companion object {
        val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
    }
}
