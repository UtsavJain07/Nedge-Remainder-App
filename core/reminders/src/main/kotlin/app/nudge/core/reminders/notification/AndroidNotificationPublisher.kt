package app.nudge.core.reminders.notification

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.nudge.core.common.Clock
import app.nudge.core.model.Priority
import app.nudge.core.model.ReminderKind
import app.nudge.core.reminders.R
import app.nudge.core.reminders.notification.NotificationPublisher.Companion.SUMMARY_ID
import app.nudge.core.reminders.notification.NotificationPublisher.Companion.TEST_ID
import app.nudge.core.reminders.notification.NotificationPublisher.Companion.notificationIdFor
import app.nudge.core.reminders.receiver.NotificationActionReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZonedDateTime
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidNotificationPublisher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: Clock,
) : NotificationPublisher {

    private val nm = NotificationManagerCompat.from(context)

    override fun ensureChannels() = NotificationChannels.ensureCreated(context)

    private fun canPost(): Boolean = nm.areNotificationsEnabled() &&
        (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            )

    @SuppressLint("MissingPermission") // checked in canPost()
    override fun post(content: ReminderContent) {
        if (!canPost()) return
        ensureChannels()
        val task = content.task
        val id = notificationIdFor(task.id)
        val title = if (content.kind == ReminderKind.DUE) context.getString(R.string.notif_due_title, task.title) else task.title
        val text = if (content.kind == ReminderKind.DUE) dueText(content) else statusText(content)
        val bigText = if (task.notes.isNotBlank()) "$text\n${task.notes.take(NOTES_PREVIEW)}" else text
        val subText = when (content.kind) {
            ReminderKind.FIXED_TIME -> context.getString(
                if (content.postedAt.atZone(clock.zone()).hour < 15) R.string.notif_morning_checkin else R.string.notif_evening_checkin,
            )
            else -> content.list.name
        }
        val snoozeLabel = durationLabel(content.snoozeMinutes)
        val notification = NotificationCompat.Builder(context, NotificationChannels.channelFor(task.priority, content.kind))
            .setSmallIcon(R.drawable.ic_stat_nudge)
            .setColor(content.list.colorArgb)
            .setColorized(false)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(subText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(if (task.priority >= Priority.HIGH) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setGroup(GROUP_KEY)
            .setWhen(content.postedAt.toEpochMilli())
            .setShowWhen(true)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false) // re-alert on each nag; same id replaces
            .setContentIntent(openTaskIntent(task.id))
            .addAction(R.drawable.ic_action_done, context.getString(R.string.action_done), actionIntent(task.id, ACTION_DONE))
            .addAction(
                R.drawable.ic_action_snooze,
                context.getString(R.string.action_snooze_fmt, snoozeLabel),
                actionIntent(task.id, ACTION_SNOOZE),
            )
            .build()
        nm.notify(id, notification)
    }

    override fun cancel(taskId: String) = nm.cancel(notificationIdFor(taskId))

    override fun cancelAllReminders() {
        activeReminderIds().forEach { nm.cancel(it) }
        nm.cancel(SUMMARY_ID)
    }

    private fun activeReminderIds(): List<Int> {
        val system = context.getSystemService(NotificationManager::class.java) ?: return emptyList()
        return system.activeNotifications
            .filter { it.notification.group == GROUP_KEY && it.id != SUMMARY_ID }
            .map { it.id }
    }

    @SuppressLint("MissingPermission")
    override fun updateGroupSummary() {
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        val active = system.activeNotifications.filter { it.notification.group == GROUP_KEY && it.id != SUMMARY_ID }
        // NOTE (07 §7.2): cancelling a summary also cancels its children on Android, so the summary is
        // only removed once no reminders remain; with one left it is kept (the shade shows it un-grouped).
        if (active.isEmpty()) {
            nm.cancel(SUMMARY_ID)
            return
        }
        val summaryShowing = system.activeNotifications.any { it.id == SUMMARY_ID }
        if (!canPost() || (active.size < 2 && !summaryShowing)) return
        val titles = active.mapNotNull { it.notification.extras.getCharSequence(NotificationCompat.EXTRA_TITLE) }
        val headline = context.resources.getQuantityString(R.plurals.notif_summary_title, active.size, active.size)
        val style = NotificationCompat.InboxStyle().setBigContentTitle(headline)
        titles.take(5).forEach { style.addLine(it) }
        val summary = NotificationCompat.Builder(context, NotificationChannels.MEDIUM)
            .setSmallIcon(R.drawable.ic_stat_nudge)
            .setContentTitle(headline)
            .setContentText(titles.take(3).joinToString(", "))
            .setStyle(style)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        nm.notify(SUMMARY_ID, summary)
    }

    @SuppressLint("MissingPermission")
    override fun postTestNudge() {
        if (!canPost()) return
        ensureChannels()
        val n = NotificationCompat.Builder(context, NotificationChannels.GENERAL)
            .setSmallIcon(R.drawable.ic_stat_nudge)
            .setContentTitle(context.getString(R.string.notif_test_title))
            .setContentText(context.getString(R.string.notif_test_text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openAppIntent())
            .build()
        nm.notify(TEST_ID, n)
    }

    private fun statusText(c: ReminderContent): String {
        val parts = mutableListOf<String>()
        if (c.task.priority != Priority.NONE) parts += context.getString(priorityLabel(c.task.priority))
        if (c.progress > 0) parts += "${c.progress}%"
        if (c.subtasksTotal > 0) parts += context.getString(R.string.notif_subtasks_fmt, c.subtasksDone, c.subtasksTotal)
        parts += context.getString(R.string.notif_reminder_n, c.count)
        return parts.joinToString(" · ")
    }

    private fun dueText(c: ReminderContent): String {
        val time = c.task.dueTime
        val dueLabel = if (time != null) {
            val instant = ZonedDateTime.of(c.task.dueDate, time, clock.zone()).toInstant()
            context.getString(R.string.notif_due_at, DateFormat.getTimeFormat(context).format(Date.from(instant)))
        } else {
            context.getString(R.string.notif_due_today)
        }
        return "$dueLabel · ${c.list.name}"
    }

    private fun durationLabel(minutes: Int): String = when {
        minutes % 60 == 0 -> context.getString(R.string.duration_hours, minutes / 60)
        else -> context.getString(R.string.duration_minutes, minutes)
    }

    private fun priorityLabel(p: Priority) = when (p) {
        Priority.URGENT -> R.string.priority_urgent
        Priority.HIGH -> R.string.priority_high
        Priority.MEDIUM -> R.string.priority_medium
        Priority.LOW -> R.string.priority_low
        Priority.NONE -> R.string.priority_none
    }

    private fun openTaskIntent(taskId: String): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$DEEP_LINK_TASK$taskId"))
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            notificationIdFor(taskId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openAppIntent(): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun actionIntent(taskId: String, action: String): PendingIntent {
        val base = notificationIdFor(taskId)
        val code = base * 31 + if (action == ACTION_DONE) 1 else 2
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_TASK_ID, taskId)
        return PendingIntent.getBroadcast(context, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val GROUP_KEY = "app.nudge.REMINDERS"
        const val ACTION_DONE = "app.nudge.action.DONE"
        const val ACTION_SNOOZE = "app.nudge.action.SNOOZE"
        const val EXTRA_TASK_ID = "task_id"
        const val DEEP_LINK_TASK = "nudge://task/"
        private const val NOTES_PREVIEW = 200
    }
}
