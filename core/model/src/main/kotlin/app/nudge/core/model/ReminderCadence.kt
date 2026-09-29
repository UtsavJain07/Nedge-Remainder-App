package app.nudge.core.model

/**
 * How often reminders repeat (FR-61, FR-62). Persisted by [name].
 */
enum class ReminderCadence(val intervalMinutes: Int?) {
    EVERY_10_MIN(10),
    EVERY_30_MIN(30),
    EVERY_1_H(60),
    EVERY_2_H(120),
    EVERY_3_H(180),
    EVERY_5_H(300),
    TWICE_DAILY(null),
    OFF(null),
    ;

    val isInterval: Boolean get() = intervalMinutes != null

    companion object {
        fun fromName(name: String?): ReminderCadence? = entries.firstOrNull { it.name == name }
    }
}

/** Why a task's next reminder time was chosen (07 §3). */
enum class ReminderKind { INTERVAL, FIXED_TIME, DUE }

enum class ListSortMode { MY_ORDER, PRIORITY, DUE_DATE }

enum class InsertPosition { TOP, BOTTOM }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class SmartViewType { TODAY, ALL }
