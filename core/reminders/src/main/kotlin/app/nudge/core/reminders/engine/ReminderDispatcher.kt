package app.nudge.core.reminders.engine

import app.nudge.core.common.Clock
import app.nudge.core.domain.repository.ListRepository
import app.nudge.core.domain.repository.SettingsRepository
import app.nudge.core.domain.repository.TaskRepository
import app.nudge.core.model.ReminderKind
import app.nudge.core.model.ReminderStateUpdate
import app.nudge.core.reminders.notification.NotificationPublisher
import app.nudge.core.reminders.notification.ReminderContent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Fires every due reminder in one wake-up and advances each task's state (07 §5).
 *
 * Implements FR-60, FR-63, FR-65, FR-66, FR-68, NFR-06.
 */
@Singleton
class ReminderDispatcher @Inject constructor(
    private val tasks: TaskRepository,
    private val lists: ListRepository,
    private val settings: SettingsRepository,
    private val calculator: ReminderCalculator,
    private val publisher: NotificationPublisher,
    private val scheduler: ReminderSchedulerImpl,
    private val lock: ReminderEngineLock,
    private val clock: Clock,
) {
    /** Returns the number of reminders posted. */
    suspend fun dispatchDue(now: Instant = clock.now()): Int = lock.mutex.withLock {
        val s = settings.settings.first().reminders
        val horizon = now.plus(TOLERANCE)
        val due = if (s.isPaused(now)) emptyList() else tasks.tasksWithReminderDueBefore(horizon)
        val listsById = lists.lists().associateBy { it.id }
        val updates = due.map { task ->
            val kind = task.reminder.nextKind ?: ReminderKind.INTERVAL
            val count = task.reminder.count + 1
            val list = listsById[task.listId]
            if (list != null) {
                val children = tasks.children(task.id)
                publisher.post(
                    ReminderContent(
                        task = task,
                        list = list,
                        kind = kind,
                        count = count,
                        subtasksDone = children.count { it.isCompleted },
                        subtasksTotal = children.size,
                        progress = if (children.isEmpty()) {
                            task.progress
                        } else {
                            children.map { if (it.isCompleted) 100 else it.progress }.average().roundToInt()
                        },
                        snoozeMinutes = s.defaultSnoozeMinutes,
                        postedAt = now,
                    ),
                )
            }
            val snoozed = task.reminder.snoozedUntil
            val newState = task.reminder.copy(
                lastRemindedAt = now,
                count = count,
                dueReminderFired = task.reminder.dueReminderFired || kind == ReminderKind.DUE,
                snoozedUntil = if (snoozed != null && snoozed <= horizon) null else snoozed,
                // Interval restarts from the actual fire time (US-7).
                anchorAt = if (kind == ReminderKind.INTERVAL) now else task.reminder.anchorAt,
            )
            // Computing from now+TOLERANCE guarantees the next one is strictly after this fire.
            val next = calculator.computeNext(task.copy(reminder = newState), s, horizon, clock.zone())
            ReminderStateUpdate(task.id, newState, next)
        }
        tasks.updateReminderState(updates)
        publisher.updateGroupSummary()
        scheduler.armNextAlarmLocked()
        updates.size
    }

    /** Re-arms without dispatching (reconciler safety net). */
    suspend fun armNextAlarm() = lock.mutex.withLock { scheduler.armNextAlarmLocked() }

    companion object {
        /** Fire up to 60 s early so near-simultaneous reminders batch into one wake-up. */
        val TOLERANCE: Duration = Duration.ofSeconds(60)
    }
}
