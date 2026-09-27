package app.nudge.core.domain.repository

import app.nudge.core.model.MoveOperation
import app.nudge.core.model.Task
import app.nudge.core.model.TaskDraft
import app.nudge.core.model.TaskPatch
import app.nudge.core.model.ReminderStateUpdate
import app.nudge.core.model.UndoSnapshot
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Single write path for tasks (05 §5). Every multi-row mutation is one transaction and returns an
 * [UndoSnapshot]. Implementations never touch AlarmManager — use cases call the ReminderScheduler.
 */
interface TaskRepository {
    /** All non-deleted tasks of the list (open + completed). */
    fun observeList(listId: String): Flow<List<Task>>

    fun observeOpenTasksAcrossLists(): Flow<List<Task>>

    fun observeTask(id: String): Flow<Task?>

    fun search(query: String): Flow<List<Task>>

    suspend fun get(id: String): Task?

    suspend fun children(parentId: String): List<Task>

    suspend fun create(draft: TaskDraft): Task

    suspend fun update(id: String, patch: TaskPatch): Task

    /**
     * Completes (cascading to open subtasks, FR-34) or un-completes (06 §5 rule 6).
     * [progressBefore] overrides the value remembered for un-complete (FR-43 slider case).
     */
    suspend fun setCompleted(id: String, completed: Boolean, progressBefore: Int? = null): UndoSnapshot

    suspend fun move(op: MoveOperation): UndoSnapshot

    suspend fun softDelete(id: String): UndoSnapshot

    suspend fun restore(snapshot: UndoSnapshot)

    suspend fun deleteCompleted(listId: String): UndoSnapshot

    // --- reminders (07) ---
    suspend fun tasksWithReminderDueBefore(instant: Instant): List<Task>

    suspend fun earliestNextReminder(): Instant?

    suspend fun allOpenTasks(): List<Task>

    /** Ids of non-open (completed/deleted) tasks that still hold a next reminder. */
    suspend fun staleReminderTaskIds(): List<String>

    /** Writes reminder runtime state only. Does NOT bump updatedAt (06 §3). */
    suspend fun updateReminderState(updates: List<ReminderStateUpdate>)

    /** Hard-deletes rows soft-deleted before [before] (06 §11). */
    suspend fun purgeDeleted(before: Instant): Int
}
