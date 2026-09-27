package app.nudge.core.data.repository

import app.nudge.core.datastore.SettingsDataSource
import app.nudge.core.domain.repository.SettingsRepository
import app.nudge.core.model.UserSettings
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val source: SettingsDataSource,
) : SettingsRepository {
    override val settings: Flow<UserSettings> = source.settings

    override suspend fun update(transform: (UserSettings) -> UserSettings) = source.update(transform)
}
