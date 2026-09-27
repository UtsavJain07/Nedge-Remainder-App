package app.nudge.feature.quickadd

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nudge.core.domain.repository.ListRepository
import app.nudge.core.domain.repository.SettingsRepository
import app.nudge.core.domain.repository.TaskRepository
import app.nudge.core.domain.usecase.CreateTaskUseCase
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.nudge.core.model.Priority
import app.nudge.core.model.Task
import app.nudge.core.model.ReminderCadence
import app.nudge.core.model.ReminderSettings
import app.nudge.core.model.TaskDraft
import app.nudge.core.model.TaskList
import app.nudge.core.model.UserSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject

/** Form state of the Quick Add sheet (03 §3.4). */
data class QuickAddForm(
    val priority: Priority = Priority.NONE,
    val dueDate: LocalDate? = null,
    val dueTime: LocalTime? = null,
    val cadenceOverride: ReminderCadence? = null,
    val showNotes: Boolean = false,
    val listId: String? = null,
)

data class QuickAddUiState(
    val form: QuickAddForm = QuickAddForm(),
    val lists: List<TaskList> = emptyList(),
    val parentTitle: String? = null,
    val reminderSettings: ReminderSettings = ReminderSettings(),
    val ready: Boolean = false,
) {
    val selectedList: TaskList? get() = lists.firstOrNull { it.id == form.listId } ?: lists.firstOrNull()

    /** The Reminder chip shows the effective cadence and changes live with priority (03 §3.4). */
    val effectiveCadence: ReminderCadence get() = form.cadenceOverride ?: reminderSettings.cadenceFor(form.priority)
}

sealed interface QuickAddEffect {
    /** A4: the saved title "flies" into the list. */
    data class Added(val title: String) : QuickAddEffect

    data object Error : QuickAddEffect
}

/** FR-10, FR-11, FR-13, US-1. */
@HiltViewModel
class QuickAddViewModel @Inject constructor(
    private val createTask: CreateTaskUseCase,
    private val lists: ListRepository,
    private val tasks: TaskRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val form = MutableStateFlow(QuickAddForm())

    /** Text lives in snapshot state so fast typing never loses characters (no async round-trip). */
    var title by mutableStateOf("")
        private set
    var notes by mutableStateOf("")
        private set
    private val parentTitle = MutableStateFlow<String?>(null)
    private var parentId: String? = null
    private var ready = MutableStateFlow(false)
    private val _effects = Channel<QuickAddEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    val state: StateFlow<QuickAddUiState> = combine(form, lists.observeLists(), parentTitle, settings.settings, ready) { f, l, p, s, r ->
        QuickAddUiState(f, l, p, s.reminders, r)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QuickAddUiState())

    private var userSettings: UserSettings = UserSettings()

    /** Configure for a list (List screen), a parent (subtask), or null list = last used (Home). */
    fun start(listId: String?, parentId: String?) {
        this.parentId = parentId
        viewModelScope.launch {
            userSettings = settings.settings.first()
            val parent = parentId?.let { tasks.get(it) }
            parentTitle.value = parent?.title
            val all = lists.lists()
            val chosen = parent?.listId ?: listId ?: userSettings.lastUsedListId?.takeIf { id -> all.any { it.id == id } } ?: all.firstOrNull()?.id
            form.value = QuickAddForm(listId = chosen, priority = userSettings.defaultPriority)
            ready.value = true
        }
    }

    fun onTitle(value: String) {
        title = value.take(Task.TITLE_MAX)
    }

    fun onPriority(p: Priority) = form.update { it.copy(priority = p) }

    fun onList(id: String) = form.update { it.copy(listId = id) }

    fun onDue(date: LocalDate?, time: LocalTime?) = form.update { it.copy(dueDate = date, dueTime = if (date == null) null else time) }

    fun onCadence(c: ReminderCadence?) = form.update { it.copy(cadenceOverride = c) }

    fun onNotes(value: String) {
        notes = value.take(Task.NOTES_MAX)
    }

    fun toggleNotes() = form.update { it.copy(showNotes = !it.showNotes) }

    /** Enter / Save: create, clear the title, keep the sheet open (FR-10). */
    fun save() {
        val f = form.value
        val listId = f.listId ?: return
        val t = title
        val n = notes
        if (t.isBlank()) return
        viewModelScope.launch {
            runCatching {
                createTask(
                    TaskDraft(
                        listId = listId,
                        parentId = parentId,
                        title = t,
                        priority = f.priority,
                        cadenceOverride = f.cadenceOverride,
                        notes = n,
                        dueDate = f.dueDate,
                        dueTime = f.dueTime,
                        position = userSettings.newTaskPosition,
                    ),
                )
            }.onSuccess {
                if (title == t) title = ""
                notes = ""
                form.update { QuickAddForm(listId = it.listId, priority = userSettings.defaultPriority) }
                _effects.send(QuickAddEffect.Added(t.trim()))
            }.onFailure {
                _effects.send(QuickAddEffect.Error)
            }
        }
    }
}
