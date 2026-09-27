package app.nudge.core.domain.usecase

import app.nudge.core.testing.aTask
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SetProgressUseCaseTest {

    private val h = Harness()
    private val toggle = ToggleCompleteUseCase(h.tasks, h.scheduler)
    private val setProgress = SetProgressUseCase(h.tasks, toggle)

    @Test
    fun `below 100 stores progress and returns no snapshot`() = runTest {
        h.tasks.seed(aTask { id = "a"; progress = 10 })

        val snapshot = setProgress("a", value = 60, valueBefore = 10)

        assertThat(snapshot).isNull()
        assertThat(h.task("a").progress).isEqualTo(60)
        assertThat(h.task("a").isCompleted).isFalse()
    }

    @Test
    fun `100 completes and remembers the pre-drag value`() = runTest {
        h.tasks.seed(aTask { id = "a"; progress = 70 })

        val snapshot = setProgress("a", value = 100, valueBefore = 70)

        assertThat(snapshot).isNotNull()
        val a = h.task("a")
        assertThat(a.isCompleted).isTrue()
        assertThat(a.progress).isEqualTo(100)
        assertThat(a.progressBeforeComplete).isEqualTo(70)
        assertThat(snapshot!!.tasksBefore.single().progress).isEqualTo(70)
    }

    @Test
    fun `un-complete after slider completion restores the pre-100 value`() = runTest {
        h.tasks.seed(aTask { id = "a"; progress = 40 })
        setProgress("a", value = 100, valueBefore = 40)

        toggle("a", completed = false)

        assertThat(h.task("a").isCompleted).isFalse()
        assertThat(h.task("a").progress).isEqualTo(40)
        assertThat(h.task("a").progressBeforeComplete).isNull()
    }

    @Test
    fun `undo of slider completion restores the row`() = runTest {
        h.tasks.seed(aTask { id = "a"; progress = 40 })
        val snapshot = setProgress("a", value = 100, valueBefore = 40)!!

        h.tasks.restore(snapshot)

        assertThat(h.task("a").isCompleted).isFalse()
        assertThat(h.task("a").progress).isEqualTo(40)
    }

    @Test
    fun `manual progress on a parent is rejected (computed from subtasks)`() = runTest {
        h.tasks.seed(aTask { id = "p" }, aTask { id = "c"; parentId = "p" })
        val error = runCatching { setProgress("p", value = 50, valueBefore = 0) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(h.task("p").progress).isEqualTo(0)
    }
}
