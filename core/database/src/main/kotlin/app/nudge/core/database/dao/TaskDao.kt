package app.nudge.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.nudge.core.database.entity.TaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query(
        """SELECT * FROM task WHERE list_id = :listId AND deleted_at IS NULL
           ORDER BY parent_id IS NOT NULL, sort_order""",
    )
    fun observeByList(listId: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM task WHERE id = :id")
    fun observe(id: String): Flow<TaskEntity?>

    @Query("SELECT * FROM task WHERE id = :id")
    suspend fun get(id: String): TaskEntity?

    @Query("SELECT * FROM task WHERE id IN (:ids)")
    suspend fun getMany(ids: List<String>): List<TaskEntity>

    @Query("SELECT * FROM task WHERE parent_id = :parentId AND deleted_at IS NULL ORDER BY sort_order")
    suspend fun children(parentId: String): List<TaskEntity>

    @Query("SELECT * FROM task WHERE list_id = :listId AND deleted_at IS NULL")
    suspend fun allInList(listId: String): List<TaskEntity>

    @Query(
        """SELECT * FROM task WHERE is_completed = 0 AND deleted_at IS NULL
           AND list_id IN (SELECT id FROM task_list WHERE deleted_at IS NULL)""",
    )
    fun observeOpenAcrossLists(): Flow<List<TaskEntity>>

    @Query(
        """SELECT MIN(sort_order) FROM task WHERE list_id = :listId AND
           ((:parentId IS NULL AND parent_id IS NULL) OR parent_id = :parentId) AND deleted_at IS NULL""",
    )
    suspend fun minSortOrder(listId: String, parentId: String?): Double?

    @Query(
        """SELECT MAX(sort_order) FROM task WHERE list_id = :listId AND
           ((:parentId IS NULL AND parent_id IS NULL) OR parent_id = :parentId) AND deleted_at IS NULL""",
    )
    suspend fun maxSortOrder(listId: String, parentId: String?): Double?

    @Query(
        """SELECT * FROM task WHERE list_id = :listId AND parent_id IS NULL AND is_completed = 1
           AND deleted_at IS NULL""",
    )
    suspend fun completedTopLevel(listId: String): List<TaskEntity>

    // --- reminders (07) ---
    @Query(
        """SELECT * FROM task WHERE next_reminder_at IS NOT NULL AND next_reminder_at <= :until
           AND is_completed = 0 AND deleted_at IS NULL ORDER BY next_reminder_at""",
    )
    suspend fun dueReminders(until: Long): List<TaskEntity>

    @Query(
        """SELECT MIN(next_reminder_at) FROM task WHERE next_reminder_at IS NOT NULL
           AND is_completed = 0 AND deleted_at IS NULL""",
    )
    suspend fun earliestNextReminder(): Long?

    @Query("SELECT * FROM task WHERE is_completed = 0 AND deleted_at IS NULL")
    suspend fun allOpen(): List<TaskEntity>

    @Query("SELECT id FROM task WHERE next_reminder_at IS NOT NULL AND (is_completed = 1 OR deleted_at IS NOT NULL)")
    suspend fun staleReminderIds(): List<String>

    /** Reminder-state writes do NOT bump updated_at: device-local, not user edits (06 §3, 09 §4). */
    @Query(
        """UPDATE task SET next_reminder_at = :nextAt, next_reminder_kind = :kind, last_reminded_at = :lastAt,
           reminder_count = :count, snoozed_until = :snoozedUntil, due_reminder_fired = :dueFired,
           reminder_anchor_at = :anchorAt WHERE id = :id""",
    )
    suspend fun updateReminderState(
        id: String,
        nextAt: Long?,
        kind: String?,
        lastAt: Long?,
        count: Int,
        snoozedUntil: Long?,
        dueFired: Boolean,
        anchorAt: Long,
    )

    @Upsert
    suspend fun upsert(tasks: List<TaskEntity>)

    @Query("UPDATE task SET deleted_at = :now, updated_at = :now WHERE id IN (:ids)")
    suspend fun softDelete(ids: List<String>, now: Long)

    @Query("DELETE FROM task WHERE id IN (:ids)")
    suspend fun hardDelete(ids: List<String>)

    @Query("DELETE FROM task WHERE deleted_at IS NOT NULL AND deleted_at < :before")
    suspend fun purgeDeleted(before: Long): Int

    @Query("DELETE FROM task")
    suspend fun deleteAll()

    @Query("SELECT * FROM task WHERE deleted_at IS NULL")
    suspend fun allLive(): List<TaskEntity>

    @Query(
        """SELECT task.* FROM task JOIN task_fts ON task.rowid = task_fts.rowid
           WHERE task_fts MATCH :ftsQuery AND task.deleted_at IS NULL""",
    )
    fun search(ftsQuery: String): Flow<List<TaskEntity>>
}
