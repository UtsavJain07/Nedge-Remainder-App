package app.nudge.core.domain.repository

import app.nudge.core.model.ListSortMode
import app.nudge.core.model.ListStats
import app.nudge.core.model.TaskList
import app.nudge.core.model.UndoSnapshot
import kotlinx.coroutines.flow.Flow

interface ListRepository {
    fun observeLists(): Flow<List<TaskList>>

    fun observeList(id: String): Flow<TaskList?>

    /** Open / completed counts and urgent flag per list id (FR-06). */
    fun observeListStats(): Flow<Map<String, ListStats>>

    suspend fun get(id: String): TaskList?

    suspend fun lists(): List<TaskList>

    suspend fun create(name: String, color: Int, emoji: String?): TaskList

    suspend fun update(id: String, name: String? = null, color: Int? = null, emoji: EmojiChange = EmojiChange.Unchanged)

    suspend fun setSortMode(id: String, mode: ListSortMode)

    suspend fun setCompletedExpanded(id: String, expanded: Boolean)

    /** Place list [id] between [beforeId] (above) and [afterId] (below) on Home (FR-04). */
    suspend fun reorder(id: String, beforeId: String?, afterId: String?)

    /** Soft-deletes the list and its tasks (FR-03). Throws if it is the last list (FR-05). */
    suspend fun softDelete(id: String): UndoSnapshot

    suspend fun restore(snapshot: UndoSnapshot)

    /** Inserts "My Tasks" if no list exists. Idempotent (06 §8). */
    suspend fun ensureDefaultList()
}

sealed interface EmojiChange {
    data object Unchanged : EmojiChange

    data class Set(val emoji: String?) : EmojiChange
}
