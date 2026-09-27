package app.nudge.core.reminders.engine

import app.nudge.core.common.ApplicationScope
import app.nudge.core.common.Clock
import app.nudge.core.domain.reminder.ReminderScheduler
import app.nudge.core.domain.repository.SettingsRepository
import app.nudge.core.domain.repository.TaskRepository
import app.nudge.core.domain.rules.TaskRules.resetAnchor
import app.nudge.core.model.ReminderCadence
import app.nudge.core.model.ReminderStateUpdate
import app.nudge.core.model.Task
import app.nudge.core.model.effectiveCadence
import app.nudge.core.reminders.alarm.AlarmScheduler
import app.nudge.core.reminders.notification.NotificationPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recomputes `nextReminderAt` after writes and re-arms the single dispatcher alarm (07 §5.3).
 * Work runs in the application scope so it survives the calling screen (05 §8).
 *
 * Implements FR-60, FR-69, FR-70.
 */
@Singleton
class ReminderSchedulerImpl @Inject constructor(
    private val tasks: TaskRepository,
    private val settings: SettingsRepository,
    private val calculator: ReminderCalculator,
    private val alarms: AlarmScheduler,
    private val publisher: NotificationPublisher,
    private val lock: ReminderEngineLock,
    private val clock: Clock,
    @param:ApplicationScope private val appScope: CoroutineScope,
) : ReminderScheduler {

    override suspend fun onTasksChanged(taskIds: Collection<String>) {
        if (taskIds.isEmpty()) return
        inAppScope {
            lock.mutex.withLock {
                val s = settings.settings.first().reminders
                val now = clock.now()
                val loaded = taskIds.distinct().map { id -> id to tasks.get(id) }
                val updates = loaded.mapNotNull { (_, t) ->
                    t?.let { ReminderStateUpdate(it.id, it.reminder, calculator.computeNext(it, s, now, clock.zone())) }
                }
                tasks.updateReminderState(updates)
                // FR-69: completed / deleted / cadence Off / snoozed → remove the posted notification.
                loaded.forEach { (id, t) ->
                    if (t == null || shouldClearNotification(t, s, now)) publisher.cancel(id)
                }
                publisher.updateGroupSummary()
                armNextAlarmLocked()
            }
        }
    }

    override suspend fun rescheduleAll(resetAnchors: Boolean) {
        inAppScope {
            lock.mutex.withLock {
                publisher.ensureChannels()
                val s = settings.settings.first().reminders
                val now = clock.now()
                val open = tasks.allOpenTasks()
                val updates = open.map { t ->
                    val state = if (resetAnchors) t.reminder.resetAnchor(now) else t.reminder
                    ReminderStateUpdate(t.id, state, calculator.computeNext(t.copy(reminder = state), s, now, clock.zone()))
                }
                val staleIds = tasks.staleReminderTaskIds()
                val stale = staleIds.mapNotNull { tasks.get(it) }.map { ReminderStateUpdate(it.id, it.reminder, null) }
                tasks.updateReminderState(updates + stale)
                staleIds.forEach { publisher.cancel(it) }
                if (s.pausedUntil != null && s.isPaused(now)) publisher.cancelAllReminders()
                publisher.updateGroupSummary()
                armNextAlarmLocked()
            }
        }
    }

    override suspend fun cancelNotification(taskId: String) {
        publisher.cancel(taskId)
        publisher.updateGroupSummary()
    }

    /** 07 §5.1. Caller must hold the lock. */
    internal suspend fun armNextAlarmLocked() {
        val earliest = tasks.earliestNextReminder()
        if (earliest == null) {
            alarms.cancel()
            return
        }
        alarms.set(maxOf(earliest, clock.now().plusSeconds(1)))
    }

    private fun shouldClearNotification(t: Task, s: app.nudge.core.model.ReminderSettings, now: java.time.Instant): Boolean =
        t.isCompleted || t.deletedAt != null ||
            (t.effectiveCadence(s) == ReminderCadence.OFF && t.dueDate == null) ||
            (t.reminder.snoozedUntil?.isAfter(now) == true)

    private suspend fun inAppScope(block: suspend () -> Unit) {
        appScope.async { block() }.await()
    }
}
