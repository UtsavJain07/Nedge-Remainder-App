# 06 — Data Model & Persistence

In v1, the local database **is** the backend. The schema is **sync-ready**: string UUID primary keys, `createdAt`/`updatedAt`, soft deletes via `deletedAt`, and no autoincrement IDs that would clash across devices.

---

## 1. Domain model (`:core:model`, pure Kotlin)

```kotlin
enum class Priority(val level: Int) {         // stored as `level` (stable ints; never use ordinal)
    NONE(0), LOW(1), MEDIUM(2), HIGH(3), URGENT(4);
    companion object { fun fromLevel(l: Int) = entries.first { it.level == l } }
}

enum class ReminderCadence(val intervalMinutes: Int?) {   // stored as name()
    EVERY_10_MIN(10), EVERY_30_MIN(30), EVERY_1_H(60), EVERY_2_H(120),
    EVERY_3_H(180), EVERY_5_H(300), TWICE_DAILY(null), OFF(null);
    val isInterval get() = intervalMinutes != null
}

enum class ReminderKind { INTERVAL, FIXED_TIME, DUE }   // why nextReminderAt was chosen

enum class ListSortMode { MY_ORDER, PRIORITY, DUE_DATE }

data class TaskList(
    val id: String,
    val name: String,
    val colorArgb: Int,
    val emoji: String?,
    val sortOrder: Double,
    val isDefault: Boolean,
    val sortMode: ListSortMode,
    val completedExpanded: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class Task(
    val id: String,
    val listId: String,
    val parentId: String?,               // null = top-level. Subtasks never have children (depth ≤ 1)
    val title: String,
    val notes: String,
    val priority: Priority,
    val cadenceOverride: ReminderCadence?,   // null = follow priority default
    val progress: Int,                   // 0..100, manual; ignored for display when task has subtasks
    val progressBeforeComplete: Int?,
    val isCompleted: Boolean,
    val completedAt: Instant?,
    val dueDate: LocalDate?,
    val dueTime: LocalTime?,             // only meaningful if dueDate != null
    val sortOrder: Double,
    val isExpanded: Boolean,
    val reminder: ReminderState,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class ReminderState(
    val anchorAt: Instant,               // interval schedule is counted from here (07 §4)
    val nextAt: Instant?,                // null = nothing scheduled
    val nextKind: ReminderKind?,
    val lastRemindedAt: Instant?,
    val count: Int,                      // reminders sent since anchor (shown as "Reminder #n")
    val snoozedUntil: Instant?,
    val dueReminderFired: Boolean,
)

/** Effective cadence = override ?: settings default for priority. */
fun Task.effectiveCadence(s: ReminderSettings): ReminderCadence = cadenceOverride ?: s.defaultCadence.getValue(priority)

data class TaskDraft(
    val listId: String, val parentId: String?, val title: String,
    val priority: Priority = Priority.NONE, val cadenceOverride: ReminderCadence? = null,
    val notes: String = "", val dueDate: LocalDate? = null, val dueTime: LocalTime? = null,
    val position: InsertPosition = InsertPosition.TOP,
)
enum class InsertPosition { TOP, BOTTOM }

/** Partial update; null = unchanged. Use Optional wrappers for nullable fields you need to clear. */
data class TaskPatch(
    val title: String? = null, val notes: String? = null, val priority: Priority? = null,
    val cadenceOverride: Patch<ReminderCadence?> = Patch.Unchanged,
    val progress: Int? = null, val dueDate: Patch<LocalDate?> = Patch.Unchanged,
    val dueTime: Patch<LocalTime?> = Patch.Unchanged, val snoozedUntil: Patch<Instant?> = Patch.Unchanged,
    val isExpanded: Boolean? = null,
)
sealed interface Patch<out T> { data object Unchanged : Patch<Nothing>; data class Set<T>(val value: T) : Patch<T> }
```

### 1.1 Settings model

```kotlin
data class UserSettings(
    val onboardingDone: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,           // SYSTEM, LIGHT, DARK
    val dynamicColor: Boolean = false,
    val pureBlack: Boolean = false,
    val tapAction: TapAction = TapAction.COMPLETE,     // COMPLETE, OPEN_DETAILS
    val newTaskPosition: InsertPosition = InsertPosition.TOP,
    val defaultPriority: Priority = Priority.NONE,
    val haptics: Boolean = true,
    val confirmDelete: Boolean = false,                // undo is the safety net
    val lastUsedListId: String? = null,
    val reminders: ReminderSettings = ReminderSettings(),
)

data class ReminderSettings(
    val defaultCadence: Map<Priority, ReminderCadence> = mapOf(
        Priority.URGENT to ReminderCadence.EVERY_10_MIN,
        Priority.HIGH   to ReminderCadence.EVERY_1_H,
        Priority.MEDIUM to ReminderCadence.EVERY_3_H,
        Priority.LOW    to ReminderCadence.TWICE_DAILY,
        Priority.NONE   to ReminderCadence.OFF,
    ),
    val morningTime: LocalTime = LocalTime.of(8, 0),
    val eveningTime: LocalTime = LocalTime.of(21, 0),
    val quietHoursEnabled: Boolean = true,
    val quietStart: LocalTime = LocalTime.of(22, 0),
    val quietEnd: LocalTime = LocalTime.of(8, 0),
    val urgentIgnoresQuietHours: Boolean = false,
    val defaultSnoozeMinutes: Int = 60,
    val pausedUntil: Instant? = null,                  // global pause; Instant.MAX = until resumed
)
```

---

## 2. Room schema (`:core:database`)

Database: `NudgeDatabase`, file `nudge.db`, `version = 1`, `exportSchema = true` (schemas in `core/database/schemas/`).

### 2.1 `task_list`

| Column | Type | Constraints / default |
|--------|------|-----------------------|
| `id` | TEXT | PK |
| `name` | TEXT | NOT NULL |
| `color_argb` | INTEGER | NOT NULL |
| `emoji` | TEXT | NULL |
| `sort_order` | REAL | NOT NULL |
| `is_default` | INTEGER (bool) | NOT NULL DEFAULT 0 |
| `sort_mode` | TEXT | NOT NULL DEFAULT 'MY_ORDER' |
| `completed_expanded` | INTEGER | NOT NULL DEFAULT 0 |
| `created_at` | INTEGER (epoch ms) | NOT NULL |
| `updated_at` | INTEGER | NOT NULL |
| `deleted_at` | INTEGER | NULL |

Index: `(deleted_at, sort_order)`.

### 2.2 `task`

| Column | Type | Constraints / default |
|--------|------|-----------------------|
| `id` | TEXT | PK |
| `list_id` | TEXT | NOT NULL, FK → `task_list.id` ON DELETE CASCADE |
| `parent_id` | TEXT | NULL, FK → `task.id` ON DELETE CASCADE |
| `title` | TEXT | NOT NULL |
| `notes` | TEXT | NOT NULL DEFAULT '' |
| `priority` | INTEGER | NOT NULL DEFAULT 0 (Priority.level) |
| `cadence_override` | TEXT | NULL (ReminderCadence.name) |
| `progress` | INTEGER | NOT NULL DEFAULT 0, CHECK 0..100 (enforce in code) |
| `progress_before_complete` | INTEGER | NULL |
| `is_completed` | INTEGER | NOT NULL DEFAULT 0 |
| `completed_at` | INTEGER | NULL |
| `due_epoch_day` | INTEGER | NULL (`LocalDate.toEpochDay()`) |
| `due_minute_of_day` | INTEGER | NULL (0..1439) |
| `sort_order` | REAL | NOT NULL |
| `is_expanded` | INTEGER | NOT NULL DEFAULT 1 |
| `reminder_anchor_at` | INTEGER | NOT NULL |
| `next_reminder_at` | INTEGER | NULL |
| `next_reminder_kind` | TEXT | NULL |
| `last_reminded_at` | INTEGER | NULL |
| `reminder_count` | INTEGER | NOT NULL DEFAULT 0 |
| `snoozed_until` | INTEGER | NULL |
| `due_reminder_fired` | INTEGER | NOT NULL DEFAULT 0 |
| `created_at` | INTEGER | NOT NULL |
| `updated_at` | INTEGER | NOT NULL |
| `deleted_at` | INTEGER | NULL |

Indexes:
- `index_task_list_parent_order` on `(list_id, parent_id, sort_order)`
- `index_task_parent` on `(parent_id)` (required for the FK)
- `index_task_next_reminder` on `(next_reminder_at)` (dispatcher query)
- `index_task_deleted` on `(deleted_at)`

Why store due date as epoch day plus minute of day, not an Instant: a due date is a **wall-clock** concept. "Tomorrow 5 PM" must stay 5 PM if the user changes time zone.

### 2.3 `task_fts` (search)

```kotlin
@Fts4(contentEntity = TaskEntity::class)
@Entity(tableName = "task_fts")
data class TaskFtsEntity(val title: String, val notes: String)
```
Query with `MATCH :query || '*'` (prefix), after sanitizing the user input (strip `"*-():^`), and join on `rowid`. Fallback when the sanitized query is empty: no results.

### 2.4 Entities → domain mapping

`TaskEntity.toDomain()` / `Task.toEntity()` in `core:database/mapper`. Enums map through `level`/`name`. Instants map through epoch ms.

---

## 3. DAOs (key queries)

```kotlin
@Dao
interface TaskDao {
    @Query("""SELECT * FROM task WHERE list_id = :listId AND deleted_at IS NULL
              ORDER BY parent_id IS NOT NULL, sort_order""")
    fun observeByList(listId: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM task WHERE id = :id")
    fun observe(id: String): Flow<TaskEntity?>

    @Query("SELECT * FROM task WHERE id = :id")
    suspend fun get(id: String): TaskEntity?

    @Query("SELECT * FROM task WHERE parent_id = :parentId AND deleted_at IS NULL ORDER BY sort_order")
    suspend fun children(parentId: String): List<TaskEntity>

    @Query("""SELECT * FROM task WHERE is_completed = 0 AND deleted_at IS NULL
              AND list_id IN (SELECT id FROM task_list WHERE deleted_at IS NULL)""")
    fun observeOpenAcrossLists(): Flow<List<TaskEntity>>

    @Query("""SELECT MIN(sort_order) FROM task WHERE list_id = :listId AND
              ((:parentId IS NULL AND parent_id IS NULL) OR parent_id = :parentId) AND deleted_at IS NULL""")
    suspend fun minSortOrder(listId: String, parentId: String?): Double?

    @Query("""SELECT MAX(sort_order) FROM task WHERE list_id = :listId AND
              ((:parentId IS NULL AND parent_id IS NULL) OR parent_id = :parentId) AND deleted_at IS NULL""")
    suspend fun maxSortOrder(listId: String, parentId: String?): Double?

    @Query("""SELECT * FROM task WHERE list_id = :listId AND
              ((:parentId IS NULL AND parent_id IS NULL) OR parent_id = :parentId) AND deleted_at IS NULL
              ORDER BY sort_order""")
    suspend fun siblings(listId: String, parentId: String?): List<TaskEntity>

    // --- reminders ---
    @Query("""SELECT * FROM task WHERE next_reminder_at IS NOT NULL AND next_reminder_at <= :until
              AND is_completed = 0 AND deleted_at IS NULL ORDER BY next_reminder_at""")
    suspend fun dueReminders(until: Long): List<TaskEntity>

    @Query("""SELECT MIN(next_reminder_at) FROM task WHERE next_reminder_at IS NOT NULL
              AND is_completed = 0 AND deleted_at IS NULL""")
    suspend fun earliestNextReminder(): Long?

    @Query("SELECT * FROM task WHERE is_completed = 0 AND deleted_at IS NULL")
    suspend fun allOpen(): List<TaskEntity>

    @Query("""UPDATE task SET next_reminder_at = :nextAt, next_reminder_kind = :kind, last_reminded_at = :lastAt,
              reminder_count = :count, snoozed_until = :snoozedUntil, due_reminder_fired = :dueFired,
              reminder_anchor_at = :anchorAt WHERE id = :id""")
    suspend fun updateReminderState(id: String, nextAt: Long?, kind: String?, lastAt: Long?, count: Int,
                                    snoozedUntil: Long?, dueFired: Boolean, anchorAt: Long)
    // NOTE: reminder-state updates do NOT bump updated_at (they are device-local, not user edits; see 09 §4)

    @Upsert suspend fun upsert(tasks: List<TaskEntity>)
    @Query("UPDATE task SET deleted_at = :now, updated_at = :now WHERE id IN (:ids)")
    suspend fun softDelete(ids: List<String>, now: Long)
    @Query("DELETE FROM task WHERE deleted_at IS NOT NULL AND deleted_at < :before")
    suspend fun purgeDeleted(before: Long): Int

    @Query("""SELECT task.* FROM task JOIN task_fts ON task.rowid = task_fts.rowid
              WHERE task_fts MATCH :ftsQuery AND task.deleted_at IS NULL""")
    fun search(ftsQuery: String): Flow<List<TaskEntity>>
}
```

`TaskListDao` mirrors this for lists, and also has:
```sql
-- stats per list (top-level tasks only)
SELECT list_id,
       SUM(CASE WHEN is_completed = 0 THEN 1 ELSE 0 END) AS open_count,
       SUM(CASE WHEN is_completed = 1 THEN 1 ELSE 0 END) AS completed_count,
       MAX(CASE WHEN is_completed = 0 AND priority = 4 THEN 1 ELSE 0 END) AS has_urgent
FROM task WHERE parent_id IS NULL AND deleted_at IS NULL GROUP BY list_id
```

---

## 4. Ordering algorithm (`OrderingCalculator`, `:core:domain`)

Siblings = tasks with the same `(listId, parentId)`, **including completed ones** (completed tasks keep their `sortOrder` so un-completing returns them to their old spot).

```kotlin
object OrderingCalculator {
    const val GAP = 1024.0
    const val MIN_GAP = 1e-6

    fun top(minExisting: Double?) = (minExisting ?: GAP) - GAP
    fun bottom(maxExisting: Double?) = (maxExisting ?: 0.0) + GAP

    /** before = sortOrder of the item that will be ABOVE, after = the item BELOW (nulls at edges). */
    fun between(before: Double?, after: Double?): Double = when {
        before == null && after == null -> 0.0
        before == null -> after!! - GAP
        after == null -> before + GAP
        else -> (before + after) / 2.0
    }

    fun needsRenormalize(before: Double?, after: Double?) =
        before != null && after != null && (after - before) < MIN_GAP

    /** Returns new sortOrders 0, GAP, 2*GAP … preserving current order. */
    fun renormalize(ordered: List<String>): Map<String, Double> =
        ordered.mapIndexed { i, id -> id to i * GAP }.toMap()
}
```

A move (`08`) computes `before`/`after` from the **visible open siblings in the target group**. If `needsRenormalize`, renormalize the target group in the same transaction first, then recompute.

List ordering on Home uses the same calculator.

---

## 5. Tree building & effective progress (`TaskTreeBuilder`, `:core:domain`)

Input: all tasks of a list (one query). Output:

```kotlin
data class TaskNode(val task: Task, val children: List<Task>) {       // children sorted: open by sortOrder, then completed by completedAt desc
    val openChildren get() = children.filterNot { it.isCompleted }
    val completedChildrenCount get() = children.count { it.isCompleted }
    val effectiveProgress: Int get() =
        if (task.isCompleted) 100
        else if (children.isEmpty()) task.progress
        else children.map { if (it.isCompleted) 100 else it.progress }.average().roundToInt()
}

data class ListTree(val open: List<TaskNode>, val completed: List<TaskNode>)
// open      = top-level !isCompleted, sorted per list.sortMode:
//             MY_ORDER → sortOrder; PRIORITY → priority desc, then sortOrder; DUE_DATE → due asc (nulls last), then sortOrder
// completed = top-level isCompleted, sorted by completedAt desc
```

**Flattening for the LazyColumn** (`ListItemUi` sealed type; this is exactly what `08` drags over):

```kotlin
sealed interface ListItemUi { val key: String }
data class TaskItemUi(val node: TaskNode?, val task: Task, val depth: Int, val isParent: Boolean,
                      val effectiveProgress: Int, val childCounter: String?) : ListItemUi { override val key = task.id }
data class CompletedHeaderUi(val count: Int, val expanded: Boolean) : ListItemUi { override val key = "completed-header" }

fun ListTree.flatten(completedExpanded: Boolean): List<ListItemUi> = buildList {
    open.forEach { n ->
        add(TaskItemUi(n, n.task, 0, n.children.isNotEmpty(), n.effectiveProgress, counterOf(n)))
        if (n.task.isExpanded) n.children.forEach { c -> add(TaskItemUi(null, c, 1, false, if (c.isCompleted) 100 else c.progress, null)) }
    }
    if (completed.isNotEmpty()) {
        add(CompletedHeaderUi(completed.size, completedExpanded))
        if (completedExpanded) completed.forEach { n ->
            add(TaskItemUi(n, n.task, 0, n.children.isNotEmpty(), 100, counterOf(n)))
            n.children.forEach { c -> add(TaskItemUi(null, c, 1, false, 100, null)) }
        }
    }
}
private fun counterOf(n: TaskNode) = if (n.children.isEmpty()) null else "${n.completedChildrenCount}/${n.children.size}"
```

**Integrity rules** (enforced in the repository, tested):
1. `parentId` must point to a task in the **same list** with `parentId == null`.
2. A task with children can never get a `parentId` (rejected with `IllegalStateException`; the UI prevents it first).
3. Moving a parent to another list moves its children too (same transaction).
4. Deleting a parent soft-deletes its children.
5. Completing a parent completes its open children (they get the same `completedAt`).
6. Un-completing a subtask whose parent is completed also un-completes the parent. (Otherwise an open subtask would be hidden inside the Completed section.)

---

## 6. Undo snapshots

```kotlin
data class UndoSnapshot(
    val tasksBefore: List<Task>,          // full rows as they were before the mutation
    val listsBefore: List<TaskList> = emptyList(),
    val createdTaskIds: List<String> = emptyList(),   // rows the mutation created (hard-delete on undo)
)
```

`restore(snapshot)` in one transaction: `upsert(tasksBefore)` with `updatedAt = now`, `upsert(listsBefore)`, delete `createdTaskIds`, then `reminderScheduler.onTasksChanged(all affected ids)`.

---

## 7. Settings persistence (`:core:datastore`)

Preferences DataStore file `settings.preferences_pb`. Keys:

| Key | Type | Default |
|-----|------|---------|
| `onboarding_done` | Boolean | false |
| `theme` | String | "SYSTEM" |
| `dynamic_color` | Boolean | false |
| `pure_black` | Boolean | false |
| `tap_action` | String | "COMPLETE" |
| `new_task_position` | String | "TOP" |
| `default_priority` | Int | 0 |
| `haptics` | Boolean | true |
| `confirm_delete` | Boolean | false |
| `last_used_list_id` | String | — |
| `cadence_urgent` / `_high` / `_medium` / `_low` / `_none` | String | per §1.1 |
| `morning_minute` / `evening_minute` | Int | 480 / 1260 |
| `quiet_enabled` | Boolean | true |
| `quiet_start_minute` / `quiet_end_minute` | Int | 1320 / 480 |
| `urgent_ignores_quiet` | Boolean | false |
| `default_snooze_minutes` | Int | 60 |
| `paused_until` | Long | — (absent = not paused; `Long.MAX_VALUE` = indefinitely) |

**Any change to a reminder setting** calls `reminderScheduler.rescheduleAll()`.

---

## 8. Seeding

On first DB creation (`RoomDatabase.Callback.onCreate`) do nothing in SQL. On app start, `ListRepository.ensureDefaultList()` inserts "My Tasks" (Indigo `#5C6BC0`, `is_default = 1`, `sort_order = 0`) if no non-deleted list exists. It is idempotent.

---

## 9. Migrations policy

- `exportSchema = true`. Every schema change bumps the version and adds an `AutoMigration`, or a manual `Migration` when renaming or splitting columns.
- **Never** use `fallbackToDestructiveMigration()` in release. Debug may use `fallbackToDestructiveMigrationOnDowngrade()`.
- `MigrationTestHelper` tests for every migration (`11`).
- Planned v2 migration (Phase 2 sync): add `sync_state INTEGER DEFAULT 1` (0 = clean, 1 = dirty) and `server_version INTEGER` to both tables (`09 §3`).

---

## 10. Backup / export format (`BackupRepository`)

A JSON file named `nudge-backup-YYYYMMDD-HHmm.json`, written through the Storage Access Framework (`ActivityResultContracts.CreateDocument("application/json")`).

```json
{
  "format": "nudge-backup",
  "version": 1,
  "exportedAt": "2026-09-27T10:15:00Z",
  "settings": { "...": "UserSettings as JSON" },
  "lists": [ { "id": "…", "name": "Work", "colorArgb": -10720320, "emoji": "💼", "sortOrder": 0.0,
               "isDefault": false, "sortMode": "MY_ORDER", "completedExpanded": false,
               "createdAt": "…", "updatedAt": "…" } ],
  "tasks": [ { "id": "…", "listId": "…", "parentId": null, "title": "…", "notes": "", "priority": 4,
               "cadenceOverride": null, "progress": 40, "isCompleted": false, "completedAt": null,
               "dueDate": "2026-09-28", "dueTime": "17:00", "sortOrder": 1024.0, "isExpanded": true,
               "createdAt": "…", "updatedAt": "…" } ]
}
```

- Reminder runtime state is **not** exported. It is recomputed on import with anchor = import time.
- Soft-deleted rows are not exported.
- Import: validate `format` and `version`. Mode **Replace** (wipe, then insert) or **Merge** (upsert by id; for the same id, the newer `updatedAt` wins). Then `rescheduleAll()`.
- Android Auto Backup includes `databases/nudge.db*` and the DataStore file (`backup_rules.xml` / `data_extraction_rules.xml`). After a restore, the first app start calls `rescheduleAll()` (detected via a `last_boot_id` mismatch or simply on every cold start; rescheduling is cheap).

## 11. Purge

`PurgeWorker` (WorkManager, periodic 24 h, requires device idle is not needed) hard-deletes tasks and lists with `deleted_at < now − 30 days`.
