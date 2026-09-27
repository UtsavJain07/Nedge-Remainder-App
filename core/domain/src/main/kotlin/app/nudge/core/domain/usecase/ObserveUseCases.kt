package app.nudge.core.domain.usecase

import app.nudge.core.common.Clock
import app.nudge.core.domain.repository.ListRepository
import app.nudge.core.domain.repository.SettingsRepository
import app.nudge.core.domain.repository.TaskRepository
import app.nudge.core.domain.tree.ListItemUi
import app.nudge.core.domain.tree.ListTree
import app.nudge.core.domain.tree.TaskTreeBuilder
import app.nudge.core.domain.tree.flatten
import app.nudge.core.model.ListStats
import app.nudge.core.model.Priority
import app.nudge.core.model.SmartViewType
import app.nudge.core.model.Task
import app.nudge.core.model.TaskList
import app.nudge.core.model.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject

/** Everything the List screen renders (FR-20..FR-38). */
data class ListScreenModel(
    val list: TaskList,
    val tree: ListTree,
    val items: List<ListItemUi>,
    val settings: UserSettings,
)

class ObserveListScreenUseCase @Inject constructor(
    private val lists: ListRepository,
    private val tasks: TaskRepository,
    private val settings: SettingsRepository,
) {
    operator fun invoke(listId: String): Flow<ListScreenModel?> = combine(
        lists.observeList(listId),
        tasks.observeList(listId),
        settings.settings,
    ) { list, all, s ->
        if (list == null || list.deletedAt != null) return@combine null
        val tree = TaskTreeBuilder.build(all, list.sortMode)
        ListScreenModel(list, tree, tree.flatten(list.completedExpanded), s)
    }
}

/** A list card on Home (FR-06). */
data class ListWithStats(val list: TaskList, val stats: ListStats)

/** A task shown outside its list (Focus now, smart views, search). */
data class TaskWithList(val task: Task, val list: TaskList, val parentTitle: String? = null)

data class HomeModel(
    val lists: List<ListWithStats>,
    val focus: List<TaskWithList>,
    val todayCount: Int,
    val allCount: Int,
    val settings: UserSettings,
    val totalTaskCount: Int,
)

/** FR-80..FR-83. */
class ObserveHomeUseCase @Inject constructor(
    private val lists: ListRepository,
    private val tasks: TaskRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
) {
    operator fun invoke(): Flow<HomeModel> = combine(
        lists.observeLists(),
        lists.observeListStats(),
        tasks.observeOpenTasksAcrossLists(),
        settings.settings,
    ) { allLists, stats, open, s ->
        val byId = allLists.associateBy { it.id }
        val openLive = open.filter { it.listId in byId }
        val titles = openLive.associate { it.id to it.title }
        val focus = openLive
            .filter { it.priority == Priority.URGENT || it.priority == Priority.HIGH }
            .sortedWith(FOCUS_ORDER)
            .take(FOCUS_MAX)
            .map { TaskWithList(it, byId.getValue(it.listId), it.parentId?.let(titles::get)) }
        val today = clock.now().atZone(clock.zone()).toLocalDate()
        HomeModel(
            lists = allLists.map { ListWithStats(it, stats[it.id] ?: ListStats.EMPTY) },
            focus = focus,
            todayCount = openLive.count { SmartViews.isInToday(it, today) },
            allCount = openLive.size,
            settings = s,
            totalTaskCount = stats.values.sumOf { it.total },
        )
    }

    companion object {
        const val FOCUS_MAX = 5

        /** 03 §3.2: priority, then due asc (nulls last), then createdAt asc. */
        val FOCUS_ORDER: Comparator<Task> = compareByDescending<Task> { it.priority.level }
            .thenBy(nullsLast()) { it.dueDate?.toEpochDay() }
            .thenBy(nullsLast()) { it.dueTime?.toSecondOfDay() }
            .thenBy { it.createdAt }
    }
}

/** A group in a smart view / search result. [isOverdue] marks the red "Overdue" group (03 §3.7). */
data class TaskGroup(val list: TaskList?, val tasks: List<TaskWithList>, val isOverdue: Boolean = false)

object SmartViews {
    fun isOverdue(task: Task, today: LocalDate, nowTime: LocalTime): Boolean {
        val due = task.dueDate ?: return false
        val time = task.dueTime
        return due < today || (due == today && time != null && time < nowTime)
    }

    /** FR-81: due today or overdue, plus open Urgent. */
    fun isInToday(task: Task, today: LocalDate): Boolean =
        !task.isCompleted && (task.dueDate?.let { it <= today } == true || task.priority == Priority.URGENT)
}

/** FR-81, FR-82. */
class ObserveSmartViewUseCase @Inject constructor(
    private val lists: ListRepository,
    private val tasks: TaskRepository,
    private val clock: Clock,
) {
    operator fun invoke(type: SmartViewType): Flow<List<TaskGroup>> = combine(
        lists.observeLists(),
        tasks.observeOpenTasksAcrossLists(),
    ) { allLists, open ->
        val now = clock.now().atZone(clock.zone())
        val today = now.toLocalDate()
        val byId = allLists.associateBy { it.id }
        val titles = open.associate { it.id to it.title }
        val selected = open.filter { it.listId in byId }
            .filter { type == SmartViewType.ALL || SmartViews.isInToday(it, today) }
        val overdue = if (type == SmartViewType.TODAY) {
            selected.filter { SmartViews.isOverdue(it, today, now.toLocalTime()) }
        } else {
            emptyList()
        }
        val overdueIds = overdue.map { it.id }.toSet()
        fun wrap(t: Task) = TaskWithList(t, byId.getValue(t.listId), t.parentId?.let(titles::get))
        val order = compareByDescending<Task> { it.priority.level }
            .thenBy(nullsLast()) { it.dueDate?.toEpochDay() }
            .thenBy { it.parentId != null }
            .thenBy { it.sortOrder }
        buildList {
            if (overdue.isNotEmpty()) add(TaskGroup(null, overdue.sortedWith(order).map(::wrap), isOverdue = true))
            val rest = selected.filter { it.id !in overdueIds }.groupBy { it.listId }
            allLists.forEach { l ->
                rest[l.id]?.let { add(TaskGroup(l, it.sortedWith(order).map(::wrap))) }
            }
        }
    }
}

/** FR-84: FTS search grouped by list. */
class SearchTasksUseCase @Inject constructor(
    private val lists: ListRepository,
    private val tasks: TaskRepository,
) {
    operator fun invoke(query: Flow<String>): Flow<List<TaskGroup>> = query
        .map { it.trim() }
        .distinctUntilChanged()
        .flatMapLatest { q ->
            if (q.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(lists.observeLists(), tasks.search(q)) { allLists, found ->
                    val byId = allLists.associateBy { it.id }
                    val grouped = found.filter { it.listId in byId }.groupBy { it.listId }
                    allLists.mapNotNull { l ->
                        grouped[l.id]?.let { ts ->
                            TaskGroup(l, ts.sortedWith(compareBy<Task> { it.isCompleted }.thenBy { it.sortOrder }).map { TaskWithList(it, l) })
                        }
                    }
                }
            }
        }
}
