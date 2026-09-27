package app.nudge.core.domain.usecase

import app.nudge.core.testing.T0
import app.nudge.core.testing.aTask
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Duration

class ToggleCompleteUseCaseTest {

    private val h = Harness()
    private val toggle = ToggleCompleteUseCase(h.tasks, h.scheduler)

    private fun seedFamily() {
        h.tasks.seed(
            aTask { id = "p"; progress = 0 },
            aTask { id = "c1"; parentId = "p"; progress = 20; sortOrder = 1.0 },
            aTask { id = "c2"; parentId = "p"; sortOrder = 2.0 },
            aTask { id = "c3"; parentId = "p"; sortOrder = 3.0; isCompleted = true; completedAt = T0 },
        )
        h.clock.advance(Duration.ofMinutes(5))
    }

    @Test
    fun `completes a leaf`() = runTest {
        h.tasks.seed(aTask { id = "a"; progress = 30 })
        h.clock.advance(Duration.ofMinutes(1))

        val result = toggle("a")!!

        assertThat(result.completed).isTrue()
        val a = h.task("a")
        assertThat(a.isCompleted).isTrue()
        assertThat(a.completedAt).isEqualTo(h.clock.now)
        assertThat(a.progressBeforeComplete).isEqualTo(30)
        assertThat(h.scheduler.changed.last()).containsExactly("a")
        assertThat(result.allSubtasksDoneParent).isNull()
    }

    @Test
    fun `toggle flips a completed task back open`() = runTest {
        h.tasks.seed(aTask { id = "a"; isCompleted = true; progress = 100; progressBeforeComplete = 30 })
        val result = toggle("a")!!
        assertThat(result.completed).isFalse()
        assertThat(h.task("a").isCompleted).isFalse()
        assertThat(h.task("a").progress).isEqualTo(30)
    }

    @Test
    fun `forced completed=true on a completed task is a no-op`() = runTest {
        h.tasks.seed(aTask { id = "a"; isCompleted = true })
        val result = toggle("a", completed = true)!!
        assertThat(result.snapshot.tasksBefore).isEmpty()
        assertThat(h.task("a").isCompleted).isTrue()
    }

    @Test
    fun `completing a parent cascades to open children only`() = runTest {
        seedFamily()

        val result = toggle("p", completed = true)!!

        val now = h.clock.now
        listOf("p", "c1", "c2").forEach {
            assertThat(h.task(it).isCompleted).isTrue()
            assertThat(h.task(it).completedAt).isEqualTo(now)
        }
        // The earlier-completed child keeps its original completedAt.
        assertThat(h.task("c3").completedAt).isEqualTo(T0)
        assertThat(result.snapshot.tasksBefore.map { it.id }).containsExactly("p", "c1", "c2")
        assertThat(h.scheduler.changed.last()).containsExactly("p", "c1", "c2")
    }

    @Test
    fun `un-completing a parent does not un-complete its children`() = runTest {
        seedFamily()
        toggle("p", completed = true)
        h.clock.advance(Duration.ofMinutes(1))

        toggle("p", completed = false)

        assertThat(h.task("p").isCompleted).isFalse()
        assertThat(h.task("p").reminder.anchorAt).isEqualTo(h.clock.now)
        listOf("c1", "c2", "c3").forEach { assertThat(h.task(it).isCompleted).isTrue() }
    }

    @Test
    fun `undo of a parent completion restores children exactly via the snapshot`() = runTest {
        seedFamily()
        val before = h.store.tasks.value
        val result = toggle("p", completed = true)!!
        h.clock.advance(Duration.ofSeconds(3))

        h.tasks.restore(result.snapshot)

        val restoreTime = h.clock.now
        before.values.forEach { original ->
            val now = h.task(original.id)
            if (original.id in result.snapshot.affectedTaskIds) {
                assertThat(now).isEqualTo(original.copy(updatedAt = restoreTime))
            } else {
                assertThat(now).isEqualTo(original)
            }
        }
    }

    @Test
    fun `un-completing a child of a completed parent un-completes the parent`() = runTest {
        seedFamily()
        toggle("p", completed = true)

        val result = toggle("c1", completed = false)!!

        assertThat(h.task("c1").isCompleted).isFalse()
        assertThat(h.task("c1").progress).isEqualTo(20)
        assertThat(h.task("p").isCompleted).isFalse()
        assertThat(h.task("c2").isCompleted).isTrue()
        assertThat(result.snapshot.tasksBefore.map { it.id }).containsExactly("c1", "p")
        assertThat(h.scheduler.changed.last()).containsExactly("c1", "p")
    }

    @Test
    fun `completing the last open subtask prompts to complete the parent (FR-36)`() = runTest {
        seedFamily()
        assertThat(toggle("c1", completed = true)!!.allSubtasksDoneParent).isNull()

        val result = toggle("c2", completed = true)!!

        assertThat(result.allSubtasksDoneParent?.id).isEqualTo("p")
        assertThat(h.task("p").isCompleted).isFalse()
    }

    @Test
    fun `no prompt when un-completing a subtask`() = runTest {
        h.tasks.seed(aTask { id = "p" }, aTask { id = "c"; parentId = "p"; isCompleted = true })
        assertThat(toggle("c")!!.allSubtasksDoneParent).isNull()
    }

    @Test
    fun `returns null for missing or deleted tasks`() = runTest {
        h.tasks.seed(aTask { id = "gone"; deletedAt = T0 })
        assertThat(toggle("missing")).isNull()
        assertThat(toggle("gone")).isNull()
        assertThat(h.scheduler.changed).isEmpty()
    }
}
