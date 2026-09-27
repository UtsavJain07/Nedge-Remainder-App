package app.nudge.core.reminders.engine

import app.nudge.core.common.Clock
import app.nudge.core.common.SystemClock
import app.nudge.core.domain.reminder.ReminderDebugTools
import app.nudge.core.domain.reminder.ReminderScheduler
import app.nudge.core.domain.reminder.ScheduledReminder
import app.nudge.core.domain.repository.TaskRepository
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Settings › Debug (07 §11). Only reachable from the UI in debug builds. */
@Singleton
class ReminderDebugToolsImpl @Inject constructor(
    private val dispatcher: ReminderDispatcher,
    private val scheduler: ReminderScheduler,
    private val tasks: TaskRepository,
    private val clock: Clock,
) : ReminderDebugTools {
    override suspend fun dispatchNow(): Int = dispatcher.dispatchDue()

    override suspend fun scheduled(): List<ScheduledReminder> = tasks.allOpenTasks()
        .mapNotNull { t -> t.reminder.nextAt?.let { ScheduledReminder(t.id, t.title, it, t.reminder.nextKind, t.reminder.count) } }
        .sortedBy { it.nextAt }

    override suspend fun nextAlarmAt(): Instant? = tasks.earliestNextReminder()

    override suspend fun timeTravel(minutes: Long) {
        val sys = clock as? SystemClock ?: return
        sys.offset = sys.offset.plus(Duration.ofMinutes(minutes))
        dispatcher.dispatchDue()
        dispatcher.armNextAlarm()
    }

    override fun timeOffsetMinutes(): Long = (clock as? SystemClock)?.offset?.toMinutes() ?: 0

    override suspend fun resetTime() {
        (clock as? SystemClock)?.offset = Duration.ZERO
        scheduler.rescheduleAll()
    }

    override suspend fun resetAllAnchors() = scheduler.rescheduleAll(resetAnchors = true)
}
