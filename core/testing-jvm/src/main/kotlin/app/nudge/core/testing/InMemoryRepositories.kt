package app.nudge.core.testing

import app.nudge.core.common.Clock
import app.nudge.core.common.IdGenerator
import app.nudge.core.domain.order.OrderingCalculator
import app.nudge.core.domain.repository.EmojiChange
import app.nudge.core.domain.repository.ListRepository
import app.nudge.core.domain.repository.SettingsRepository
import app.nudge.core.domain.repository.TaskRepository
import app.nudge.core.domain.rules.MovePlanner
import app.nudge.core.domain.rules.TaskRules
import app.nudge.core.model.IllegalMoveException
import app.nudge.core.model.InsertPosition
import app.nudge.core.model.ListColors
import app.nudge.core.model.ListSortMode
import app.nudge.core.model.ListStats
import app.nudge.core.model.MoveOperation
import app.nudge.core.model.Priority
import app.nudge.core.model.ReminderStateUpdate
import app.nudge.core.model.Task
import app.nudge.core.model.TaskDraft
import app.nudge.core.model.TaskList
import app.nudge.core.model.TaskPatch
import app.nudge.core.model.UndoSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.Instant

/** Shared in-memory "tables" so the task and list fakes see each other's rows (like one Room database). */
class InMemoryStore {
    val tasks = MutableStateFlow<Map<String, Task>>(emptyMap())
    val lists = MutableStateFlow<Map<String, TaskList>>(emptyMap())

    fun upsertTasks(rows: Collection<Task>) = tasks.update { current -> current + rows.associateBy { it.id } }

    fun upsertLists(rows: Collection<TaskList>) = lists.update { current -> current + rows.associateBy { it.id } }

    val liveLists: List<TaskList>
        get() = lists.value.values.filter { it.deletedAt == null }
            .sortedWith(compareBy({ it.sortOrder }, { it.createdAt }))
}

/**
 * JVM fake of TaskRepositoryImpl: the same [TaskRules] / [MovePlanner] calls over an in-memory map,
 * with the same snapshot semantics (06 §5, §6). Use it for use-case and reminder-engine tests.
 */
class InMemoryTaskRepository(
    private val clock: Clock,
    private val ids: IdGenerator = SequentialIdGenerator("task"),
    private val settings: SettingsRepository = FakeSettingsRepository(),
    val store: InMemoryStore = InMemoryStore(),
) : TaskRepository {

    /** Seeds rows directly (bypassing rules). */
    fun seed(vararg tasks: Task) = store.upsertTasks(tasks.toList())

    val all: List<Task> get() = store.tasks.value.values.toList()

    private fun live(): List<Task> = store.tasks.value.values.filter { it.deletedAt == null }

    override fun observeList(listId: String): Flow<List<Task>> = store.tasks.map { m ->
        m.values.filter { it.listId == listId && it.deletedAt == null }
            .sortedWith(compareBy({ it.parentId != null }, { it.sortOrder }))
    }.distinctUntilChanged()

    override fun observeOpenTasksAcrossLists(): Flow<List<Task>> = combine(store.tasks, store.lists) { t, l ->
        val liveListIds = l.values.filter { it.deletedAt == null }.map { it.id }.toSet()
        t.values.filter { !it.isCompleted && it.deletedAt == null && it.listId in liveListIds }
    }.distinctUntilChanged()

    override fun observeTask(id: String): Flow<Task?> = store.tasks.map { it[id] }.distinctUntilChanged()

    /** Same semantics as the FTS query: every sanitized term must prefix-match a word of title or notes. */
    override fun search(query: String): Flow<List<Task>> {
        val terms = query.replace(Regex("[\"*\\-():^']"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }
            .map { it.lowercase() }
        if (terms.isEmpty()) return flowOf(emptyList())
        return store.tasks.map { m ->
            m.values.filter { t ->
                t.deletedAt == null && run {
                    val words = "${t.title} ${t.notes}".lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
                    terms.all { term -> words.any { it.startsWith(term) } }
                }
            }
        }
    }

    override suspend fun get(id: String): Task? = store.tasks.value[id]

    override suspend fun children(parentId: String): List<Task> =
        live().filter { it.parentId == parentId }.sortedBy { it.sortOrder }

    private fun group(listId: String, parentId: String?) =
        live().filter { it.listId == listId && it.parentId == parentId }

    override suspend fun create(draft: TaskDraft): Task {
        val morning = settings.settings.first().reminders.morningTime
        val parentId = draft.parentId
        if (parentId != null) {
            val parent = get(parentId) ?: throw IllegalMoveException("Parent $parentId not found")
            if (parent.parentId != null) throw IllegalMoveException("Max nesting depth is 1")
            if (parent.listId != draft.listId) throw IllegalMoveException("Parent must be in the same list")
        }
        val position = if (parentId != null) InsertPosition.BOTTOM else draft.position
        val siblings = group(draft.listId, parentId)
        val sortOrder = when (position) {
            InsertPosition.TOP -> OrderingCalculator.top(siblings.minOfOrNull { it.sortOrder })
            InsertPosition.BOTTOM -> OrderingCalculator.bottom(siblings.maxOfOrNull { it.sortOrder })
        }
        val task = TaskRules.newTask(ids.newId(), draft, sortOrder, clock.now(), clock.zone(), morning)
        store.upsertTasks(listOf(task))
        return task
    }

    override suspend fun update(id: String, patch: TaskPatch): Task {
        val morning = settings.settings.first().reminders.morningTime
        val task = requireTask(id)
        val updated = TaskRules.applyPatch(task, patch, children(id).isNotEmpty(), clock.now(), clock.zone(), morning)
        if (updated != task) store.upsertTasks(listOf(updated))
        return updated
    }

    override suspend fun setCompleted(id: String, completed: Boolean, progressBefore: Int?): UndoSnapshot {
        val task = requireTask(id)
        val now = clock.now()
        val changed = if (completed) {
            TaskRules.complete(task, children(id), now, progressBefore)
        } else {
            TaskRules.uncomplete(task, task.parentId?.let { get(it) }, now)
        }
        return writeChanges(changed)
    }

    override suspend fun move(op: MoveOperation): UndoSnapshot {
        val task = requireTask(op.taskId)
        val listTasks = live().filter { it.listId == task.listId }
        val targetTopMin = (op as? MoveOperation.ToList)
            ?.let { o -> group(o.listId, null).minOfOrNull { it.sortOrder } }
        return writeChanges(MovePlanner.plan(op, listTasks, clock.now(), targetTopMin))
    }

    override suspend fun softDelete(id: String): UndoSnapshot {
        val task = requireTask(id)
        return writeChanges(TaskRules.softDelete(task, children(id), clock.now()))
    }

    override suspend fun restore(snapshot: UndoSnapshot) {
        store.tasks.update { it - snapshot.createdTaskIds.toSet() }
        val now = clock.now()
        store.upsertTasks(snapshot.tasksBefore.map { it.copy(updatedAt = now) })
    }

    override suspend fun deleteCompleted(listId: String): UndoSnapshot {
        val now = clock.now()
        val changed = group(listId, null).filter { it.isCompleted }.flatMap { parent ->
            TaskRules.softDelete(parent, children(parent.id), now)
        }
        return writeChanges(changed)
    }

    override suspend fun tasksWithReminderDueBefore(instant: Instant): List<Task> = live()
        .filter { !it.isCompleted && it.reminder.nextAt?.let { at -> at <= instant } == true }
        .sortedBy { it.reminder.nextAt }

    override suspend fun earliestNextReminder(): Instant? =
        live().filter { !it.isCompleted }.mapNotNull { it.reminder.nextAt }.minOrNull()

    override suspend fun allOpenTasks(): List<Task> = live().filter { !it.isCompleted }

    override suspend fun staleReminderTaskIds(): List<String> = store.tasks.value.values
        .filter { it.reminder.nextAt != null && (it.isCompleted || it.deletedAt != null) }
        .map { it.id }

    /** Like the DAO query: writes reminder columns only, never updatedAt (06 §3). */
    override suspend fun updateReminderState(updates: List<ReminderStateUpdate>) {
        if (updates.isEmpty()) return
        store.tasks.update { current ->
            val next = current.toMutableMap()
            updates.forEach { u ->
                val t = next[u.taskId] ?: return@forEach
                next[u.taskId] = t.copy(
                    reminder = u.state.copy(nextAt = u.next?.at, nextKind = u.next?.kind),
                )
            }
            next
        }
    }

    override suspend fun purgeDeleted(before: Instant): Int {
        val doomed = store.tasks.value.values.filter { it.deletedAt != null && it.deletedAt!! < before }.map { it.id }
        store.tasks.update { it - doomed.toSet() }
        return doomed.size
    }

    private fun writeChanges(changed: List<Task>): UndoSnapshot {
        if (changed.isEmpty()) return UndoSnapshot.EMPTY
        val before = changed.mapNotNull { store.tasks.value[it.id] }
        store.upsertTasks(changed)
        return UndoSnapshot(tasksBefore = before)
    }

    private suspend fun requireTask(id: String): Task =
        get(id)?.takeIf { it.deletedAt == null } ?: throw NoSuchElementException("Task $id not found")
}

/** JVM fake of ListRepositoryImpl sharing [store] with an [InMemoryTaskRepository]. */
class InMemoryListRepository(
    private val clock: Clock,
    private val ids: IdGenerator = SequentialIdGenerator("list"),
    val store: InMemoryStore = InMemoryStore(),
) : ListRepository {

    fun seed(vararg lists: TaskList) = store.upsertLists(lists.toList())

    override fun observeLists(): Flow<List<TaskList>> = store.lists.map { _ -> store.liveLists }.distinctUntilChanged()

    override fun observeList(id: String): Flow<TaskList?> = store.lists.map { it[id] }.distinctUntilChanged()

    override fun observeListStats(): Flow<Map<String, ListStats>> = store.tasks.map { m ->
        m.values.filter { it.parentId == null && it.deletedAt == null }.groupBy { it.listId }.mapValues { (_, ts) ->
            ListStats(
                openCount = ts.count { !it.isCompleted },
                completedCount = ts.count { it.isCompleted },
                hasUrgent = ts.any { !it.isCompleted && it.priority == Priority.URGENT },
            )
        }
    }.distinctUntilChanged()

    override suspend fun get(id: String): TaskList? = store.lists.value[id]

    override suspend fun lists(): List<TaskList> = store.liveLists

    override suspend fun create(name: String, color: Int, emoji: String?): TaskList {
        val now = clock.now()
        val list = TaskList(
            id = ids.newId(),
            name = name,
            colorArgb = color,
            emoji = emoji,
            sortOrder = OrderingCalculator.bottom(store.liveLists.maxOfOrNull { it.sortOrder }),
            isDefault = false,
            sortMode = ListSortMode.MY_ORDER,
            completedExpanded = false,
            createdAt = now,
            updatedAt = now,
        )
        store.upsertLists(listOf(list))
        return list
    }

    override suspend fun update(id: String, name: String?, color: Int?, emoji: EmojiChange) = edit(id) {
        it.copy(
            name = name ?: it.name,
            colorArgb = color ?: it.colorArgb,
            emoji = if (emoji is EmojiChange.Set) emoji.emoji else it.emoji,
        )
    }

    override suspend fun setSortMode(id: String, mode: ListSortMode) = edit(id) { it.copy(sortMode = mode) }

    override suspend fun setCompletedExpanded(id: String, expanded: Boolean) =
        edit(id) { it.copy(completedExpanded = expanded) }

    override suspend fun reorder(id: String, beforeId: String?, afterId: String?) {
        var all = store.liveLists
        var before = beforeId?.let { b -> all.firstOrNull { it.id == b }?.sortOrder }
        var after = afterId?.let { a -> all.firstOrNull { it.id == a }?.sortOrder }
        if (OrderingCalculator.needsRenormalize(before, after)) {
            val renorm = OrderingCalculator.renormalize(all.filter { it.id != id }.map { it.id })
            all = all.map { l -> renorm[l.id]?.let { l.copy(sortOrder = it) } ?: l }
            store.upsertLists(all.filter { it.id != id })
            before = beforeId?.let(renorm::get)
            after = afterId?.let(renorm::get)
        }
        val list = all.firstOrNull { it.id == id } ?: return
        val moved = list.copy(sortOrder = OrderingCalculator.between(before, after), updatedAt = clock.now())
        store.upsertLists(listOf(moved))
    }

    override suspend fun softDelete(id: String): UndoSnapshot {
        check(store.liveLists.size > 1) { "The last list cannot be deleted" }
        val list = store.lists.value[id] ?: throw NoSuchElementException("List $id not found")
        val tasks = store.tasks.value.values.filter { it.listId == id && it.deletedAt == null }
        val now = clock.now()
        store.upsertLists(listOf(list.copy(deletedAt = now, updatedAt = now)))
        store.upsertTasks(tasks.map { it.copy(deletedAt = now, updatedAt = now) })
        return UndoSnapshot(tasksBefore = tasks, listsBefore = listOf(list))
    }

    override suspend fun restore(snapshot: UndoSnapshot) {
        val now = clock.now()
        store.upsertLists(snapshot.listsBefore.map { it.copy(updatedAt = now) })
        store.tasks.update { it - snapshot.createdTaskIds.toSet() }
        store.upsertTasks(snapshot.tasksBefore.map { it.copy(updatedAt = now) })
    }

    override suspend fun ensureDefaultList() {
        if (store.liveLists.isNotEmpty()) return
        val now = clock.now()
        store.upsertLists(
            listOf(
                TaskList(
                    id = ids.newId(),
                    name = "My Tasks",
                    colorArgb = ListColors.DEFAULT,
                    emoji = null,
                    sortOrder = 0.0,
                    isDefault = true,
                    sortMode = ListSortMode.MY_ORDER,
                    completedExpanded = false,
                    createdAt = now,
                    updatedAt = now,
                ),
            ),
        )
    }

    private fun edit(id: String, transform: (TaskList) -> TaskList) {
        val list = store.lists.value[id] ?: return
        val next = transform(list)
        if (next != list) store.upsertLists(listOf(next.copy(updatedAt = clock.now())))
    }
}
