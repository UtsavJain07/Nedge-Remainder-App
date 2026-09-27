package app.nudge.core.domain.tree

import app.nudge.core.model.ListSortMode
import app.nudge.core.model.Priority
import app.nudge.core.testing.T0
import app.nudge.core.testing.aTask
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class TaskTreeBuilderTest {

    private val today = LocalDate.of(2026, 9, 27)

    // --- effective progress (FR-42) ---

    @Test
    fun `leaf with no progress is zero`() {
        val tree = TaskTreeBuilder.build(listOf(aTask { id = "a" }))
        assertThat(tree.node("a")!!.effectiveProgress).isEqualTo(0)
    }

    @Test
    fun `leaf uses its manual progress`() {
        val tree = TaskTreeBuilder.build(listOf(aTask { id = "a"; progress = 40 }))
        assertThat(tree.node("a")!!.effectiveProgress).isEqualTo(40)
    }

    @Test
    fun `parent progress is the average of its children and ignores its own value`() {
        val tree = TaskTreeBuilder.build(
            listOf(
                aTask { id = "p"; progress = 90 },
                aTask { id = "c1"; parentId = "p"; progress = 20 },
                aTask { id = "c2"; parentId = "p"; progress = 60 },
            ),
        )
        assertThat(tree.node("p")!!.effectiveProgress).isEqualTo(40)
    }

    @Test
    fun `completed child counts as 100`() {
        val tree = TaskTreeBuilder.build(
            listOf(
                aTask { id = "p" },
                aTask { id = "c1"; parentId = "p"; progress = 30; isCompleted = true },
                aTask { id = "c2"; parentId = "p"; progress = 0 },
            ),
        )
        assertThat(tree.node("p")!!.effectiveProgress).isEqualTo(50)
    }

    @Test
    fun `parent progress rounds to nearest integer`() {
        // (100 + 0 + 0) / 3 = 33.33 → 33
        val down = TaskTreeBuilder.build(
            listOf(
                aTask { id = "p" },
                aTask { id = "a"; parentId = "p"; isCompleted = true },
                aTask { id = "b"; parentId = "p" },
                aTask { id = "c"; parentId = "p" },
            ),
        )
        assertThat(down.node("p")!!.effectiveProgress).isEqualTo(33)

        // (100 + 100 + 0) / 3 = 66.67 → 67
        val up = TaskTreeBuilder.build(
            listOf(
                aTask { id = "p" },
                aTask { id = "a"; parentId = "p"; isCompleted = true },
                aTask { id = "b"; parentId = "p"; isCompleted = true },
                aTask { id = "c"; parentId = "p" },
            ),
        )
        assertThat(up.node("p")!!.effectiveProgress).isEqualTo(67)

        // (0 + 1) / 2 = 0.5 → 1 (half rounds up)
        val half = TaskTreeBuilder.build(
            listOf(
                aTask { id = "p" },
                aTask { id = "a"; parentId = "p"; progress = 0 },
                aTask { id = "b"; parentId = "p"; progress = 1 },
            ),
        )
        assertThat(half.node("p")!!.effectiveProgress).isEqualTo(1)
    }

    @Test
    fun `completed task is 100 regardless of stored progress`() {
        val tree = TaskTreeBuilder.build(listOf(aTask { id = "a"; progress = 10; isCompleted = true }))
        assertThat(tree.node("a")!!.effectiveProgress).isEqualTo(100)
    }

    @Test
    fun `counter shows done over total, null for leaves`() {
        val tree = TaskTreeBuilder.build(
            listOf(
                aTask { id = "p" },
                aTask { id = "leaf"; sortOrder = 5.0 },
                aTask { id = "c1"; parentId = "p"; isCompleted = true },
                aTask { id = "c2"; parentId = "p" },
                aTask { id = "c3"; parentId = "p" },
            ),
        )
        assertThat(tree.node("p")!!.counter).isEqualTo("1/3")
        assertThat(tree.node("leaf")!!.counter).isNull()
    }

    // --- structure ---

    @Test
    fun `deleted tasks are excluded`() {
        val tree = TaskTreeBuilder.build(
            listOf(
                aTask { id = "p" },
                aTask { id = "gone"; deletedAt = T0 },
                aTask { id = "c"; parentId = "p"; deletedAt = T0 },
            ),
        )
        assertThat(tree.open.map { it.task.id }).containsExactly("p")
        assertThat(tree.node("p")!!.children).isEmpty()
    }

    @Test
    fun `children are open by sortOrder then completed by completedAt desc`() {
        val tree = TaskTreeBuilder.build(
            listOf(
                aTask { id = "p" },
                aTask { id = "o2"; parentId = "p"; sortOrder = 20.0 },
                aTask { id = "o1"; parentId = "p"; sortOrder = 10.0 },
                aTask { id = "cOld"; parentId = "p"; isCompleted = true; completedAt = T0; sortOrder = 1.0 },
                aTask {
                    id = "cNew"; parentId = "p"; isCompleted = true; completedAt = T0.plusSeconds(60); sortOrder = 2.0
                },
            ),
        )
        assertThat(tree.node("p")!!.children.map { it.id }).containsExactly("o1", "o2", "cNew", "cOld").inOrder()
        assertThat(tree.node("p")!!.openChildren.map { it.id }).containsExactly("o1", "o2").inOrder()
    }

    @Test
    fun `nodeContaining finds parent of a subtask`() {
        val tree = TaskTreeBuilder.build(listOf(aTask { id = "p" }, aTask { id = "c"; parentId = "p" }))
        assertThat(tree.nodeContaining("c")!!.task.id).isEqualTo("p")
        assertThat(tree.nodeContaining("p")!!.task.id).isEqualTo("p")
        assertThat(tree.nodeContaining("nope")).isNull()
    }

    // --- sort modes ---

    @Test
    fun `MY_ORDER sorts open top-level by sortOrder`() {
        val tree = TaskTreeBuilder.build(
            listOf(
                aTask { id = "b"; sortOrder = 2.0; priority = Priority.URGENT },
                aTask { id = "a"; sortOrder = 1.0 },
                aTask { id = "c"; sortOrder = 3.0 },
            ),
            ListSortMode.MY_ORDER,
        )
        assertThat(tree.open.map { it.task.id }).containsExactly("a", "b", "c").inOrder()
    }

    @Test
    fun `PRIORITY sorts by priority desc then sortOrder`() {
        val tree = TaskTreeBuilder.build(
            listOf(
                aTask { id = "none"; sortOrder = 0.0 },
                aTask { id = "high2"; sortOrder = 5.0; priority = Priority.HIGH },
                aTask { id = "high1"; sortOrder = 1.0; priority = Priority.HIGH },
                aTask { id = "urgent"; sortOrder = 9.0; priority = Priority.URGENT },
                aTask { id = "low"; sortOrder = -1.0; priority = Priority.LOW },
            ),
            ListSortMode.PRIORITY,
        )
        assertThat(tree.open.map { it.task.id }).containsExactly("urgent", "high1", "high2", "low", "none").inOrder()
    }

    @Test
    fun `DUE_DATE sorts by due asc with nulls last then sortOrder`() {
        val tree = TaskTreeBuilder.build(
            listOf(
                aTask { id = "noDue1"; sortOrder = 1.0 },
                aTask { id = "tomorrow"; sortOrder = 2.0; dueDate = today.plusDays(1) },
                aTask { id = "today17"; sortOrder = 3.0; dueDate = today; dueTime = LocalTime.of(17, 0) },
                aTask { id = "today09"; sortOrder = 4.0; dueDate = today; dueTime = LocalTime.of(9, 0) },
                aTask { id = "noDue0"; sortOrder = 0.0 },
                aTask { id = "yesterday"; sortOrder = 5.0; dueDate = today.minusDays(1) },
            ),
            ListSortMode.DUE_DATE,
        )
        assertThat(tree.open.map { it.task.id })
            .containsExactly("yesterday", "today09", "today17", "tomorrow", "noDue0", "noDue1").inOrder()
    }

    @Test
    fun `completed top-level sorted by completedAt desc regardless of sort mode`() {
        val tasks = listOf(
            aTask { id = "first"; isCompleted = true; completedAt = T0; priority = Priority.URGENT },
            aTask { id = "third"; isCompleted = true; completedAt = T0.plusSeconds(120) },
            aTask { id = "second"; isCompleted = true; completedAt = T0.plusSeconds(60) },
            aTask { id = "open" },
        )
        ListSortMode.entries.forEach { mode ->
            val tree = TaskTreeBuilder.build(tasks, mode)
            assertThat(tree.completed.map { it.task.id }).containsExactly("third", "second", "first").inOrder()
            assertThat(tree.open.map { it.task.id }).containsExactly("open")
            assertThat(tree.openCount).isEqualTo(1)
            assertThat(tree.completedCount).isEqualTo(3)
        }
    }

    @Test
    fun `completed subtask of open parent stays under the parent (FR-35)`() {
        val tree = TaskTreeBuilder.build(
            listOf(aTask { id = "p" }, aTask { id = "c"; parentId = "p"; isCompleted = true }),
        )
        assertThat(tree.completed).isEmpty()
        assertThat(tree.node("p")!!.children.map { it.id }).containsExactly("c")
    }

    // --- flatten ---

    private val flattenFixture = listOf(
        aTask { id = "p1"; sortOrder = 1.0; title = "Parent 1" },
        aTask { id = "p1c1"; parentId = "p1"; sortOrder = 1.0; progress = 30 },
        aTask { id = "p1c2"; parentId = "p1"; sortOrder = 2.0; isCompleted = true },
        aTask { id = "p2"; sortOrder = 2.0; isExpanded = false; title = "Parent 2" },
        aTask { id = "p2c1"; parentId = "p2"; sortOrder = 1.0 },
        aTask { id = "leaf"; sortOrder = 3.0; progress = 70 },
        aTask { id = "done"; isCompleted = true; completedAt = T0; title = "Done parent" },
        aTask { id = "doneChild"; parentId = "done"; isCompleted = true; completedAt = T0 },
    )

    @Test
    fun `flatten shows expanded children, hides collapsed children, and collapsed completed section`() {
        val items = TaskTreeBuilder.build(flattenFixture).flatten(completedExpanded = false)

        assertThat(items.map { it.key })
            .containsExactly("p1", "p1c1", "p1c2", "p2", "leaf", CompletedHeaderUi.KEY).inOrder()

        val p1 = items[0] as TaskItemUi
        assertThat(p1.depth).isEqualTo(0)
        assertThat(p1.isParent).isTrue()
        assertThat(p1.childCounter).isEqualTo("1/2")
        assertThat(p1.childCount).isEqualTo(2)
        assertThat(p1.effectiveProgress).isEqualTo(65)

        val child = items[1] as TaskItemUi
        assertThat(child.depth).isEqualTo(1)
        assertThat(child.isParent).isFalse()
        assertThat(child.effectiveProgress).isEqualTo(30)
        assertThat(child.parentTitle).isEqualTo("Parent 1")
        assertThat((items[2] as TaskItemUi).effectiveProgress).isEqualTo(100)

        val p2 = items[3] as TaskItemUi
        assertThat(p2.isParent).isTrue()
        assertThat(p2.childCounter).isEqualTo("0/1")

        val leaf = items[4] as TaskItemUi
        assertThat(leaf.isParent).isFalse()
        assertThat(leaf.childCounter).isNull()
        assertThat(leaf.effectiveProgress).isEqualTo(70)

        assertThat(items[5]).isEqualTo(CompletedHeaderUi(count = 1, expanded = false))
    }

    @Test
    fun `flatten with completed section expanded lists completed groups with children`() {
        val items = TaskTreeBuilder.build(flattenFixture).flatten(completedExpanded = true)

        assertThat(items.map { it.key })
            .containsExactly("p1", "p1c1", "p1c2", "p2", "leaf", CompletedHeaderUi.KEY, "done", "doneChild").inOrder()
        assertThat(items[5]).isEqualTo(CompletedHeaderUi(count = 1, expanded = true))
        val done = items[6] as TaskItemUi
        assertThat(done.depth).isEqualTo(0)
        assertThat(done.effectiveProgress).isEqualTo(100)
        val doneChild = items[7] as TaskItemUi
        assertThat(doneChild.depth).isEqualTo(1)
        assertThat(doneChild.parentTitle).isEqualTo("Done parent")
    }

    @Test
    fun `completed section children show even if the completed parent is collapsed`() {
        val items = TaskTreeBuilder.build(
            listOf(
                aTask { id = "done"; isCompleted = true; isExpanded = false },
                aTask { id = "doneChild"; parentId = "done"; isCompleted = true },
            ),
        ).flatten(completedExpanded = true)
        assertThat(items.map { it.key }).containsExactly(CompletedHeaderUi.KEY, "done", "doneChild").inOrder()
    }

    @Test
    fun `flatten has no header when nothing is completed`() {
        val items = TaskTreeBuilder.build(listOf(aTask { id = "a" })).flatten(completedExpanded = true)
        assertThat(items.map { it.key }).containsExactly("a")
    }

    @Test
    fun `flatten of empty tree is empty`() {
        assertThat(ListTree.EMPTY.flatten(completedExpanded = true)).isEmpty()
    }
}
