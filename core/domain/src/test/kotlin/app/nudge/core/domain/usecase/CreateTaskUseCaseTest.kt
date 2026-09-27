package app.nudge.core.domain.usecase

import app.nudge.core.model.IllegalMoveException
import app.nudge.core.model.InsertPosition
import app.nudge.core.model.Priority
import app.nudge.core.model.TaskDraft
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Duration

class CreateTaskUseCaseTest {

    private val h = Harness()
    private val create = CreateTaskUseCase(h.tasks, h.settings, h.scheduler)

    private fun draft(title: String, position: InsertPosition = InsertPosition.TOP, parentId: String? = null) =
        TaskDraft(listId = "l1", parentId = parentId, title = title, position = position)

    private suspend fun topLevelOrder() =
        h.tasks.observeList("l1").first().filter { it.parentId == null }.sortedBy { it.sortOrder }.map { it.title }

    @Test
    fun `top insertion puts each new task first`() = runTest {
        create(draft("one"))
        create(draft("two"))
        create(draft("three"))
        assertThat(topLevelOrder()).containsExactly("three", "two", "one").inOrder()
    }

    @Test
    fun `bottom insertion puts each new task last`() = runTest {
        create(draft("one", InsertPosition.BOTTOM))
        create(draft("two", InsertPosition.BOTTOM))
        create(draft("top", InsertPosition.TOP))
        assertThat(topLevelOrder()).containsExactly("top", "one", "two").inOrder()
    }

    @Test
    fun `subtasks always go to the bottom of the parent`() = runTest {
        val parent = create(draft("parent"))
        create(draft("s1", InsertPosition.TOP, parent.id))
        create(draft("s2", InsertPosition.TOP, parent.id))

        val kids = h.tasks.children(parent.id).map { it.title }
        assertThat(kids).containsExactly("s1", "s2").inOrder()
    }

    @Test
    fun `title is trimmed and anchor is now`() = runTest {
        h.clock.advance(Duration.ofMinutes(7))
        val t = create(TaskDraft(listId = "l1", parentId = null, title = "  Fix bug  ", priority = Priority.URGENT))

        assertThat(t.title).isEqualTo("Fix bug")
        assertThat(t.priority).isEqualTo(Priority.URGENT)
        assertThat(t.reminder.anchorAt).isEqualTo(h.clock.now)
        assertThat(t.createdAt).isEqualTo(h.clock.now)
        assertThat(h.task(t.id)).isEqualTo(t)
    }

    @Test
    fun `blank and over-long titles are rejected and nothing is written`() = runTest {
        listOf("   ", "", "x".repeat(201)).forEach { bad ->
            val error = runCatching { create(draft(bad)) }.exceptionOrNull()
            assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThat(h.store.tasks.value).isEmpty()
        assertThat(h.scheduler.changed).isEmpty()
        assertThat(h.settings.state.value.lastUsedListId).isNull()
    }

    @Test
    fun `exactly 200 chars is accepted`() = runTest {
        assertThat(create(draft("x".repeat(200))).title).hasLength(200)
    }

    @Test
    fun `reminder is scheduled for the new task`() = runTest {
        val t = create(draft("a"))
        assertThat(h.scheduler.changed).containsExactly(setOf(t.id))
    }

    @Test
    fun `top-level creation remembers the list, subtask creation does not`() = runTest {
        val parent = create(TaskDraft(listId = "l2", parentId = null, title = "p"))
        assertThat(h.settings.state.value.lastUsedListId).isEqualTo("l2")

        h.settings.update { it.copy(lastUsedListId = "l1") }
        create(TaskDraft(listId = "l2", parentId = parent.id, title = "child"))
        assertThat(h.settings.state.value.lastUsedListId).isEqualTo("l1")
    }

    @Test
    fun `subtask of a subtask is rejected`() = runTest {
        val parent = create(draft("p"))
        val child = create(draft("c", parentId = parent.id))
        val error = runCatching { create(draft("gc", parentId = child.id)) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalMoveException::class.java)
    }

    @Test
    fun `subtask in a different list than its parent is rejected`() = runTest {
        val parent = create(draft("p"))
        val error = runCatching {
            create(TaskDraft(listId = "l2", parentId = parent.id, title = "c"))
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalMoveException::class.java)
    }
}
