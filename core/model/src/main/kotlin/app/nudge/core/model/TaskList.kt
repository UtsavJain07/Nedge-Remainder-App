package app.nudge.core.model

import java.time.Instant

/** A named, colored container of tasks (FR-01). */
data class TaskList(
    val id: String,
    val name: String,
    val colorArgb: Int,
    val emoji: String?,
    val sortOrder: Double,
    val isDefault: Boolean,
    val sortMode: ListSortMode,
    val completedExpanded: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
) {
    companion object {
        const val NAME_MAX = 40
    }
}

/** Aggregates shown on a Home list card (FR-06). Counts are top-level tasks only. */
data class ListStats(
    val openCount: Int,
    val completedCount: Int,
    val hasUrgent: Boolean,
) {
    val total: Int get() = openCount + completedCount
    val completedFraction: Float get() = if (total == 0) 0f else completedCount.toFloat() / total

    companion object {
        val EMPTY = ListStats(0, 0, false)
    }
}
