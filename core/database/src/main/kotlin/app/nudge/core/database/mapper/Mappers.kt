package app.nudge.core.database.mapper

import app.nudge.core.database.entity.TaskEntity
import app.nudge.core.database.entity.TaskListEntity
import app.nudge.core.model.ListSortMode
import app.nudge.core.model.Priority
import app.nudge.core.model.ReminderCadence
import app.nudge.core.model.ReminderKind
import app.nudge.core.model.ReminderState
import app.nudge.core.model.Task
import app.nudge.core.model.TaskList
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

private fun Long?.toInstant(): Instant? = this?.let(Instant::ofEpochMilli)

fun TaskListEntity.toDomain() = TaskList(
    id = id,
    name = name,
    colorArgb = colorArgb,
    emoji = emoji,
    sortOrder = sortOrder,
    isDefault = isDefault,
    sortMode = ListSortMode.entries.firstOrNull { it.name == sortMode } ?: ListSortMode.MY_ORDER,
    completedExpanded = completedExpanded,
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt),
    deletedAt = deletedAt.toInstant(),
)

fun TaskList.toEntity() = TaskListEntity(
    id = id,
    name = name,
    colorArgb = colorArgb,
    emoji = emoji,
    sortOrder = sortOrder,
    isDefault = isDefault,
    sortMode = sortMode.name,
    completedExpanded = completedExpanded,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli(),
    deletedAt = deletedAt?.toEpochMilli(),
)

fun TaskEntity.toDomain() = Task(
    id = id,
    listId = listId,
    parentId = parentId,
    title = title,
    notes = notes,
    priority = Priority.fromLevel(priority),
    cadenceOverride = ReminderCadence.fromName(cadenceOverride),
    progress = progress,
    progressBeforeComplete = progressBeforeComplete,
    isCompleted = isCompleted,
    completedAt = completedAt.toInstant(),
    dueDate = dueEpochDay?.let(LocalDate::ofEpochDay),
    dueTime = dueMinuteOfDay?.let { LocalTime.of(it / 60, it % 60) },
    sortOrder = sortOrder,
    isExpanded = isExpanded,
    reminder = ReminderState(
        anchorAt = Instant.ofEpochMilli(reminderAnchorAt),
        nextAt = nextReminderAt.toInstant(),
        nextKind = ReminderKind.entries.firstOrNull { it.name == nextReminderKind },
        lastRemindedAt = lastRemindedAt.toInstant(),
        count = reminderCount,
        snoozedUntil = snoozedUntil.toInstant(),
        dueReminderFired = dueReminderFired,
    ),
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt),
    deletedAt = deletedAt.toInstant(),
)

fun Task.toEntity() = TaskEntity(
    id = id,
    listId = listId,
    parentId = parentId,
    title = title,
    notes = notes,
    priority = priority.level,
    cadenceOverride = cadenceOverride?.name,
    progress = progress,
    progressBeforeComplete = progressBeforeComplete,
    isCompleted = isCompleted,
    completedAt = completedAt?.toEpochMilli(),
    dueEpochDay = dueDate?.toEpochDay(),
    dueMinuteOfDay = dueTime?.let { it.hour * 60 + it.minute },
    sortOrder = sortOrder,
    isExpanded = isExpanded,
    reminderAnchorAt = reminder.anchorAt.toEpochMilli(),
    nextReminderAt = reminder.nextAt?.toEpochMilli(),
    nextReminderKind = reminder.nextKind?.name,
    lastRemindedAt = reminder.lastRemindedAt?.toEpochMilli(),
    reminderCount = reminder.count,
    snoozedUntil = reminder.snoozedUntil?.toEpochMilli(),
    dueReminderFired = reminder.dueReminderFired,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli(),
    deletedAt = deletedAt?.toEpochMilli(),
)

/**
 * Sanitizes user input into an FTS4 prefix query (06 §2.3): strips operators, ANDs the terms,
 * each with a trailing `*`. Returns null when nothing searchable remains.
 */
fun ftsQueryOf(raw: String): String? {
    val terms = raw.replace(Regex("[\"*\\-():^']"), " ")
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
    if (terms.isEmpty()) return null
    return terms.joinToString(" ") { "$it*" }
}
