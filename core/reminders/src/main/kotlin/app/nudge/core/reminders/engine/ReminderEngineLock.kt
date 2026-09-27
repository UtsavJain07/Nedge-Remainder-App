package app.nudge.core.reminders.engine

import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/** One lock shared by scheduler and dispatcher so recomputations never interleave (05 §8). Not reentrant. */
@Singleton
class ReminderEngineLock @Inject constructor() {
    val mutex = Mutex()
}
