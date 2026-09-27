package app.nudge.core.domain.rules

import app.nudge.core.domain.order.OrderingCalculator
import app.nudge.core.model.IllegalMoveException
import app.nudge.core.model.MoveOperation
import app.nudge.core.model.Task
import java.time.Instant

/**
 * Plans a structural move (08 §5.2) over the non-deleted tasks of the moving task's list.
 * Pure: the repository loads [listTasks], calls [plan], and upserts the returned rows.
 *
 * Validates 06 §5 rules 1–3 and throws [IllegalMoveException] on violation (FR-25).
 */
object MovePlanner {

    /**
     * @param listTasks all non-deleted tasks in the moving task's current list.
     * @param targetListTopMin for [MoveOperation.ToList]: min sortOrder among the target list's top-level tasks.
     * @return rows that changed (new values).
     */
    fun plan(
        op: MoveOperation,
        listTasks: List<Task>,
        now: Instant,
        targetListTopMin: Double? = null,
    ): List<Task> {
        val byId = listTasks.associateBy { it.id }
        val task = byId[op.taskId] ?: throw IllegalMoveException("Task ${op.taskId} not found")
        val children = listTasks.filter { it.parentId == task.id }

        return when (op) {
            is MoveOperation.Reorder -> reorder(task, children, op, byId, listTasks, now)
            is MoveOperation.Nest -> nest(task, children, op.parentId, byId, listTasks, now)
            is MoveOperation.ToTopLevel -> toTopLevel(task, byId, listTasks, now)
            is MoveOperation.ToList -> toList(task, children, op.listId, targetListTopMin, now)
        }
    }

    private fun validateParent(task: Task, children: List<Task>, parentId: String, byId: Map<String, Task>): Task {
        val parent = byId[parentId] ?: throw IllegalMoveException("Parent not in the same list")
        if (parent.id == task.id) throw IllegalMoveException("A task cannot be its own parent")
        if (parent.parentId != null) throw IllegalMoveException("Max nesting depth is 1")
        if (children.isNotEmpty()) throw IllegalMoveException("A task with subtasks cannot become a subtask")
        if (parent.isCompleted && !task.isCompleted) throw IllegalMoveException("Cannot nest into a completed task")
        return parent
    }

    private fun group(listTasks: List<Task>, parentId: String?, excludeId: String): List<Task> =
        listTasks.filter { it.parentId == parentId && it.id != excludeId }.sortedWith(compareBy({ it.sortOrder }, { it.id }))

    /** Renormalizes the group when the neighbours are too close (06 §4). Returns changed rows + lookup. */
    private fun maybeRenormalize(
        siblings: List<Task>,
        aboveId: String?,
        belowId: String?,
        now: Instant,
    ): Pair<List<Task>, Map<String, Double>> {
        val orders = siblings.associate { it.id to it.sortOrder }
        val a = aboveId?.let { orders[it] }
        val b = belowId?.let { orders[it] }
        if (!OrderingCalculator.needsRenormalize(a, b)) return emptyList<Task>() to orders
        val renorm = OrderingCalculator.renormalize(siblings.map { it.id })
        val changed = siblings.map { it.copy(sortOrder = renorm.getValue(it.id), updatedAt = now) }
        return changed to renorm
    }

    private fun reorder(
        task: Task,
        children: List<Task>,
        op: MoveOperation.Reorder,
        byId: Map<String, Task>,
        listTasks: List<Task>,
        now: Instant,
    ): List<Task> {
        val parent = op.parentId?.let { validateParent(task, children, it, byId) }
        val siblings = group(listTasks, op.parentId, task.id)
        val siblingIds = siblings.map { it.id }.toSet()
        if (op.aboveId != null && op.aboveId !in siblingIds) throw IllegalMoveException("above is not a sibling")
        if (op.belowId != null && op.belowId !in siblingIds) throw IllegalMoveException("below is not a sibling")

        val (renormalized, orders) = maybeRenormalize(siblings, op.aboveId, op.belowId, now)
        val newOrder = OrderingCalculator.between(op.aboveId?.let { orders[it] }, op.belowId?.let { orders[it] })
        val moved = task.copy(parentId = op.parentId, sortOrder = newOrder, updatedAt = now)
        val parentChange = parent?.takeIf { !it.isExpanded && task.parentId != parent.id }
            ?.copy(isExpanded = true, updatedAt = now)
        return merge(renormalized, listOfNotNull(moved, parentChange))
    }

    private fun nest(
        task: Task,
        children: List<Task>,
        parentId: String,
        byId: Map<String, Task>,
        listTasks: List<Task>,
        now: Instant,
    ): List<Task> {
        val parent = validateParent(task, children, parentId, byId)
        val maxChild = group(listTasks, parentId, task.id).maxOfOrNull { it.sortOrder }
        val moved = task.copy(parentId = parentId, sortOrder = OrderingCalculator.bottom(maxChild), updatedAt = now)
        val expanded = if (parent.isExpanded) null else parent.copy(isExpanded = true, updatedAt = now)
        return listOfNotNull(moved, expanded)
    }

    private fun toTopLevel(task: Task, byId: Map<String, Task>, listTasks: List<Task>, now: Instant): List<Task> {
        val parent = task.parentId?.let { byId[it] } ?: throw IllegalMoveException("Task is already top-level")
        val topLevel = group(listTasks, null, task.id)
        val idx = topLevel.indexOfFirst { it.id == parent.id }
        val below = topLevel.getOrNull(idx + 1)
        val (renormalized, orders) = maybeRenormalize(topLevel, parent.id, below?.id, now)
        val newOrder = OrderingCalculator.between(orders[parent.id], below?.let { orders[it.id] })
        return merge(renormalized, listOf(task.copy(parentId = null, sortOrder = newOrder, updatedAt = now)))
    }

    private fun toList(task: Task, children: List<Task>, listId: String, targetTopMin: Double?, now: Instant): List<Task> {
        if (listId == task.listId) return emptyList()
        val moved = task.copy(
            listId = listId,
            parentId = null,
            sortOrder = OrderingCalculator.top(targetTopMin),
            updatedAt = now,
        )
        return listOf(moved) + children.map { it.copy(listId = listId, updatedAt = now) }
    }

    /** Later rows override earlier rows with the same id. */
    private fun merge(first: List<Task>, second: List<Task>): List<Task> =
        (first + second).associateBy { it.id }.values.toList()
}
