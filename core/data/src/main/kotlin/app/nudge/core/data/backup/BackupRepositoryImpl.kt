package app.nudge.core.data.backup

import androidx.room.withTransaction
import app.nudge.core.common.Clock
import app.nudge.core.common.DispatcherProvider
import app.nudge.core.database.NudgeDatabase
import app.nudge.core.database.dao.TaskDao
import app.nudge.core.database.dao.TaskListDao
import app.nudge.core.database.mapper.toDomain
import app.nudge.core.database.mapper.toEntity
import app.nudge.core.datastore.SettingsDataSource
import app.nudge.core.domain.repository.BackupRepository
import app.nudge.core.domain.repository.ImportMode
import app.nudge.core.domain.repository.ImportResult
import app.nudge.core.domain.repository.ListRepository
import app.nudge.core.domain.rules.TaskRules
import app.nudge.core.model.InsertPosition
import app.nudge.core.model.ListSortMode
import app.nudge.core.model.Priority
import app.nudge.core.model.ReminderCadence
import app.nudge.core.model.ReminderSettings
import app.nudge.core.model.ReminderState
import app.nudge.core.model.TapAction
import app.nudge.core.model.Task
import app.nudge.core.model.TaskList
import app.nudge.core.model.ThemeMode
import app.nudge.core.model.UserSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * JSON export/import (FR-103, 06 §10). Reminder runtime state is never exported; it is rebuilt on
 * import with anchor = import time. Soft-deleted rows are skipped.
 */
@Singleton
class BackupRepositoryImpl @Inject constructor(
    private val db: NudgeDatabase,
    private val listDao: TaskListDao,
    private val taskDao: TaskDao,
    private val settings: SettingsDataSource,
    private val lists: ListRepository,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
) : BackupRepository {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }

    override suspend fun exportJson(): String = withContext(dispatchers.io) {
        val backup = BackupDto(
            format = FORMAT,
            version = VERSION,
            exportedAt = clock.now().toString(),
            settings = settings.settings.first().toDto(),
            lists = listDao.all().map { it.toDomain().toDto() },
            tasks = taskDao.allLive().map { it.toDomain().toDto() },
        )
        json.encodeToString(BackupDto.serializer(), backup)
    }

    override suspend fun importJson(json: String, mode: ImportMode): ImportResult = withContext(dispatchers.io) {
        val dto = try {
            this@BackupRepositoryImpl.json.decodeFromString(BackupDto.serializer(), json)
        } catch (_: SerializationException) {
            return@withContext ImportResult.InvalidFile
        } catch (_: IllegalArgumentException) {
            return@withContext ImportResult.InvalidFile
        }
        if (dto.format != FORMAT) return@withContext ImportResult.InvalidFile
        if (dto.version > VERSION) return@withContext ImportResult.UnsupportedVersion(dto.version)

        val now = clock.now()
        val morning = settings.settings.first().reminders.morningTime
        val importedLists = try {
            dto.lists.map { it.toDomain() }
        } catch (_: RuntimeException) {
            return@withContext ImportResult.InvalidFile
        }
        val listIds = importedLists.map { it.id }.toSet()
        val importedTasks = try {
            repairHierarchy(dto.tasks.filter { it.listId in listIds }.map { it.toDomain(now, morning, clock) })
        } catch (_: RuntimeException) {
            return@withContext ImportResult.InvalidFile
        }

        db.withTransaction {
            when (mode) {
                ImportMode.REPLACE -> {
                    taskDao.deleteAll()
                    listDao.deleteAll()
                    listDao.upsert(importedLists.map { it.toEntity() })
                    taskDao.upsert(importedTasks.sortedBy { it.parentId != null }.map { it.toEntity() })
                }
                ImportMode.MERGE -> {
                    // Include soft-deleted rows: a newer local delete must win over an older backup row.
                    val localLists = importedLists.mapNotNull { listDao.get(it.id) }.associateBy { it.id }
                    listDao.upsert(
                        importedLists.filter { l -> localLists[l.id]?.let { l.updatedAt.toEpochMilli() > it.updatedAt } ?: true }
                            .map { it.toEntity() },
                    )
                    val localTasks = importedTasks.map { it.id }.chunked(SQL_CHUNK).flatMap { taskDao.getMany(it) }.associateBy { it.id }
                    val winners = importedTasks.filter { t ->
                        localTasks[t.id]?.let { t.updatedAt.toEpochMilli() > it.updatedAt } ?: true
                    }
                    // Keep merged parents valid: a local parent may have become a subtask.
                    val existingParents = winners.mapNotNull { it.parentId }.distinct().chunked(SQL_CHUNK).flatMap { taskDao.getMany(it) }
                        .associateBy { it.id }
                    val safe = winners.map { t ->
                        val p = t.parentId?.let { existingParents[it] }
                        if (t.parentId != null && p != null && p.parentId != null) t.copy(parentId = null) else t
                    }
                    taskDao.upsert(safe.sortedBy { it.parentId != null }.map { it.toEntity() })
                }
            }
        }
        if (mode == ImportMode.REPLACE) {
            dto.settings?.let { s -> settings.update { current -> s.toDomain(current) } }
        }
        lists.ensureDefaultList()
        ImportResult.Success(importedLists.size, importedTasks.size)
    }

    override suspend fun deleteAllData() = withContext(dispatchers.io) {
        db.withTransaction {
            taskDao.deleteAll()
            listDao.deleteAll()
        }
        settings.clear()
        settings.update { it.copy(onboardingDone = true) }
        lists.ensureDefaultList()
    }

    /** Parent missing → top-level; subtask of subtask → flattened; child follows parent's list (09 §2.4 step 3). */
    private fun repairHierarchy(tasks: List<Task>): List<Task> {
        val byId = tasks.associateBy { it.id }
        return tasks.map { t ->
            val parent = t.parentId?.let(byId::get)
            when {
                t.parentId == null -> t
                parent == null || parent.parentId != null -> t.copy(parentId = null)
                parent.listId != t.listId -> t.copy(listId = parent.listId)
                else -> t
            }
        }
    }

    companion object {
        const val FORMAT = "nudge-backup"
        const val VERSION = 1

        /** Stay under SQLite's 999 bound-variable limit on API 26–29. */
        private const val SQL_CHUNK = 900
    }
}

@Serializable
internal data class BackupDto(
    val format: String,
    val version: Int,
    val exportedAt: String,
    val settings: SettingsDto? = null,
    val lists: List<ListDto> = emptyList(),
    val tasks: List<TaskDto> = emptyList(),
)

@Serializable
internal data class ListDto(
    val id: String,
    val name: String,
    val colorArgb: Int,
    val emoji: String? = null,
    val sortOrder: Double,
    val isDefault: Boolean = false,
    val sortMode: String = "MY_ORDER",
    val completedExpanded: Boolean = false,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
internal data class TaskDto(
    val id: String,
    val listId: String,
    val parentId: String? = null,
    val title: String,
    val notes: String = "",
    val priority: Int = 0,
    val cadenceOverride: String? = null,
    val progress: Int = 0,
    val progressBeforeComplete: Int? = null,
    val isCompleted: Boolean = false,
    val completedAt: String? = null,
    val dueDate: String? = null,
    val dueTime: String? = null,
    val sortOrder: Double,
    val isExpanded: Boolean = true,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
internal data class SettingsDto(
    val theme: String = "SYSTEM",
    val dynamicColor: Boolean = false,
    val pureBlack: Boolean = false,
    val tapAction: String = "COMPLETE",
    val newTaskPosition: String = "TOP",
    val defaultPriority: Int = 0,
    val haptics: Boolean = true,
    val confirmDelete: Boolean = false,
    val cadence: Map<String, String> = emptyMap(),
    @SerialName("morningTime") val morning: String = "08:00",
    @SerialName("eveningTime") val evening: String = "21:00",
    val quietHoursEnabled: Boolean = true,
    val quietStart: String = "22:00",
    val quietEnd: String = "08:00",
    val urgentIgnoresQuietHours: Boolean = false,
    val defaultSnoozeMinutes: Int = 60,
)

private fun TaskList.toDto() = ListDto(
    id, name, colorArgb, emoji, sortOrder, isDefault, sortMode.name, completedExpanded,
    createdAt.toString(), updatedAt.toString(),
)

private fun ListDto.toDomain() = TaskList(
    id = id,
    name = TaskRules.validateListName(name),
    colorArgb = colorArgb,
    emoji = emoji,
    sortOrder = sortOrder,
    isDefault = isDefault,
    sortMode = ListSortMode.entries.firstOrNull { it.name == sortMode } ?: ListSortMode.MY_ORDER,
    completedExpanded = completedExpanded,
    createdAt = Instant.parse(createdAt),
    updatedAt = Instant.parse(updatedAt),
)

private fun Task.toDto() = TaskDto(
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
    completedAt = completedAt?.toString(),
    dueDate = dueDate?.toString(),
    dueTime = dueTime?.toString(),
    sortOrder = sortOrder,
    isExpanded = isExpanded,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)

private fun TaskDto.toDomain(now: Instant, morning: LocalTime, clock: Clock): Task {
    val date = dueDate?.let(LocalDate::parse)
    val time = if (date == null) null else dueTime?.let(LocalTime::parse)
    return Task(
        id = id,
        listId = listId,
        parentId = parentId,
        title = TaskRules.validateTitle(title),
        notes = TaskRules.clampNotes(notes),
        priority = Priority.fromLevel(priority),
        cadenceOverride = ReminderCadence.fromName(cadenceOverride),
        progress = progress.coerceIn(0, 100),
        progressBeforeComplete = progressBeforeComplete,
        isCompleted = isCompleted,
        completedAt = completedAt?.let(Instant::parse) ?: if (isCompleted) now else null,
        dueDate = date,
        dueTime = time,
        sortOrder = sortOrder,
        isExpanded = isExpanded,
        reminder = ReminderState.fresh(now).copy(
            dueReminderFired = TaskRules.dueAlreadyPassed(date, time, morning, now, clock.zone()),
        ),
        createdAt = Instant.parse(createdAt),
        updatedAt = Instant.parse(updatedAt),
    )
}

private fun UserSettings.toDto(): SettingsDto {
    val r = reminders
    return SettingsDto(
        theme = theme.name,
        dynamicColor = dynamicColor,
        pureBlack = pureBlack,
        tapAction = tapAction.name,
        newTaskPosition = newTaskPosition.name,
        defaultPriority = defaultPriority.level,
        haptics = haptics,
        confirmDelete = confirmDelete,
        cadence = Priority.entries.associate { it.name to r.cadenceFor(it).name },
        morning = r.morningTime.toString(),
        evening = r.eveningTime.toString(),
        quietHoursEnabled = r.quietHoursEnabled,
        quietStart = r.quietStart.toString(),
        quietEnd = r.quietEnd.toString(),
        urgentIgnoresQuietHours = r.urgentIgnoresQuietHours,
        defaultSnoozeMinutes = r.defaultSnoozeMinutes,
    )
}

private fun SettingsDto.toDomain(current: UserSettings): UserSettings {
    val d = ReminderSettings()
    fun time(s: String, fallback: LocalTime) = runCatching { LocalTime.parse(s) }.getOrDefault(fallback)
    return current.copy(
        theme = ThemeMode.entries.firstOrNull { it.name == theme } ?: current.theme,
        dynamicColor = dynamicColor,
        pureBlack = pureBlack,
        tapAction = TapAction.entries.firstOrNull { it.name == tapAction } ?: current.tapAction,
        newTaskPosition = InsertPosition.entries.firstOrNull { it.name == newTaskPosition } ?: current.newTaskPosition,
        defaultPriority = Priority.fromLevel(defaultPriority),
        haptics = haptics,
        confirmDelete = confirmDelete,
        reminders = current.reminders.copy(
            defaultCadence = Priority.entries.associateWith { p ->
                ReminderCadence.fromName(cadence[p.name]) ?: d.cadenceFor(p)
            },
            morningTime = time(morning, d.morningTime),
            eveningTime = time(evening, d.eveningTime),
            quietHoursEnabled = quietHoursEnabled,
            quietStart = time(quietStart, d.quietStart),
            quietEnd = time(quietEnd, d.quietEnd),
            urgentIgnoresQuietHours = urgentIgnoresQuietHours,
            defaultSnoozeMinutes = defaultSnoozeMinutes.coerceIn(5, 24 * 60),
        ),
    )
}
