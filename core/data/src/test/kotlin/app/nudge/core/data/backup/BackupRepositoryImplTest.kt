package app.nudge.core.data.backup

import app.nudge.core.data.DataTestBase
import app.nudge.core.data.TestDispatchers
import app.nudge.core.domain.repository.ImportMode
import app.nudge.core.domain.repository.ImportResult
import app.nudge.core.model.ListSortMode
import app.nudge.core.model.Priority
import app.nudge.core.model.ReminderCadence
import app.nudge.core.model.ReminderState
import app.nudge.core.model.Task
import app.nudge.core.model.ThemeMode
import app.nudge.core.testing.T0
import app.nudge.core.testing.aList
import app.nudge.core.testing.aTask
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class BackupRepositoryImplTest : DataTestBase() {

    private lateinit var backup: BackupRepositoryImpl

    @Before
    fun createRepo() {
        backup = BackupRepositoryImpl(db, db.taskListDao(), db.taskDao(), settings, lists, clock, TestDispatchers)
    }

    private fun seedSample() {
        seedLists(
            aList(id = "l1", name = "Work", sortMode = ListSortMode.PRIORITY, completedExpanded = true)
                .copy(emoji = "💼", isDefault = true),
            aList(id = "l2", name = "Home", sortOrder = 1024.0, color = 0xFFEC407A.toInt()),
        )
        seedTasks(
            aTask {
                id = "p"; title = "Ship release"; notes = "Check logs first"; priority = Priority.URGENT
                cadenceOverride = ReminderCadence.EVERY_2_H; progress = 30; dueDate = LocalDate.of(2026, 9, 30)
                dueTime = LocalTime.of(17, 0); sortOrder = 512.0; isExpanded = false
            },
            aTask {
                id = "c"; parentId = "p"; title = "Write notes"; isCompleted = true; progress = 100
                progressBeforeComplete = 40; completedAt = T0.plusSeconds(90); sortOrder = 1024.0
            },
            aTask { id = "h"; listId = "l2"; title = "Water plants"; priority = Priority.LOW; reminderCount = 5 },
            aTask { id = "del"; title = "Deleted"; deletedAt = T0 },
        )
    }

    /** Rows compared without device-local reminder state. */
    private fun Task.withoutReminder() = copy(reminder = ReminderState.fresh(Instant.EPOCH))

    @Test
    fun `export then import Replace restores lists, tasks and settings`() = runTest {
        seedSample()
        settings.update { it.copy(theme = ThemeMode.DARK, reminders = it.reminders.copy(defaultSnoozeMinutes = 15)) }
        val listsBefore = lists.lists()
        val tasksBefore = (tasks.observeList("l1").first() + tasks.observeList("l2").first()).associateBy { it.id }

        val json = backup.exportJson()

        // Mutate everything, then restore from the file.
        tasks.create(app.nudge.core.model.TaskDraft(listId = "l1", parentId = null, title = "Added later"))
        lists.softDelete("l2")
        settings.update { it.copy(theme = ThemeMode.LIGHT, reminders = it.reminders.copy(defaultSnoozeMinutes = 60)) }
        clock.advance(Duration.ofHours(2))

        val result = backup.importJson(json, ImportMode.REPLACE)

        assertThat(result).isEqualTo(ImportResult.Success(lists = 2, tasks = 3))
        assertThat(lists.lists()).isEqualTo(listsBefore)
        val after = (tasks.observeList("l1").first() + tasks.observeList("l2").first()).associateBy { it.id }
        assertThat(after.keys).containsExactly("p", "c", "h")
        after.forEach { (id, t) ->
            assertThat(t.withoutReminder()).isEqualTo(tasksBefore.getValue(id).withoutReminder())
        }
        assertThat(tasks.get("del")).isNull()
        val s = settings.settings.first()
        assertThat(s.theme).isEqualTo(ThemeMode.DARK)
        assertThat(s.reminders.defaultSnoozeMinutes).isEqualTo(15)
    }

    @Test
    fun `export skips soft-deleted rows and reminder runtime state`() = runTest {
        seedSample()
        val root = Json.parseToJsonElement(backup.exportJson()).jsonObject

        assertThat((root["format"] as JsonPrimitive).content).isEqualTo("nudge-backup")
        assertThat((root["version"] as JsonPrimitive).content).isEqualTo("1")
        val taskIds = root["tasks"]!!.jsonArray.map { (it.jsonObject["id"] as JsonPrimitive).content }
        assertThat(taskIds).containsExactly("p", "c", "h")
        val keys = root["tasks"]!!.jsonArray.first().jsonObject.keys
        assertThat(keys.none { it.contains("reminder", ignoreCase = true) || it.contains("snooze", ignoreCase = true) })
            .isTrue()
    }

    @Test
    fun `import rebuilds reminder state anchored at import time`() = runTest {
        seedSample()
        val json = backup.exportJson()
        clock.now = Instant.parse("2026-10-01T06:00:00Z") // after p's due date (Sep 30 17:00 IST)

        backup.importJson(json, ImportMode.REPLACE)

        val p = tasks.get("p")!!
        assertThat(p.reminder.anchorAt).isEqualTo(clock.now)
        assertThat(p.reminder.count).isEqualTo(0)
        assertThat(p.reminder.nextAt).isNull()
        assertThat(p.reminder.lastRemindedAt).isNull()
        assertThat(p.reminder.dueReminderFired).isTrue() // due already passed
        val h = tasks.get("h")!!
        assertThat(h.reminder.count).isEqualTo(0)
        assertThat(h.reminder.dueReminderFired).isFalse()
    }

    // --- hand-built files ---

    private fun listJson(id: String, name: String, updatedAt: Instant = T0) = buildJsonObject {
        put("id", id)
        put("name", name)
        put("colorArgb", -10720320)
        put("sortOrder", 0.0)
        put("createdAt", T0.toString())
        put("updatedAt", updatedAt.toString())
    }

    private fun taskJson(
        id: String,
        title: String,
        listId: String = "l1",
        parentId: String? = null,
        updatedAt: Instant = T0,
        extra: Map<String, String> = emptyMap(),
    ) = buildJsonObject {
        put("id", id)
        put("listId", listId)
        put("parentId", parentId?.let(::JsonPrimitive) ?: JsonNull)
        put("title", title)
        put("sortOrder", 1024.0)
        put("createdAt", T0.toString())
        put("updatedAt", updatedAt.toString())
        extra.forEach { (k, v) -> put(k, v) }
    }

    private fun file(
        lists: List<JsonObject>,
        tasks: List<JsonObject>,
        version: Int = 1,
        format: String = "nudge-backup",
    ): String = buildJsonObject {
        put("format", format)
        put("version", version)
        put("exportedAt", T0.toString())
        put("lists", JsonArray(lists))
        put("tasks", JsonArray(tasks))
    }.toString()

    @Test
    fun `merge keeps the newer updatedAt per id and adds new rows`() = runTest {
        seedLists(aList(id = "l1", name = "Local name").copy(updatedAt = T0.plusSeconds(600)))
        seedTasks(
            aTask { id = "a"; title = "local newer" }.copy(updatedAt = T0.plusSeconds(600)),
            aTask { id = "b"; title = "local older" }.copy(updatedAt = T0),
            aTask { id = "c"; title = "local only" },
        )
        settings.update { it.copy(theme = ThemeMode.DARK) }
        val json = file(
            lists = listOf(
                listJson("l1", "Imported name", updatedAt = T0.plusSeconds(300)),
                listJson("l9", "New list"),
            ),
            tasks = listOf(
                taskJson("a", "import older", updatedAt = T0.plusSeconds(300)),
                taskJson("b", "import newer", updatedAt = T0.plusSeconds(300)),
                taskJson("d", "brand new"),
                taskJson("e", "in new list", listId = "l9"),
            ),
        )

        val result = backup.importJson(json, ImportMode.MERGE)

        assertThat(result).isEqualTo(ImportResult.Success(lists = 2, tasks = 4))
        assertThat(lists.get("l1")!!.name).isEqualTo("Local name")
        assertThat(lists.get("l9")!!.name).isEqualTo("New list")
        assertThat(tasks.get("a")!!.title).isEqualTo("local newer")
        assertThat(tasks.get("b")!!.title).isEqualTo("import newer")
        assertThat(tasks.get("c")!!.title).isEqualTo("local only")
        assertThat(tasks.get("d")!!.title).isEqualTo("brand new")
        assertThat(tasks.get("e")!!.listId).isEqualTo("l9")
        assertThat(settings.settings.first().theme).isEqualTo(ThemeMode.DARK) // merge leaves settings alone
    }

    @Test
    fun `merge does not resurrect a list deleted locally after the export`() = runTest {
        seedLists(aList(id = "keep"), aList(id = "l1", name = "Old"))
        seedTasks(aTask { id = "t"; listId = "l1" }.copy(updatedAt = T0))
        clock.now = T0.plusSeconds(600)
        lists.softDelete("l1") // local deletion is newer than the backup rows below
        val json = file(
            lists = listOf(listJson("l1", "Old", updatedAt = T0)),
            tasks = listOf(taskJson("t", "Task", updatedAt = T0)),
        )

        backup.importJson(json, ImportMode.MERGE)

        assertThat(lists.lists().map { it.id }).containsExactly("keep")
        assertThat(lists.get("l1")!!.deletedAt).isNotNull()
        assertThat(tasks.get("t")!!.deletedAt).isNotNull()
    }

    @Test
    fun `merge flattens a subtask whose local parent became a subtask`() = runTest {
        seedLists(aList(id = "l1"))
        seedTasks(
            aTask { id = "top" },
            aTask { id = "wasParent"; parentId = "top" }.copy(updatedAt = T0.plusSeconds(999)),
        )
        val json = file(
            lists = listOf(listJson("l1", "Work")),
            tasks = listOf(
                taskJson("wasParent", "Old parent", updatedAt = T0),
                taskJson("kid", "Kid", parentId = "wasParent"),
            ),
        )

        backup.importJson(json, ImportMode.MERGE)

        assertThat(tasks.get("wasParent")!!.parentId).isEqualTo("top")
        assertThat(tasks.get("kid")!!.parentId).isNull()
    }

    @Test
    fun `orphans are repaired on import`() = runTest {
        val json = file(
            lists = listOf(listJson("l1", "Work"), listJson("l2", "Home")),
            tasks = listOf(
                taskJson("orphan", "Orphan", parentId = "missing"),
                taskJson("p", "Parent"),
                taskJson("kid", "Kid", parentId = "p"),
                taskJson("grandkid", "Grandkid", parentId = "kid"),
                taskJson("stray", "Wrong list", listId = "l2", parentId = "p"),
                taskJson("lost", "Unknown list", listId = "nope"),
            ),
        )

        val result = backup.importJson(json, ImportMode.REPLACE)

        assertThat(result).isEqualTo(ImportResult.Success(lists = 2, tasks = 5))
        assertThat(tasks.get("orphan")!!.parentId).isNull()
        assertThat(tasks.get("kid")!!.parentId).isEqualTo("p")
        assertThat(tasks.get("grandkid")!!.parentId).isNull()
        assertThat(tasks.get("stray")!!.listId).isEqualTo("l1")
        assertThat(tasks.get("stray")!!.parentId).isEqualTo("p")
        assertThat(tasks.get("lost")).isNull()
    }

    @Test
    fun `invalid files are rejected without touching data`() = runTest {
        seedSample()
        val bad = listOf(
            "not json at all",
            "[]",
            "{}",
            "",
            file(lists = listOf(listJson("l1", "Work")), tasks = emptyList(), format = "todoist"),
            file(lists = listOf(listJson("l1", "Work")), tasks = listOf(taskJson("x", "   "))),
            file(lists = listOf(listJson("l1", "   ")), tasks = emptyList()),
            file(
                lists = listOf(listJson("l1", "Work")),
                tasks = listOf(taskJson("x", "Bad date", extra = mapOf("dueDate" to "2026-13-45"))),
            ),
            file(
                lists = listOf(buildJsonObject { put("id", "l1") }),
                tasks = emptyList(),
            ),
        )
        bad.forEach { json ->
            assertThat(backup.importJson(json, ImportMode.REPLACE)).isEqualTo(ImportResult.InvalidFile)
        }
        assertThat(tasks.get("p")).isNotNull()
        assertThat(lists.lists().map { it.id }).containsExactly("l1", "l2")
    }

    @Test
    fun `newer format version is reported as unsupported`() = runTest {
        val json = file(lists = listOf(listJson("l1", "Work")), tasks = emptyList(), version = 2)
        assertThat(backup.importJson(json, ImportMode.REPLACE)).isEqualTo(ImportResult.UnsupportedVersion(2))
    }

    @Test
    fun `unknown keys are ignored`() = runTest {
        val json = buildJsonObject {
            put("format", "nudge-backup")
            put("version", 1)
            put("exportedAt", T0.toString())
            put("futureField", "x")
            put("lists", buildJsonArray { add(listJson("l1", "Work")) })
            put("tasks", buildJsonArray { add(JsonObject(taskJson("t", "Task") + ("mood" to JsonPrimitive("happy")))) })
        }.toString()
        assertThat(backup.importJson(json, ImportMode.REPLACE)).isEqualTo(ImportResult.Success(1, 1))
    }

    @Test
    fun `replace with an empty file re-seeds the default list`() = runTest {
        seedSample()
        val result = backup.importJson(file(lists = emptyList(), tasks = emptyList()), ImportMode.REPLACE)
        assertThat(result).isEqualTo(ImportResult.Success(0, 0))
        assertThat(lists.lists().map { it.name }).containsExactly("My Tasks")
    }

    @Test
    fun `delete all data leaves only the default list and keeps onboarding done`() = runTest {
        seedSample()
        settings.update { it.copy(theme = ThemeMode.DARK, lastUsedListId = "l1") }

        backup.deleteAllData()

        assertThat(lists.lists().map { it.name }).containsExactly("My Tasks")
        assertThat(tasks.get("p")).isNull()
        val s = settings.settings.first()
        assertThat(s.onboardingDone).isTrue()
        assertThat(s.theme).isEqualTo(ThemeMode.SYSTEM)
        assertThat(s.lastUsedListId).isNull()
    }
}
