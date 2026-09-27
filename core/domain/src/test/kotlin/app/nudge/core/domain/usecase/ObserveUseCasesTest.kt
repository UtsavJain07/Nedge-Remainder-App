package app.nudge.core.domain.usecase

import app.cash.turbine.test
import app.nudge.core.model.ListSortMode
import app.nudge.core.model.Priority
import app.nudge.core.model.SmartViewType
import app.nudge.core.testing.T0
import app.nudge.core.testing.aList
import app.nudge.core.testing.aTask
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

class ObserveUseCasesTest {

    private val h = Harness()

    /** Clock is 2026-09-27 10:00 Asia/Kolkata. */
    private val today = LocalDate.of(2026, 9, 27)

    private fun seedHome() {
        h.lists.seed(aList(id = "gone", name = "Deleted").copy(deletedAt = T0))
        h.tasks.seed(
            aTask { id = "u1"; priority = Priority.URGENT; dueDate = today.plusDays(1); createdAt = T0 },
            aTask { id = "parent"; title = "Parent" },
            aTask {
                id = "u2"; parentId = "parent"; priority = Priority.URGENT; createdAt = T0.minusSeconds(3600)
            },
            aTask { id = "u3"; priority = Priority.URGENT; dueDate = today; dueTime = LocalTime.of(9, 0) },
            aTask { id = "h1"; priority = Priority.HIGH; dueDate = today },
            aTask { id = "h2"; priority = Priority.HIGH; createdAt = T0.minusSeconds(60) },
            aTask { id = "h3"; priority = Priority.HIGH; createdAt = T0 },
            aTask { id = "m"; priority = Priority.MEDIUM },
            aTask { id = "od"; priority = Priority.MEDIUM; dueDate = today.minusDays(1) },
            aTask { id = "doneU"; priority = Priority.URGENT; isCompleted = true },
            aTask { id = "delU"; priority = Priority.URGENT; deletedAt = T0 },
            aTask { id = "ghost"; listId = "gone"; priority = Priority.URGENT },
        )
    }

    private fun home() = ObserveHomeUseCase(h.lists, h.tasks, h.settings, h.clock)

    @Test
    fun `focus shows urgent and high by priority, due asc nulls last, createdAt, max 5`() = runTest {
        seedHome()
        val model = home()().first()

        assertThat(model.focus.map { it.task.id }).containsExactly("u3", "u1", "u2", "h1", "h2").inOrder()
        assertThat(model.focus.first { it.task.id == "u2" }.parentTitle).isEqualTo("Parent")
        assertThat(model.focus.first().list.id).isEqualTo("l1")
    }

    @Test
    fun `today count is due today or overdue plus urgent, all count is every open task`() = runTest {
        seedHome()
        val model = home()().first()

        // u1, u2, u3 (urgent) + h1 (due today) + od (overdue)
        assertThat(model.todayCount).isEqualTo(5)
        assertThat(model.allCount).isEqualTo(9)
        assertThat(model.lists.map { it.list.id }).containsExactly("l1", "l2").inOrder()
    }

    @Test
    fun `home list stats count top-level only and flag urgent`() = runTest {
        seedHome()
        val stats = home()().first().lists.first { it.list.id == "l1" }.stats

        // Top-level live: u1, parent, u3, h1, h2, h3, m, od (open) + doneU (completed)
        assertThat(stats.openCount).isEqualTo(8)
        assertThat(stats.completedCount).isEqualTo(1)
        assertThat(stats.hasUrgent).isTrue()
        assertThat(home()().first().lists.first { it.list.id == "l2" }.stats.total).isEqualTo(0)
    }

    @Test
    fun `home re-emits when a task completes`() = runTest {
        h.tasks.seed(aTask { id = "u"; priority = Priority.URGENT })
        val toggle = ToggleCompleteUseCase(h.tasks, h.scheduler)

        home()().test {
            assertThat(awaitItem().focus.map { it.task.id }).containsExactly("u")
            toggle("u")
            // combine() may emit once per upstream (stats, then open tasks); wait for the settled model.
            var model = awaitItem()
            while (model.focus.isNotEmpty()) model = awaitItem()
            assertThat(model.allCount).isEqualTo(0)
            assertThat(model.lists.first { it.list.id == "l1" }.stats.completedCount).isEqualTo(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- smart views ---

    @Test
    fun `isOverdue compares date and, for today, the time`() {
        val now = LocalTime.of(10, 0)
        fun t(date: LocalDate?, time: LocalTime? = null) = aTask { dueDate = date; dueTime = time }

        assertThat(SmartViews.isOverdue(t(today.minusDays(1)), today, now)).isTrue()
        assertThat(SmartViews.isOverdue(t(today.minusDays(1), LocalTime.of(23, 0)), today, now)).isTrue()
        assertThat(SmartViews.isOverdue(t(today, LocalTime.of(9, 59)), today, now)).isTrue()
        assertThat(SmartViews.isOverdue(t(today, LocalTime.of(10, 0)), today, now)).isFalse()
        assertThat(SmartViews.isOverdue(t(today, LocalTime.of(17, 0)), today, now)).isFalse()
        assertThat(SmartViews.isOverdue(t(today), today, now)).isFalse()
        assertThat(SmartViews.isOverdue(t(today.plusDays(1)), today, now)).isFalse()
        assertThat(SmartViews.isOverdue(t(null), today, now)).isFalse()
    }

    @Test
    fun `isInToday includes due today, overdue and urgent, excludes completed`() {
        assertThat(SmartViews.isInToday(aTask { dueDate = today }, today)).isTrue()
        assertThat(SmartViews.isInToday(aTask { dueDate = today.minusDays(3) }, today)).isTrue()
        assertThat(SmartViews.isInToday(aTask { priority = Priority.URGENT }, today)).isTrue()
        assertThat(SmartViews.isInToday(aTask { dueDate = today.plusDays(1) }, today)).isFalse()
        assertThat(SmartViews.isInToday(aTask { priority = Priority.HIGH }, today)).isFalse()
        assertThat(SmartViews.isInToday(aTask { dueDate = today; isCompleted = true }, today)).isFalse()
    }

    @Test
    fun `today view puts overdue first then groups by list in list order`() = runTest {
        h.tasks.seed(
            aTask { id = "od"; priority = Priority.MEDIUM; dueDate = today.minusDays(1) },
            aTask { id = "u3"; priority = Priority.URGENT; dueDate = today; dueTime = LocalTime.of(9, 0) },
            aTask { id = "h1"; priority = Priority.HIGH; dueDate = today },
            aTask { id = "late"; priority = Priority.LOW; dueDate = today; dueTime = LocalTime.of(17, 0) },
            aTask { id = "u1"; priority = Priority.URGENT; dueDate = today.plusDays(1); listId = "l2" },
            aTask { id = "future"; dueDate = today.plusDays(1) },
        )

        val groups = ObserveSmartViewUseCase(h.lists, h.tasks, h.clock)(SmartViewType.TODAY).first()

        assertThat(groups.map { it.isOverdue }).containsExactly(true, false, false).inOrder()
        assertThat(groups[0].list).isNull()
        assertThat(groups[0].tasks.map { it.task.id }).containsExactly("u3", "od").inOrder()
        assertThat(groups[1].list!!.id).isEqualTo("l1")
        assertThat(groups[1].tasks.map { it.task.id }).containsExactly("h1", "late").inOrder()
        assertThat(groups[2].list!!.id).isEqualTo("l2")
        assertThat(groups[2].tasks.map { it.task.id }).containsExactly("u1")
    }

    @Test
    fun `overdue flips as the clock passes the due time`() = runTest {
        h.tasks.seed(aTask { id = "a"; dueDate = today; dueTime = LocalTime.of(10, 30) })
        val view = ObserveSmartViewUseCase(h.lists, h.tasks, h.clock)

        assertThat(view(SmartViewType.TODAY).first().single().isOverdue).isFalse()
        h.clock.advance(Duration.ofMinutes(31))
        assertThat(view(SmartViewType.TODAY).first().single().isOverdue).isTrue()
    }

    @Test
    fun `all view groups every open task by list without an overdue group`() = runTest {
        h.tasks.seed(
            aTask { id = "od"; dueDate = today.minusDays(1) },
            aTask { id = "plain" },
            aTask { id = "done"; isCompleted = true },
            aTask { id = "other"; listId = "l2" },
        )
        val groups = ObserveSmartViewUseCase(h.lists, h.tasks, h.clock)(SmartViewType.ALL).first()

        assertThat(groups.none { it.isOverdue }).isTrue()
        assertThat(groups.map { it.list!!.id }).containsExactly("l1", "l2").inOrder()
        assertThat(groups[0].tasks.map { it.task.id }).containsExactly("od", "plain")
    }

    // --- list screen & search ---

    @Test
    fun `list screen model builds the tree with the list sort mode`() = runTest {
        h.lists.setSortMode("l1", ListSortMode.PRIORITY)
        h.tasks.seed(
            aTask { id = "a"; sortOrder = 0.0 },
            aTask { id = "b"; sortOrder = 1.0; priority = Priority.HIGH },
        )
        val model = ObserveListScreenUseCase(h.lists, h.tasks, h.settings)("l1").first()!!
        assertThat(model.items.map { it.key }).containsExactly("b", "a").inOrder()
    }

    @Test
    fun `list screen model is null for a deleted list`() = runTest {
        h.lists.seed(aList(id = "gone").copy(deletedAt = T0))
        assertThat(ObserveListScreenUseCase(h.lists, h.tasks, h.settings)("gone").first()).isNull()
        assertThat(ObserveListScreenUseCase(h.lists, h.tasks, h.settings)("missing").first()).isNull()
    }

    @Test
    fun `search groups results by list with open tasks first`() = runTest {
        h.tasks.seed(
            aTask { id = "a"; title = "Buy milk"; isCompleted = true; sortOrder = 0.0 },
            aTask { id = "b"; title = "Buy bread"; sortOrder = 5.0 },
            aTask { id = "c"; title = "Call"; notes = "buyer meeting"; listId = "l2" },
            aTask { id = "d"; title = "Unrelated" },
        )
        val query = MutableStateFlow("  buy ")
        SearchTasksUseCase(h.lists, h.tasks)(query).test {
            val groups = awaitItem()
            assertThat(groups.map { it.list!!.id }).containsExactly("l1", "l2").inOrder()
            assertThat(groups[0].tasks.map { it.task.id }).containsExactly("b", "a").inOrder()
            assertThat(groups[1].tasks.map { it.task.id }).containsExactly("c")

            query.value = "   "
            assertThat(awaitItem()).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
