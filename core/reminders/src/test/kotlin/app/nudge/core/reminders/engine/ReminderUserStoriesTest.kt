package app.nudge.core.reminders.engine

import app.nudge.core.model.Priority
import app.nudge.core.model.ReminderCadence
import app.nudge.core.model.ReminderKind
import app.nudge.core.model.TaskDraft
import app.nudge.core.model.TaskPatch
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

/** End-to-end engine scenarios from 02 §8, driven only by the TestClock and the single alarm. */
class ReminderUserStoriesTest {

    private val today = LocalDate.of(2026, 9, 27)

    @Test
    fun `US-2 urgent nags every 10 minutes until Done`() = runTest {
        val e = EngineHarness(backgroundScope)
        e.clock.now = e.clock.at(today, LocalTime.of(10, 0))
        val task = e.create(
            TaskDraft(listId = "l1", parentId = null, title = "Fix prod bug", priority = Priority.URGENT),
        )

        // Nothing before 10:10.
        assertThat(e.runUntil(e.clock.at(today, LocalTime.of(10, 9, 59)))).isEmpty()

        val first = e.runUntil(e.clock.at(today, LocalTime.of(10, 10)))
        assertThat(first).hasSize(1)
        assertThat(first.single().task.title).isEqualTo("Fix prod bug")
        assertThat(first.single().postedAt).isEqualTo(e.clock.at(today, LocalTime.of(10, 10)))
        assertThat(first.single().count).isEqualTo(1)

        val second = e.runUntil(e.clock.at(today, LocalTime.of(10, 20)))
        assertThat(second).hasSize(1)
        assertThat(second.single().count).isEqualTo(2) // "Reminder #2"
        assertThat(second.single().postedAt).isEqualTo(e.clock.at(today, LocalTime.of(10, 20)))
        // Same task id → replaces the previous notification, doesn't stack.
        assertThat(e.publisher.active.keys).containsExactly(task.id)

        e.clock.advance(Duration.ofMinutes(2))
        e.toggle(task.id, completed = true)

        assertThat(e.task(task.id).isCompleted).isTrue()
        assertThat(e.publisher.active).isEmpty()
        assertThat(e.alarms.armedAt).isNull()
        assertThat(e.runUntil(e.clock.at(today.plusDays(1), LocalTime.of(12, 0)))).isEmpty()
    }

    @Test
    fun `US-7 high priority is held by quiet hours and released once at 08 00`() = runTest {
        val e = EngineHarness(backgroundScope)
        e.clock.now = e.clock.at(today, LocalTime.of(21, 30))
        val task = e.create(TaskDraft(listId = "l1", parentId = null, title = "Pay rent", priority = Priority.HIGH))

        // Next would be 22:30, inside quiet hours → deferred to 08:00 tomorrow.
        val tomorrow = today.plusDays(1)
        assertThat(e.task(task.id).reminder.nextAt).isEqualTo(e.clock.at(tomorrow, LocalTime.of(8, 0)))

        assertThat(e.runUntil(e.clock.at(tomorrow, LocalTime.of(7, 59, 59)))).isEmpty()

        val atEight = e.runUntil(e.clock.at(tomorrow, LocalTime.of(8, 30)))
        assertThat(atEight.map { it.postedAt }).containsExactly(e.clock.at(tomorrow, LocalTime.of(8, 0)))
        assertThat(e.task(task.id).reminder.nextAt).isEqualTo(e.clock.at(tomorrow, LocalTime.of(9, 0)))

        val atNine = e.runUntil(e.clock.at(tomorrow, LocalTime.of(9, 30)))
        assertThat(atNine.map { it.postedAt }).containsExactly(e.clock.at(tomorrow, LocalTime.of(9, 0)))
    }

    @Test
    fun `US-3 low priority fires at the morning and evening check-ins`() = runTest {
        val e = EngineHarness(backgroundScope)
        e.clock.now = e.clock.at(today, LocalTime.of(10, 0))
        e.create(TaskDraft(listId = "l1", parentId = null, title = "Water plants", priority = Priority.LOW))

        val posted = e.runUntil(e.clock.at(today.plusDays(1), LocalTime.of(22, 0)))

        assertThat(posted.map { it.postedAt }).containsExactly(
            e.clock.at(today, LocalTime.of(21, 0)),
            e.clock.at(today.plusDays(1), LocalTime.of(8, 0)),
            e.clock.at(today.plusDays(1), LocalTime.of(21, 0)),
        ).inOrder()
        assertThat(posted.map { it.kind }.toSet()).containsExactly(ReminderKind.FIXED_TIME)
    }

    @Test
    fun `changing priority restarts the schedule from now`() = runTest {
        val e = EngineHarness(backgroundScope)
        e.clock.now = e.clock.at(today, LocalTime.of(10, 0))
        val task = e.create(TaskDraft(listId = "l1", parentId = null, title = "x", priority = Priority.HIGH))
        e.runUntil(e.clock.at(today, LocalTime.of(10, 25)))

        e.update(task.id, TaskPatch(priority = Priority.URGENT))

        assertThat(e.task(task.id).reminder.nextAt).isEqualTo(e.clock.at(today, LocalTime.of(10, 35)))
        assertThat(e.alarms.armedAt).isEqualTo(e.clock.at(today, LocalTime.of(10, 35)))
    }

    @Test
    fun `cadence override Off stops interval reminders and clears the notification`() = runTest {
        val e = EngineHarness(backgroundScope)
        e.clock.now = e.clock.at(today, LocalTime.of(10, 0))
        val task = e.create(TaskDraft(listId = "l1", parentId = null, title = "x", priority = Priority.URGENT))
        e.runUntil(e.clock.at(today, LocalTime.of(10, 10)))
        assertThat(e.publisher.active).isNotEmpty()

        e.update(task.id, TaskPatch(cadenceOverride = app.nudge.core.model.Patch.Set(ReminderCadence.OFF)))

        assertThat(e.publisher.active).isEmpty()
        assertThat(e.task(task.id).reminder.nextAt).isNull()
        assertThat(e.alarms.armedAt).isNull()
    }
}
