package app.nudge.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nudge.core.common.Clock
import app.nudge.core.domain.reminder.ReminderHealth
import app.nudge.core.domain.reminder.ReminderHealthMonitor
import app.nudge.core.domain.repository.EmojiChange
import app.nudge.core.domain.usecase.CreateListUseCase
import app.nudge.core.domain.usecase.DeleteListUseCase
import app.nudge.core.domain.usecase.HomeModel
import app.nudge.core.domain.usecase.ObserveHomeUseCase
import app.nudge.core.domain.usecase.PauseRemindersUseCase
import app.nudge.core.domain.usecase.ReorderListsUseCase
import app.nudge.core.domain.usecase.ToggleCompleteUseCase
import app.nudge.core.domain.usecase.UndoRunner
import app.nudge.core.domain.usecase.UpdateListUseCase
import app.nudge.core.model.ListColors
import app.nudge.core.model.TaskList
import app.nudge.core.model.UndoSnapshot
import app.nudge.core.ui.snackbar.SnackbarDispatcher
import app.nudge.core.ui.text.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

data class HomeUiState(
    val model: HomeModel? = null,
    val health: ReminderHealth = ReminderHealth.HEALTHY,
    val completing: Set<String> = emptySet(),
    val now: Instant = Instant.EPOCH,
)

sealed interface HomeEffect {
    data class Undo(val message: UiText, val snapshot: UndoSnapshot, val durationMs: Long) : HomeEffect

    data class Message(val message: UiText) : HomeEffect
}

/** FR-04, FR-06, FR-71, FR-80..FR-83, FR-91. */
@HiltViewModel
class HomeViewModel @Inject constructor(
    observeHome: ObserveHomeUseCase,
    private val health: ReminderHealthMonitor,
    private val toggleComplete: ToggleCompleteUseCase,
    private val createList: CreateListUseCase,
    private val updateList: UpdateListUseCase,
    private val deleteList: DeleteListUseCase,
    private val reorderLists: ReorderListsUseCase,
    private val pause: PauseRemindersUseCase,
    private val undoRunner: UndoRunner,
    val clock: Clock,
) : ViewModel() {
    private val completing = MutableStateFlow<Set<String>>(emptySet())
    private val _effects = Channel<HomeEffect>(Channel.BUFFERED)
    val effects: Flow<HomeEffect> = _effects.receiveAsFlow()

    private val ticker = flow {
        while (true) {
            emit(clock.now())
            delay(60_000)
        }
    }

    val state: StateFlow<HomeUiState> = combine(observeHome(), health.health, completing, ticker) { m, h, c, now ->
        HomeUiState(m, h, c, now)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState(now = clock.now()))

    fun refreshHealth() = health.refresh()

    /** Focus card check: 600 ms hold then complete, with Undo (A1). */
    fun completeFocus(taskId: String, title: String) {
        if (taskId in completing.value) return
        completing.update { it + taskId }
        viewModelScope.launch {
            delay(600)
            val r = toggleComplete(taskId, completed = true)
            completing.update { it - taskId }
            if (r != null) {
                _effects.send(HomeEffect.Undo(UiText.Res(R.string.home_completed, listOf(title)), r.snapshot, SnackbarDispatcher.COMPLETE_MS))
            }
        }
    }

    fun onCreateList(name: String, color: Int, emoji: String?) {
        viewModelScope.launch {
            runCatching { createList(name, color, emoji) }.onFailure { _effects.send(HomeEffect.Message(UiText.Res(R.string.home_invalid_name))) }
        }
    }

    fun onEditList(id: String, name: String, color: Int, emoji: String?) {
        viewModelScope.launch { runCatching { updateList(id, name, color, EmojiChange.Set(emoji)) } }
    }

    fun onDeleteList(list: TaskList) {
        viewModelScope.launch {
            runCatching { deleteList(list.id) }
                .onSuccess { _effects.send(HomeEffect.Undo(UiText.Res(R.string.home_list_deleted, listOf(list.name)), it, SnackbarDispatcher.DELETE_MS)) }
                .onFailure { _effects.send(HomeEffect.Message(UiText.Res(R.string.home_last_list))) }
        }
    }

    fun onReorder(orderedIds: List<String>, movedId: String) {
        viewModelScope.launch { reorderLists(orderedIds, movedId) }
    }

    fun onResume() {
        viewModelScope.launch { pause.resume() }
    }

    fun nextColor(): Int = ListColors.nextUnused(state.value.model?.lists?.map { it.list.colorArgb }.orEmpty())

    fun undo(snapshot: UndoSnapshot) = undoRunner.restore(snapshot)
}
