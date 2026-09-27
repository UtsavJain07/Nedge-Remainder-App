package app.nudge.core.domain.usecase

import app.nudge.core.domain.repository.EmojiChange
import app.nudge.core.model.ListColors
import app.nudge.core.testing.aTask
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Duration

class DeleteListUseCaseTest {

    private val h = Harness()
    private val delete = DeleteListUseCase(h.lists, h.scheduler)
    private val restore = RestoreSnapshotUseCase(h.tasks, h.lists, h.scheduler)

    @Test
    fun `deleting a list soft-deletes it and all its tasks`() = runTest {
        h.tasks.seed(
            aTask { id = "a"; listId = "l1" },
            aTask { id = "a1"; listId = "l1"; parentId = "a" },
            aTask { id = "b"; listId = "l2" },
        )
        h.clock.advance(Duration.ofMinutes(1))

        val snapshot = delete("l1")

        assertThat(h.lists.lists().map { it.id }).containsExactly("l2")
        assertThat(h.task("a").deletedAt).isEqualTo(h.clock.now)
        assertThat(h.task("a1").deletedAt).isEqualTo(h.clock.now)
        assertThat(h.task("b").deletedAt).isNull()
        assertThat(snapshot.affectedTaskIds).containsExactly("a", "a1")
        assertThat(h.scheduler.changed.single()).containsExactly("a", "a1")
        assertThat(h.tasks.observeOpenTasksAcrossLists().first().map { it.id }).containsExactly("b")
    }

    @Test
    fun `the last list cannot be deleted`() = runTest {
        delete("l1")
        val error = runCatching { delete("l2") }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalStateException::class.java)
        assertThat(h.lists.lists().map { it.id }).containsExactly("l2")
    }

    @Test
    fun `undo restores the list and its tasks`() = runTest {
        h.tasks.seed(aTask { id = "a"; listId = "l1" }, aTask { id = "a1"; listId = "l1"; parentId = "a" })
        val snapshot = delete("l1")
        h.scheduler.changed.clear()

        restore(snapshot)

        assertThat(h.lists.lists().map { it.id }).containsExactly("l1", "l2").inOrder()
        assertThat(h.task("a").deletedAt).isNull()
        assertThat(h.task("a1").deletedAt).isNull()
        assertThat(h.scheduler.changed.single()).containsExactly("a", "a1")
    }
}

class ListUseCasesTest {

    private val h = Harness()

    @Test
    fun `create list validates name and picks the next unused color`() = runTest {
        val create = CreateListUseCase(h.lists)
        // l1 and l2 both use DEFAULT (Indigo) → next unused is Violet.
        val list = create("  Groceries ", color = null, emoji = " ")

        assertThat(list.name).isEqualTo("Groceries")
        assertThat(list.colorArgb).isEqualTo(ListColors.presets[1])
        assertThat(list.emoji).isNull()
        assertThat(h.lists.lists().last().id).isEqualTo(list.id)

        val error = runCatching { create("x".repeat(41), null, null) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `update list validates the name and edits emoji`() = runTest {
        val update = UpdateListUseCase(h.lists)
        update("l1", name = " Job ", color = null, emoji = EmojiChange.Set("💼"))
        assertThat(h.lists.get("l1")!!.name).isEqualTo("Job")
        assertThat(h.lists.get("l1")!!.emoji).isEqualTo("💼")

        val error = runCatching { update("l1", " ", null, EmojiChange.Unchanged) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `reorder lists moves a list between its new neighbours`() = runTest {
        val l3 = h.lists.create("Three", ListColors.DEFAULT, null)
        val reorder = ReorderListsUseCase(h.lists)

        reorder(listOf(l3.id, "l1", "l2"), l3.id)

        assertThat(h.lists.lists().map { it.id }).containsExactly(l3.id, "l1", "l2").inOrder()
    }
}
