package app.nudge.core.data.di

import app.nudge.core.data.backup.BackupRepositoryImpl
import app.nudge.core.data.repository.ListRepositoryImpl
import app.nudge.core.data.repository.SettingsRepositoryImpl
import app.nudge.core.data.repository.TaskRepositoryImpl
import app.nudge.core.domain.repository.BackupRepository
import app.nudge.core.domain.repository.ListRepository
import app.nudge.core.domain.repository.SettingsRepository
import app.nudge.core.domain.repository.TaskRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
interface DataModule {
    @Binds
    fun taskRepository(impl: TaskRepositoryImpl): TaskRepository

    @Binds
    fun listRepository(impl: ListRepositoryImpl): ListRepository

    @Binds
    fun settingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds
    fun backupRepository(impl: BackupRepositoryImpl): BackupRepository
}
