package app.nudge.core.domain.usecase

import app.nudge.core.model.IllegalMoveException
import app.nudge.core.model.MoveOperation
import app.nudge.core.model.Patch
import app.nudge.core.model.Priority
import app.nudge.core.model.TaskPatch
import app.nudge.core.testing.T0
import app.nudge.core.testing.aTask
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Duration

class MoveTaskUseCaseTest {

    private val h = Harness()
    private val move = MoveTaskUseCase(h.tasks, h.scheduler)

    private fun seed() = h.tasks.seed(
        aTask { id = "a"; sortOrder = 0.0 },
        aTask { id = "b"; sortOrder = 1024.0 },
        aTask { id = "p"; sortOrder = 2048.0 },
        aTask { id = "p1"; parentId = "p"; sortOrder = 1024.0 },
        aTask { id = "x"; listId = "l2"; sortOrder = 500.0 },
    )

    @Test
    fun `reorder and nest do not notify the scheduler`() = runTest {
        seed()
        move(MoveOperation.Reorder("b", null, null, "a"))
        move(MoveOperation.Nest("a", "p"))
        assertThat(h.scheduler.changed).isEmpty()
        assertThat(h.task("a").parentId).isEqualTo("p")
    }

    @Test
    fun `to list moves children and notifies the scheduler`() = runTest {
        seed()
        val snapshot = move(MoveOperation.ToList("p", "l2"))

        assertThat(h.task("p").listId).isEqualTo("l2")
        assertThat(h.task("p1").listId).isEqualTo("l2")
        assertThat(h.task("p").sortOrder).isLessThan(500.0)
        assertThat(h.scheduler.changed.single()).containsExactly("p", "p1")
        assertThat(snapshot.affectedTaskIds).containsExactly("p", "p1")
    }

    @Test
    fun `undo of a move restores the previous structure`() = runTest {
        seed()
        val snapshot = move(MoveOperation.Nest("a", "b"))
        h.tasks.restore(snapshot)
        assertThat(h.task("a").parentId).isNull()
        assertThat(h.task("a").sortOrder).isEqualTo(0.0)
    }

    @Test
    fun `illegal moves throw and leave data unchanged`() = runTest {
        seed()
        val before = h.store.tasks.value
        listOf(
            MoveOperation.Nest("p", "a"), // parent with children
            MoveOperation.Nest("a", "p1"), // depth > 1
            MoveOperation.Nest("a", "x"), // other list
        ).forEach { op ->
            assertThat(runCatching { move(op) }.exceptionOrNull()).isInstanceOf(IllegalMoveException::class.java)
        }
        assertThat(h.store.tasks.value).isEqualTo(before)
    }
}

class TaskUseCasesTest {

    private val h = Harness()

    @Test
    fun `delete task soft-deletes children and notifies scheduler, undo restores`() = runTest {
        h.tasks.seed(aTask { id = "p" }, aTask { id = "c"; parentId = "p" })
        val snapshot = DeleteTaskUseCase(h.tasks, h.scheduler)("p")

        assertThat(h.task("p").deletedAt).isNotNull()
        assertThat(h.task("c").deletedAt).isNotNull()
        assertThat(h.scheduler.changed.single()).containsExactly("p", "c")

        RestoreSnapshotUseCase(h.tasks, h.lists, h.scheduler)(snapshot)
        assertThat(h.task("p").deletedAt).isNull()
        assertThat(h.task("c").deletedAt).isNull()
    }

    @Test
    fun `delete completed removes completed groups only`() = runTest {
        h.tasks.seed(
            aTask { id = "done"; isCompleted = true },
            aTask { id = "doneChild"; parentId = "done"; isCompleted = true },
            aTask { id = "open" },
            aTask { id = "openDoneChild"; parentId = "open"; isCompleted = true },
            aTask { id = "otherList"; listId = "l2"; isCompleted = true },
        )
        val snapshot = DeleteCompletedUseCase(h.tasks, h.scheduler)("l1")

        assertThat(snapshot.affectedTaskIds).containsExactly("done", "doneChild")
        assertThat(h.task("open").deletedAt).isNull()
        assertThat(h.task("openDoneChild").deletedAt).isNull()
        assertThat(h.task("otherList").deletedAt).isNull()
    }

    @Test
    fun `update task notifies the scheduler`() = runTest {
        h.tasks.seed(aTask { id = "a" })
        h.clock.advance(Duration.ofMinutes(3))
        val updated = UpdateTaskUseCase(h.tasks, h.scheduler)("a", TaskPatch(priority = Priority.HIGH))
        assertThat(updated.reminder.anchorAt).isEqualTo(h.clock.now)
        assertThat(h.scheduler.changed.single()).containsExactly("a")
    }

    @Test
    fun `snooze sets snoozedUntil and notifies scheduler`() = runTest {
        h.tasks.seed(aTask { id = "a"; priority = Priority.HIGH })
        val until = h.clock.now.plus(Duration.ofHours(1))

        val task = SnoozeUseCase(h.tasks, h.scheduler)("a", until)

        assertThat(task!!.reminder.snoozedUntil).isEqualTo(until)
        assertThat(h.scheduler.changed.single()).containsExactly("a")
    }

    @Test
    fun `cancel snooze resets the anchor`() = runTest {
        h.tasks.seed(aTask { id = "a"; snoozedUntil = T0.plus(Duration.ofHours(2)) })
        h.clock.advance(Duration.ofMinutes(10))

        SnoozeUseCase(h.tasks, h.scheduler)("a", null)

        assertThat(h.task("a").reminder.snoozedUntil).isNull()
        assertThat(h.task("a").reminder.anchorAt).isEqualTo(h.clock.now)
    }

    @Test
    fun `snooze of a completed or deleted task is ignored`() = runTest {
        h.tasks.seed(aTask { id = "done"; isCompleted = true }, aTask { id = "del"; deletedAt = T0 })
        val snooze = SnoozeUseCase(h.tasks, h.scheduler)
        assertThat(snooze("done", h.clock.now.plusSeconds(60))).isNull()
        assertThat(snooze("del", h.clock.now.plusSeconds(60))).isNull()
        assertThat(snooze("missing", h.clock.now.plusSeconds(60))).isNull()
        assertThat(h.scheduler.changed).isEmpty()
    }

    @Test
    fun `due change through update clears the fired flag`() = runTest {
        h.tasks.seed(aTask { id = "a"; dueReminderFired = true; dueDate = java.time.LocalDate.of(2026, 9, 27) })
        UpdateTaskUseCase(h.tasks, h.scheduler)(
            "a",
            TaskPatch(dueDate = Patch.Set(java.time.LocalDate.of(2026, 9, 30))),
        )
        assertThat(h.task("a").reminder.dueReminderFired).isFalse()
    }
}
