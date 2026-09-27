# 11 — Testing & QA

## 1. Test pyramid

| Layer | Tooling | Location | Target |
|-------|---------|----------|--------|
| Pure unit (JVM) | JUnit4, Truth, Turbine, MockK, `kotlinx-coroutines-test` | `core:domain`, `core:reminders` (calculator), `core:ui` (DnD state) | ≥ 90% line coverage for calculator/ordering/tree |
| Data (Robolectric or instrumented) | Room in-memory DB, `MigrationTestHelper`, DataStore with a temp file | `core:database`, `core:data`, `core:datastore` | Every DAO query and integrity rule |
| Android components | Robolectric (`ShadowAlarmManager`, `ShadowNotificationManager`), `work-testing` | `core:reminders` | Dispatcher, receivers, workers |
| UI | Compose UI test (`createAndroidComposeRule`), Hilt test runner with fakes | `feature:*`, `app` | Every user story US-1…US-7 |
| Screenshot | Compose Preview Screenshot Testing (or Roborazzi) | `core:designsystem`, feature `*Content` | Light/dark, font 1.0/2.0 |
| Performance | Macrobenchmark + Baseline Profile | `:benchmark` | Startup < 800 ms, scroll jank < 1% |

Rules:
- Inject `Clock` everywhere. Tests use `TestClock(now = …, zone = …)`.
- Inject `DispatcherProvider`. Tests use `StandardTestDispatcher`.
- There is no `Thread.sleep`. Use `advanceTimeBy` / `advanceUntilIdle`.
- ViewModel tests use Turbine on `uiState` and `effects`.

## 2. Domain tests (must exist)

| Suite | Cases |
|-------|-------|
| `OrderingCalculatorTest` | top/bottom/between at the edges, renormalize trigger at < 1e-6, renormalize keeps order |
| `TaskTreeBuilderTest` | effective progress (none, manual, with children, completed child = 100, rounding), sort modes (MY_ORDER, PRIORITY, DUE_DATE with nulls last), completed ordering by `completedAt` desc, flatten with collapsed parents and with the completed section collapsed/expanded |
| `ToggleCompleteUseCaseTest` | complete a leaf; complete a parent cascades to open children only; un-complete a parent does NOT un-complete children that were completed earlier (restore via snapshot only); un-completing a child of a completed parent un-completes the parent; the snapshot restores exactly |
| `SetProgressUseCaseTest` | 100 → completes + stores `progressBeforeComplete`; un-complete restores it; parent progress is rejected (computed) |
| `MoveTaskUseCaseTest` | all rules in `06 §5` + `08 §5.2` |
| `CreateTaskUseCaseTest` | top/bottom insertion, title trim/validation (blank rejected, > 200 rejected), anchor = now, reminder scheduled |
| `DeleteListUseCaseTest` | cascades soft delete, the last list can't be deleted, Undo |

## 3. Reminder tests (must exist)

- `ReminderCalculatorTest`: **C1–C25** from `07 §12`, parameterized.
- `QuietHoursTest`: windows crossing midnight and same-day windows, the boundaries (start inclusive, end exclusive), DST gap and overlap.
- `ReminderDispatcherTest` (Robolectric): **D1–D7**.
- `AlarmSchedulerTest`: exact vs inexact based on `canScheduleExactAlarms` (Shadow), `SecurityException` fallback.
- `SystemEventsReceiverTest`: each whitelisted action → `rescheduleAll`; an unknown action → ignored.
- `NotificationPublisherTest`: channel by priority/kind, stable IDs, summary appears at ≥ 2 and disappears below 2, actions present, notes in BigText.

## 4. UI tests (Compose)

| Test | Covers |
|------|--------|
| `QuickAddTest` | US-1: Enter keeps the sheet open, the priority chip sets the flag, discard dialog |
| `CompleteTaskTest` | US-5: tap → Completed section, Undo |
| `SubtaskTest` | + on a row adds a subtask; parent counter; FR-36 snackbar |
| `ProgressTest` | US-6: slider, disabled for parents, 100 → completes |
| `ListDragTest` | US-4: nest via drag; reorder; un-nest via drag left |
| `TaskDetailAutosaveTest` | edits persist after closing the sheet |
| `SettingsReminderTest` | changing the Urgent cadence reschedules (verified with a fake scheduler) |
| `AccessibilityActionsTest` | custom actions Move up/down/Make subtask |
| `DeepLinkTest` | `nudge://task/{id}` opens the correct list and detail |

## 5. Migration tests

`MigrationTest` for every version bump, using the exported schemas. v1 has no migration yet; the test harness is set up anyway.

## 6. Manual QA checklist (run before every release, on ≥ 2 devices)

**Install & onboarding**
- [ ] A fresh install shows onboarding; permissions granted → Home shows no banner.
- [ ] Deny notifications → the Home health banner appears; Fix → the system screen; granting it clears the banner on return.
- [ ] Deny exact alarms → the banner reads "Reminders may be delayed".

**Lists & tasks**
- [ ] Create 3 lists with different colors; each List screen is tinted correctly in light and dark.
- [ ] Quick Add 10 tasks rapidly with Enter; order is correct (Top setting).
- [ ] Tap to complete → animation → Completed (n) increments; Undo restores the position.
- [ ] Swipe left deletes with Undo; swipe right completes.
- [ ] Delete a list with tasks → Undo restores everything.

**Subtasks & DnD**
- [ ] Drag a task onto another → it nests; the counter updates; Undo works.
- [ ] Dragging a parent onto another → reject shake; the order is unchanged unless dropped in a reorder zone.
- [ ] Drag a subtask left → top level. Drag a task right under another → indented.
- [ ] Auto-scroll works in a list of 60 tasks.
- [ ] TalkBack custom actions perform the same moves.

**Reminders** (use debug time travel where possible, and real time for at least one of each)
- [ ] Urgent: notifications at +10 and +20 min (real time), replacing (not stacking).
- [ ] High: +1 h. Medium: +3 h. Low: 08:00 and 21:00.
- [ ] Override 2 h / 5 h works.
- [ ] Quiet hours hold reminders and release one at 08:00.
- [ ] Done from the notification completes the task (and its subtasks) and the notification disappears.
- [ ] Snooze from the notification → the next one at +1 h.
- [ ] A due-time reminder fires at the due time, even in quiet hours.
- [ ] Reboot the device → reminders continue.
- [ ] Change the timezone → fixed times follow local wall-clock.
- [ ] Force-stop via recents swipe (not Force stop in Settings) → reminders continue (the reconciler covers it).
- [ ] 5 tasks due at once → a grouped summary "5 tasks need you".
- [ ] Pause all for 1 h → no reminders; resume after.
- [ ] Samsung/Xiaomi: follow the OEM tip and verify reminders over 2 hours with the screen off.

**Settings & data**
- [ ] Theme switch, dynamic color, pure black.
- [ ] Tap action "Open details" changes the row tap behavior.
- [ ] Export → uninstall → reinstall → Import (Replace) restores everything, and reminders are rescheduled.

**Quality**
- [ ] Font size max + display size max: no clipped text.
- [ ] System "Remove animations" → decorative animations skipped.
- [ ] Rotation / fold: state preserved; no crash.
- [ ] No ANR or StrictMode violations in the debug log for all flows.

## 7. Bug severity

| Sev | Definition | Release blocker? |
|-----|-----------|------------------|
| S1 | Data loss, crash on a main flow, reminders not firing | Yes |
| S2 | Wrong reminder timing, broken DnD result, accessibility blocker | Yes |
| S3 | Visual glitch, animation jank | No (fix within the next release) |
| S4 | Cosmetic / copy | No |
