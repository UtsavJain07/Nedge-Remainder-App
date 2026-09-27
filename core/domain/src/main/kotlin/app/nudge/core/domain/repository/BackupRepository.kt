package app.nudge.core.domain.repository

/** JSON export / import (FR-103, 06 §10). */
interface BackupRepository {
    suspend fun exportJson(): String

    suspend fun importJson(json: String, mode: ImportMode): ImportResult

    /** Wipes all lists, tasks and settings, then re-seeds the default list. */
    suspend fun deleteAllData()
}

enum class ImportMode { REPLACE, MERGE }

sealed interface ImportResult {
    data class Success(val lists: Int, val tasks: Int) : ImportResult

    data object InvalidFile : ImportResult

    data class UnsupportedVersion(val version: Int) : ImportResult
}
