package app.nudge.core.data.repository

import androidx.room.withTransaction
import app.nudge.core.common.Clock
import app.nudge.core.common.DispatcherProvider
import app.nudge.core.common.IdGenerator
import app.nudge.core.database.NudgeDatabase
import app.nudge.core.database.dao.TaskDao
import app.nudge.core.database.mapper.ftsQueryOf
import app.nudge.core.database.mapper.toDomain
import app.nudge.core.database.mapper.toEntity
import app.nudge.core.datastore.SettingsDataSource
import app.nudge.core.domain.order.OrderingCalculator
import app.nudge.core.domain.repository.TaskRepository
import app.nudge.core.domain.rules.MovePlanner
import app.nudge.core.domain.rules.TaskRules
import app.nudge.core.model.IllegalMoveException
import app.nudge.core.model.InsertPosition
import app.nudge.core.model.MoveOperation
import app.nudge.core.model.ReminderStateUpdate
import app.nudge.core.model.Task
import app.nudge.core.model.TaskDraft
import app.nudge.core.model.TaskPatch
import app.nudge.core.model.UndoSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed [TaskRepository]. Loads rows, applies [TaskRules] / [MovePlanner], writes the result in
 * one transaction and returns an [UndoSnapshot] of the rows as they were (06 §5, §6).
 */
@Singleton
class TaskRepositoryImpl @Inject constructor(
    private val db: NudgeDatabase,
    private val dao: TaskDao,
    private val settings: SettingsDataSource,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val dispatchers: DispatcherProvider,
) : TaskRepository {

    override fun observeList(listId: String): Flow<List<Task>> =
        dao.observeByList(listId).map { rows -> rows.map { it.toDomain() } }

    override fun observeOpenTasksAcrossLists(): Flow<List<Task>> =
        dao.observeOpenAcrossLists().map { rows -> rows.map { it.toDomain() } }

    override fun observeTask(id: String): Flow<Task?> = dao.observe(id).map { it?.toDomain() }

    override fun search(query: String): Flow<List<Task>> {
        val fts = ftsQueryOf(query) ?: return flowOf(emptyList())
        return dao.search(fts).map { rows -> rows.map { it.toDomain() } }
    }

    override suspend fun get(id: String): Task? = io { dao.get(id)?.toDomain() }

    override suspend fun children(parentId: String): List<Task> = io { dao.children(parentId).map { it.toDomain() } }

    override suspend fun create(draft: TaskDraft): Task = io {
        val morning = settings.settings.first().reminders.morningTime
        db.withTransaction {
            val parentId = draft.parentId
            if (parentId != null) {
                val parent = dao.get(parentId)?.toDomain()
                    ?: throw IllegalMoveException("Parent $parentId not found")
                if (parent.parentId != null) throw IllegalMoveException("Max nesting depth is 1")
                if (parent.listId != draft.listId) throw IllegalMoveException("Parent must be in the same list")
                // 06 §5 rule 6: an open subtask must never hide under a completed or deleted parent.
                if (parent.deletedAt != null) throw IllegalMoveException("Parent was deleted")
                if (parent.isCompleted) throw IllegalMoveException("Cannot add a subtask to a completed task")
            }
            // FR-13: top-level at the chosen position; subtasks always at the bottom.
            val position = if (parentId != null) InsertPosition.BOTTOM else draft.position
            val sortOrder = when (position) {
                InsertPosition.TOP -> OrderingCalculator.top(dao.minSortOrder(draft.listId, parentId))
                InsertPosition.BOTTOM -> OrderingCalculator.bottom(dao.maxSortOrder(draft.listId, parentId))
            }
            val task = TaskRules.newTask(ids.newId(), draft, sortOrder, clock.now(), clock.zone(), morning)
            dao.upsert(listOf(task.toEntity()))
            task
        }
    }

    override suspend fun update(id: String, patch: TaskPatch): Task = io {
        val morning = settings.settings.first().reminders.morningTime
        db.withTransaction {
            val task = requireTask(id)
            val hasChildren = dao.children(id).isNotEmpty()
            val updated = TaskRules.applyPatch(task, patch, hasChildren, clock.now(), clock.zone(), morning)
            if (updated != task) dao.upsert(listOf(updated.toEntity()))
            updated
        }
    }

    override suspend fun setCompleted(id: String, completed: Boolean, progressBefore: Int?): UndoSnapshot = io {
        db.withTransaction {
            val task = requireTask(id)
            val now = clock.now()
            val changed = if (completed) {
                TaskRules.complete(task, dao.children(id).map { it.toDomain() }, now, progressBefore)
            } else {
                val parent = task.parentId?.let { dao.get(it)?.toDomain() }
                TaskRules.uncomplete(task, parent, now)
            }
            writeChanges(changed)
        }
    }

    override suspend fun move(op: MoveOperation): UndoSnapshot = io {
        db.withTransaction {
            val task = requireTask(op.taskId)
            val listTasks = dao.allInList(task.listId).map { it.toDomain() }
            val toList = op as? MoveOperation.ToList
            if (toList != null) {
                val target = db.taskListDao().get(toList.listId)
                if (target == null || target.deletedAt != null) throw IllegalMoveException("Target list not found")
            }
            val targetTopMin = toList?.let { dao.minSortOrder(it.listId, null) }
            val now = clock.now()
            val planned = MovePlanner.plan(op, listTasks, now, targetTopMin)
            // Soft-deleted children follow their parent so Undo of the delete stays consistent.
            val deletedChildren = if (toList != null) {
                dao.allChildren(task.id).map { it.toDomain() }.filter { it.deletedAt != null && it.listId != toList.listId }
                    .map { it.copy(listId = toList.listId, updatedAt = now) }
            } else {
                emptyList()
            }
            writeChanges(planned + deletedChildren)
        }
    }

    override suspend fun softDelete(id: String): UndoSnapshot = io {
        db.withTransaction {
            val task = requireTask(id)
            writeChanges(TaskRules.softDelete(task, dao.children(id).map { it.toDomain() }, clock.now()))
        }
    }

    override suspend fun restore(snapshot: UndoSnapshot) = io {
        db.withTransaction {
            snapshot.createdTaskIds.chunked(SQL_CHUNK).forEach { dao.hardDelete(it) }
            val now = clock.now()
            // Parents before children so the parent_id FK is satisfied.
            val rows = snapshot.tasksBefore.sortedBy { it.parentId != null }.map { it.copy(updatedAt = now).toEntity() }
            if (rows.isNotEmpty()) dao.upsert(rows)
        }
    }

    override suspend fun deleteCompleted(listId: String): UndoSnapshot = io {
        db.withTransaction {
            val now = clock.now()
            val changed = dao.completedTopLevel(listId).map { it.toDomain() }.flatMap { parent ->
                TaskRules.softDelete(parent, dao.children(parent.id).map { it.toDomain() }, now)
            }
            writeChanges(changed)
        }
    }

    override suspend fun tasksWithReminderDueBefore(instant: Instant): List<Task> =
        io { dao.dueReminders(instant.toEpochMilli()).map { it.toDomain() } }

    override suspend fun earliestNextReminder(): Instant? = io { dao.earliestNextReminder()?.let(Instant::ofEpochMilli) }

    override suspend fun allOpenTasks(): List<Task> = io { dao.allOpen().map { it.toDomain() } }

    override suspend fun staleReminderTaskIds(): List<String> = io { dao.staleReminderIds() }

    override suspend fun updateReminderState(updates: List<ReminderStateUpdate>) = io {
        if (updates.isEmpty()) return@io
        db.withTransaction {
            updates.forEach { u ->
                val s = u.state
                dao.updateReminderState(
                    id = u.taskId,
                    nextAt = u.next?.at?.toEpochMilli(),
                    kind = u.next?.kind?.name,
                    lastAt = s.lastRemindedAt?.toEpochMilli(),
                    count = s.count,
                    snoozedUntil = s.snoozedUntil?.toEpochMilli(),
                    dueFired = s.dueReminderFired,
                    anchorAt = s.anchorAt.toEpochMilli(),
                )
            }
        }
    }

    override suspend fun purgeDeleted(before: Instant): Int = io {
        db.withTransaction { dao.purgeDeleted(before.toEpochMilli()) + db.taskListDao().purgeDeleted(before.toEpochMilli()) }
    }

    /** Upserts [changed] and returns the snapshot of their previous rows. Call inside a transaction. */
    private suspend fun writeChanges(changed: List<Task>): UndoSnapshot {
        if (changed.isEmpty()) return UndoSnapshot.EMPTY
        val before = changed.map { it.id }.chunked(SQL_CHUNK).flatMap { dao.getMany(it) }.map { it.toDomain() }
        dao.upsert(changed.sortedBy { it.parentId != null }.map { it.toEntity() })
        return UndoSnapshot(tasksBefore = before)
    }

    private suspend fun requireTask(id: String): Task =
        dao.get(id)?.toDomain()?.takeIf { it.deletedAt == null } ?: throw NoSuchElementException("Task $id not found")

    private suspend fun <T> io(block: suspend () -> T): T = withContext(dispatchers.io) { block() }

    companion object {
        /** Stay under SQLite's 999 bound-variable limit on API 26–29. */
        internal const val SQL_CHUNK = 900
    }
}
