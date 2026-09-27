package app.nudge.core.reminders.engine

import app.nudge.core.model.Priority
import app.nudge.core.model.ReminderKind
import app.nudge.core.model.ReminderSettings
import app.nudge.core.model.TaskDraft
import app.nudge.core.model.UserSettings
import app.nudge.core.testing.aTask
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** D1–D7 from 07 §12, driven through use cases, the real scheduler/dispatcher and in-memory fakes. */
class ReminderDispatcherTest {

    /** Clock starts at 2026-09-27 10:00 Asia/Kolkata. */
    private val today = LocalDate.of(2026, 9, 27)

    private fun TestScope.engine(settings: UserSettings = UserSettings()) = EngineHarness(backgroundScope, settings)

    private fun EngineHarness.at(h: Int, m: Int, s: Int = 0, date: LocalDate = today): Instant =
        clock.at(date, LocalTime.of(h, m, s))

    private suspend fun EngineHarness.newTask(title: String, priority: Priority, parentId: String? = null) =
        create(TaskDraft(listId = "l1", parentId = parentId, title = title, priority = priority))

    @Test
    fun `D1 two tasks due 30 s apart fire in one dispatch with a group summary`() = runTest {
        val e = engine()
        val a = e.newTask("A", Priority.URGENT)
        e.clock.advance(Duration.ofSeconds(30))
        val b = e.newTask("B", Priority.URGENT)

        assertThat(e.task(a.id).reminder.nextAt).isEqualTo(e.at(10, 10))
        assertThat(e.task(b.id).reminder.nextAt).isEqualTo(e.at(10, 10, 30))
        assertThat(e.alarms.armedAt).isEqualTo(e.at(10, 10))

        e.clock.now = e.alarms.armedAt!!
        val fired = e.dispatcher.dispatchDue()

        assertThat(fired).isEqualTo(2)
        assertThat(e.publisher.posted.map { it.task.id }).containsExactly(a.id, b.id)
        assertThat(e.publisher.active.keys).containsExactly(a.id, b.id)
        assertThat(e.publisher.summaryShown).isTrue()
        // Both restart from the actual fire time → the next alarm is the batched 10:20.
        assertThat(e.task(a.id).reminder.nextAt).isEqualTo(e.at(10, 20))
        assertThat(e.task(b.id).reminder.nextAt).isEqualTo(e.at(10, 20))
        assertThat(e.alarms.armedAt).isEqualTo(e.at(10, 20))
    }

    @Test
    fun `D1b tasks further apart than the tolerance are not batched`() = runTest {
        val e = engine()
        val a = e.newTask("A", Priority.URGENT)
        e.clock.advance(Duration.ofSeconds(90))
        e.newTask("B", Priority.URGENT)

        e.clock.now = e.alarms.armedAt!!
        assertThat(e.dispatcher.dispatchDue()).isEqualTo(1)
        assertThat(e.publisher.active.keys).containsExactly(a.id)
        assertThat(e.publisher.summaryShown).isFalse()
        assertThat(e.alarms.armedAt).isEqualTo(e.at(10, 11, 30))
    }

    @Test
    fun `D2 after dispatch the anchor is the fire time and the next interval follows it`() = runTest {
        val e = engine()
        val t = e.newTask("High", Priority.HIGH)
        assertThat(e.alarms.armedAt).isEqualTo(e.at(11, 0))

        e.runUntil(e.at(11, 0))

        val r = e.task(t.id).reminder
        assertThat(r.anchorAt).isEqualTo(e.at(11, 0))
        assertThat(r.count).isEqualTo(1)
        assertThat(r.lastRemindedAt).isEqualTo(e.at(11, 0))
        assertThat(r.nextAt).isEqualTo(e.at(12, 0))
        assertThat(r.nextKind).isEqualTo(ReminderKind.INTERVAL)
        assertThat(e.alarms.armedAt).isEqualTo(e.at(12, 0))
    }

    @Test
    fun `D2b a late dispatch restarts the interval from the actual fire time`() = runTest {
        val e = engine()
        val t = e.newTask("High", Priority.HIGH)

        e.clock.now = e.at(11, 7) // alarm delayed (Doze / inexact)
        e.dispatcher.dispatchDue()

        assertThat(e.task(t.id).reminder.anchorAt).isEqualTo(e.at(11, 7))
        assertThat(e.task(t.id).reminder.nextAt).isEqualTo(e.at(12, 7))
    }

    @Test
    fun `D2c the content carries count, list and subtask progress`() = runTest {
        val e = engine()
        val p = e.newTask("Parent", Priority.HIGH)
        val c1 = e.newTask("c1", Priority.NONE, parentId = p.id)
        e.newTask("c2", Priority.NONE, parentId = p.id)
        e.toggle(c1.id, completed = true)

        e.runUntil(e.at(11, 0))

        val content = e.publisher.active.getValue(p.id)
        assertThat(content.count).isEqualTo(1)
        assertThat(content.list.id).isEqualTo("l1")
        assertThat(content.subtasksDone).isEqualTo(1)
        assertThat(content.subtasksTotal).isEqualTo(2)
        assertThat(content.progress).isEqualTo(50)
        assertThat(content.kind).isEqualTo(ReminderKind.INTERVAL)
        assertThat(content.snoozeMinutes).isEqualTo(60)
        assertThat(content.postedAt).isEqualTo(e.at(11, 0))
    }

    @Test
    fun `D3 a DUE fire sets dueReminderFired and keeps the interval anchor`() = runTest {
        val e = engine()
        val due = e.create(
            TaskDraft(
                listId = "l1", parentId = null, title = "Pay",
                priority = Priority.HIGH, dueDate = today, dueTime = LocalTime.of(10, 30),
            ),
        )
        assertThat(e.task(due.id).reminder.nextKind).isEqualTo(ReminderKind.DUE)
        assertThat(e.alarms.armedAt).isEqualTo(e.at(10, 30))

        val posted = e.runUntil(e.at(10, 30))

        assertThat(posted.single().kind).isEqualTo(ReminderKind.DUE)
        val r = e.task(due.id).reminder
        assertThat(r.dueReminderFired).isTrue()
        assertThat(r.anchorAt).isEqualTo(e.at(10, 0))
        assertThat(r.nextAt).isEqualTo(e.at(11, 0))
        assertThat(r.nextKind).isEqualTo(ReminderKind.INTERVAL)
    }

    @Test
    fun `D3b a DUE-only task fires once and then has nothing scheduled`() = runTest {
        val e = engine()
        val due = e.create(
            TaskDraft(listId = "l1", parentId = null, title = "Call", dueDate = today, dueTime = LocalTime.of(12, 0)),
        )

        val posted = e.runUntil(e.at(23, 59))

        assertThat(posted.map { it.task.id }).containsExactly(due.id)
        assertThat(e.task(due.id).reminder.dueReminderFired).isTrue()
        assertThat(e.task(due.id).reminder.nextAt).isNull()
        assertThat(e.alarms.armedAt).isNull()
    }

    @Test
    fun `D4 Done completes the task and children and cancels their notifications`() = runTest {
        val e = engine()
        val parent = e.newTask("Parent", Priority.URGENT)
        val child = e.newTask("Child", Priority.URGENT, parentId = parent.id)
        e.runUntil(e.at(10, 10))
        assertThat(e.publisher.active.keys).containsExactly(parent.id, child.id)

        e.toggle(parent.id, completed = true)

        assertThat(e.task(parent.id).isCompleted).isTrue()
        assertThat(e.task(child.id).isCompleted).isTrue()
        assertThat(e.publisher.active).isEmpty()
        assertThat(e.publisher.summaryShown).isFalse()
        assertThat(e.task(parent.id).reminder.nextAt).isNull()
        assertThat(e.task(child.id).reminder.nextAt).isNull()
        assertThat(e.alarms.armedAt).isNull()
    }

    @Test
    fun `D5 snooze moves the next reminder to the snooze end and clears the notification`() = runTest {
        val e = engine()
        val t = e.newTask("Urgent", Priority.URGENT)
        e.runUntil(e.at(10, 10))
        assertThat(e.publisher.active.keys).containsExactly(t.id)

        val until = e.clock.now.plus(Duration.ofMinutes(60))
        e.snooze(t.id, until)

        assertThat(e.publisher.active).isEmpty()
        assertThat(e.task(t.id).reminder.nextAt).isEqualTo(e.at(11, 10))
        assertThat(e.alarms.armedAt).isEqualTo(e.at(11, 10))
        // Nothing fires during the snooze.
        assertThat(e.runUntil(e.at(11, 9))).isEmpty()

        val posted = e.runUntil(e.at(11, 10))

        assertThat(posted.map { it.task.id }).containsExactly(t.id)
        val r = e.task(t.id).reminder
        assertThat(r.snoozedUntil).isNull()
        assertThat(r.anchorAt).isEqualTo(e.at(11, 10))
        assertThat(r.nextAt).isEqualTo(e.at(11, 20))
    }

    @Test
    fun `D5b cancelling a snooze restarts the interval from now`() = runTest {
        val e = engine()
        val t = e.newTask("Urgent", Priority.URGENT)
        e.snooze(t.id, e.at(12, 0))
        e.clock.now = e.at(10, 30)

        e.snooze(t.id, null)

        assertThat(e.task(t.id).reminder.nextAt).isEqualTo(e.at(10, 40))
        assertThat(e.alarms.armedAt).isEqualTo(e.at(10, 40))
    }

    @Test
    fun `D6 rescheduleAll after boot arms the earliest and clears stale state`() = runTest {
        val e = engine()
        // Rows as they are after a reboot: no alarm, next times not yet computed.
        e.tasks.seed(
            aTask { id = "high"; priority = Priority.HIGH; anchorAt = e.at(9, 30) },
            aTask { id = "urgent"; priority = Priority.URGENT; anchorAt = e.at(9, 55) },
            aTask { id = "none"; anchorAt = e.at(9, 0) },
        )
        val stale = aTask { id = "done"; isCompleted = true; priority = Priority.URGENT }
        e.tasks.seed(stale.copy(reminder = stale.reminder.copy(nextAt = e.at(10, 5), nextKind = ReminderKind.INTERVAL)))

        e.scheduler.rescheduleAll()

        assertThat(e.publisher.channelsEnsured).isEqualTo(1)
        assertThat(e.task("urgent").reminder.nextAt).isEqualTo(e.at(10, 5))
        assertThat(e.task("high").reminder.nextAt).isEqualTo(e.at(10, 30))
        assertThat(e.task("none").reminder.nextAt).isNull()
        assertThat(e.task("done").reminder.nextAt).isNull()
        assertThat(e.publisher.cancelled).contains("done")
        assertThat(e.alarms.armedAt).isEqualTo(e.at(10, 5))
    }

    @Test
    fun `D6b missed reminders catch up once, never in the past`() = runTest {
        val e = engine()
        e.tasks.seed(aTask { id = "urgent"; priority = Priority.URGENT; anchorAt = e.at(8, 0) })

        e.scheduler.rescheduleAll()

        assertThat(e.task("urgent").reminder.nextAt).isEqualTo(e.at(10, 0))
        assertThat(e.alarms.armedAt).isEqualTo(e.at(10, 0, 1))
        val posted = e.runUntil(e.at(10, 5))
        assertThat(posted).hasSize(1)
        assertThat(e.task("urgent").reminder.nextAt).isEqualTo(e.at(10, 10, 1))
    }

    @Test
    fun `D6c rescheduleAll with resetAnchors restarts every interval from now`() = runTest {
        val e = engine()
        e.tasks.seed(aTask { id = "high"; priority = Priority.HIGH; anchorAt = e.at(9, 30); reminderCount = 4 })
        e.clock.now = e.at(10, 15)

        e.scheduler.rescheduleAll(resetAnchors = true)

        val r = e.task("high").reminder
        assertThat(r.anchorAt).isEqualTo(e.at(10, 15))
        assertThat(r.count).isEqualTo(0)
        assertThat(r.nextAt).isEqualTo(e.at(11, 15))
    }

    @Test
    fun `D7 the alarm is cancelled when no tasks remain`() = runTest {
        val e = engine()
        val t = e.newTask("Urgent", Priority.URGENT)
        assertThat(e.alarms.armedAt).isNotNull()

        e.delete(t.id)

        assertThat(e.alarms.armedAt).isNull()
        assertThat(e.alarms.cancelCalls).isAtLeast(1)
        assertThat(e.task(t.id).reminder.nextAt).isNull()
        assertThat(e.runUntil(e.at(12, 0))).isEmpty()
    }

    @Test
    fun `D7b dispatch with nothing due cancels the alarm`() = runTest {
        val e = engine()
        assertThat(e.dispatcher.dispatchDue()).isEqualTo(0)
        assertThat(e.alarms.armedAt).isNull()
    }

    @Test
    fun `global pause until resumed silences everything`() = runTest {
        val e = engine()
        val t = e.newTask("Urgent", Priority.URGENT)
        e.runUntil(e.at(10, 10))
        e.settings.update { it.copy(reminders = it.reminders.copy(pausedUntil = Instant.MAX)) }

        e.scheduler.rescheduleAll()

        assertThat(e.publisher.cancelAllCalls).isEqualTo(1)
        assertThat(e.publisher.active).isEmpty()
        assertThat(e.task(t.id).reminder.nextAt).isNull()
        assertThat(e.alarms.armedAt).isNull()
    }

    @Test
    fun `timed pause holds reminders until it ends`() = runTest {
        val e = engine(
            UserSettings(reminders = ReminderSettings(pausedUntil = Instant.parse("2026-09-27T06:30:00Z"))),
        ) // paused until 12:00 IST
        val t = e.newTask("Urgent", Priority.URGENT)
        assertThat(e.task(t.id).reminder.nextAt).isEqualTo(e.at(12, 0))

        val posted = e.runUntil(e.at(12, 5))

        assertThat(posted.map { it.postedAt }).containsExactly(e.at(12, 0))
    }

    @Test
    fun `dispatch while paused posts nothing`() = runTest {
        val e = engine()
        e.newTask("Urgent", Priority.URGENT)
        e.settings.update {
            it.copy(reminders = it.reminders.copy(pausedUntil = e.at(13, 0)))
        }
        e.clock.now = e.at(10, 10)
        assertThat(e.dispatcher.dispatchDue()).isEqualTo(0)
        assertThat(e.publisher.posted).isEmpty()
    }
}
