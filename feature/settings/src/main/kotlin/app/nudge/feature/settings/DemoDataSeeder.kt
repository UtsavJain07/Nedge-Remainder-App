package app.nudge.feature.settings

import app.nudge.core.common.Clock
import app.nudge.core.domain.usecase.CreateListUseCase
import app.nudge.core.domain.usecase.CreateTaskUseCase
import app.nudge.core.domain.usecase.UpdateTaskUseCase
import app.nudge.core.model.InsertPosition
import app.nudge.core.model.ListColors
import app.nudge.core.model.Priority
import app.nudge.core.model.TaskDraft
import app.nudge.core.model.TaskPatch
import java.time.LocalTime
import javax.inject.Inject

/**
 * Debug-only fixture data (07 §11): three lists with a mix of priorities, due dates (overdue, today,
 * later), notes, and a parent with subtasks. Titles are sample content, not UI copy.
 */
class DemoDataSeeder @Inject constructor(
    private val createList: CreateListUseCase,
    private val createTask: CreateTaskUseCase,
    private val updateTask: UpdateTaskUseCase,
    private val clock: Clock,
) {
    /** Returns the number of tasks created. */
    suspend fun seed(): Int {
        val today = clock.now().atZone(clock.zone()).toLocalDate()
        var count = 0
        suspend fun task(
            listId: String,
            title: String,
            priority: Priority = Priority.NONE,
            parentId: String? = null,
            notes: String = "",
            dueInDays: Long? = null,
            dueTime: LocalTime? = null,
        ): String {
            count++
            return createTask(
                TaskDraft(
                    listId = listId,
                    parentId = parentId,
                    title = title,
                    priority = priority,
                    notes = notes,
                    dueDate = dueInDays?.let { today.plusDays(it) },
                    dueTime = dueTime,
                    position = InsertPosition.BOTTOM,
                ),
            ).id
        }

        val work = createList("Work", ListColors.presets[0], "💼").id
        task(work, "Fix login crash on Android 16", Priority.URGENT, notes = "Repro: cold start → tap Sign in.", dueInDays = 0, dueTime = LocalTime.of(17, 0))
        task(work, "Prepare Q4 roadmap slides", Priority.HIGH, dueInDays = 1)
        task(work, "Reply to design review comments", Priority.MEDIUM)
        task(work, "Book the team offsite venue", Priority.LOW, dueInDays = 5)

        val home = createList("Home", ListColors.presets[6], "🏠").id
        task(home, "Renew car insurance", Priority.HIGH, dueInDays = -1)
        task(home, "Pay electricity bill", Priority.HIGH, dueInDays = 0)
        task(home, "Buy groceries", Priority.MEDIUM, notes = "Milk, eggs, spinach, coffee beans")
        task(home, "Water the plants", Priority.LOW)
        task(home, "Call mom")

        val reading = createList("Reading", ListColors.presets[9], "📚").id
        val books = task(reading, "Read books", Priority.MEDIUM)
        val atomicHabits = task(reading, "Atomic Habits", parentId = books)
        updateTask(atomicHabits, TaskPatch(progress = 50))
        task(reading, "Deep Work", parentId = books)
        task(reading, "Write notes on 'The Pragmatic Programmer'", Priority.LOW, notes = "Focus on the tracer bullets chapter.")
        return count
    }
}
