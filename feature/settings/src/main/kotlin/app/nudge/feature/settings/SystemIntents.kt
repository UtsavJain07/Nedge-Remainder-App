package app.nudge.feature.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** System settings deep links used by Settings and Reminder health (07 §6). */
internal object SystemIntents {
    fun appNotificationSettings(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    /** API 31+ only; callers gate on [Build.VERSION_CODES.S]. */
    fun exactAlarmSettings(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))

    /** The battery-optimization list screen — never the direct request intent (Play policy, 07 §6). */
    fun batteryOptimizationSettings(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    fun dontKillMyApp(vendor: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://dontkillmyapp.com/$vendor"))

    /** Starts [intent], falling back to the app's details screen when the target activity is missing (some OEMs). */
    fun launch(context: Context, intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }
    }

    val supportsExactAlarmSettings: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
}
