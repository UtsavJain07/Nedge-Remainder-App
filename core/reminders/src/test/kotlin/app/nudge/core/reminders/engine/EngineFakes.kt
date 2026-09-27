package app.nudge.core.reminders.engine

import app.nudge.core.domain.usecase.CreateTaskUseCase
import app.nudge.core.domain.usecase.DeleteTaskUseCase
import app.nudge.core.domain.usecase.SnoozeUseCase
import app.nudge.core.domain.usecase.ToggleCompleteUseCase
import app.nudge.core.domain.usecase.UpdateTaskUseCase
import app.nudge.core.model.UserSettings
import app.nudge.core.reminders.alarm.AlarmScheduler
import app.nudge.core.reminders.notification.NotificationPublisher
import app.nudge.core.reminders.notification.ReminderContent
import app.nudge.core.testing.FakeSettingsRepository
import app.nudge.core.testing.InMemoryListRepository
import app.nudge.core.testing.InMemoryStore
import app.nudge.core.testing.InMemoryTaskRepository
import app.nudge.core.testing.SequentialIdGenerator
import app.nudge.core.testing.TestClock
import app.nudge.core.testing.aList
import kotlinx.coroutines.CoroutineScope
import java.time.Instant

/** Records the single dispatcher alarm (07 §5.2). */
class FakeAlarmScheduler(var exact: Boolean = true) : AlarmScheduler {
    /** The currently armed alarm, or null when cancelled / never set. */
    var armedAt: Instant? = null
        private set
    val setHistory = mutableListOf<Instant>()
    var cancelCalls = 0
        private set
    val testAlarms = mutableListOf<Instant>()

    override fun canExact(): Boolean = exact

    override fun set(at: Instant) {
        armedAt = at
        setHistory += at
    }

    override fun cancel() {
        armedAt = null
        cancelCalls++
    }

    override fun setTest(at: Instant) {
        testAlarms += at
    }
}

/** Models the notification shade: one entry per task id, summary shown at ≥ 2 (07 §7.2). */
class FakeNotificationPublisher : NotificationPublisher {
    val active = linkedMapOf<String, ReminderContent>()
    val posted = mutableListOf<ReminderContent>()
    val cancelled = mutableListOf<String>()
    var summaryShown = false
        private set
    var summaryUpdates = 0
        private set
    var channelsEnsured = 0
        private set
    var cancelAllCalls = 0
        private set
    var testNudges = 0
        private set

    override fun ensureChannels() {
        channelsEnsured++
    }

    override fun post(content: ReminderContent) {
        active[content.task.id] = content
        posted += content
    }

    override fun cancel(taskId: String) {
        active.remove(taskId)
        cancelled += taskId
    }

    override fun cancelAllReminders() {
        active.clear()
        cancelAllCalls++
    }

    override fun updateGroupSummary() {
        summaryUpdates++
        summaryShown = active.size >= 2
    }

    override fun postTestNudge() {
        testNudges++
    }
}

/**
 * The reminder engine wired with in-memory fakes, driven by a [TestClock]. [appScope] should be the
 * test's `backgroundScope` so scheduler work runs on the test scheduler.
 */
class EngineHarness(appScope: CoroutineScope, settings: UserSettings = UserSettings()) {
    val clock = TestClock()
    val store = InMemoryStore()
    val settings = FakeSettingsRepository(settings)
    val tasks = InMemoryTaskRepository(clock, SequentialIdGenerator("task"), this.settings, store)
    val lists = InMemoryListRepository(clock, SequentialIdGenerator("list"), store)
    val alarms = FakeAlarmScheduler()
    val publisher = FakeNotificationPublisher()
    private val calculator = ReminderCalculator()
    private val lock = ReminderEngineLock()

    val scheduler = ReminderSchedulerImpl(tasks, this.settings, calculator, alarms, publisher, lock, clock, appScope)
    val dispatcher = ReminderDispatcher(tasks, lists, this.settings, calculator, publisher, scheduler, lock, clock)

    val create = CreateTaskUseCase(tasks, this.settings, scheduler)
    val update = UpdateTaskUseCase(tasks, scheduler)
    val toggle = ToggleCompleteUseCase(tasks, scheduler)
    val snooze = SnoozeUseCase(tasks, scheduler)
    val delete = DeleteTaskUseCase(tasks, scheduler)

    init {
        lists.seed(aList(id = "l1", name = "Work"))
    }

    fun task(id: String) = store.tasks.value.getValue(id)

    /** Fires the armed alarm (moving the clock to it) if it is due at or before [until]. */
    suspend fun fireAlarmIfDue(until: Instant): Boolean {
        val at = alarms.armedAt ?: return false
        if (at > until) return false
        clock.now = maxOf(clock.now, at)
        dispatcher.dispatchDue()
        return true
    }

    /**
     * Advances the clock to [until], firing the single alarm each time it comes due, exactly as
     * AlarmManager would. Returns the reminders posted on the way.
     */
    suspend fun runUntil(until: Instant): List<ReminderContent> {
        val start = publisher.posted.size
        var guard = 0
        while (fireAlarmIfDue(until)) {
            check(++guard < 10_000) { "Alarm loop did not settle" }
        }
        clock.now = maxOf(clock.now, until)
        return publisher.posted.subList(start, publisher.posted.size).toList()
    }
}
