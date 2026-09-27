package app.nudge.core.data.settings

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import app.nudge.core.data.DataTestBase
import app.nudge.core.datastore.SettingsDataSource
import app.nudge.core.model.InsertPosition
import app.nudge.core.model.Priority
import app.nudge.core.model.ReminderCadence
import app.nudge.core.model.ReminderSettings
import app.nudge.core.model.TapAction
import app.nudge.core.model.ThemeMode
import app.nudge.core.model.UserSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import java.time.LocalTime

class SettingsDataSourceTest : DataTestBase() {

    private val custom = UserSettings(
        onboardingDone = true,
        theme = ThemeMode.DARK,
        dynamicColor = true,
        pureBlack = true,
        tapAction = TapAction.OPEN_DETAILS,
        newTaskPosition = InsertPosition.BOTTOM,
        defaultPriority = Priority.HIGH,
        haptics = false,
        confirmDelete = true,
        lastUsedListId = "list-42",
        reminders = ReminderSettings(
            defaultCadence = mapOf(
                Priority.URGENT to ReminderCadence.EVERY_30_MIN,
                Priority.HIGH to ReminderCadence.EVERY_2_H,
                Priority.MEDIUM to ReminderCadence.EVERY_5_H,
                Priority.LOW to ReminderCadence.OFF,
                Priority.NONE to ReminderCadence.TWICE_DAILY,
            ),
            morningTime = LocalTime.of(7, 15),
            eveningTime = LocalTime.of(20, 45),
            quietHoursEnabled = false,
            quietStart = LocalTime.of(23, 30),
            quietEnd = LocalTime.of(6, 5),
            urgentIgnoresQuietHours = true,
            defaultSnoozeMinutes = 15,
            pausedUntil = Instant.parse("2026-09-27T10:15:30.123Z"),
        ),
    )

    @Test
    fun `empty store yields defaults`() = runTest {
        assertThat(settings.settings.first()).isEqualTo(UserSettings())
    }

    @Test
    fun `every field round-trips`() = runTest {
        settings.update { custom }
        assertThat(settings.settings.first()).isEqualTo(custom)
    }

    @Test
    fun `values survive a new data source on the same file`() = runTest {
        settings.update { custom }
        assertThat(SettingsDataSource(dataStore).settings.first()).isEqualTo(custom)
    }

    @Test
    fun `defaults are written with documented keys and values`() = runTest {
        settings.update { it }
        val prefs = dataStore.data.first()
        assertThat(prefs[intPreferencesKey("morning_minute")]).isEqualTo(480)
        assertThat(prefs[intPreferencesKey("evening_minute")]).isEqualTo(1260)
        assertThat(prefs[intPreferencesKey("quiet_start_minute")]).isEqualTo(1320)
        assertThat(prefs[intPreferencesKey("quiet_end_minute")]).isEqualTo(480)
        assertThat(prefs[stringPreferencesKey("cadence_urgent")]).isEqualTo("EVERY_10_MIN")
        assertThat(prefs[stringPreferencesKey("cadence_none")]).isEqualTo("OFF")
        assertThat(prefs[stringPreferencesKey("theme")]).isEqualTo("SYSTEM")
        assertThat(prefs[intPreferencesKey("default_priority")]).isEqualTo(0)
        assertThat(prefs[longPreferencesKey("paused_until")]).isNull()
    }

    @Test
    fun `pause until resumed is stored as Long MAX_VALUE and read back as Instant MAX`() = runTest {
        settings.update { it.copy(reminders = it.reminders.copy(pausedUntil = Instant.MAX)) }

        assertThat(dataStore.data.first()[longPreferencesKey("paused_until")]).isEqualTo(Long.MAX_VALUE)
        assertThat(settings.settings.first().reminders.pausedUntil).isEqualTo(Instant.MAX)
    }

    @Test
    fun `clearing nullable fields removes their keys`() = runTest {
        settings.update { custom }
        settings.update { it.copy(lastUsedListId = null, reminders = it.reminders.copy(pausedUntil = null)) }

        val prefs = dataStore.data.first()
        assertThat(prefs.contains(longPreferencesKey("paused_until"))).isFalse()
        assertThat(prefs.contains(stringPreferencesKey("last_used_list_id"))).isFalse()
        val s = settings.settings.first()
        assertThat(s.reminders.pausedUntil).isNull()
        assertThat(s.lastUsedListId).isNull()
        assertThat(s.theme).isEqualTo(ThemeMode.DARK)
    }

    @Test
    fun `corrupt enum values fall back to defaults`() = runTest {
        dataStore.edit {
            it[stringPreferencesKey("theme")] = "NEON"
            it[stringPreferencesKey("cadence_high")] = "EVERY_7_MIN"
            it[intPreferencesKey("default_priority")] = 99
        }
        val s = settings.settings.first()
        assertThat(s.theme).isEqualTo(ThemeMode.SYSTEM)
        assertThat(s.reminders.cadenceFor(Priority.HIGH)).isEqualTo(ReminderCadence.EVERY_1_H)
        assertThat(s.defaultPriority).isEqualTo(Priority.NONE)
    }

    @Test
    fun `clear resets to defaults`() = runTest {
        settings.update { custom }
        settings.clear()
        assertThat(settings.settings.first()).isEqualTo(UserSettings())
    }

    @Test
    fun `flow emits only on change`() = runTest {
        settings.settings.test {
            assertThat(awaitItem()).isEqualTo(UserSettings())
            settings.update { it.copy(haptics = false) }
            assertThat(awaitItem().haptics).isFalse()
            settings.update { it } // no-op write
            settings.update { it.copy(confirmDelete = true) }
            assertThat(awaitItem().confirmDelete).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
