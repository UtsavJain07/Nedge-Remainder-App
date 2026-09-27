package app.nudge.core.data.repository

import app.cash.turbine.test
import app.nudge.core.data.DataTestBase
import app.nudge.core.model.IllegalMoveException
import app.nudge.core.model.InsertPosition
import app.nudge.core.model.MoveOperation
import app.nudge.core.model.NextReminder
import app.nudge.core.model.Priority
import app.nudge.core.model.ReminderKind
import app.nudge.core.model.ReminderStateUpdate
import app.nudge.core.model.Task
import app.nudge.core.model.TaskDraft
import app.nudge.core.model.TaskPatch
import app.nudge.core.testing.T0
import app.nudge.core.testing.aList
import app.nudge.core.testing.aTask
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

class TaskRepositoryImplTest : DataTestBase() {

    @Before
    fun seed() {
        seedLists(aList(id = "l1", name = "Work"), aList(id = "l2", name = "Home", sortOrder = 1024.0))
    }

    private fun draft(title: String, position: InsertPosition = InsertPosition.TOP, parentId: String? = null) =
        TaskDraft(listId = "l1", parentId = parentId, title = title, position = position)

    private suspend fun order(parentId: String? = null, listId: String = "l1") =
        tasks.observeList(listId).first().filter { it.parentId == parentId }.sortedBy { it.sortOrder }.map { it.title }

    private suspend fun get(id: String): Task = tasks.get(id)!!

    private fun family() = seedTasks(
        aTask { id = "p"; progress = 10; sortOrder = 0.0 },
        aTask { id = "c1"; parentId = "p"; progress = 20; sortOrder = 1024.0 },
        aTask { id = "c2"; parentId = "p"; sortOrder = 2048.0 },
        aTask { id = "c3"; parentId = "p"; sortOrder = 3072.0; isCompleted = true; completedAt = T0 },
        aTask { id = "other"; sortOrder = 1024.0 },
    )

    // --- create ---

    @Test
    fun `create positions top, bottom and subtasks at the bottom`() = runTest {
        tasks.create(draft("one"))
        tasks.create(draft("two"))
        tasks.create(draft("last", InsertPosition.BOTTOM))
        tasks.create(draft("first", InsertPosition.TOP))
        assertThat(order()).containsExactly("first", "two", "one", "last").inOrder()

        val parentId = tasks.observeList("l1").first().first { it.title == "one" }.id
        tasks.create(draft("s1", InsertPosition.TOP, parentId))
        tasks.create(draft("s2", InsertPosition.TOP, parentId))
        assertThat(order(parentId)).containsExactly("s1", "s2").inOrder()
    }

    @Test
    fun `create stores the full row with anchor = now`() = runTest {
        clock.advance(Duration.ofMinutes(3))
        val created = tasks.create(
            TaskDraft(
                listId = "l1", parentId = null, title = " Call mom ", priority = Priority.HIGH, notes = "n",
                dueDate = LocalDate.of(2026, 10, 1), dueTime = LocalTime.of(17, 30),
            ),
        )
        assertThat(get(created.id)).isEqualTo(created)
        assertThat(created.title).isEqualTo("Call mom")
        assertThat(created.reminder.anchorAt).isEqualTo(clock.now)
    }

    @Test
    fun `create rejects nesting deeper than one level and cross-list parents`() = runTest {
        family()
        assertThat(runCatching { tasks.create(draft("x", parentId = "c1")) }.exceptionOrNull())
            .isInstanceOf(IllegalMoveException::class.java)
        assertThat(
            runCatching {
                tasks.create(TaskDraft(listId = "l2", parentId = "p", title = "x"))
            }.exceptionOrNull(),
        ).isInstanceOf(IllegalMoveException::class.java)
        assertThat(runCatching { tasks.create(draft("x", parentId = "missing")) }.exceptionOrNull())
            .isInstanceOf(IllegalMoveException::class.java)
    }

    // --- complete / undo ---

    @Test
    fun `complete cascades to open children in one write`() = runTest {
        family()
        clock.advance(Duration.ofMinutes(1))

        val snapshot = tasks.setCompleted("p", completed = true)

        listOf("p", "c1", "c2").forEach {
            assertThat(get(it).isCompleted).isTrue()
            assertThat(get(it).completedAt).isEqualTo(clock.now)
        }
        assertThat(get("c3").completedAt).isEqualTo(T0)
        assertThat(get("c1").progressBeforeComplete).isEqualTo(20)
        assertThat(snapshot.tasksBefore.map { it.id }).containsExactly("p", "c1", "c2")
    }

    @Test
    fun `undo of a completion restores the exact rows`() = runTest {
        family()
        val before = listOf("p", "c1", "c2", "c3").associateWith { get(it) }
        clock.advance(Duration.ofMinutes(1))
        val snapshot = tasks.setCompleted("p", completed = true)
        clock.advance(Duration.ofSeconds(2))

        tasks.restore(snapshot)

        listOf("p", "c1", "c2").forEach {
            assertThat(get(it)).isEqualTo(before.getValue(it).copy(updatedAt = clock.now))
        }
        assertThat(get("c3")).isEqualTo(before.getValue("c3"))
    }

    @Test
    fun `un-completing a child of a completed parent reopens the parent`() = runTest {
        family()
        tasks.setCompleted("p", completed = true)

        tasks.setCompleted("c2", completed = false)

        assertThat(get("c2").isCompleted).isFalse()
        assertThat(get("p").isCompleted).isFalse()
        assertThat(get("p").progress).isEqualTo(10)
        assertThat(get("c1").isCompleted).isTrue()
    }

    @Test
    fun `update rejects manual progress on a parent and writes nothing`() = runTest {
        family()
        val error = runCatching { tasks.update("p", TaskPatch(progress = 50)) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(get("p").progress).isEqualTo(10)
    }

    // --- soft delete / undo ---

    @Test
    fun `soft delete of a parent hides it and its children, undo restores exactly`() = runTest {
        family()
        val before = listOf("p", "c1", "c2", "c3").associateWith { get(it) }
        clock.advance(Duration.ofMinutes(1))

        val snapshot = tasks.softDelete("p")

        assertThat(tasks.observeList("l1").first().map { it.id }).containsExactly("other")
        listOf("p", "c1", "c2", "c3").forEach { assertThat(get(it).deletedAt).isEqualTo(clock.now) }
        assertThat(snapshot.tasksBefore.map { it.id }).containsExactly("p", "c1", "c2", "c3")

        clock.advance(Duration.ofSeconds(4))
        tasks.restore(snapshot)

        before.forEach { (id, row) -> assertThat(get(id)).isEqualTo(row.copy(updatedAt = clock.now)) }
        assertThat(tasks.observeList("l1").first()).hasSize(5)
    }

    @Test
    fun `soft deleted task cannot be modified`() = runTest {
        family()
        tasks.softDelete("other")
        assertThat(runCatching { tasks.update("other", TaskPatch(title = "x")) }.exceptionOrNull())
            .isInstanceOf(NoSuchElementException::class.java)
    }

    @Test
    fun `restore hard-deletes created rows`() = runTest {
        val t = tasks.create(draft("temp"))
        tasks.restore(app.nudge.core.model.UndoSnapshot(tasksBefore = emptyList(), createdTaskIds = listOf(t.id)))
        assertThat(tasks.get(t.id)).isNull()
    }

    // --- move ---

    @Test
    fun `nest through the database appends and expands the parent`() = runTest {
        family()
        tasks.update("p", TaskPatch(isExpanded = false))

        val snapshot = tasks.move(MoveOperation.Nest("other", "p"))

        assertThat(get("other").parentId).isEqualTo("p")
        assertThat(order("p").last()).isEqualTo("Task")
        assertThat(get("other").sortOrder).isGreaterThan(get("c3").sortOrder)
        assertThat(get("p").isExpanded).isTrue()
        assertThat(snapshot.tasksBefore.map { it.id }).containsExactly("other", "p")

        tasks.restore(snapshot)
        assertThat(get("other").parentId).isNull()
        assertThat(get("p").isExpanded).isFalse()
    }

    @Test
    fun `illegal moves are rejected and leave the database unchanged`() = runTest {
        family()
        seedTasks(aTask { id = "far"; listId = "l2" })
        val before = tasks.observeList("l1").first()

        listOf(
            MoveOperation.Nest("p", "other"),
            MoveOperation.Nest("other", "c1"),
            MoveOperation.Nest("other", "far"),
            MoveOperation.Reorder("p", parentId = "other", aboveId = null, belowId = null),
            MoveOperation.ToTopLevel("other"),
        ).forEach { op ->
            assertThat(runCatching { tasks.move(op) }.exceptionOrNull()).isInstanceOf(IllegalMoveException::class.java)
        }
        assertThat(tasks.observeList("l1").first()).isEqualTo(before)
    }

    @Test
    fun `move to list carries children and lands on top`() = runTest {
        family()
        seedTasks(aTask { id = "h1"; listId = "l2"; sortOrder = 100.0 })

        val snapshot = tasks.move(MoveOperation.ToList("p", "l2"))

        assertThat(tasks.observeList("l1").first().map { it.id }).containsExactly("other")
        val moved = tasks.observeList("l2").first()
        assertThat(moved.map { it.id }).containsExactly("p", "h1", "c1", "c2", "c3").inOrder()
        assertThat(get("c1").parentId).isEqualTo("p")
        assertThat(get("p").sortOrder).isLessThan(100.0)

        tasks.restore(snapshot)
        assertThat(tasks.observeList("l2").first().map { it.id }).containsExactly("h1")
    }

    @Test
    fun `to top level lands right after the old parent`() = runTest {
        family()
        tasks.move(MoveOperation.ToTopLevel("c2"))
        assertThat(tasks.observeList("l1").first().filter { it.parentId == null }.map { it.id })
            .containsExactly("p", "c2", "other").inOrder()
    }

    @Test
    fun `reorder renormalizes a collapsed gap in the database`() = runTest {
        seedTasks(
            aTask { id = "x"; sortOrder = 1.0 },
            aTask { id = "y"; sortOrder = 1.0 + 1e-7 },
            aTask { id = "z"; sortOrder = 2.0 },
        )
        tasks.move(MoveOperation.Reorder("z", null, aboveId = "x", belowId = "y"))
        assertThat(tasks.observeList("l1").first().map { it.id }).containsExactly("x", "z", "y").inOrder()
        assertThat(get("y").sortOrder - get("x").sortOrder).isAtLeast(1.0)
    }

    // --- delete completed ---

    @Test
    fun `delete completed removes completed top-level groups only`() = runTest {
        family()
        seedTasks(
            aTask { id = "done"; isCompleted = true; sortOrder = 5.0 },
            aTask { id = "doneKid"; parentId = "done"; isCompleted = true },
            aTask { id = "otherListDone"; listId = "l2"; isCompleted = true },
        )

        val snapshot = tasks.deleteCompleted("l1")

        assertThat(snapshot.tasksBefore.map { it.id }).containsExactly("done", "doneKid")
        assertThat(get("done").deletedAt).isNotNull()
        assertThat(get("c3").deletedAt).isNull() // completed subtask of an open parent stays
        assertThat(get("otherListDone").deletedAt).isNull()
        tasks.restore(snapshot)
        assertThat(get("done").deletedAt).isNull()
    }

    // --- reminders ---

    @Test
    fun `reminder state update does not bump updatedAt`() = runTest {
        family()
        val before = get("other")
        clock.advance(Duration.ofHours(1))
        val state = before.reminder.copy(count = 2, lastRemindedAt = clock.now, anchorAt = clock.now)

        tasks.updateReminderState(
            listOf(
                ReminderStateUpdate("other", state, NextReminder(clock.now.plusSeconds(600), ReminderKind.INTERVAL)),
            ),
        )

        val after = get("other")
        assertThat(after.updatedAt).isEqualTo(before.updatedAt)
        assertThat(after.reminder.count).isEqualTo(2)
        assertThat(after.reminder.anchorAt).isEqualTo(clock.now)
        assertThat(after.reminder.nextAt).isEqualTo(clock.now.plusSeconds(600))
        assertThat(after.reminder.nextKind).isEqualTo(ReminderKind.INTERVAL)
    }

    private suspend fun setNext(id: String, minutes: Long?) {
        val t = get(id)
        tasks.updateReminderState(
            listOf(
                ReminderStateUpdate(
                    id,
                    t.reminder,
                    minutes?.let { NextReminder(T0.plusSeconds(it * 60), ReminderKind.INTERVAL) },
                ),
            ),
        )
    }

    @Test
    fun `earliest next reminder ignores completed and deleted tasks`() = runTest {
        family()
        assertThat(tasks.earliestNextReminder()).isNull()
        setNext("p", 30)
        setNext("other", 20)
        setNext("c1", 10)
        setNext("c3", 5) // completed

        assertThat(tasks.earliestNextReminder()).isEqualTo(T0.plusSeconds(600))

        tasks.softDelete("p") // takes c1 with it
        assertThat(tasks.earliestNextReminder()).isEqualTo(T0.plusSeconds(1200))
        assertThat(tasks.tasksWithReminderDueBefore(T0.plusSeconds(3600)).map { it.id }).containsExactly("other")
    }

    @Test
    fun `due reminders are ordered and bounded`() = runTest {
        family()
        setNext("other", 20)
        setNext("c1", 10)
        setNext("p", 40)
        assertThat(tasks.tasksWithReminderDueBefore(T0.plusSeconds(1200)).map { it.id })
            .containsExactly("c1", "other").inOrder()
    }

    @Test
    fun `stale reminder ids are completed or deleted tasks still holding a next time`() = runTest {
        family()
        setNext("c3", 5) // completed
        setNext("other", 5)
        setNext("c2", 5)
        tasks.softDelete("c2")

        assertThat(tasks.staleReminderTaskIds()).containsExactly("c3", "c2")
        assertThat(tasks.allOpenTasks().map { it.id }).containsExactly("p", "c1", "other")
    }

    @Test
    fun `open tasks across lists skip deleted lists`() = runTest {
        family()
        seedTasks(aTask { id = "h"; listId = "l2" })
        seedLists(aList(id = "l3").copy(deletedAt = T0))
        seedTasks(aTask { id = "ghost"; listId = "l3" })

        assertThat(tasks.observeOpenTasksAcrossLists().first().map { it.id })
            .containsExactly("p", "c1", "c2", "other", "h")
    }

    @Test
    fun `purge hard-deletes rows deleted before the cutoff`() = runTest {
        family()
        tasks.softDelete("other")
        clock.advance(Duration.ofDays(31))
        assertThat(tasks.purgeDeleted(clock.now.minus(Duration.ofDays(30)))).isEqualTo(1)
        assertThat(tasks.get("other")).isNull()
        assertThat(tasks.get("p")).isNotNull()
    }

    // --- search ---

    @Test
    fun `search matches title and notes by prefix`() = runTest {
        seedTasks(
            aTask { id = "milk"; title = "Buy milk" },
            aTask { id = "notes"; title = "Errands"; notes = "Remember the buttermilk" },
            aTask { id = "bread"; title = "Bread"; notes = "from the bakery" },
            aTask { id = "gone"; title = "Buy eggs"; deletedAt = T0 },
        )

        assertThat(tasks.search("mil").first().map { it.id }).containsExactly("milk")
        assertThat(tasks.search("butter").first().map { it.id }).containsExactly("notes")
        assertThat(tasks.search("bu").first().map { it.id }).containsExactly("milk", "notes")
        assertThat(tasks.search("BAK").first().map { it.id }).containsExactly("bread")
        // Terms are ANDed.
        assertThat(tasks.search("buy mi").first().map { it.id }).containsExactly("milk")
        assertThat(tasks.search("buy bread").first()).isEmpty()
    }

    @Test
    fun `search sanitizes FTS operators and never throws`() = runTest {
        seedTasks(aTask { id = "milk"; title = "Buy milk" }, aTask { id = "or"; title = "Order pizza" })

        assertThat(tasks.search("\"milk").first().map { it.id }).containsExactly("milk")
        assertThat(tasks.search("mil*k").first()).isEmpty()
        assertThat(tasks.search("-milk").first().map { it.id }).containsExactly("milk")
        assertThat(tasks.search("(buy)").first().map { it.id }).containsExactly("milk")
        assertThat(tasks.search("buy OR").first().map { it.id }).isEmpty()
        assertThat(tasks.search("OR").first().map { it.id }).containsExactly("or")
        assertThat(tasks.search("NEAR").first()).isEmpty()
        assertThat(tasks.search("^milk:").first().map { it.id }).containsExactly("milk")
        listOf("", "   ", "\"*-():^'", "*").forEach { assertThat(tasks.search(it).first()).isEmpty() }
    }

    @Test
    fun `search index follows edits and restores`() = runTest {
        val t = tasks.create(draft("Plan trip"))

        tasks.search("vacation").test {
            assertThat(awaitItem()).isEmpty()
            tasks.update(t.id, TaskPatch(notes = "vacation in Goa"))
            assertThat(awaitItem().map { it.id }).containsExactly(t.id)
            cancelAndIgnoreRemainingEvents()
        }
        tasks.update(t.id, TaskPatch(title = "Book flights"))
        assertThat(tasks.search("plan").first()).isEmpty()
        assertThat(tasks.search("flig").first().map { it.id }).containsExactly(t.id)
    }
}
