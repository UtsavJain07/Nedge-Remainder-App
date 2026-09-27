package app.nudge.feature.smartview

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.nudge.core.common.Clock
import app.nudge.core.domain.repository.SettingsRepository
import app.nudge.core.domain.usecase.ObserveSmartViewUseCase
import app.nudge.core.domain.usecase.TaskGroup
import app.nudge.core.domain.usecase.ToggleCompleteUseCase
import app.nudge.core.domain.usecase.UndoRunner
import app.nudge.core.model.SmartViewType
import app.nudge.core.model.UndoSnapshot
import app.nudge.core.model.UserSettings
import app.nudge.core.ui.text.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

data class SmartViewUiState(
    val groups: List<TaskGroup> = emptyList(),
    val settings: UserSettings = UserSettings(),
    val completing: Set<String> = emptySet(),
    val loaded: Boolean = false,
)

sealed interface SmartViewEffect {
    data class Undo(val message: UiText, val snapshot: UndoSnapshot) : SmartViewEffect

    /** FR-36: the last open subtask of a parent was completed. */
    data class AllSubtasksDone(val parentId: String, val parentTitle: String) : SmartViewEffect
}

/**
 * Today / All tasks smart views (FR-81, FR-82, 03 §3.7): open tasks across lists grouped by list,
 * read-and-complete only, with the 600 ms completion hold of A1 and Undo.
 */
@HiltViewModel
class SmartViewViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    observeSmartView: ObserveSmartViewUseCase,
    settings: SettingsRepository,
    private val toggleComplete: ToggleCompleteUseCase,
    private val undoRunner: UndoRunner,
    val clock: Clock,
) : ViewModel() {
    val type: SmartViewType = SmartViewType.valueOf(savedStateHandle.toRoute<SmartViewRoute>().type)

    private val completing = MutableStateFlow<Set<String>>(emptySet())
    private val _effects = Channel<SmartViewEffect>(Channel.BUFFERED)
    val effects: Flow<SmartViewEffect> = _effects.receiveAsFlow()

    val state: StateFlow<SmartViewUiState> = combine(observeSmartView(type), settings.settings, completing) { groups, s, c ->
        SmartViewUiState(groups, s, c, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SmartViewUiState())

    init {
        // Release holds once the completed rows have left the (open-only) result.
        viewModelScope.launch {
            state.collect { s ->
                if (!s.loaded || s.completing.isEmpty()) return@collect
                val visible = s.groups.flatMap { g -> g.tasks.map { it.task.id } }.toSet()
                val gone = s.completing - visible
                if (gone.isNotEmpty()) completing.update { it - gone }
            }
        }
    }

    /** Tap: check immediately, complete after the 600 ms hold (A1 step 4). */
    fun onComplete(taskId: String, title: String) {
        if (taskId in completing.value) return
        completing.update { it + taskId }
        viewModelScope.launch {
            delay(COMPLETE_HOLD_MS)
            val r = toggleComplete(taskId, completed = true)
            if (r == null) {
                completing.update { it - taskId }
                return@launch
            }
            _effects.send(SmartViewEffect.Undo(UiText.Res(R.string.smart_completed, listOf(title)), r.snapshot))
            r.allSubtasksDoneParent?.let { _effects.send(SmartViewEffect.AllSubtasksDone(it.id, it.title)) }
        }
    }

    fun completeParent(id: String) {
        viewModelScope.launch { toggleComplete(id, completed = true) }
    }

    fun undo(snapshot: UndoSnapshot) = undoRunner.restore(snapshot)

    fun now(): Instant = clock.now()

    companion object {
        const val COMPLETE_HOLD_MS = 600L
    }
}
