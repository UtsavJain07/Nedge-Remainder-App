package app.nudge.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.nudge.core.database.entity.ListStatsRow
import app.nudge.core.database.entity.TaskListEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskListDao {
    @Query("SELECT * FROM task_list WHERE deleted_at IS NULL ORDER BY sort_order, created_at")
    fun observeAll(): Flow<List<TaskListEntity>>

    @Query("SELECT * FROM task_list WHERE id = :id")
    fun observe(id: String): Flow<TaskListEntity?>

    @Query("SELECT * FROM task_list WHERE id = :id")
    suspend fun get(id: String): TaskListEntity?

    @Query("SELECT * FROM task_list WHERE deleted_at IS NULL ORDER BY sort_order, created_at")
    suspend fun all(): List<TaskListEntity>

    @Query("SELECT COUNT(*) FROM task_list WHERE deleted_at IS NULL")
    suspend fun liveCount(): Int

    @Query("SELECT MAX(sort_order) FROM task_list WHERE deleted_at IS NULL")
    suspend fun maxSortOrder(): Double?

    /** Per-list stats, top-level tasks only (06 §3). */
    @Query(
        """SELECT list_id,
                  SUM(CASE WHEN is_completed = 0 THEN 1 ELSE 0 END) AS open_count,
                  SUM(CASE WHEN is_completed = 1 THEN 1 ELSE 0 END) AS completed_count,
                  MAX(CASE WHEN is_completed = 0 AND priority = 4 THEN 1 ELSE 0 END) AS has_urgent
           FROM task WHERE parent_id IS NULL AND deleted_at IS NULL GROUP BY list_id""",
    )
    fun observeStats(): Flow<List<ListStatsRow>>

    @Upsert
    suspend fun upsert(lists: List<TaskListEntity>)

    @Query("UPDATE task_list SET deleted_at = :now, updated_at = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    @Query("DELETE FROM task_list WHERE deleted_at IS NOT NULL AND deleted_at < :before")
    suspend fun purgeDeleted(before: Long): Int

    @Query("DELETE FROM task_list")
    suspend fun deleteAll()
}
