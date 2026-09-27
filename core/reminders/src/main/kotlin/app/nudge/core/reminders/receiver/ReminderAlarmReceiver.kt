package app.nudge.core.reminders.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.nudge.core.common.ApplicationScope
import app.nudge.core.reminders.alarm.AndroidAlarmScheduler
import app.nudge.core.reminders.engine.ReminderDispatcher
import app.nudge.core.reminders.notification.NotificationPublisher
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject

/** The single dispatcher alarm fired (07 §1). Not exported. */
@AndroidEntryPoint
class ReminderAlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var dispatcher: ReminderDispatcher

    @Inject lateinit var publisher: NotificationPublisher

    @Inject @field:ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AndroidAlarmScheduler.ACTION_DISPATCH -> goAsync(scope) { dispatcher.dispatchDue() }
            AndroidAlarmScheduler.ACTION_TEST -> publisher.postTestNudge()
        }
    }
}
