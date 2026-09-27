package app.nudge.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import app.nudge.core.database.dao.TaskDao
import app.nudge.core.database.dao.TaskListDao
import app.nudge.core.database.entity.TaskEntity
import app.nudge.core.database.entity.TaskFtsEntity
import app.nudge.core.database.entity.TaskListEntity

/** Local source of truth (ADR-03). Schema exported to core/database/schemas (06 §9). */
@Database(
    entities = [TaskListEntity::class, TaskEntity::class, TaskFtsEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class NudgeDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao

    abstract fun taskListDao(): TaskListDao

    companion object {
        const val NAME = "nudge.db"
    }
}
