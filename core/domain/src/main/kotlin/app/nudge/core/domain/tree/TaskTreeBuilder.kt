package app.nudge.core.domain.tree

import app.nudge.core.model.ListSortMode
import app.nudge.core.model.Task
import kotlin.math.roundToInt

/**
 * A top-level task with its subtasks. [children] are sorted: open by sortOrder, then completed by
 * completedAt desc (FR-35).
 */
data class TaskNode(val task: Task, val children: List<Task>) {
    val openChildren: List<Task> get() = children.filterNot { it.isCompleted }
    val completedChildrenCount: Int get() = children.count { it.isCompleted }
    val hasChildren: Boolean get() = children.isNotEmpty()

    /** FR-42: parent progress = average of children's effective progress (completed = 100). */
    val effectiveProgress: Int
        get() = when {
            task.isCompleted -> 100
            children.isEmpty() -> task.progress
            else -> children.map { if (it.isCompleted) 100 else it.progress }.average().roundToInt()
        }

    /** "2/5" counter (FR-21), null for leaf tasks. */
    val counter: String? get() = if (children.isEmpty()) null else "$completedChildrenCount/${children.size}"
}

data class ListTree(val open: List<TaskNode>, val completed: List<TaskNode>) {
    val openCount: Int get() = open.size
    val completedCount: Int get() = completed.size

    fun node(taskId: String): TaskNode? =
        open.firstOrNull { it.task.id == taskId } ?: completed.firstOrNull { it.task.id == taskId }

    /** The node holding [taskId] either as the top-level task or as a child. */
    fun nodeContaining(taskId: String): TaskNode? =
        (open + completed).firstOrNull { n -> n.task.id == taskId || n.children.any { it.id == taskId } }

    companion object {
        val EMPTY = ListTree(emptyList(), emptyList())
    }
}

/** Builds a [ListTree] from all non-deleted tasks of one list (06 §5). */
object TaskTreeBuilder {
    private val openChildOrder = compareBy<Task> { it.sortOrder }.thenBy { it.id }
    private val completedOrder = compareByDescending<Task> { it.completedAt }.thenBy { it.sortOrder }

    fun build(tasks: List<Task>, sortMode: ListSortMode = ListSortMode.MY_ORDER): ListTree {
        val live = tasks.filter { it.deletedAt == null }
        val byParent = live.filter { it.parentId != null }.groupBy { it.parentId }
        val topLevel = live.filter { it.parentId == null }
        fun nodeOf(t: Task): TaskNode {
            val kids = byParent[t.id].orEmpty()
            val sortedKids = kids.filterNot { it.isCompleted }.sortedWith(openChildOrder) +
                kids.filter { it.isCompleted }.sortedWith(completedOrder)
            return TaskNode(t, sortedKids)
        }
        val open = topLevel.filterNot { it.isCompleted }.sortedWith(openComparator(sortMode)).map(::nodeOf)
        val completed = topLevel.filter { it.isCompleted }.sortedWith(completedOrder).map(::nodeOf)
        return ListTree(open, completed)
    }

    fun openComparator(sortMode: ListSortMode): Comparator<Task> = when (sortMode) {
        ListSortMode.MY_ORDER -> compareBy<Task> { it.sortOrder }.thenBy { it.id }
        ListSortMode.PRIORITY -> compareByDescending<Task> { it.priority.level }.thenBy { it.sortOrder }.thenBy { it.id }
        ListSortMode.DUE_DATE -> compareBy<Task, Long?>(nullsLast()) { it.dueDate?.toEpochDay() }
            .thenBy(nullsLast()) { it.dueTime?.toSecondOfDay() }
            .thenBy { it.sortOrder }
            .thenBy { it.id }
    }
}

/** One row of the flattened List screen (06 §5). This is exactly what drag-and-drop works over (08). */
sealed interface ListItemUi {
    val key: String
}

data class TaskItemUi(
    val task: Task,
    val depth: Int,
    val isParent: Boolean,
    val effectiveProgress: Int,
    val childCounter: String?,
    val childCount: Int,
    val parentTitle: String? = null,
) : ListItemUi {
    override val key: String get() = task.id
}

data class CompletedHeaderUi(val count: Int, val expanded: Boolean) : ListItemUi {
    override val key: String get() = KEY

    companion object {
        const val KEY = "completed-header"
    }
}

fun ListTree.flatten(completedExpanded: Boolean): List<ListItemUi> = buildList {
    open.forEach { n ->
        add(n.toItem())
        if (n.task.isExpanded) {
            n.children.forEach { c -> add(c.toChildItem(n.task.title)) }
        }
    }
    if (completed.isNotEmpty()) {
        add(CompletedHeaderUi(completed.size, completedExpanded))
        if (completedExpanded) {
            completed.forEach { n ->
                add(n.toItem())
                n.children.forEach { c -> add(c.toChildItem(n.task.title)) }
            }
        }
    }
}

private fun TaskNode.toItem() = TaskItemUi(
    task = task,
    depth = 0,
    isParent = hasChildren,
    effectiveProgress = effectiveProgress,
    childCounter = counter,
    childCount = children.size,
)

private fun Task.toChildItem(parentTitle: String) = TaskItemUi(
    task = this,
    depth = 1,
    isParent = false,
    effectiveProgress = if (isCompleted) 100 else progress,
    childCounter = null,
    childCount = 0,
    parentTitle = parentTitle,
)
