package app.nudge.core.testing

import app.nudge.core.domain.reminder.ReminderScheduler
import app.nudge.core.domain.repository.SettingsRepository
import app.nudge.core.model.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Records scheduler calls so tests can assert reminders were re-armed (11 §4 SettingsReminderTest). */
class RecordingReminderScheduler : ReminderScheduler {
    val changed = mutableListOf<Set<String>>()
    var rescheduleAllCalls = 0
    var lastResetAnchors = false
    val cancelled = mutableListOf<String>()

    override suspend fun onTasksChanged(taskIds: Collection<String>) {
        changed += taskIds.toSet()
    }

    override suspend fun rescheduleAll(resetAnchors: Boolean) {
        rescheduleAllCalls++
        lastResetAnchors = resetAnchors
    }

    override suspend fun cancelNotification(taskId: String) {
        cancelled += taskId
    }
}

class FakeSettingsRepository(initial: UserSettings = UserSettings()) : SettingsRepository {
    val state = MutableStateFlow(initial)
    override val settings: Flow<UserSettings> = state

    override suspend fun update(transform: (UserSettings) -> UserSettings) {
        state.value = transform(state.value)
    }
}
