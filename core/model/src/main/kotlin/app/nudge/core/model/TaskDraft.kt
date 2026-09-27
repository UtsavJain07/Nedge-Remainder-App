package app.nudge.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** Input for creating a task (FR-10, FR-13). */
data class TaskDraft(
    val listId: String,
    val parentId: String?,
    val title: String,
    val priority: Priority = Priority.NONE,
    val cadenceOverride: ReminderCadence? = null,
    val notes: String = "",
    val dueDate: LocalDate? = null,
    val dueTime: LocalTime? = null,
    val position: InsertPosition = InsertPosition.TOP,
)

/** Partial update; `null` / [Patch.Unchanged] = unchanged (FR-14). */
data class TaskPatch(
    val title: String? = null,
    val notes: String? = null,
    val priority: Priority? = null,
    val cadenceOverride: Patch<ReminderCadence?> = Patch.Unchanged,
    val progress: Int? = null,
    val dueDate: Patch<LocalDate?> = Patch.Unchanged,
    val dueTime: Patch<LocalTime?> = Patch.Unchanged,
    val snoozedUntil: Patch<Instant?> = Patch.Unchanged,
    val isExpanded: Boolean? = null,
)

sealed interface Patch<out T> {
    data object Unchanged : Patch<Nothing>

    data class Set<T>(val value: T) : Patch<T>
}

fun <T> Patch<T>.valueOr(current: T): T = when (this) {
    Patch.Unchanged -> current
    is Patch.Set -> value
}
