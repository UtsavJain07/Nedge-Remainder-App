package app.nudge.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

/** `task_list` (06 §2.1). */
@Entity(
    tableName = "task_list",
    indices = [Index(value = ["deleted_at", "sort_order"], name = "index_task_list_deleted_order")],
)
data class TaskListEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "color_argb") val colorArgb: Int,
    val emoji: String?,
    @ColumnInfo(name = "sort_order") val sortOrder: Double,
    @ColumnInfo(name = "is_default", defaultValue = "0") val isDefault: Boolean,
    @ColumnInfo(name = "sort_mode", defaultValue = "MY_ORDER") val sortMode: String,
    @ColumnInfo(name = "completed_expanded", defaultValue = "0") val completedExpanded: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
)

/** `task` (06 §2.2). Due date is wall-clock (epoch day + minute of day), not an Instant. */
@Entity(
    tableName = "task",
    foreignKeys = [
        ForeignKey(
            entity = TaskListEntity::class,
            parentColumns = ["id"],
            childColumns = ["list_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["list_id", "parent_id", "sort_order"], name = "index_task_list_parent_order"),
        Index(value = ["parent_id"], name = "index_task_parent"),
        Index(value = ["next_reminder_at"], name = "index_task_next_reminder"),
        Index(value = ["deleted_at"], name = "index_task_deleted"),
    ],
)
data class TaskEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "list_id") val listId: String,
    @ColumnInfo(name = "parent_id") val parentId: String?,
    val title: String,
    @ColumnInfo(defaultValue = "") val notes: String,
    @ColumnInfo(defaultValue = "0") val priority: Int,
    @ColumnInfo(name = "cadence_override") val cadenceOverride: String?,
    @ColumnInfo(defaultValue = "0") val progress: Int,
    @ColumnInfo(name = "progress_before_complete") val progressBeforeComplete: Int?,
    @ColumnInfo(name = "is_completed", defaultValue = "0") val isCompleted: Boolean,
    @ColumnInfo(name = "completed_at") val completedAt: Long?,
    @ColumnInfo(name = "due_epoch_day") val dueEpochDay: Long?,
    @ColumnInfo(name = "due_minute_of_day") val dueMinuteOfDay: Int?,
    @ColumnInfo(name = "sort_order") val sortOrder: Double,
    @ColumnInfo(name = "is_expanded", defaultValue = "1") val isExpanded: Boolean,
    @ColumnInfo(name = "reminder_anchor_at") val reminderAnchorAt: Long,
    @ColumnInfo(name = "next_reminder_at") val nextReminderAt: Long?,
    @ColumnInfo(name = "next_reminder_kind") val nextReminderKind: String?,
    @ColumnInfo(name = "last_reminded_at") val lastRemindedAt: Long?,
    @ColumnInfo(name = "reminder_count", defaultValue = "0") val reminderCount: Int,
    @ColumnInfo(name = "snoozed_until") val snoozedUntil: Long?,
    @ColumnInfo(name = "due_reminder_fired", defaultValue = "0") val dueReminderFired: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
)

/** External-content FTS over title + notes (06 §2.3). Room keeps it in sync with triggers. */
@Fts4(contentEntity = TaskEntity::class)
@Entity(tableName = "task_fts")
data class TaskFtsEntity(
    val title: String,
    val notes: String,
)

/** Row of the per-list stats query (FR-06). */
data class ListStatsRow(
    @ColumnInfo(name = "list_id") val listId: String,
    @ColumnInfo(name = "open_count") val openCount: Int,
    @ColumnInfo(name = "completed_count") val completedCount: Int,
    @ColumnInfo(name = "has_urgent") val hasUrgent: Boolean,
)
