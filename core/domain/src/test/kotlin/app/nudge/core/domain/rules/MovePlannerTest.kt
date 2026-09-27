package app.nudge.core.domain.rules

import app.nudge.core.domain.order.OrderingCalculator
import app.nudge.core.model.IllegalMoveException
import app.nudge.core.model.MoveOperation
import app.nudge.core.model.Task
import app.nudge.core.testing.T0
import app.nudge.core.testing.aTask
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant

class MovePlannerTest {

    private val now: Instant = T0.plusSeconds(600)

    /** a(0) b(1024) c(2048) top-level; p(3072) with children p1(1024) p2(2048). */
    private val tasks = listOf(
        aTask { id = "a"; sortOrder = 0.0 },
        aTask { id = "b"; sortOrder = 1024.0 },
        aTask { id = "c"; sortOrder = 2048.0 },
        aTask { id = "p"; sortOrder = 3072.0 },
        aTask { id = "p1"; parentId = "p"; sortOrder = 1024.0 },
        aTask { id = "p2"; parentId = "p"; sortOrder = 2048.0 },
    )

    private fun plan(op: MoveOperation, list: List<Task> = tasks, targetTopMin: Double? = null) =
        MovePlanner.plan(op, list, now, targetTopMin)

    private fun List<Task>.byId(id: String) = first { it.id == id }

    /** Applies the planned rows to [list] and returns the resulting top-level (or child) order. */
    private fun List<Task>.applied(changed: List<Task>): List<Task> {
        val m = associateBy { it.id }.toMutableMap()
        changed.forEach { m[it.id] = it }
        return m.values.toList()
    }

    private fun List<Task>.orderOf(parentId: String?) =
        filter { it.parentId == parentId }.sortedBy { it.sortOrder }.map { it.id }

    // --- Reorder ---

    @Test
    fun `reorder between two siblings uses the midpoint`() {
        val changed = plan(MoveOperation.Reorder("c", parentId = null, aboveId = "a", belowId = "b"))

        assertThat(changed).hasSize(1)
        val moved = changed.single()
        assertThat(moved.sortOrder).isEqualTo(512.0)
        assertThat(moved.updatedAt).isEqualTo(now)
        assertThat(tasks.applied(changed).orderOf(null)).containsExactly("a", "c", "b", "p").inOrder()
    }

    @Test
    fun `reorder to the top and bottom edges`() {
        val top = plan(MoveOperation.Reorder("c", null, aboveId = null, belowId = "a")).single()
        assertThat(top.sortOrder).isEqualTo(0.0 - OrderingCalculator.GAP)

        val bottom = plan(MoveOperation.Reorder("a", null, aboveId = "p", belowId = null)).single()
        assertThat(bottom.sortOrder).isEqualTo(3072.0 + OrderingCalculator.GAP)
    }

    @Test
    fun `reorder renormalizes the group when the gap is below 1e-6 and keeps order`() {
        val tight = listOf(
            aTask { id = "x"; sortOrder = 1.0 },
            aTask { id = "y"; sortOrder = 1.0 + 1e-7 },
            aTask { id = "z"; sortOrder = 5.0 },
            aTask { id = "m"; sortOrder = 9.0 },
        )
        val changed = plan(MoveOperation.Reorder("m", null, aboveId = "x", belowId = "y"), tight)

        // Siblings were renormalized (x, y, z) and m placed between x and y.
        assertThat(changed.map { it.id }).containsExactly("x", "y", "z", "m")
        assertThat(changed.byId("x").sortOrder).isEqualTo(0.0)
        assertThat(changed.byId("y").sortOrder).isEqualTo(OrderingCalculator.GAP)
        assertThat(changed.byId("z").sortOrder).isEqualTo(2 * OrderingCalculator.GAP)
        assertThat(changed.byId("m").sortOrder).isEqualTo(OrderingCalculator.GAP / 2)
        assertThat(changed.all { it.updatedAt == now }).isTrue()
        assertThat(tight.applied(changed).orderOf(null)).containsExactly("x", "m", "y", "z").inOrder()
    }

    @Test
    fun `reorder does not renormalize when the gap is large enough`() {
        val changed = plan(MoveOperation.Reorder("c", null, aboveId = "a", belowId = "b"))
        assertThat(changed.map { it.id }).containsExactly("c")
    }

    @Test
    fun `reorder rejects neighbours that are not siblings in the target group`() {
        assertThrows(IllegalMoveException::class.java) {
            plan(MoveOperation.Reorder("a", null, aboveId = "p1", belowId = null))
        }
        assertThrows(IllegalMoveException::class.java) {
            plan(MoveOperation.Reorder("a", null, aboveId = null, belowId = "a"))
        }
    }

    @Test
    fun `reorder into a child group nests the task and expands a collapsed parent`() {
        val collapsed = tasks.map { if (it.id == "p") it.copy(isExpanded = false) else it }
        val changed = plan(MoveOperation.Reorder("a", parentId = "p", aboveId = "p1", belowId = "p2"), collapsed)

        val moved = changed.byId("a")
        assertThat(moved.parentId).isEqualTo("p")
        assertThat(moved.sortOrder).isEqualTo(1536.0)
        assertThat(changed.byId("p").isExpanded).isTrue()
    }

    @Test
    fun `reorder within a child group does not touch the parent`() {
        val changed = plan(MoveOperation.Reorder("p2", parentId = "p", aboveId = null, belowId = "p1"))
        assertThat(changed.map { it.id }).containsExactly("p2")
        assertThat(changed.single().sortOrder).isEqualTo(0.0)
    }

    @Test
    fun `reorder of a subtask to top level clears parentId`() {
        val changed = plan(MoveOperation.Reorder("p1", parentId = null, aboveId = "a", belowId = "b"))
        assertThat(changed.single().parentId).isNull()
        assertThat(changed.single().sortOrder).isEqualTo(512.0)
    }

    @Test
    fun `reorder of a parent into a child group is rejected`() {
        val withParent2 = tasks + aTask { id = "q"; sortOrder = 5000.0 } + aTask { id = "q1"; parentId = "q" }
        assertThrows(IllegalMoveException::class.java) {
            plan(MoveOperation.Reorder("q", parentId = "p", aboveId = null, belowId = null), withParent2)
        }
    }

    // --- Nest ---

    @Test
    fun `nest appends as last child`() {
        val changed = plan(MoveOperation.Nest("a", "p"))
        val moved = changed.single()
        assertThat(moved.parentId).isEqualTo("p")
        assertThat(moved.sortOrder).isEqualTo(2048.0 + OrderingCalculator.GAP)
        assertThat(moved.updatedAt).isEqualTo(now)
        assertThat(tasks.applied(changed).orderOf("p")).containsExactly("p1", "p2", "a").inOrder()
    }

    @Test
    fun `nest into a childless task gives the first child sortOrder GAP`() {
        val moved = plan(MoveOperation.Nest("a", "b")).single()
        assertThat(moved.parentId).isEqualTo("b")
        assertThat(moved.sortOrder).isEqualTo(OrderingCalculator.GAP)
    }

    @Test
    fun `nest expands a collapsed parent`() {
        val collapsed = tasks.map { if (it.id == "p") it.copy(isExpanded = false) else it }
        val changed = plan(MoveOperation.Nest("a", "p"), collapsed)
        assertThat(changed.map { it.id }).containsExactly("a", "p")
        assertThat(changed.byId("p").isExpanded).isTrue()
        assertThat(changed.byId("p").updatedAt).isEqualTo(now)
    }

    @Test
    fun `nest does not rewrite an already expanded parent`() {
        assertThat(plan(MoveOperation.Nest("a", "p")).map { it.id }).containsExactly("a")
    }

    @Test
    fun `a task with children cannot be nested`() {
        assertThrows(IllegalMoveException::class.java) { plan(MoveOperation.Nest("p", "a")) }
    }

    @Test
    fun `nesting under a subtask is rejected (depth at most 1)`() {
        assertThrows(IllegalMoveException::class.java) { plan(MoveOperation.Nest("a", "p1")) }
    }

    @Test
    fun `nesting under itself is rejected`() {
        assertThrows(IllegalMoveException::class.java) { plan(MoveOperation.Nest("a", "a")) }
    }

    @Test
    fun `nesting under a task from another list is rejected`() {
        // listTasks only holds the moving task's list, so a foreign parent is not found.
        assertThrows(IllegalMoveException::class.java) { plan(MoveOperation.Nest("a", "elsewhere")) }
    }

    @Test
    fun `an open task cannot be nested into a completed parent`() {
        val done = tasks.map { if (it.id == "b") it.copy(isCompleted = true, completedAt = T0) else it }
        assertThrows(IllegalMoveException::class.java) { plan(MoveOperation.Nest("a", "b"), done) }
    }

    @Test
    fun `a completed task may be nested into a completed parent`() {
        val done = tasks.map {
            if (it.id == "a" || it.id == "b") it.copy(isCompleted = true, completedAt = T0) else it
        }
        assertThat(plan(MoveOperation.Nest("a", "b"), done).single().parentId).isEqualTo("b")
    }

    @Test
    fun `unknown task is rejected`() {
        assertThrows(IllegalMoveException::class.java) { plan(MoveOperation.Nest("ghost", "p")) }
    }

    // --- ToTopLevel ---

    @Test
    fun `to top level places the subtask right after its old parent`() {
        val withAfter = tasks + aTask { id = "d"; sortOrder = 4096.0 }
        val changed = plan(MoveOperation.ToTopLevel("p1"), withAfter)

        val moved = changed.single()
        assertThat(moved.parentId).isNull()
        assertThat(moved.sortOrder).isEqualTo((3072.0 + 4096.0) / 2)
        assertThat(withAfter.applied(changed).orderOf(null)).containsExactly("a", "b", "c", "p", "p1", "d").inOrder()
    }

    @Test
    fun `to top level when the parent is last goes to the bottom`() {
        val moved = plan(MoveOperation.ToTopLevel("p2")).single()
        assertThat(moved.sortOrder).isEqualTo(3072.0 + OrderingCalculator.GAP)
    }

    @Test
    fun `to top level renormalizes when parent and next sibling are too close`() {
        val tight = listOf(
            aTask { id = "p"; sortOrder = 1.0 },
            aTask { id = "n"; sortOrder = 1.0 + 1e-7 },
            aTask { id = "c"; parentId = "p"; sortOrder = 0.0 },
        )
        val changed = plan(MoveOperation.ToTopLevel("c"), tight)
        assertThat(tight.applied(changed).orderOf(null)).containsExactly("p", "c", "n").inOrder()
        assertThat(changed.byId("c").sortOrder).isEqualTo(OrderingCalculator.GAP / 2)
    }

    @Test
    fun `to top level of a top-level task is rejected`() {
        assertThrows(IllegalMoveException::class.java) { plan(MoveOperation.ToTopLevel("a")) }
    }

    // --- ToList ---

    @Test
    fun `to list moves a parent with its children and puts it at the top`() {
        val changed = plan(MoveOperation.ToList("p", "l2"), targetTopMin = 100.0)

        assertThat(changed.map { it.id }).containsExactly("p", "p1", "p2")
        assertThat(changed.all { it.listId == "l2" && it.updatedAt == now }).isTrue()
        val moved = changed.byId("p")
        assertThat(moved.parentId).isNull()
        assertThat(moved.sortOrder).isEqualTo(100.0 - OrderingCalculator.GAP)
        assertThat(changed.byId("p1").parentId).isEqualTo("p")
        assertThat(changed.byId("p1").sortOrder).isEqualTo(1024.0)
    }

    @Test
    fun `to list of a subtask makes it top-level in the target`() {
        val moved = plan(MoveOperation.ToList("p1", "l2"), targetTopMin = null).single()
        assertThat(moved.listId).isEqualTo("l2")
        assertThat(moved.parentId).isNull()
        assertThat(moved.sortOrder).isEqualTo(0.0)
    }

    @Test
    fun `to the same list is a no-op`() {
        assertThat(plan(MoveOperation.ToList("a", "l1"))).isEmpty()
    }

    @Test
    fun `moves never change reminder state`() {
        val ops = listOf(
            MoveOperation.Reorder("c", null, "a", "b"),
            MoveOperation.Nest("a", "p"),
            MoveOperation.ToTopLevel("p1"),
            MoveOperation.ToList("p", "l2"),
        )
        ops.forEach { op ->
            plan(op).forEach { changed ->
                assertThat(changed.reminder).isEqualTo(tasks.byId(changed.id).reminder)
            }
        }
    }
}
