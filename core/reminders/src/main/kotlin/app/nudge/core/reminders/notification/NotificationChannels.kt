package app.nudge.core.reminders.notification

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.os.Build
import app.nudge.core.model.Priority
import app.nudge.core.model.ReminderKind
import app.nudge.core.reminders.R

/** Notification channels (07 §7.1). Creating them is idempotent. */
object NotificationChannels {
    const val GROUP_REMINDERS = "reminders"
    const val URGENT = "reminders_urgent"
    const val HIGH = "reminders_high"
    const val MEDIUM = "reminders_medium"
    const val LOW = "reminders_low"
    const val DUE = "reminders_due"
    const val GENERAL = "general"

    val reminderChannels = listOf(URGENT, HIGH, MEDIUM, LOW, DUE)

    /** DUE kind → due channel; otherwise by priority (NONE with an override → medium). */
    fun channelFor(priority: Priority, kind: ReminderKind): String = when {
        kind == ReminderKind.DUE -> DUE
        priority == Priority.URGENT -> URGENT
        priority == Priority.HIGH -> HIGH
        priority == Priority.LOW -> LOW
        else -> MEDIUM
    }

    fun ensureCreated(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannelGroup(
            NotificationChannelGroup(GROUP_REMINDERS, context.getString(R.string.channel_group_reminders)),
        )
        fun channel(id: String, name: Int, importance: Int, block: NotificationChannel.() -> Unit = {}) =
            NotificationChannel(id, context.getString(name), importance).apply {
                if (id != GENERAL) group = GROUP_REMINDERS
                block()
            }
        nm.createNotificationChannels(
            listOf(
                channel(URGENT, R.string.channel_urgent, NotificationManager.IMPORTANCE_HIGH) {
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 250, 150, 250)
                    enableLights(true)
                    lightColor = Color.RED
                },
                channel(HIGH, R.string.channel_high, NotificationManager.IMPORTANCE_HIGH) { enableVibration(true) },
                channel(MEDIUM, R.string.channel_medium, NotificationManager.IMPORTANCE_DEFAULT),
                channel(LOW, R.string.channel_low, NotificationManager.IMPORTANCE_DEFAULT),
                channel(DUE, R.string.channel_due, NotificationManager.IMPORTANCE_HIGH),
                channel(GENERAL, R.string.channel_general, NotificationManager.IMPORTANCE_LOW),
            ),
        )
    }
}
