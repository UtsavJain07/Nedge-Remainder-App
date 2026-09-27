package app.nudge.core.model

/**
 * Rows as they were before a mutation, used by Undo (06 §6). Restoring upserts [tasksBefore] and
 * [listsBefore] and hard-deletes [createdTaskIds].
 */
data class UndoSnapshot(
    val tasksBefore: List<Task>,
    val listsBefore: List<TaskList> = emptyList(),
    val createdTaskIds: List<String> = emptyList(),
) {
    val affectedTaskIds: Set<String> get() = tasksBefore.mapTo(mutableSetOf()) { it.id } + createdTaskIds

    companion object {
        val EMPTY = UndoSnapshot(emptyList())
    }
}

/** A structural move of a task (08 §5.2, FR-16, FR-22..FR-26). */
sealed interface MoveOperation {
    val taskId: String

    /** Place the task in group [parentId] between [aboveId] and [belowId] (siblings in that group). */
    data class Reorder(
        override val taskId: String,
        val parentId: String?,
        val aboveId: String?,
        val belowId: String?,
    ) : MoveOperation

    /** Append the task as the last child of [parentId]. */
    data class Nest(override val taskId: String, val parentId: String) : MoveOperation

    /** Promote a subtask to top level, right after its old parent. */
    data class ToTopLevel(override val taskId: String) : MoveOperation

    /** Move the task (and its children) to the top of another list. */
    data class ToList(override val taskId: String, val listId: String) : MoveOperation
}

/** Rejected structural change (integrity rules in 06 §5). */
class IllegalMoveException(message: String) : IllegalStateException(message)
