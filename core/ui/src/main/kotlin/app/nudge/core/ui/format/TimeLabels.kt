package app.nudge.core.ui.format

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.nudge.core.ui.R
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import java.util.Locale

/** The current locale, observed from the configuration so formatting updates on locale change. */
@Composable
@ReadOnlyComposable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

/** Time in the user's 12/24 h preference (NFR-07). */
@Composable
@ReadOnlyComposable
fun formatTime(time: LocalTime): String {
    val ctx = LocalContext.current
    val pattern = if (DateFormat.is24HourFormat(ctx)) "HH:mm" else "h:mm a"
    return DateTimeFormatter.ofPattern(pattern, currentLocale()).format(time)
}

/** "Today", "Tomorrow", "Yesterday", weekday within a week, else "12 Oct". */
@Composable
@ReadOnlyComposable
fun formatDay(date: LocalDate, today: LocalDate): String = when (date) {
    today -> stringResource(R.string.day_today)
    today.plusDays(1) -> stringResource(R.string.day_tomorrow)
    today.minusDays(1) -> stringResource(R.string.day_yesterday)
    else -> if (date.isAfter(today) && date.isBefore(today.plusDays(7))) {
        DateTimeFormatter.ofPattern("EEE", currentLocale()).format(date)
    } else if (date.year == today.year) {
        DateTimeFormatter.ofPattern("d MMM", currentLocale()).format(date)
    } else {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(currentLocale()).format(date)
    }
}

/** Due chip label: "Today 5 PM" / "Tomorrow". */
@Composable
@ReadOnlyComposable
fun formatDue(date: LocalDate, time: LocalTime?, today: LocalDate): String =
    if (time == null) formatDay(date, today) else "${formatDay(date, today)} ${formatTime(time)}"

/** "in 7 min", "in 2 h 5 min", "in 3 days". */
@Composable
@ReadOnlyComposable
fun formatRelative(from: Instant, to: Instant): String {
    val d = Duration.between(from, to)
    if (d.isNegative || d.toMinutes() < 1) return stringResource(R.string.relative_now)
    val minutes = d.toMinutes()
    return when {
        minutes < 60 -> stringResource(R.string.relative_in_min, minutes)
        minutes < 24 * 60 -> {
            val m = minutes % 60
            if (m == 0L) stringResource(R.string.relative_in_h, minutes / 60) else stringResource(R.string.relative_in_h_min, minutes / 60, m)
        }
        else -> stringResource(R.string.relative_in_days, d.toDays())
    }
}

/** Day + time for an instant: "Today 3:00 PM", "Mon 8:00 AM". */
@Composable
@ReadOnlyComposable
fun formatInstant(instant: Instant, zone: ZoneId, today: LocalDate): String {
    val z = instant.atZone(zone)
    return formatDue(z.toLocalDate(), z.toLocalTime(), today)
}
