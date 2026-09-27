package app.nudge.feature.taskdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nudge.core.common.ApplicationScope
import app.nudge.core.common.Clock
import app.nudge.core.domain.repository.ListRepository
import app.nudge.core.domain.repository.SettingsRepository
import app.nudge.core.domain.repository.TaskRepository
import app.nudge.core.domain.usecase.CreateTaskUseCase
import app.nudge.core.domain.usecase.DeleteTaskUseCase
import app.nudge.core.domain.usecase.MoveTaskUseCase
import app.nudge.core.domain.usecase.SetProgressUseCase
import app.nudge.core.domain.usecase.SnoozeOptions
import app.nudge.core.domain.usecase.SnoozeUseCase
import app.nudge.core.domain.usecase.ToggleCompleteUseCase
import app.nudge.core.domain.usecase.UndoRunner
import app.nudge.core.domain.usecase.UpdateTaskUseCase
import app.nudge.core.model.MoveOperation
import app.nudge.core.model.Patch
import app.nudge.core.model.Priority
import app.nudge.core.model.ReminderCadence
import app.nudge.core.model.Task
import app.nudge.core.model.TaskDraft
import app.nudge.core.model.TaskList
import app.nudge.core.model.TaskPatch
import app.nudge.core.model.UndoSnapshot
import app.nudge.core.model.UserSettings
import app.nudge.core.model.effectiveCadence
import app.nudge.core.ui.snackbar.SnackbarDispatcher
import app.nudge.core.ui.text.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import kotlin.math.roundToInt

data class TaskDetailUiState(
    val task: Task? = null,
    val list: TaskList? = null,
    val parent: Task? = null,
    val children: List<Task> = emptyList(),
    val lists: List<TaskList> = emptyList(),
    val settings: UserSettings = UserSettings(),
    val now: Instant = Instant.EPOCH,
    val zone: ZoneId = ZoneId.systemDefault(),
    val loaded: Boolean = false,
) {
    val hasChildren: Boolean get() = children.isNotEmpty()
    val effectiveProgress: Int
        get() = when {
            task == null -> 0
            task.isCompleted -> 100
            children.isEmpty() -> task.progress
            else -> children.map { if (it.isCompleted) 100 else it.progress }.average().roundToInt()
        }
    val effectiveCadence: ReminderCadence get() = task?.effectiveCadence(settings.reminders) ?: ReminderCadence.OFF
    val defaultCadence: ReminderCadence get() = settings.reminders.cadenceFor(task?.priority ?: Priority.NONE)
    val isSnoozed: Boolean get() = task?.reminder?.snoozedUntil?.isAfter(now) == true
    val isPaused: Boolean get() = settings.reminders.isPaused(now)
}

sealed interface TaskDetailEffect {
    data class Undo(val message: UiText, val snapshot: UndoSnapshot, val durationMs: Long) : TaskDetailEffect

    data class AllSubtasksDone(val parent: Task) : TaskDetailEffect

    data class Message(val message: UiText) : TaskDetailEffect

    data object Dismiss : TaskDetailEffect
}

/** FR-14 autosave (debounced 400 ms), FR-40..FR-43, FR-62, FR-67, 03 §3.5. */
@OptIn(FlowPreview::class)
@HiltViewModel
class TaskDetailViewModel @Inject constructor(
    private val tasks: TaskRepository,
    private val lists: ListRepository,
    private val settings: SettingsRepository,
    private val updateTask: UpdateTaskUseCase,
    private val toggleComplete: ToggleCompleteUseCase,
    private val setProgress: SetProgressUseCase,
    private val deleteTask: DeleteTaskUseCase,
    private val moveTask: MoveTaskUseCase,
    private val snooze: SnoozeUseCase,
    private val createTask: CreateTaskUseCase,
    private val undoRunner: UndoRunner,
    private val clock: Clock,
    @param:ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val taskId = MutableStateFlow<String?>(null)
    private val _effects = Channel<TaskDetailEffect>(Channel.BUFFERED)
    val effects: Flow<TaskDetailEffect> = _effects.receiveAsFlow()

    /** Local drafts so typing is never clobbered by DB emissions. */
    val titleDraft = MutableStateFlow("")
    val notesDraft = MutableStateFlow("")
    private var draftsFor: String? = null
    private var titleJob: Job? = null
    private var notesJob: Job? = null

    private val ticker: Flow<Instant> = flow {
        while (true) {
            emit(clock.now())
            delay(30_000)
        }
    }

    val state: StateFlow<TaskDetailUiState> = taskId.filterNotNull().flatMapLatest { id ->
        tasks.observeTask(id).flatMapLatest { task ->
            if (task == null || task.deletedAt != null) {
                flowOf(TaskDetailUiState(loaded = true))
            } else {
                combine(
                    lists.observeList(task.listId),
                    tasks.observeList(task.listId),
                    lists.observeLists(),
                    settings.settings,
                    ticker,
                ) { list, all, allLists, s, now ->
                    TaskDetailUiState(
                        task = task,
                        list = list,
                        parent = task.parentId?.let { pid -> all.firstOrNull { it.id == pid } },
                        children = all.filter { it.parentId == task.id }
                            .sortedWith(compareBy<Task> { it.isCompleted }.thenBy { it.sortOrder }),
                        lists = allLists,
                        settings = s,
                        now = now,
                        zone = clock.zone(),
                        loaded = true,
                    )
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TaskDetailUiState())

    fun start(id: String) {
        if (taskId.value == id) return
        flushDrafts()
        taskId.value = id
        viewModelScope.launch {
            val t = tasks.get(id) ?: return@launch
            draftsFor = id
            titleDraft.value = t.title
            notesDraft.value = t.notes
        }
    }

    private fun id(): String? = taskId.value

    fun onTitle(value: String) {
        titleDraft.value = value.take(Task.TITLE_MAX)
        titleJob?.cancel()
        titleJob = viewModelScope.launch {
            delay(AUTOSAVE_MS)
            saveTitle()
        }
    }

    fun onNotes(value: String) {
        notesDraft.value = value.take(Task.NOTES_MAX)
        notesJob?.cancel()
        notesJob = viewModelScope.launch {
            delay(AUTOSAVE_MS)
            saveNotes()
        }
    }

    private suspend fun saveTitle() {
        val id = draftsFor ?: return
        val t = titleDraft.value.trim()
        if (t.isNotEmpty()) runCatching { updateTask(id, TaskPatch(title = t)) }
    }

    private suspend fun saveNotes() {
        val id = draftsFor ?: return
        runCatching { updateTask(id, TaskPatch(notes = notesDraft.value)) }
    }

    /** Persist pending drafts immediately (sheet closing / switching task). */
    fun flushDrafts() {
        val pendingTitle = titleJob?.isActive == true
        val pendingNotes = notesJob?.isActive == true
        titleJob?.cancel()
        notesJob?.cancel()
        if (pendingTitle || pendingNotes) {
            // Run outside viewModelScope so it survives the sheet closing.
            appScope.launch {
                if (pendingTitle) saveTitle()
                if (pendingNotes) saveNotes()
            }
        }
    }

    fun onPriority(p: Priority) = patch(TaskPatch(priority = p))

    fun onCadence(c: ReminderCadence?) = patch(TaskPatch(cadenceOverride = Patch.Set(c)))

    fun onDue(date: LocalDate?, time: LocalTime?) = patch(TaskPatch(dueDate = Patch.Set(date), dueTime = Patch.Set(time)))

    fun onExpanded(expanded: Boolean) = patch(TaskPatch(isExpanded = expanded))

    private fun patch(p: TaskPatch) {
        val id = id() ?: return
        viewModelScope.launch { runCatching { updateTask(id, p) } }
    }

    /** FR-43: releasing at 100 completes (with Undo); otherwise saves the value. */
    fun onProgressCommit(value: Int, before: Int) {
        val id = id() ?: return
        viewModelScope.launch {
            val snapshot = runCatching { setProgress(id, value, before) }.getOrNull()
            if (snapshot != null) {
                val title = state.value.task?.title.orEmpty()
                _effects.send(TaskDetailEffect.Undo(UiText.Res(R.string.detail_completed, listOf(title)), snapshot, SnackbarDispatcher.COMPLETE_MS))
            }
        }
    }

    fun onToggleComplete() = toggle(id())

    fun onToggleSubtask(subId: String) = toggle(subId)

    private fun toggle(target: String?) {
        target ?: return
        viewModelScope.launch {
            val r = toggleComplete(target) ?: return@launch
            if (r.completed) {
                _effects.send(TaskDetailEffect.Undo(UiText.Res(R.string.detail_completed, listOf(r.task.title)), r.snapshot, SnackbarDispatcher.COMPLETE_MS))
            }
            r.allSubtasksDoneParent?.let { _effects.send(TaskDetailEffect.AllSubtasksDone(it)) }
        }
    }

    fun completeParent(parentId: String) {
        viewModelScope.launch { toggleComplete(parentId, completed = true) }
    }

    fun onSnoozeMinutes(minutes: Long) = snoozeUntil(clock.now().plus(Duration.ofMinutes(minutes)))

    fun onSnoozeTomorrow() {
        viewModelScope.launch {
            val morning = settings.settings.first().reminders.morningTime
            snoozeUntil(SnoozeOptions.nextMorning(clock.now(), morning, clock))
        }
    }

    fun onSnoozeAt(date: LocalDate, time: LocalTime) {
        val until = date.atTime(time).atZone(clock.zone()).toInstant()
        if (!until.isAfter(clock.now())) {
            viewModelScope.launch { _effects.send(TaskDetailEffect.Message(UiText.Res(R.string.detail_snooze_past))) }
            return
        }
        snoozeUntil(until)
    }

    private fun snoozeUntil(until: Instant) {
        val id = id() ?: return
        viewModelScope.launch { runCatching { snooze(id, until) } }
    }

    fun onCancelSnooze() {
        val id = id() ?: return
        viewModelScope.launch { runCatching { snooze(id, null) } }
    }

    fun onDelete() {
        val id = id() ?: return
        val title = state.value.task?.title.orEmpty()
        viewModelScope.launch {
            val snapshot = deleteTask(id)
            _effects.send(TaskDetailEffect.Dismiss)
            _effects.send(TaskDetailEffect.Undo(UiText.Res(R.string.detail_deleted, listOf(title)), snapshot, SnackbarDispatcher.DELETE_MS))
        }
    }

    fun onMoveToList(listId: String) {
        val id = id() ?: return
        val name = state.value.lists.firstOrNull { it.id == listId }?.name.orEmpty()
        viewModelScope.launch {
            val snapshot = runCatching { moveTask(MoveOperation.ToList(id, listId)) }.getOrNull() ?: return@launch
            _effects.send(TaskDetailEffect.Undo(UiText.Res(R.string.detail_moved, listOf(name)), snapshot, SnackbarDispatcher.MOVE_MS))
        }
    }

    fun onAddSubtask(title: String) {
        val s = state.value
        val task = s.task ?: return
        if (title.isBlank() || task.parentId != null) return
        viewModelScope.launch { runCatching { createTask(TaskDraft(listId = task.listId, parentId = task.id, title = title)) } }
    }

    fun undo(snapshot: UndoSnapshot) = undoRunner.restore(snapshot)

    override fun onCleared() {
        flushDrafts()
        super.onCleared()
    }

    companion object {
        const val AUTOSAVE_MS = 400L
    }
}
