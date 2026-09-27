package app.nudge.core.reminders.di

import android.app.AlarmManager
import android.content.Context
import app.nudge.core.domain.reminder.ReminderDebugTools
import app.nudge.core.domain.reminder.ReminderHealthMonitor
import app.nudge.core.domain.reminder.ReminderScheduler
import app.nudge.core.reminders.alarm.AlarmScheduler
import app.nudge.core.reminders.alarm.AndroidAlarmScheduler
import app.nudge.core.reminders.engine.ReminderDebugToolsImpl
import app.nudge.core.reminders.engine.ReminderSchedulerImpl
import app.nudge.core.reminders.health.ReminderHealthChecker
import app.nudge.core.reminders.notification.AndroidNotificationPublisher
import app.nudge.core.reminders.notification.NotificationPublisher
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
interface RemindersModule {
    @Binds
    fun scheduler(impl: ReminderSchedulerImpl): ReminderScheduler

    @Binds
    fun alarmScheduler(impl: AndroidAlarmScheduler): AlarmScheduler

    @Binds
    fun publisher(impl: AndroidNotificationPublisher): NotificationPublisher

    @Binds
    fun health(impl: ReminderHealthChecker): ReminderHealthMonitor

    @Binds
    fun debugTools(impl: ReminderDebugToolsImpl): ReminderDebugTools

    companion object {
        @Provides
        fun alarmManager(@ApplicationContext context: Context): AlarmManager =
            context.getSystemService(AlarmManager::class.java)
    }
}
