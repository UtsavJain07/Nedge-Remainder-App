package app.nudge.core.reminders.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import app.nudge.core.reminders.receiver.ReminderAlarmReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Wraps the single dispatcher alarm (07 §5.2, ADR-04, NFR-06). */
interface AlarmScheduler {
    fun canExact(): Boolean

    fun set(at: Instant)

    fun cancel()

    /** One-off "test nudge" alarm (07 §10). */
    fun setTest(at: Instant)
}

@Singleton
class AndroidAlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alarmManager: AlarmManager,
) : AlarmScheduler {

    private fun pendingIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(context, ReminderAlarmReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    override fun canExact(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    override fun set(at: Instant) = setAlarm(at, pendingIntent(ACTION_DISPATCH, REQ_DISPATCH))

    override fun cancel() = alarmManager.cancel(pendingIntent(ACTION_DISPATCH, REQ_DISPATCH))

    override fun setTest(at: Instant) = setAlarm(at, pendingIntent(ACTION_TEST, REQ_TEST))

    private fun setAlarm(at: Instant, pi: PendingIntent) {
        val ms = at.toEpochMilli()
        if (canExact()) {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
                return
            } catch (e: SecurityException) {
                // Permission revoked in a race: fall back to inexact (07 §5.2).
                Timber.w(e, "Exact alarm denied; falling back to inexact")
            }
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
    }

    companion object {
        const val REQ_DISPATCH = 1001
        const val REQ_TEST = 1002
        const val ACTION_DISPATCH = "app.nudge.action.DISPATCH"
        const val ACTION_TEST = "app.nudge.action.TEST_NUDGE"
    }
}
