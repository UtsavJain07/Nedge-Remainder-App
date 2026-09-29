package app.nudge.feature.list

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.nudge.core.common.Clock
import app.nudge.core.domain.repository.EmojiChange
import app.nudge.core.domain.repository.ListRepository
import app.nudge.core.domain.repository.SettingsRepository
import app.nudge.core.domain.usecase.DeleteCompletedUseCase
import app.nudge.core.domain.usecase.DeleteListUseCase
import app.nudge.core.domain.usecase.DeleteTaskUseCase
import app.nudge.core.domain.usecase.ListScreenModel
import app.nudge.core.domain.usecase.MoveTaskUseCase
import app.nudge.core.domain.usecase.ObserveListScreenUseCase
import app.nudge.core.domain.usecase.SnoozeOptions
import app.nudge.core.domain.usecase.SnoozeUseCase
import app.nudge.core.domain.usecase.ToggleCompleteUseCase
import app.nudge.core.domain.usecase.UndoRunner
import app.nudge.core.domain.usecase.UpdateListUseCase
import app.nudge.core.domain.usecase.UpdateTaskUseCase
import app.nudge.core.model.ListSortMode
import app.nudge.core.model.MoveOperation
import app.nudge.core.model.Priority
import app.nudge.core.model.TaskList
import app.nudge.core.model.TaskPatch
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/** Pager host: all lists in Home order + the initial page (FR-85). */
data class ListScreenState(val lists: List<TaskList> = emptyList(), val initialListId: String = "", val loaded: Boolean = false)

@HiltViewModel
class ListScreenViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    lists: ListRepository,
) : ViewModel() {
    val route: ListRoute = savedStateHandle.toRoute<ListRoute>()

    val state: StateFlow<ListScreenState> = lists.observeLists()
        .map { ListScreenState(it, route.listId, loaded = true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListScreenState(initialListId = route.listId))
}

data class ListPageState(
    val model: ListScreenModel? = null,
    val completing: Set<String> = emptySet(),
    val loaded: Boolean = false,
    val deleted: Boolean = false,
)

sealed interface ListEffect {
    data class Undo(val message: UiText, val snapshot: UndoSnapshot, val durationMs: Long) : ListEffect

    data class AllSubtasksDone(val parentId: String, val parentTitle: String) : ListEffect

    data class Message(val message: UiText) : ListEffect

    /** FR-38: the list went from ≥ 1 open task to 0. */
    data object AllDone : ListEffect

    /** After deleting the list: pop back and offer Undo on Home (03 §4.8). */
    data class ListDeleted(val snapshot: UndoSnapshot, val name: String) : ListEffect
}

/**
 * One list page (03 §3.3). Implements FR-15, FR-30..FR-38, FR-53, DD-11, and the 600 ms completion
 * hold of A1.
 */
@HiltViewModel
class ListPageViewModel @Inject constructor(
    private val observeListScreen: ObserveListScreenUseCase,
    private val toggleComplete: ToggleCompleteUseCase,
    private val deleteTask: DeleteTaskUseCase,
    private val moveTask: MoveTaskUseCase,
    private val updateTask: UpdateTaskUseCase,
    private val snooze: SnoozeUseCase,
    private val deleteCompleted: DeleteCompletedUseCase,
    private val deleteList: DeleteListUseCase,
    private val updateList: UpdateListUseCase,
    private val lists: ListRepository,
    private val settings: SettingsRepository,
    private val undoRunner: UndoRunner,
    val clock: Clock,
) : ViewModel() {
    private val listId = MutableStateFlow<String?>(null)
    private val completing = MutableStateFlow<Set<String>>(emptySet())
    private val _effects = Channel<ListEffect>(Channel.BUFFERED)
    val effects: Flow<ListEffect> = _effects.receiveAsFlow()
    private var lastOpenCount: Int? = null

    val state: StateFlow<ListPageState> = listId.filterNotNull().flatMapLatest { id ->
        combine(observeListScreen(id), completing) { model, c ->
            if (model == null) {
                ListPageState(loaded = true, deleted = true)
            } else {
                ListPageState(model, c, loaded = true)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListPageState())

    init {
        viewModelScope.launch {
            state.collect { s ->
                val open = s.model?.tree?.openCount ?: return@collect
                val before = lastOpenCount
                if (before != null && before >= 1 && open == 0) _effects.send(ListEffect.AllDone)
                lastOpenCount = open
                // Clear holds once the DB reflects the completion.
                val doneIds = s.model.tree.completed.flatMap { n -> listOf(n.task.id) + n.children.map { it.id } }.toSet() +
                    s.model.tree.open.flatMap { n -> n.children.filter { it.isCompleted }.map { it.id } }
                if (s.completing.any { it in doneIds }) completing.update { it - doneIds }
            }
        }
    }

    fun start(id: String) {
        if (listId.value != id) {
            lastOpenCount = null
            listId.value = id
        }
    }

    /** Tap on an open row: check immediately, move to Completed after a 600 ms hold (A1 step 4). */
    fun onToggle(taskId: String, title: String, isCompleted: Boolean) {
        if (isCompleted) {
            viewModelScope.launch { toggleComplete(taskId, completed = false) }
            return
        }
        if (taskId in completing.value) return
        completing.update { it + taskId }
        viewModelScope.launch {
            delay(COMPLETE_HOLD_MS)
            val r = toggleComplete(taskId, completed = true)
            if (r == null) {
                completing.update { it - taskId }
                return@launch
            }
            _effects.send(ListEffect.Undo(UiText.Res(R.string.list_completed, listOf(title)), r.snapshot, SnackbarDispatcher.COMPLETE_MS))
            r.allSubtasksDoneParent?.let { _effects.send(ListEffect.AllSubtasksDone(it.id, it.title)) }
        }
    }

    /** Swipe right: complete immediately (the row is already gone). */
    fun completeNow(taskId: String, title: String) {
        viewModelScope.launch {
            val r = toggleComplete(taskId, completed = true) ?: return@launch
            _effects.send(ListEffect.Undo(UiText.Res(R.string.list_completed, listOf(title)), r.snapshot, SnackbarDispatcher.COMPLETE_MS))
            r.allSubtasksDoneParent?.let { _effects.send(ListEffect.AllSubtasksDone(it.id, it.title)) }
        }
    }

    fun completeParent(id: String) {
        viewModelScope.launch { toggleComplete(id, completed = true) }
    }

    fun onDelete(taskId: String, title: String) {
        viewModelScope.launch {
            val snapshot = deleteTask(taskId)
            _effects.send(ListEffect.Undo(UiText.Res(R.string.list_deleted_task, listOf(title)), snapshot, SnackbarDispatcher.DELETE_MS))
        }
    }

    /** DnD drop / accessibility move (FR-22..FR-26). Nesting shows "Moved into 'X'" with Undo (DD-11). */
    fun onMove(op: MoveOperation, nestTargetTitle: String? = null) {
        viewModelScope.launch {
            val snapshot = runCatching { moveTask(op) }.getOrElse {
                _effects.send(ListEffect.Message(UiText.Res(R.string.list_move_rejected)))
                return@launch
            }
            val title = nestTargetTitle ?: (op as? MoveOperation.Nest)?.let { n ->
                state.value.model?.tree?.node(n.parentId)?.task?.title
            }
            when {
                op is MoveOperation.Nest && title != null ->
                    _effects.send(ListEffect.Undo(UiText.Res(R.string.list_moved_into, listOf(title)), snapshot, SnackbarDispatcher.MOVE_MS))
                op is MoveOperation.ToList -> {
                    val name = lists.get(op.listId)?.name.orEmpty()
                    _effects.send(ListEffect.Undo(UiText.Res(R.string.list_moved_to_list, listOf(name)), snapshot, SnackbarDispatcher.MOVE_MS))
                }
                op is MoveOperation.ToTopLevel ->
                    _effects.send(ListEffect.Undo(UiText.Res(R.string.list_moved_top), snapshot, SnackbarDispatcher.MOVE_MS))
            }
        }
    }

    fun onToggleExpanded(taskId: String, expanded: Boolean) {
        viewModelScope.launch { updateTask(taskId, TaskPatch(isExpanded = expanded)) }
    }

    fun onPriority(taskId: String, p: Priority) {
        viewModelScope.launch { updateTask(taskId, TaskPatch(priority = p)) }
    }

    fun onSnoozeMinutes(taskId: String, minutes: Long) {
        viewModelScope.launch { snooze(taskId, clock.now().plus(Duration.ofMinutes(minutes))) }
    }

    fun onSnoozeTomorrow(taskId: String) {
        viewModelScope.launch {
            val morning = settings.settings.first().reminders.morningTime
            snooze(taskId, SnoozeOptions.nextMorning(clock.now(), morning, clock))
        }
    }

    fun onSortMode(mode: ListSortMode) {
        val id = listId.value ?: return
        viewModelScope.launch { lists.setSortMode(id, mode) }
    }

    fun onToggleCompletedSection() {
        val m = state.value.model ?: return
        viewModelScope.launch { lists.setCompletedExpanded(m.list.id, !m.list.completedExpanded) }
    }

    fun onDeleteCompleted() {
        val id = listId.value ?: return
        viewModelScope.launch {
            val snapshot = deleteCompleted(id)
            if (snapshot.tasksBefore.isNotEmpty()) {
                _effects.send(ListEffect.Undo(UiText.Res(R.string.list_deleted_completed), snapshot, SnackbarDispatcher.DELETE_MS))
            }
        }
    }

    fun onEditList(name: String, color: Int) {
        val id = listId.value ?: return
        viewModelScope.launch {
            runCatching { updateList(id, name, color, EmojiChange.Unchanged) }
                .onFailure { _effects.send(ListEffect.Message(UiText.Res(R.string.list_invalid_name))) }
        }
    }

    fun onDeleteList() {
        val m = state.value.model ?: return
        viewModelScope.launch {
            runCatching { deleteList(m.list.id) }
                .onSuccess { _effects.send(ListEffect.ListDeleted(it, m.list.name)) }
                .onFailure { _effects.send(ListEffect.Message(UiText.Res(R.string.list_last_cannot_delete))) }
        }
    }

    fun undo(snapshot: UndoSnapshot) = undoRunner.restore(snapshot)

    fun now(): Instant = clock.now()

    companion object {
        const val COMPLETE_HOLD_MS = 600L
    }
}
