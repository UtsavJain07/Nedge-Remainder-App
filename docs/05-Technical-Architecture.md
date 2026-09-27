# 05 — Technical Architecture

## 1. Architecture overview

```
┌──────────────────────────────── :app ────────────────────────────────┐
│ MainActivity (single activity) · NudgeApplication (@HiltAndroidApp)   │
│ NudgeNavHost · deep-link handling · WorkManager/Hilt config           │
└──────────────┬───────────────────────────────────────────────────────┘
               │ depends on
┌──────────────▼──────────── :feature:* (UI layer) ─────────────────────┐
│ onboarding · home · list · taskdetail · smartview · search · settings │
│ Each = Screen composables + ViewModel (StateFlow<UiState>) + Route    │
└──────────────┬───────────────────────────────────────────────────────┘
               │
┌──────────────▼────────────── :core:domain ────────────────────────────┐
│ Use cases (pure Kotlin): CreateTask, ToggleComplete, MoveTask,         │
│ NestTask, ComputeProgress, BuildTaskTree, Snapshot/Undo …             │
└──────┬────────────────────────────────────────────┬──────────────────┘
       │                                            │
┌──────▼───────── :core:data ─────────┐   ┌─────────▼──── :core:reminders ─────────┐
│ TaskRepository, ListRepository,     │   │ ReminderCalculator (pure)               │
│ SettingsRepository, BackupRepository│   │ ReminderScheduler / Dispatcher          │
│ (implement interfaces from domain)  │   │ AlarmManager wrapper, Receivers,        │
└──────┬───────────────┬──────────────┘   │ NotificationPublisher, Workers          │
       │               │                  └─────────────────────────────────────────┘
┌──────▼─────┐  ┌──────▼──────┐
│:core:      │  │:core:       │     :core:model (pure Kotlin data classes/enums)
│database    │  │datastore    │     :core:common (Clock, dispatchers, utils)
│(Room)      │  │(Prefs DS)   │     :core:designsystem (theme, components)
└────────────┘  └─────────────┘     :core:ui (shared feature UI: TaskRow wiring, DnD)
```

**Pattern:** MVVM with unidirectional data flow.
`UI event → ViewModel.onEvent() → UseCase → Repository → Room → Flow → ViewModel combines → StateFlow<UiState> → Compose`.
Side effects (snackbar, navigation, haptic) go through a `Channel<UiEffect>` collected once with `LaunchedEffect`.

**Reminder side effects:** repositories never call AlarmManager directly. Every write use case that affects reminders ends with `reminderScheduler.onTasksChanged(ids)`. The scheduler recomputes `nextReminderAt` for those tasks and re-arms the single dispatcher alarm (`07`).

---

## 2. Tech stack

Use the **latest stable** version of each at build time. The minimums below are known to have the needed APIs.

| Area | Library | Min version | Notes |
|------|---------|-------------|-------|
| Language | Kotlin | 2.2.0 | K2 compiler, Compose compiler Gradle plugin `org.jetbrains.kotlin.plugin.compose` |
| Build | Android Gradle Plugin | 8.12 | Version catalog `gradle/libs.versions.toml`, convention plugins in `build-logic/` |
| Build | Gradle | 8.14 | Configuration cache ON |
| Annotation processing | KSP | matching Kotlin | Room, Hilt |
| UI | Compose BOM | 2025.10.00 | |
| UI | `androidx.compose.material3:material3` | 1.4.0 | Expressive APIs (`MaterialExpressiveTheme`, `MotionScheme`, `MaterialShapes`). If Expressive is only in a newer version, use it |
| UI | `androidx.graphics:graphics-shapes` | 1.0.1 | Shape morphing |
| UI | `androidx.compose.material3.adaptive:*` | 1.1.0 | `ListDetailPaneScaffold` (tablets) |
| UI | `com.materialkolor:material-kolor` | 2.1 / 3.x | Seed → scheme for list colors |
| Navigation | `androidx.navigation:navigation-compose` | 2.9.0 | Type-safe `@Serializable` routes |
| Serialization | `kotlinx-serialization-json` | 1.8 | Routes, backup JSON |
| Lifecycle | `lifecycle-runtime-compose`, `lifecycle-viewmodel-compose` | 2.9 | `collectAsStateWithLifecycle` |
| DI | Hilt (`com.google.dagger:hilt-android`) | 2.56 | `hilt-navigation-compose`, `hilt-work` |
| DB | Room (`room-runtime`, `room-ktx`, `room-compiler`) | 2.7.0 | KSP, exported schemas, auto-migrations |
| Settings | DataStore Preferences | 1.1.x | |
| Background | WorkManager (`work-runtime-ktx`) | 2.10 | Reconciler, purge |
| Coroutines | kotlinx-coroutines | 1.10 | |
| Date/time | `java.time` (desugared on API 26 is not needed; java.time is available from 26) | — | Use `Instant`, `ZonedDateTime`, `LocalTime` |
| Splash | `androidx.core:core-splashscreen` | 1.0.1 | |
| Performance | `androidx.profileinstaller`, Baseline Profile Gradle plugin, Macrobenchmark | latest | |
| Logging | Timber | 5.x | Debug tree only in debug |
| Testing | JUnit4, `kotlinx-coroutines-test`, Turbine, MockK, Truth, Robolectric, Room testing, Compose UI test, `work-testing` | latest | See `11` |
| Static analysis | ktlint (via `org.jlleitschuh.gradle.ktlint` or Spotless), detekt | latest | CI gate |
| Leak detection | LeakCanary (debug only) | 2.14 | |

**Explicitly not used:** RxJava, LiveData, XML layouts, Accompanist (deprecated pieces), Firebase in v1, Lottie (animations are Canvas/Compose so the code is self-contained).

---

## 3. Module structure

```
nudge/
├── app/
├── build-logic/convention/          # Gradle convention plugins
│   ├── AndroidApplicationConventionPlugin.kt   (nudge.android.application)
│   ├── AndroidLibraryConventionPlugin.kt       (nudge.android.library)
│   ├── AndroidComposeConventionPlugin.kt       (nudge.android.compose)
│   ├── AndroidFeatureConventionPlugin.kt       (nudge.android.feature → library+compose+hilt+core deps)
│   ├── AndroidHiltConventionPlugin.kt          (nudge.hilt)
│   ├── AndroidRoomConventionPlugin.kt          (nudge.android.room)
│   └── JvmLibraryConventionPlugin.kt           (nudge.jvm.library)
├── core/
│   ├── model/          (JVM)      entities of the domain: Task, TaskList, Priority, ReminderCadence, UserSettings …
│   ├── common/         (JVM)      Clock, DispatcherProvider, IdGenerator, Result helpers, time utils
│   ├── domain/         (JVM)      use cases + repository interfaces + TaskTreeBuilder + OrderingCalculator
│   ├── database/       (Android)  Room DB, entities, DAOs, mappers, migrations
│   ├── datastore/      (Android)  Preferences DataStore + serializer for UserSettings
│   ├── data/           (Android)  Repository implementations, BackupRepository (JSON)
│   ├── reminders/      (Android)  ReminderCalculator (pure), scheduler, receivers, notifications, workers, health checker
│   ├── designsystem/   (Android)  NudgeTheme, tokens, components (04 §7)
│   ├── ui/             (Android)  shared feature UI: TaskRowItem wiring, drag-and-drop engine (08), UiText, snackbar controller
│   └── testing/        (Android)  fakes (FakeTaskRepository, TestClock), test rules, test data builders
└── feature/
    ├── onboarding/
    ├── home/
    ├── list/
    ├── taskdetail/     (sheet composable used by list/home/smartview)
    ├── quickadd/       (sheet composable)
    ├── smartview/
    ├── search/
    └── settings/
```

**Dependency rules (enforced by review; add the `dependency-analysis` plugin to report):**
- `feature:*` → `core:domain`, `core:model`, `core:ui`, `core:designsystem`, `core:common`. **Never** `core:database` or another `feature`, with this exception: `feature:list`/`home`/`smartview` may depend on `feature:taskdetail` and `feature:quickadd` (the shared sheets).
- `core:domain` → `core:model`, `core:common` only (pure Kotlin/JVM).
- `core:data` → `core:database`, `core:datastore`, `core:domain`, `core:reminders` (interface only, `ReminderScheduler`), `core:model`, `core:common`.
- `core:reminders` → `core:domain` (repository interfaces), `core:model`, `core:common`, `core:designsystem` (colors for notifications).
- `app` → everything (for DI graph assembly and navigation).

> **Pragmatic note for AI builders:** if the multi-module setup blocks progress, you may build M1–M2 in a single `:app` module **with the same package structure** (`app.nudge.core.model`, `app.nudge.feature.home`, …) and split into modules before M5. The package names below are the same either way.

---

## 4. Package layout (inside each module)

```
app.nudge.feature.list/
├── ListRoute.kt            // @Serializable data class ListRoute(val listId: String, val highlightTaskId: String? = null)
├── ListScreen.kt           // stateful wrapper: hiltViewModel(), collect state, effects
├── ListContent.kt          // stateless UI (previewable)
├── ListViewModel.kt
├── ListUiState.kt          // UiState + UiEvent + UiEffect sealed types
├── components/             // screen-private composables
└── navigation/ListNavigation.kt  // NavGraphBuilder.listScreen(...) + NavController.navigateToList(...)
```

---

## 5. Core contracts (interfaces)

```kotlin
// core:common
interface Clock { fun now(): Instant; fun zone(): ZoneId }
class SystemClock @Inject constructor() : Clock { override fun now() = Instant.now(); override fun zone() = ZoneId.systemDefault() }

interface DispatcherProvider { val io: CoroutineDispatcher; val default: CoroutineDispatcher; val main: CoroutineDispatcher }
fun interface IdGenerator { fun newId(): String }   // UUID v4 string (v7 preferred if available)

// core:domain — repository interfaces
interface TaskRepository {
    fun observeList(listId: String): Flow<List<Task>>                  // all non-deleted tasks of the list (open + completed)
    fun observeOpenTasksAcrossLists(): Flow<List<Task>>
    fun observeTask(id: String): Flow<Task?>
    fun search(query: String): Flow<List<Task>>
    suspend fun get(id: String): Task?
    suspend fun create(draft: TaskDraft): Task
    suspend fun update(id: String, patch: TaskPatch): Task
    suspend fun setCompleted(id: String, completed: Boolean): UndoSnapshot
    suspend fun move(op: MoveOperation): UndoSnapshot                    // reorder / nest / un-nest / change list
    suspend fun softDelete(id: String): UndoSnapshot
    suspend fun restore(snapshot: UndoSnapshot)
    suspend fun deleteCompleted(listId: String): UndoSnapshot
    suspend fun tasksWithReminderDueBefore(instant: Instant): List<Task>
    suspend fun earliestNextReminder(): Instant?
    suspend fun allOpenTasksWithReminders(): List<Task>
    suspend fun updateReminderState(updates: List<ReminderStateUpdate>)
}

interface ListRepository {
    fun observeLists(): Flow<List<TaskList>>
    fun observeListStats(): Flow<Map<String, ListStats>>   // open count, completed count, hasUrgent
    suspend fun create(name: String, color: Int, emoji: String?): TaskList
    suspend fun update(id: String, name: String?, color: Int?, emoji: String?)
    suspend fun reorder(id: String, beforeId: String?, afterId: String?)
    suspend fun softDelete(id: String): UndoSnapshot
    suspend fun restore(snapshot: UndoSnapshot)
    suspend fun ensureDefaultList()
}

interface SettingsRepository {
    val settings: Flow<UserSettings>
    suspend fun update(transform: (UserSettings) -> UserSettings)
}

// core:reminders (interface lives in core:domain so data layer can call it)
interface ReminderScheduler {
    suspend fun onTasksChanged(taskIds: Collection<String>)
    suspend fun rescheduleAll()          // boot, time change, settings change, reconciler
    suspend fun cancelNotification(taskId: String)
}
```

---

## 6. ViewModel pattern (reference implementation)

```kotlin
@HiltViewModel
class ListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val observeListScreen: ObserveListScreenUseCase,   // combines list + tasks + settings → ListScreenModel
    private val toggleComplete: ToggleCompleteUseCase,
    private val moveTask: MoveTaskUseCase,
    private val deleteTask: DeleteTaskUseCase,
    private val restore: RestoreSnapshotUseCase,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<ListRoute>()
    private val _effects = Channel<ListUiEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private val dragPreview = MutableStateFlow<DragPreview?>(null)   // in-flight drag order, see 08

    val uiState: StateFlow<ListUiState> = combine(observeListScreen(route.listId), dragPreview) { model, preview ->
        ListUiState.Ready(model.applyPreview(preview))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListUiState.Loading)

    fun onEvent(e: ListUiEvent) = when (e) {
        is ListUiEvent.ToggleComplete -> launchUndoable("Completed '${e.title}'") { toggleComplete(e.taskId) }
        is ListUiEvent.Delete -> launchUndoable("Deleted '${e.title}'") { deleteTask(e.taskId) }
        is ListUiEvent.Drop -> launchUndoable(null) { moveTask(e.operation) }
        // …
    }

    private fun launchUndoable(message: String?, block: suspend () -> UndoSnapshot) = viewModelScope.launch {
        val snapshot = block()
        if (message != null) _effects.send(ListUiEffect.ShowUndo(message, snapshot))
    }
    fun undo(snapshot: UndoSnapshot) = viewModelScope.launch { restore(snapshot) }
}
```

UI state is **immutable** (`@Immutable` data classes, `kotlinx.collections.immutable` `ImmutableList`), so Compose can skip recompositions.

---

## 7. Hilt modules

| Module | Provides |
|--------|----------|
| `CommonModule` (core:common) | `Clock` → `SystemClock`, `DispatcherProvider`, `IdGenerator`, `@ApplicationScope CoroutineScope` (SupervisorJob + default) |
| `DatabaseModule` (core:database) | `NudgeDatabase` (singleton), DAOs |
| `DataStoreModule` (core:datastore) | `DataStore<Preferences>` |
| `DataModule` (core:data) | `@Binds` repositories |
| `RemindersModule` (core:reminders) | `@Binds ReminderScheduler`, `AlarmManager`, `NotificationManagerCompat` |
| `WorkModule` (app) | `HiltWorkerFactory` via `Configuration.Provider` on `NudgeApplication` (remove the default WorkManager initializer in the manifest) |

---

## 8. Concurrency & consistency

- All DB writes that touch several rows run in `database.withTransaction { }`.
- Reminder re-arming after writes runs on the `@ApplicationScope` scope (not `viewModelScope`), so it survives the screen closing. Wrap it in a `Mutex` inside `ReminderSchedulerImpl` to serialize recomputations.
- Receivers use `goAsync()` + the application scope with a 9-second timeout.
- `SharingStarted.WhileSubscribed(5_000)` for UI flows.

## 9. Error handling & logging

- Repositories throw only for programmer errors. Expected failures (import parse errors) return a `sealed interface ImportResult`.
- A global `CoroutineExceptionHandler` in the application scope logs via Timber. In v1 there is no crash reporting SDK (privacy). An optional Phase 2 path is Firebase Crashlytics, opt-in.
- `StrictMode` is enabled in debug builds (disk/network on the main thread → penaltyLog + penaltyFlashScreen).

## 10. App manifest essentials

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM"/>
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>
<uses-permission android:name="android.permission.VIBRATE"/>
<!-- NOT used: USE_EXACT_ALARM (Play policy restricts to alarm/calendar apps), INTERNET (v1) -->

<application android:name=".NudgeApplication" android:allowBackup="true"
    android:dataExtractionRules="@xml/data_extraction_rules" android:fullBackupContent="@xml/backup_rules"
    android:enableOnBackInvokedCallback="true" android:localeConfig="@xml/locales_config" ...>
  <activity android:name=".MainActivity" android:exported="true" android:launchMode="singleTop"
      android:windowSoftInputMode="adjustResize" android:theme="@style/Theme.Nudge.Splash">
    <intent-filter> MAIN / LAUNCHER </intent-filter>
    <intent-filter> <action VIEW/> <category DEFAULT/> <data android:scheme="nudge" android:host="task"/> </intent-filter>
  </activity>
  <receiver android:name="app.nudge.core.reminders.receiver.ReminderAlarmReceiver" android:exported="false"/>
  <receiver android:name="app.nudge.core.reminders.receiver.NotificationActionReceiver" android:exported="false"/>
  <receiver android:name="app.nudge.core.reminders.receiver.SystemEventsReceiver" android:exported="true">
    <intent-filter>
      <action android:name="android.intent.action.BOOT_COMPLETED"/>
      <action android:name="android.intent.action.TIME_SET"/>
      <action android:name="android.intent.action.TIMEZONE_CHANGED"/>
      <action android:name="android.intent.action.MY_PACKAGE_REPLACED"/>
      <action android:name="android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"/>
    </intent-filter>
  </receiver>
  <provider androidx.startup.InitializationProvider … remove WorkManagerInitializer (tools:node="remove")/>
</application>
```

`SystemEventsReceiver` must check `intent.action` against the whitelist and ignore anything else.

## 11. Build variants

| Variant | applicationIdSuffix | Minify | Notes |
|---------|---------------------|--------|-------|
| debug | `.debug` | no | LeakCanary, StrictMode, "Debug tools" section in Settings (fire dispatcher now, list scheduled reminders, time travel +10 min) |
| release | — | R8 full mode + resource shrinking | Signed with the upload key (`12`) |
| benchmark | `.benchmark` | yes | For Macrobenchmark/baseline profiles |

## 12. Coding conventions

- Kotlin official style (ktlint). Max line 120.
- Composables: `PascalCase`. Stateless `XxxContent` + stateful `XxxScreen`. The `modifier: Modifier = Modifier` parameter comes first among the optional params.
- No business logic in composables. No `Context` in ViewModels (use `@ApplicationContext` only in data/reminders).
- Strings only in `strings.xml`. `UiText` sealed class for ViewModel-produced text.
- Every public class/function in `core:*` has KDoc citing requirement IDs where applicable.
- Time: **store** `Instant` as epoch millis (`Long`). **Compute** with `ZonedDateTime` in `clock.zone()`. Never use `System.currentTimeMillis()` directly; always go through `Clock`.
