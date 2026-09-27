package app.nudge.core.testing

import app.nudge.core.model.ListColors
import app.nudge.core.model.ListSortMode
import app.nudge.core.model.Priority
import app.nudge.core.model.ReminderCadence
import app.nudge.core.model.ReminderState
import app.nudge.core.model.Task
import app.nudge.core.model.TaskList
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

val T0: Instant = Instant.parse("2026-09-27T04:30:00Z")

/** Test data builder: `aTask { title = "x"; priority = Priority.HIGH }` (10 M1). */
class TaskBuilder {
    var id = "t1"
    var listId = "l1"
    var parentId: String? = null
    var title = "Task"
    var notes = ""
    var priority = Priority.NONE
    var cadenceOverride: ReminderCadence? = null
    var progress = 0
    var progressBeforeComplete: Int? = null
    var isCompleted = false
    var completedAt: Instant? = null
    var dueDate: LocalDate? = null
    var dueTime: LocalTime? = null
    var sortOrder = 0.0
    var isExpanded = true
    var anchorAt: Instant = T0
    var snoozedUntil: Instant? = null
    var dueReminderFired = false
    var reminderCount = 0
    var createdAt: Instant = T0
    var deletedAt: Instant? = null

    fun build() = Task(
        id = id,
        listId = listId,
        parentId = parentId,
        title = title,
        notes = notes,
        priority = priority,
        cadenceOverride = cadenceOverride,
        progress = progress,
        progressBeforeComplete = progressBeforeComplete,
        isCompleted = isCompleted,
        completedAt = completedAt ?: if (isCompleted) createdAt else null,
        dueDate = dueDate,
        dueTime = dueTime,
        sortOrder = sortOrder,
        isExpanded = isExpanded,
        reminder = ReminderState(
            anchorAt = anchorAt,
            nextAt = null,
            nextKind = null,
            lastRemindedAt = null,
            count = reminderCount,
            snoozedUntil = snoozedUntil,
            dueReminderFired = dueReminderFired,
        ),
        createdAt = createdAt,
        updatedAt = createdAt,
        deletedAt = deletedAt,
    )
}

fun aTask(block: TaskBuilder.() -> Unit = {}): Task = TaskBuilder().apply(block).build()

fun aList(
    id: String = "l1",
    name: String = "Work",
    color: Int = ListColors.DEFAULT,
    sortOrder: Double = 0.0,
    sortMode: ListSortMode = ListSortMode.MY_ORDER,
    completedExpanded: Boolean = false,
): TaskList = TaskList(
    id = id,
    name = name,
    colorArgb = color,
    emoji = null,
    sortOrder = sortOrder,
    isDefault = false,
    sortMode = sortMode,
    completedExpanded = completedExpanded,
    createdAt = T0,
    updatedAt = T0,
)
