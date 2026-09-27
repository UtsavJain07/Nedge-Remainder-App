package app.nudge.di

import app.nudge.AppUndoRunner
import app.nudge.core.common.ApplicationScope
import app.nudge.core.common.Clock
import app.nudge.core.common.DefaultDispatcherProvider
import app.nudge.core.common.DispatcherProvider
import app.nudge.core.common.IdGenerator
import app.nudge.core.common.SystemClock
import app.nudge.core.common.UuidV7Generator
import app.nudge.core.domain.usecase.UndoRunner
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import timber.log.Timber
import javax.inject.Singleton

/** CommonModule (05 §7): clock, dispatchers, ids, the application scope, undo. */
@Module
@InstallIn(SingletonComponent::class)
interface AppModule {
    @Binds
    fun clock(impl: SystemClock): Clock

    @Binds
    fun dispatchers(impl: DefaultDispatcherProvider): DispatcherProvider

    @Binds
    fun ids(impl: UuidV7Generator): IdGenerator

    @Binds
    fun undo(impl: AppUndoRunner): UndoRunner

    companion object {
        @Provides
        @Singleton
        @ApplicationScope
        fun applicationScope(): CoroutineScope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Timber.e(e, "Uncaught in application scope") },
        )
    }
}
