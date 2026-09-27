package app.nudge.core.domain.repository

import app.nudge.core.model.UserSettings
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val settings: Flow<UserSettings>

    suspend fun update(transform: (UserSettings) -> UserSettings)
}
