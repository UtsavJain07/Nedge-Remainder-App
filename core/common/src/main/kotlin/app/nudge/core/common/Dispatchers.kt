package app.nudge.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/** Injected dispatchers so tests can substitute `StandardTestDispatcher` (NFR-09, 11 §1). */
interface DispatcherProvider {
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
    val main: CoroutineDispatcher
}

@Singleton
class DefaultDispatcherProvider @Inject constructor() : DispatcherProvider {
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val default: CoroutineDispatcher = Dispatchers.Default
    override val main: CoroutineDispatcher get() = Dispatchers.Main
}

/** Long-lived scope (SupervisorJob + default) for work that must outlive a screen (05 §8). */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApplicationScope
