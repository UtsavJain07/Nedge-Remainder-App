package app.nudge.core.reminders.health

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.nudge.core.common.Clock
import app.nudge.core.domain.reminder.ReminderHealth
import app.nudge.core.domain.reminder.ReminderHealthMonitor
import app.nudge.core.reminders.alarm.AlarmScheduler
import app.nudge.core.reminders.notification.NotificationChannels
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Checks notifications, channels, exact alarms and battery optimization (07 §10, FR-91). */
@Singleton
class ReminderHealthChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alarms: AlarmScheduler,
    private val clock: Clock,
) : ReminderHealthMonitor {

    private val _health = MutableStateFlow(ReminderHealth.HEALTHY)
    override val health: StateFlow<ReminderHealth> = _health.asStateFlow()

    override fun refresh() {
        _health.value = check()
    }

    override fun sendTestNudge(delaySeconds: Long) = alarms.setTest(clock.now().plusSeconds(delaySeconds))

    fun check(): ReminderHealth {
        val nm = NotificationManagerCompat.from(context)
        val permission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val blocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val sys = context.getSystemService(NotificationManager::class.java)
            NotificationChannels.reminderChannels.filter { id ->
                sys?.getNotificationChannel(id)?.importance == NotificationManager.IMPORTANCE_NONE
            }
        } else {
            emptyList()
        }
        val power = context.getSystemService(PowerManager::class.java)
        return ReminderHealth(
            notificationsEnabled = permission && nm.areNotificationsEnabled(),
            blockedChannels = blocked,
            exactAlarmsAllowed = alarms.canExact(),
            ignoringBatteryOptimizations = power?.isIgnoringBatteryOptimizations(context.packageName) ?: true,
            oemVendor = oemVendor(Build.MANUFACTURER),
        )
    }

    companion object {
        private val OEMS = mapOf(
            "xiaomi" to "xiaomi", "redmi" to "xiaomi", "poco" to "xiaomi",
            "oppo" to "oppo", "realme" to "realme", "vivo" to "vivo", "oneplus" to "oneplus",
            "samsung" to "samsung", "huawei" to "huawei", "honor" to "honor",
        )

        /** dontkillmyapp.com vendor slug for aggressive OEMs, or null. */
        fun oemVendor(manufacturer: String?): String? = OEMS[manufacturer?.lowercase()?.trim()]
    }
}
