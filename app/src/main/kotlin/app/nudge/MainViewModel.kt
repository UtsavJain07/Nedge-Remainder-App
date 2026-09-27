package app.nudge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nudge.core.domain.reminder.ReminderHealthMonitor
import app.nudge.core.domain.reminder.ReminderScheduler
import app.nudge.core.domain.repository.SettingsRepository
import app.nudge.core.domain.repository.TaskRepository
import app.nudge.core.model.UserSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A resolved notification deep link: open the task's list, highlight it and open its details (03 §4.6). */
data class TaskLink(val listId: String, val taskId: String, val nonce: Long = System.nanoTime())

sealed interface MainUiState {
    data object Loading : MainUiState

    data class Ready(val settings: UserSettings) : MainUiState
}

@HiltViewModel
class MainViewModel @Inject constructor(
    settings: SettingsRepository,
    private val tasks: TaskRepository,
    private val health: ReminderHealthMonitor,
    private val scheduler: ReminderScheduler,
) : ViewModel() {
    val uiState: StateFlow<MainUiState> = settings.settings
        .map { MainUiState.Ready(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, MainUiState.Loading)

    private val _taskLink = MutableStateFlow<TaskLink?>(null)
    val taskLink: StateFlow<TaskLink?> = _taskLink.asStateFlow()

    private var lastExactAllowed: Boolean? = null

    fun openTask(taskId: String) {
        viewModelScope.launch {
            val task = tasks.get(taskId) ?: return@launch
            if (task.deletedAt == null) _taskLink.value = TaskLink(task.listId, task.id)
        }
    }

    fun consumeTaskLink() {
        _taskLink.value = null
    }

    /** Re-check health on every resume; reschedule if exact-alarm permission changed (07 §6). */
    fun onResume() {
        health.refresh()
        val exact = health.health.value.exactAlarmsAllowed
        val before = lastExactAllowed
        lastExactAllowed = exact
        if (before != null && before != exact) viewModelScope.launch { scheduler.rescheduleAll() }
    }
}
