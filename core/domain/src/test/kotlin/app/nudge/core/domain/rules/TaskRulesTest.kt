package app.nudge.core.domain.rules

import app.nudge.core.domain.rules.TaskRules.resetAnchor
import app.nudge.core.model.Patch
import app.nudge.core.model.Priority
import app.nudge.core.model.ReminderCadence
import app.nudge.core.model.Task
import app.nudge.core.model.TaskDraft
import app.nudge.core.model.TaskPatch
import app.nudge.core.testing.T0
import app.nudge.core.testing.aTask
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class TaskRulesTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val morning = LocalTime.of(8, 0)

    /** T0 is 10:00 IST on 2026-09-27. */
    private val today = LocalDate.of(2026, 9, 27)
    private val now: Instant = T0.plus(Duration.ofHours(2)) // 12:00 IST

    /** A task that has been reminded a few times since T0. */
    private fun reminded(block: app.nudge.core.testing.TaskBuilder.() -> Unit = {}): Task {
        val t = aTask {
            priority = Priority.HIGH
            reminderCount = 3
            block()
        }
        return t.copy(reminder = t.reminder.copy(lastRemindedAt = T0.plusSeconds(3600)))
    }

    private fun patch(task: Task, patch: TaskPatch, hasChildren: Boolean = false) =
        TaskRules.applyPatch(task, patch, hasChildren, now, zone, morning)

    // --- validation ---

    @Test
    fun `title is trimmed`() {
        assertThat(TaskRules.validateTitle("  Buy milk \n")).isEqualTo("Buy milk")
    }

    @Test
    fun `blank title is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { TaskRules.validateTitle("   ") }
        assertThrows(IllegalArgumentException::class.java) { TaskRules.validateTitle("") }
    }

    @Test
    fun `title of 200 chars is accepted, 201 rejected`() {
        assertThat(TaskRules.validateTitle("a".repeat(200))).hasLength(200)
        assertThat(TaskRules.validateTitle(" " + "a".repeat(200) + " ")).hasLength(200)
        assertThrows(IllegalArgumentException::class.java) { TaskRules.validateTitle("a".repeat(201)) }
    }

    @Test
    fun `list name trimmed, blank and over 40 chars rejected`() {
        assertThat(TaskRules.validateListName("  Work ")).isEqualTo("Work")
        assertThat(TaskRules.validateListName("w".repeat(40))).hasLength(40)
        assertThrows(IllegalArgumentException::class.java) { TaskRules.validateListName(" ") }
        assertThrows(IllegalArgumentException::class.java) { TaskRules.validateListName("w".repeat(41)) }
    }

    @Test
    fun `notes are clamped to 2000 chars`() {
        assertThat(TaskRules.clampNotes("n".repeat(2500))).hasLength(Task.NOTES_MAX)
        assertThat(TaskRules.clampNotes("short")).isEqualTo("short")
    }

    // --- newTask ---

    @Test
    fun `new task has fresh reminder anchored at now`() {
        val t = TaskRules.newTask(
            "id",
            TaskDraft(listId = "l1", parentId = null, title = " Hi ", priority = Priority.URGENT),
            sortOrder = 5.0,
            now = now,
            zone = zone,
            morning = morning,
        )
        assertThat(t.title).isEqualTo("Hi")
        assertThat(t.reminder.anchorAt).isEqualTo(now)
        assertThat(t.reminder.count).isEqualTo(0)
        assertThat(t.reminder.dueReminderFired).isFalse()
        assertThat(t.createdAt).isEqualTo(now)
        assertThat(t.updatedAt).isEqualTo(now)
        assertThat(t.sortOrder).isEqualTo(5.0)
        assertThat(t.isExpanded).isTrue()
        assertThat(t.progress).isEqualTo(0)
    }

    @Test
    fun `new task with past due date marks the due reminder as fired`() {
        val t = TaskRules.newTask(
            "id",
            TaskDraft(listId = "l1", parentId = null, title = "x", dueDate = today, dueTime = LocalTime.of(9, 0)),
            0.0, now, zone, morning,
        )
        assertThat(t.reminder.dueReminderFired).isTrue()
    }

    @Test
    fun `new task drops due time without a due date`() {
        val t = TaskRules.newTask(
            "id",
            TaskDraft(listId = "l1", parentId = null, title = "x", dueTime = LocalTime.of(9, 0)),
            0.0, now, zone, morning,
        )
        assertThat(t.dueTime).isNull()
    }

    // --- anchor reset (07 §3.1) ---

    @Test
    fun `priority change resets anchor, count and lastRemindedAt`() {
        val t = reminded()
        val updated = patch(t, TaskPatch(priority = Priority.URGENT))

        assertThat(updated.priority).isEqualTo(Priority.URGENT)
        assertThat(updated.reminder.anchorAt).isEqualTo(now)
        assertThat(updated.reminder.count).isEqualTo(0)
        assertThat(updated.reminder.lastRemindedAt).isNull()
        assertThat(updated.updatedAt).isEqualTo(now)
    }

    @Test
    fun `cadence override change resets anchor`() {
        val t = reminded()
        val set = patch(t, TaskPatch(cadenceOverride = Patch.Set(ReminderCadence.EVERY_2_H)))
        assertThat(set.cadenceOverride).isEqualTo(ReminderCadence.EVERY_2_H)
        assertThat(set.reminder.anchorAt).isEqualTo(now)
        assertThat(set.reminder.count).isEqualTo(0)

        val cleared = patch(
            reminded { cadenceOverride = ReminderCadence.OFF },
            TaskPatch(cadenceOverride = Patch.Set(null)),
        )
        assertThat(cleared.cadenceOverride).isNull()
        assertThat(cleared.reminder.anchorAt).isEqualTo(now)
    }

    @Test
    fun `setting the same priority does not reset anchor or bump updatedAt`() {
        val t = reminded()
        val updated = patch(t, TaskPatch(priority = Priority.HIGH))
        assertThat(updated).isEqualTo(t)
    }

    @Test
    fun `title, notes and progress edits do not reset anchor`() {
        val t = reminded()
        val updated = patch(t, TaskPatch(title = "New title", notes = "Some notes", progress = 40))

        assertThat(updated.title).isEqualTo("New title")
        assertThat(updated.notes).isEqualTo("Some notes")
        assertThat(updated.progress).isEqualTo(40)
        assertThat(updated.reminder).isEqualTo(t.reminder)
        assertThat(updated.updatedAt).isEqualTo(now)
    }

    @Test
    fun `patch validates title`() {
        assertThrows(IllegalArgumentException::class.java) { patch(aTask(), TaskPatch(title = "  ")) }
    }

    @Test
    fun `progress is clamped to 0-100`() {
        assertThat(patch(aTask(), TaskPatch(progress = 150)).progress).isEqualTo(100)
        assertThat(patch(aTask { progress = 50 }, TaskPatch(progress = -5)).progress).isEqualTo(0)
    }

    @Test
    fun `manual progress on a parent is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            patch(aTask(), TaskPatch(progress = 50), hasChildren = true)
        }
    }

    @Test
    fun `parent can still be edited without progress`() {
        val updated = patch(aTask(), TaskPatch(title = "ok"), hasChildren = true)
        assertThat(updated.title).isEqualTo("ok")
    }

    // --- dueReminderFired ---

    @Test
    fun `changing due to a future date clears dueReminderFired`() {
        val t = reminded { dueReminderFired = true; dueDate = today; dueTime = LocalTime.of(9, 0) }
        val updated = patch(t, TaskPatch(dueDate = Patch.Set(today.plusDays(1))))
        assertThat(updated.reminder.dueReminderFired).isFalse()
        assertThat(updated.dueTime).isEqualTo(LocalTime.of(9, 0))
    }

    @Test
    fun `changing due time to a future time today clears dueReminderFired`() {
        val t = reminded { dueReminderFired = true; dueDate = today; dueTime = LocalTime.of(9, 0) }
        val updated = patch(t, TaskPatch(dueTime = Patch.Set(LocalTime.of(17, 0))))
        assertThat(updated.reminder.dueReminderFired).isFalse()
    }

    @Test
    fun `changing due to a past instant sets dueReminderFired`() {
        val t = reminded { dueDate = today.plusDays(1) }
        val yesterday = patch(t, TaskPatch(dueDate = Patch.Set(today.minusDays(1))))
        assertThat(yesterday.reminder.dueReminderFired).isTrue()

        // Today with no time → defaults to morning 08:00, already past at 12:00.
        val todayNoTime = patch(t, TaskPatch(dueDate = Patch.Set(today), dueTime = Patch.Set(null)))
        assertThat(todayNoTime.reminder.dueReminderFired).isTrue()
    }

    @Test
    fun `due exactly now counts as past`() {
        val t = reminded()
        val updated = patch(t, TaskPatch(dueDate = Patch.Set(today), dueTime = Patch.Set(LocalTime.of(12, 0))))
        assertThat(updated.reminder.dueReminderFired).isTrue()
    }

    @Test
    fun `clearing the due date clears due time and dueReminderFired`() {
        val t = reminded { dueDate = today; dueTime = LocalTime.of(9, 0); dueReminderFired = true }
        val updated = patch(t, TaskPatch(dueDate = Patch.Set(null)))
        assertThat(updated.dueDate).isNull()
        assertThat(updated.dueTime).isNull()
        assertThat(updated.reminder.dueReminderFired).isFalse()
    }

    @Test
    fun `due change does not reset the interval anchor`() {
        val t = reminded()
        val updated = patch(t, TaskPatch(dueDate = Patch.Set(today.plusDays(2))))
        assertThat(updated.reminder.anchorAt).isEqualTo(t.reminder.anchorAt)
        assertThat(updated.reminder.count).isEqualTo(3)
    }

    @Test
    fun `untouched due leaves dueReminderFired alone`() {
        val t = reminded { dueDate = today; dueReminderFired = true }
        assertThat(patch(t, TaskPatch(title = "x")).reminder.dueReminderFired).isTrue()
    }

    // --- snooze ---

    @Test
    fun `snooze sets snoozedUntil without touching the anchor`() {
        val t = reminded()
        val until = now.plus(Duration.ofHours(1))
        val updated = patch(t, TaskPatch(snoozedUntil = Patch.Set(until)))
        assertThat(updated.reminder.snoozedUntil).isEqualTo(until)
        assertThat(updated.reminder.anchorAt).isEqualTo(t.reminder.anchorAt)
    }

    @Test
    fun `snooze in the past is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            patch(aTask(), TaskPatch(snoozedUntil = Patch.Set(now)))
        }
    }

    @Test
    fun `cancelling a snooze clears it and resets the anchor to now (07 §8)`() {
        val t = reminded { snoozedUntil = now.plus(Duration.ofHours(1)) }
        val updated = patch(t, TaskPatch(snoozedUntil = Patch.Set(null)))
        assertThat(updated.reminder.snoozedUntil).isNull()
        assertThat(updated.reminder.anchorAt).isEqualTo(now)
    }

    @Test
    fun `cancelling when not snoozed is a no-op`() {
        val t = reminded()
        assertThat(patch(t, TaskPatch(snoozedUntil = Patch.Set(null)))).isEqualTo(t)
    }

    @Test
    fun `isExpanded patch applies`() {
        assertThat(patch(aTask { isExpanded = true }, TaskPatch(isExpanded = false)).isExpanded).isFalse()
    }

    @Test
    fun `resetAnchor keeps snooze and due state`() {
        val s = reminded { snoozedUntil = now.plusSeconds(60); dueReminderFired = true }.reminder.resetAnchor(now)
        assertThat(s.anchorAt).isEqualTo(now)
        assertThat(s.count).isEqualTo(0)
        assertThat(s.lastRemindedAt).isNull()
        assertThat(s.snoozedUntil).isEqualTo(now.plusSeconds(60))
        assertThat(s.dueReminderFired).isTrue()
    }

    // --- complete / uncomplete ---

    @Test
    fun `complete a leaf stores progressBeforeComplete and completedAt`() {
        val changed = TaskRules.complete(aTask { id = "a"; progress = 40 }, emptyList(), now)
        assertThat(changed).hasSize(1)
        val done = changed.single()
        assertThat(done.isCompleted).isTrue()
        assertThat(done.completedAt).isEqualTo(now)
        assertThat(done.progress).isEqualTo(100)
        assertThat(done.progressBeforeComplete).isEqualTo(40)
        assertThat(done.updatedAt).isEqualTo(now)
    }

    @Test
    fun `complete uses the explicit progressBefore when given (slider case)`() {
        val done = TaskRules.complete(aTask { progress = 100 }, emptyList(), now, progressBefore = 70).single()
        assertThat(done.progressBeforeComplete).isEqualTo(70)
    }

    @Test
    fun `completing a parent cascades only to open children with the same completedAt`() {
        val parent = aTask { id = "p" }
        val open1 = aTask { id = "c1"; parentId = "p"; progress = 20 }
        val open2 = aTask { id = "c2"; parentId = "p" }
        val earlier = aTask { id = "c3"; parentId = "p"; isCompleted = true; completedAt = T0 }
        val deleted = aTask { id = "c4"; parentId = "p"; deletedAt = T0 }

        val changed = TaskRules.complete(parent, listOf(open1, open2, earlier, deleted), now)

        assertThat(changed.map { it.id }).containsExactly("p", "c1", "c2")
        assertThat(changed.map { it.completedAt }.toSet()).containsExactly(now)
        assertThat(changed.first { it.id == "c1" }.progressBeforeComplete).isEqualTo(20)
    }

    @Test
    fun `completing an already completed task changes nothing`() {
        assertThat(TaskRules.complete(aTask { isCompleted = true }, emptyList(), now)).isEmpty()
    }

    @Test
    fun `un-complete restores progressBeforeComplete and resets the anchor`() {
        val done = TaskRules.complete(reminded { id = "a"; progress = 40 }, emptyList(), T0).single()
        val reopened = TaskRules.uncomplete(done, null, now).single()

        assertThat(reopened.isCompleted).isFalse()
        assertThat(reopened.completedAt).isNull()
        assertThat(reopened.progress).isEqualTo(40)
        assertThat(reopened.progressBeforeComplete).isNull()
        assertThat(reopened.reminder.anchorAt).isEqualTo(now)
        assertThat(reopened.reminder.count).isEqualTo(0)
        assertThat(reopened.updatedAt).isEqualTo(now)
    }

    @Test
    fun `un-complete without progressBeforeComplete resets 100 to 0`() {
        val reopened = TaskRules.uncomplete(aTask { isCompleted = true; progress = 100 }, null, now).single()
        assertThat(reopened.progress).isEqualTo(0)
    }

    @Test
    fun `un-completing a child of a completed parent un-completes the parent`() {
        val parent = aTask { id = "p"; isCompleted = true; progress = 100; progressBeforeComplete = 10 }
        val child = aTask { id = "c"; parentId = "p"; isCompleted = true }

        val changed = TaskRules.uncomplete(child, parent, now)

        assertThat(changed.map { it.id }).containsExactly("c", "p")
        assertThat(changed.all { !it.isCompleted }).isTrue()
        assertThat(changed.first { it.id == "p" }.progress).isEqualTo(10)
    }

    @Test
    fun `un-completing a child of an open parent leaves the parent alone`() {
        val parent = aTask { id = "p" }
        val child = aTask { id = "c"; parentId = "p"; isCompleted = true }
        assertThat(TaskRules.uncomplete(child, parent, now).map { it.id }).containsExactly("c")
    }

    @Test
    fun `un-completing an open task changes nothing`() {
        assertThat(TaskRules.uncomplete(aTask(), null, now)).isEmpty()
    }

    // --- soft delete ---

    @Test
    fun `soft delete marks the task and its live children`() {
        val changed = TaskRules.softDelete(
            aTask { id = "p" },
            listOf(aTask { id = "c1"; parentId = "p" }, aTask { id = "c2"; parentId = "p"; deletedAt = T0 }),
            now,
        )
        assertThat(changed.map { it.id }).containsExactly("p", "c1")
        assertThat(changed.all { it.deletedAt == now && it.updatedAt == now }).isTrue()
    }
}
