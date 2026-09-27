package app.nudge.core.domain.usecase

import app.nudge.core.model.Task
import app.nudge.core.model.UserSettings
import app.nudge.core.testing.FakeSettingsRepository
import app.nudge.core.testing.InMemoryListRepository
import app.nudge.core.testing.InMemoryStore
import app.nudge.core.testing.InMemoryTaskRepository
import app.nudge.core.testing.RecordingReminderScheduler
import app.nudge.core.testing.SequentialIdGenerator
import app.nudge.core.testing.TestClock
import app.nudge.core.testing.aList

/** Wires the in-memory repositories the way Hilt wires the real ones. */
class Harness(settings: UserSettings = UserSettings()) {
    val clock = TestClock()
    val store = InMemoryStore()
    val settings = FakeSettingsRepository(settings)
    val tasks = InMemoryTaskRepository(clock, SequentialIdGenerator("task"), this.settings, store)
    val lists = InMemoryListRepository(clock, SequentialIdGenerator("list"), store)
    val scheduler = RecordingReminderScheduler()

    init {
        lists.seed(
            aList(id = "l1", name = "Work", sortOrder = 0.0),
            aList(id = "l2", name = "Home", sortOrder = 1024.0),
        )
    }

    fun task(id: String): Task = store.tasks.value.getValue(id)
}
