package app.nudge.core.data.repository

import app.nudge.core.data.DataTestBase
import app.nudge.core.domain.repository.EmojiChange
import app.nudge.core.model.ListColors
import app.nudge.core.model.ListSortMode
import app.nudge.core.model.ListStats
import app.nudge.core.model.Priority
import app.nudge.core.testing.T0
import app.nudge.core.testing.aList
import app.nudge.core.testing.aTask
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Duration

class ListRepositoryImplTest : DataTestBase() {

    @Test
    fun `ensureDefaultList seeds My Tasks in Indigo once`() = runTest {
        lists.ensureDefaultList()
        lists.ensureDefaultList()

        val all = lists.lists()
        assertThat(all).hasSize(1)
        val list = all.single()
        assertThat(list.name).isEqualTo("My Tasks")
        assertThat(list.colorArgb).isEqualTo(0xFF5C6BC0.toInt())
        assertThat(list.colorArgb).isEqualTo(ListColors.DEFAULT)
        assertThat(list.isDefault).isTrue()
        assertThat(list.sortOrder).isEqualTo(0.0)
    }

    @Test
    fun `concurrent ensureDefaultList calls still seed one list`() = runTest {
        (1..5).map { async { lists.ensureDefaultList() } }.awaitAll()
        assertThat(lists.lists()).hasSize(1)
    }

    @Test
    fun `ensureDefaultList does nothing when a list exists`() = runTest {
        lists.create("Work", ListColors.presets[3], null)
        lists.ensureDefaultList()
        assertThat(lists.lists().map { it.name }).containsExactly("Work")
    }

    @Test
    fun `create appends lists at the bottom`() = runTest {
        val a = lists.create("A", ListColors.DEFAULT, null)
        val b = lists.create("B", ListColors.DEFAULT, "🏠")
        assertThat(lists.observeLists().first().map { it.id }).containsExactly(a.id, b.id).inOrder()
        assertThat(lists.get(b.id)!!.emoji).isEqualTo("🏠")
    }

    @Test
    fun `stats count top-level tasks only and flag open urgent`() = runTest {
        seedLists(aList(id = "l1"), aList(id = "l2", sortOrder = 1.0), aList(id = "l3", sortOrder = 2.0))
        seedTasks(
            aTask { id = "a"; listId = "l1" },
            aTask { id = "b"; listId = "l1"; isCompleted = true },
            aTask { id = "sub"; listId = "l1"; parentId = "a"; priority = Priority.URGENT },
            aTask { id = "subDone"; listId = "l1"; parentId = "a"; isCompleted = true },
            aTask { id = "del"; listId = "l1"; deletedAt = T0; priority = Priority.URGENT },
            aTask { id = "u"; listId = "l2"; priority = Priority.URGENT },
            aTask { id = "uDone"; listId = "l3"; priority = Priority.URGENT; isCompleted = true },
        )

        val stats = lists.observeListStats().first()

        assertThat(stats["l1"]).isEqualTo(ListStats(openCount = 1, completedCount = 1, hasUrgent = false))
        assertThat(stats["l2"]).isEqualTo(ListStats(openCount = 1, completedCount = 0, hasUrgent = true))
        assertThat(stats["l3"]).isEqualTo(ListStats(openCount = 0, completedCount = 1, hasUrgent = false))
    }

    @Test
    fun `soft delete cascades to tasks and undo restores them`() = runTest {
        seedLists(aList(id = "l1"), aList(id = "l2", sortOrder = 1.0))
        seedTasks(
            aTask { id = "a"; listId = "l1" },
            aTask { id = "a1"; listId = "l1"; parentId = "a" },
            aTask { id = "b"; listId = "l2" },
        )
        clock.advance(Duration.ofMinutes(1))

        val snapshot = lists.softDelete("l1")

        assertThat(lists.lists().map { it.id }).containsExactly("l2")
        assertThat(lists.get("l1")!!.deletedAt).isEqualTo(clock.now)
        assertThat(tasks.get("a")!!.deletedAt).isEqualTo(clock.now)
        assertThat(tasks.get("a1")!!.deletedAt).isEqualTo(clock.now)
        assertThat(tasks.get("b")!!.deletedAt).isNull()
        assertThat(tasks.observeOpenTasksAcrossLists().first().map { it.id }).containsExactly("b")
        assertThat(snapshot.affectedTaskIds).containsExactly("a", "a1")

        lists.restore(snapshot)

        assertThat(lists.lists().map { it.id }).containsExactly("l1", "l2").inOrder()
        assertThat(tasks.get("a")!!.deletedAt).isNull()
        assertThat(tasks.get("a1")!!.deletedAt).isNull()
        assertThat(tasks.observeList("l1").first().map { it.id }).containsExactly("a", "a1")
    }

    @Test
    fun `the last list cannot be deleted`() = runTest {
        seedLists(aList(id = "l1"), aList(id = "l2", sortOrder = 1.0))
        lists.softDelete("l1")

        val error = runCatching { lists.softDelete("l2") }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalStateException::class.java)
        assertThat(lists.lists().map { it.id }).containsExactly("l2")
    }

    @Test
    fun `reorder moves a list between neighbours`() = runTest {
        seedLists(
            aList(id = "a", sortOrder = 0.0),
            aList(id = "b", sortOrder = 1024.0),
            aList(id = "c", sortOrder = 2048.0),
        )

        lists.reorder("c", beforeId = "a", afterId = "b")
        assertThat(lists.lists().map { it.id }).containsExactly("a", "c", "b").inOrder()

        lists.reorder("a", beforeId = "b", afterId = null)
        assertThat(lists.lists().map { it.id }).containsExactly("c", "b", "a").inOrder()

        lists.reorder("a", beforeId = null, afterId = "c")
        assertThat(lists.lists().map { it.id }).containsExactly("a", "c", "b").inOrder()
    }

    @Test
    fun `reorder renormalizes when neighbours are too close`() = runTest {
        seedLists(
            aList(id = "a", sortOrder = 1.0),
            aList(id = "b", sortOrder = 1.0 + 1e-7),
            aList(id = "c", sortOrder = 5.0),
        )

        lists.reorder("c", beforeId = "a", afterId = "b")

        assertThat(lists.lists().map { it.id }).containsExactly("a", "c", "b").inOrder()
        assertThat(lists.get("b")!!.sortOrder - lists.get("a")!!.sortOrder).isAtLeast(1.0)
    }

    @Test
    fun `edits persist and bump updatedAt only on change`() = runTest {
        seedLists(aList(id = "l1", name = "Work"))
        clock.advance(Duration.ofMinutes(1))

        lists.update("l1", name = "Job", color = ListColors.presets[2], emoji = EmojiChange.Set("💼"))
        lists.setSortMode("l1", ListSortMode.DUE_DATE)
        lists.setCompletedExpanded("l1", true)

        val l = lists.get("l1")!!
        assertThat(l.name).isEqualTo("Job")
        assertThat(l.colorArgb).isEqualTo(ListColors.presets[2])
        assertThat(l.emoji).isEqualTo("💼")
        assertThat(l.sortMode).isEqualTo(ListSortMode.DUE_DATE)
        assertThat(l.completedExpanded).isTrue()
        assertThat(l.updatedAt).isEqualTo(clock.now)

        clock.advance(Duration.ofMinutes(1))
        lists.update("l1", emoji = EmojiChange.Unchanged)
        lists.setSortMode("l1", ListSortMode.DUE_DATE)
        assertThat(lists.get("l1")!!.updatedAt).isEqualTo(l.updatedAt)

        lists.update("l1", emoji = EmojiChange.Set(null))
        assertThat(lists.get("l1")!!.emoji).isNull()
    }
}
