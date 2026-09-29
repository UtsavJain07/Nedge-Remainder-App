package app.nudge.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.nudge.core.common.minuteOfDay
import app.nudge.core.common.minuteOfDayToLocalTime
import app.nudge.core.common.toEpochMilliOrNull
import app.nudge.core.common.toInstantOrNull
import app.nudge.core.model.InsertPosition
import app.nudge.core.model.Priority
import app.nudge.core.model.ReminderCadence
import app.nudge.core.model.ReminderSettings
import app.nudge.core.model.ThemeMode
import app.nudge.core.model.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Maps [UserSettings] to Preferences DataStore keys (06 §7). */
@Singleton
class SettingsDataSource @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<UserSettings> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toSettings() }
        .distinctUntilChanged()

    suspend fun update(transform: (UserSettings) -> UserSettings) {
        dataStore.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs.write(next)
        }
    }

    suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    internal object Keys {
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val theme = stringPreferencesKey("theme")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val pureBlack = booleanPreferencesKey("pure_black")
        val newTaskPosition = stringPreferencesKey("new_task_position")
        val defaultPriority = intPreferencesKey("default_priority")
        val haptics = booleanPreferencesKey("haptics")
        val confirmDelete = booleanPreferencesKey("confirm_delete")
        val lastUsedListId = stringPreferencesKey("last_used_list_id")
        val cadence: Map<Priority, Preferences.Key<String>> = Priority.entries.associateWith {
            stringPreferencesKey("cadence_${it.name.lowercase()}")
        }
        val morningMinute = intPreferencesKey("morning_minute")
        val eveningMinute = intPreferencesKey("evening_minute")
        val quietEnabled = booleanPreferencesKey("quiet_enabled")
        val quietStartMinute = intPreferencesKey("quiet_start_minute")
        val quietEndMinute = intPreferencesKey("quiet_end_minute")
        val urgentIgnoresQuiet = booleanPreferencesKey("urgent_ignores_quiet")
        val defaultSnoozeMinutes = intPreferencesKey("default_snooze_minutes")
        val pausedUntil = longPreferencesKey("paused_until")
    }

    private inline fun <reified T : Enum<T>> String?.toEnum(default: T): T =
        enumValues<T>().firstOrNull { it.name == this } ?: default

    private fun Preferences.toSettings(): UserSettings {
        val d = UserSettings()
        val r = d.reminders
        return UserSettings(
            onboardingDone = this[Keys.onboardingDone] ?: d.onboardingDone,
            theme = this[Keys.theme].toEnum(d.theme),
            dynamicColor = this[Keys.dynamicColor] ?: d.dynamicColor,
            pureBlack = this[Keys.pureBlack] ?: d.pureBlack,
            newTaskPosition = this[Keys.newTaskPosition].toEnum(InsertPosition.TOP),
            defaultPriority = this[Keys.defaultPriority]?.let(Priority::fromLevel) ?: d.defaultPriority,
            haptics = this[Keys.haptics] ?: d.haptics,
            confirmDelete = this[Keys.confirmDelete] ?: d.confirmDelete,
            lastUsedListId = this[Keys.lastUsedListId],
            reminders = ReminderSettings(
                defaultCadence = Priority.entries.associateWith { p ->
                    ReminderCadence.fromName(this[Keys.cadence.getValue(p)]) ?: r.cadenceFor(p)
                },
                morningTime = this[Keys.morningMinute]?.let(::minuteOfDayToLocalTime) ?: r.morningTime,
                eveningTime = this[Keys.eveningMinute]?.let(::minuteOfDayToLocalTime) ?: r.eveningTime,
                quietHoursEnabled = this[Keys.quietEnabled] ?: r.quietHoursEnabled,
                quietStart = this[Keys.quietStartMinute]?.let(::minuteOfDayToLocalTime) ?: r.quietStart,
                quietEnd = this[Keys.quietEndMinute]?.let(::minuteOfDayToLocalTime) ?: r.quietEnd,
                urgentIgnoresQuietHours = this[Keys.urgentIgnoresQuiet] ?: r.urgentIgnoresQuietHours,
                defaultSnoozeMinutes = this[Keys.defaultSnoozeMinutes] ?: r.defaultSnoozeMinutes,
                pausedUntil = this[Keys.pausedUntil].toInstantOrNull(),
            ),
        )
    }

    private fun MutablePreferences.write(s: UserSettings) {
        this[Keys.onboardingDone] = s.onboardingDone
        this[Keys.theme] = s.theme.name
        this[Keys.dynamicColor] = s.dynamicColor
        this[Keys.pureBlack] = s.pureBlack
        this[Keys.newTaskPosition] = s.newTaskPosition.name
        this[Keys.defaultPriority] = s.defaultPriority.level
        this[Keys.haptics] = s.haptics
        this[Keys.confirmDelete] = s.confirmDelete
        if (s.lastUsedListId != null) this[Keys.lastUsedListId] = s.lastUsedListId!! else remove(Keys.lastUsedListId)
        val r = s.reminders
        Priority.entries.forEach { p -> this[Keys.cadence.getValue(p)] = r.cadenceFor(p).name }
        this[Keys.morningMinute] = r.morningTime.minuteOfDay()
        this[Keys.eveningMinute] = r.eveningTime.minuteOfDay()
        this[Keys.quietEnabled] = r.quietHoursEnabled
        this[Keys.quietStartMinute] = r.quietStart.minuteOfDay()
        this[Keys.quietEndMinute] = r.quietEnd.minuteOfDay()
        this[Keys.urgentIgnoresQuiet] = r.urgentIgnoresQuietHours
        this[Keys.defaultSnoozeMinutes] = r.defaultSnoozeMinutes
        val paused = r.pausedUntil.toEpochMilliOrNull()
        if (paused != null) this[Keys.pausedUntil] = paused else remove(Keys.pausedUntil)
    }
}
