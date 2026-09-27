package app.nudge.core.reminders.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.nudge.core.common.ApplicationScope
import app.nudge.core.common.Clock
import app.nudge.core.domain.reminder.ReminderScheduler
import app.nudge.core.domain.repository.SettingsRepository
import app.nudge.core.domain.usecase.SnoozeUseCase
import app.nudge.core.domain.usecase.ToggleCompleteUseCase
import app.nudge.core.reminders.notification.AndroidNotificationPublisher
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import java.time.Duration
import javax.inject.Inject

/** Done / Snooze from the notification (07 §7.3, FR-65). Not exported. */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {
    @Inject lateinit var toggleComplete: ToggleCompleteUseCase

    @Inject lateinit var snooze: SnoozeUseCase

    @Inject lateinit var scheduler: ReminderScheduler

    @Inject lateinit var settings: SettingsRepository

    @Inject lateinit var clock: Clock

    @Inject @field:ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(AndroidNotificationPublisher.EXTRA_TASK_ID) ?: return
        when (intent.action) {
            AndroidNotificationPublisher.ACTION_DONE -> goAsync(scope) {
                // Completes open subtasks too; the scheduler cancels the notification.
                toggleComplete(taskId, completed = true)
                scheduler.cancelNotification(taskId)
            }
            AndroidNotificationPublisher.ACTION_SNOOZE -> goAsync(scope) {
                val minutes = settings.settings.first().reminders.defaultSnoozeMinutes
                snooze(taskId, clock.now().plus(Duration.ofMinutes(minutes.toLong())))
                scheduler.cancelNotification(taskId)
            }
        }
    }
}
