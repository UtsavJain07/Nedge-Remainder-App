package app.nudge.core.data.repository

import androidx.room.withTransaction
import app.nudge.core.common.Clock
import app.nudge.core.common.DispatcherProvider
import app.nudge.core.common.IdGenerator
import app.nudge.core.database.NudgeDatabase
import app.nudge.core.database.dao.TaskDao
import app.nudge.core.database.dao.TaskListDao
import app.nudge.core.database.mapper.toDomain
import app.nudge.core.database.mapper.toEntity
import app.nudge.core.domain.order.OrderingCalculator
import app.nudge.core.domain.repository.EmojiChange
import app.nudge.core.domain.repository.ListRepository
import app.nudge.core.model.ListColors
import app.nudge.core.model.ListSortMode
import app.nudge.core.model.ListStats
import app.nudge.core.model.TaskList
import app.nudge.core.model.UndoSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ListRepositoryImpl @Inject constructor(
    private val db: NudgeDatabase,
    private val listDao: TaskListDao,
    private val taskDao: TaskDao,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val dispatchers: DispatcherProvider,
) : ListRepository {
    private val seedMutex = Mutex()

    override fun observeLists(): Flow<List<TaskList>> = listDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun observeList(id: String): Flow<TaskList?> = listDao.observe(id).map { it?.toDomain() }

    override fun observeListStats(): Flow<Map<String, ListStats>> = listDao.observeStats().map { rows ->
        rows.associate { it.listId to ListStats(it.openCount, it.completedCount, it.hasUrgent) }
    }

    override suspend fun get(id: String): TaskList? = io { listDao.get(id)?.toDomain() }

    override suspend fun lists(): List<TaskList> = io { listDao.all().map { it.toDomain() } }

    override suspend fun create(name: String, color: Int, emoji: String?): TaskList = io {
        db.withTransaction {
            val now = clock.now()
            val list = TaskList(
                id = ids.newId(),
                name = name,
                colorArgb = color,
                emoji = emoji,
                sortOrder = OrderingCalculator.bottom(listDao.maxSortOrder()),
                isDefault = false,
                sortMode = ListSortMode.MY_ORDER,
                completedExpanded = false,
                createdAt = now,
                updatedAt = now,
            )
            listDao.upsert(listOf(list.toEntity()))
            list
        }
    }

    override suspend fun update(id: String, name: String?, color: Int?, emoji: EmojiChange) = edit(id) {
        it.copy(
            name = name ?: it.name,
            colorArgb = color ?: it.colorArgb,
            emoji = if (emoji is EmojiChange.Set) emoji.emoji else it.emoji,
        )
    }

    override suspend fun setSortMode(id: String, mode: ListSortMode) = edit(id) { it.copy(sortMode = mode) }

    override suspend fun setCompletedExpanded(id: String, expanded: Boolean) = edit(id) { it.copy(completedExpanded = expanded) }

    override suspend fun reorder(id: String, beforeId: String?, afterId: String?) = io {
        db.withTransaction {
            var all = listDao.all().map { it.toDomain() }
            var before = beforeId?.let { b -> all.firstOrNull { it.id == b }?.sortOrder }
            var after = afterId?.let { a -> all.firstOrNull { it.id == a }?.sortOrder }
            if (OrderingCalculator.needsRenormalize(before, after)) {
                val renorm = OrderingCalculator.renormalize(all.filter { it.id != id }.map { it.id })
                all = all.map { l -> renorm[l.id]?.let { l.copy(sortOrder = it) } ?: l }
                listDao.upsert(all.filter { it.id != id }.map { it.toEntity() })
                before = beforeId?.let(renorm::get)
                after = afterId?.let(renorm::get)
            }
            val list = all.firstOrNull { it.id == id } ?: return@withTransaction
            listDao.upsert(listOf(list.copy(sortOrder = OrderingCalculator.between(before, after), updatedAt = clock.now()).toEntity()))
        }
    }

    override suspend fun softDelete(id: String): UndoSnapshot = io {
        db.withTransaction {
            check(listDao.liveCount() > 1) { "The last list cannot be deleted" }
            val list = listDao.get(id)?.toDomain() ?: throw NoSuchElementException("List $id not found")
            val tasks = taskDao.allInList(id).map { it.toDomain() }
            val now = clock.now().toEpochMilli()
            listDao.softDelete(id, now)
            tasks.map { it.id }.chunked(TaskRepositoryImpl.SQL_CHUNK).forEach { taskDao.softDelete(it, now) }
            UndoSnapshot(tasksBefore = tasks, listsBefore = listOf(list))
        }
    }

    override suspend fun restore(snapshot: UndoSnapshot) = io {
        db.withTransaction {
            val now = clock.now()
            listDao.upsert(snapshot.listsBefore.map { it.copy(updatedAt = now).toEntity() })
            snapshot.createdTaskIds.chunked(TaskRepositoryImpl.SQL_CHUNK).forEach { taskDao.hardDelete(it) }
            val rows = snapshot.tasksBefore.sortedBy { it.parentId != null }.map { it.copy(updatedAt = now).toEntity() }
            if (rows.isNotEmpty()) taskDao.upsert(rows)
        }
    }

    override suspend fun ensureDefaultList() = io {
        seedMutex.withLock {
            db.withTransaction {
                if (listDao.liveCount() > 0) return@withTransaction
                val now = clock.now()
                val list = TaskList(
                    id = ids.newId(),
                    name = DEFAULT_LIST_NAME,
                    colorArgb = ListColors.DEFAULT,
                    emoji = null,
                    sortOrder = 0.0,
                    isDefault = true,
                    sortMode = ListSortMode.MY_ORDER,
                    completedExpanded = false,
                    createdAt = now,
                    updatedAt = now,
                )
                listDao.upsert(listOf(list.toEntity()))
            }
        }
    }

    private suspend fun edit(id: String, transform: (TaskList) -> TaskList) = io {
        db.withTransaction {
            val list = listDao.get(id)?.toDomain() ?: return@withTransaction
            val next = transform(list)
            if (next != list) listDao.upsert(listOf(next.copy(updatedAt = clock.now()).toEntity()))
        }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(dispatchers.io) { block() }

    companion object {
        /** FR-05. */
        const val DEFAULT_LIST_NAME = "My Tasks"
    }
}
