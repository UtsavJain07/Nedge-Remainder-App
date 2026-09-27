# 10 — Implementation Plan

Build in this order. Each milestone ends in a **runnable app**, green tests and a ticked Definition of Done (DoD). Estimates assume one experienced developer (or an AI agent with review), in ideal days.

| Milestone | Theme | Est. |
|-----------|-------|------|
| M0 | Project foundation | 1.5 d |
| M1 | Data layer & domain | 3 d |
| M2 | Lists & tasks UI (no DnD) | 4 d |
| M3 | Subtasks, completion, progress, detail sheet | 3 d |
| M4 | **Reminder engine** | 4 d |
| M5 | Drag & drop (reorder / nest) | 3 d |
| M6 | Home dashboard, smart views, search | 2.5 d |
| M7 | Onboarding, settings, health, backup | 2.5 d |
| M8 | Motion polish & accessibility | 3 d |
| M9 | Performance, QA, release | 2.5 d |
| **Total** | | **≈ 29 d** |

---

## M0 — Foundation

- [ ] Create the Android project `Nudge`, package `app.nudge.reminders` (applicationId), namespace per module `app.nudge.<module>`.
- [ ] `gradle/libs.versions.toml` with every library in `05 §2`.
- [ ] `build-logic` convention plugins (`05 §3`). Create all modules as empty shells with the correct dependencies.
- [ ] minSdk 26, target/compile 36, Java 17 toolchain, `kotlinOptions.jvmTarget = 17`.
- [ ] Hilt: `NudgeApplication` (`@HiltAndroidApp`, `Configuration.Provider`), `MainActivity` (`@AndroidEntryPoint`, `enableEdgeToEdge()`, splash screen).
- [ ] `NudgeTheme` skeleton with the color schemes, typography and shapes from `04`.
- [ ] `NudgeNavHost` with placeholder Home.
- [ ] ktlint + detekt configured. `./gradlew check` passes.
- [ ] GitHub Actions CI (`12 §3`) running `check`, `testDebugUnitTest`, `assembleDebug`.
- [ ] `core:common`: `Clock`, `SystemClock`, `DispatcherProvider`, `IdGenerator`, `@ApplicationScope`.

**DoD:** the app launches to a themed empty Home in light and dark, and CI is green.

## M1 — Data layer & domain

- [ ] `core:model`: every type in `06 §1`.
- [ ] `core:database`: entities, DAOs, mappers, FTS table, `NudgeDatabase`, schema export.
- [ ] `core:datastore`: settings keys and `UserSettings` mapping (`06 §7`).
- [ ] `core:domain`: repository interfaces, `OrderingCalculator`, `TaskTreeBuilder` + `flatten`, `UndoSnapshot`, `ReminderScheduler` interface.
- [ ] Use cases: `CreateTask`, `UpdateTask`, `ToggleComplete` (with parent/child rules FR-34, and rule 6 in `06 §5`), `DeleteTask`, `RestoreSnapshot`, `MoveTask`, `CreateList`, `UpdateList`, `DeleteList`, `ReorderLists`, `ObserveListScreen`, `ObserveHome`, `SetProgress` (FR-43: 100 ⇒ complete), `Snooze`.
- [ ] `core:data`: repository implementations. Temporarily bind a `NoOpReminderScheduler`.
- [ ] `ensureDefaultList()` on app start.
- [ ] `core:testing`: `TestClock`, fakes, builders (`aTask { }`, `aList { }`).
- [ ] Unit tests: OrderingCalculator, TaskTreeBuilder (progress, sorting, flatten), every use case, and DAO tests (Robolectric/instrumented).

**DoD:** coverage ≥ 80% in domain/data, and every integrity rule in `06 §5` has a test.

## M2 — Lists & tasks UI

- [ ] Design system components: `NudgeCheckbox` (basic, animated later), `PriorityFlag`, `PriorityChipRow`, `ProgressBarThin`, `ListCard` (basic), `SectionHeader`, `EmptyState`.
- [ ] Home (simple version): list grid + New list card + FAB.
- [ ] Create/Edit List sheet (name, 12 colors, emoji).
- [ ] List screen: top bar tinted with the list color (seed theme), open tasks, Completed section (collapsed), FAB → Quick Add.
- [ ] Quick Add sheet: title, priority chips, Enter = save & stay open, discard confirm.
- [ ] Tap row → complete, with the Undo snackbar. Tap a completed row → un-complete.
- [ ] Swipe right to complete / left to delete (`SwipeToDismissBox`) + Undo.
- [ ] List overflow: rename, color, delete completed, delete list (+Undo).
- [ ] Navigation Home ↔ List (plain transition for now).

**DoD:** US-1 (minus the reminder) and US-5 pass as Compose UI tests.

## M3 — Subtasks, progress, detail

- [ ] The flattened list renders subtasks (indent 40 dp), the expand/collapse chevron, and the "1/3" counter.
- [ ] The "+" add-subtask button on the row → Quick Add with `parentId`.
- [ ] Task Detail sheet (`03 §3.5`) with autosave: title, priority, progress slider (disabled with the computed ring for parents), due date/time pickers, cadence picker (UI only), notes, inline subtasks.
- [ ] Context menu (long-press without moving) with all actions, including "Make subtask of ›" / "Move to top level" / "Move to list ›".
- [ ] FR-35 display of completed subtasks, and FR-36 snackbar.

**DoD:** US-6 passes. A parent-completion cascade with Undo is verified in a UI test.

## M4 — Reminder engine

- [ ] `ReminderCalculator` + **all of C1–C25** (`07 §12`).
- [ ] `AlarmScheduler`, `ReminderSchedulerImpl` (Mutex), `ReminderDispatcher`, and `armNextAlarm`.
- [ ] Receivers: `ReminderAlarmReceiver`, `NotificationActionReceiver`, `SystemEventsReceiver` (manifest `05 §10`).
- [ ] `NotificationPublisher`: channels, per-task notification, group summary, actions.
- [ ] `ReconcilerWorker` + `PurgeWorker` enqueued in `NudgeApplication`.
- [ ] Wire the real scheduler into the use cases (replace the NoOp). Apply the anchor reset rules (`07 §3.1`).
- [ ] Deep link `nudge://task/{id}` → List + highlight + open detail.
- [ ] Task Detail reminders card: "Repeats…", live "Next nudge in…", snooze row, "Nudged n times".
- [ ] Debug tools (`07 §11`).
- [ ] Robolectric tests D1–D7.

**DoD:** on a real device (Pixel, Android 14+) with the exact alarm permission: an Urgent task nags at +10 and +20 min; Done from the notification works; a reboot preserves the schedule; quiet hours are respected (use debug time travel).

## M5 — Drag & drop

- [ ] `DragDropState` per `08` in `core:ui/dnd`.
- [ ] Integrate it into the List screen: live reorder, nest dwell and highlight, reject shake, parent carries children, horizontal indent/outdent, auto-scroll, pager/swipe disabled while dragging.
- [ ] `MoveTaskUseCase` + nest Undo snackbar.
- [ ] Accessibility custom actions (`08 §6`).
- [ ] Tests T1–T6 + `ListDragTest`.
- [ ] Home list-card reorder (FR-04) using the `sh.calvin.reorderable` library on `LazyVerticalGrid` (no nesting is needed there).

**DoD:** US-4 passes on a device and in a UI test. There is no flicker on drop.

## M6 — Home dashboard, smart views, search

- [ ] Greeting header, Focus now strip, smart view cards, list cards with progress ring and urgent dot.
- [ ] Today / All smart view screens.
- [ ] Search screen with FTS.
- [ ] List pager with color dots (FR-85).
- [ ] Home FAB → Quick Add with a list picker chip (remembers the last used list).

**DoD:** FR-80 to FR-85 are demonstrable.

## M7 — Onboarding, settings, health, backup

- [ ] 3-page onboarding with permission cards.
- [ ] Settings screens (`02 §5.10`), with every reminder setting triggering `rescheduleAll()`.
- [ ] Reminder health screen + Home banner + test nudge.
- [ ] Pause all reminders (FR-71) + Home chip.
- [ ] JSON export/import (Replace/Merge) + delete all data.
- [ ] Auto Backup rules XML.

**DoD:** a fresh install shows the full onboarding, and denying the permissions shows the degraded banner with working Fix buttons.

## M8 — Motion & accessibility polish

- [ ] Implement **A1–A20** exactly (`04 §6.2`), including the checkbox morph, particles, strike-through and confetti.
- [ ] Shared element list card → List screen (A8).
- [ ] Reduced-motion handling (`04 §6.5`).
- [ ] Haptics map (`03 §7`) + the setting.
- [ ] TalkBack pass on every screen. Font scale 200%. Contrast check.
- [ ] Tablet layout (list-detail) if time allows.

**DoD:** the accessibility scanner reports no issues, and a manual TalkBack run of every flow works.

## M9 — Performance, QA, release

- [ ] Baseline profile + Macrobenchmark (startup, scroll 500 tasks, complete task).
- [ ] R8 rules verified (kotlinx-serialization, Room, Hilt).
- [ ] The manual QA checklist in `11 §6` is fully passed on at least 2 devices (Pixel + one Samsung/Xiaomi).
- [ ] App icon, store listing assets, privacy policy page.
- [ ] Signed release AAB/APK through the CI release workflow (`12`).

**DoD:** `v1.0.0` tagged, release artifacts published, `CHANGELOG.md` updated.

---

## Phase 2 backlog (ordered)

1. Home screen widget (Glance): "Focus now" + quick add.
2. Cloud sync & account (`09`).
3. Recurring tasks (RRULE subset: daily/weekly/monthly) with `recurrence` column.
4. Natural-language quick add ("tomorrow 5pm !1 #Work").
5. Escalation: cadence speeds up as the due date nears.
6. Stats screen (completion trends).
7. Quick settings tile + app shortcuts ("New task").
8. Wear OS complication/tile.
