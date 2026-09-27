# 02 — Product Requirements Document (PRD)

| Field | Value |
|-------|-------|
| Product | Nudge (working name). Android to-do app with priority-based periodic reminders |
| Version | v1.0 (MVP) |
| Platform | Android 8.0+ (API 26+), phones first. Tablets supported by responsive layout, not a tailored UI |
| Status | Approved for build |
| Owner | Product owner (the user) |

---

## 1. Vision

**"The to-do app that doesn't let you forget."** Capture a task in seconds. Nudge keeps reminding you, as often as the task's priority calls for, until it's done. Everything should feel fast, beautiful and satisfying.

## 2. Goals & success metrics

| Goal | Metric | Target |
|------|--------|--------|
| Fast capture | Time from app open to task saved | ≤ 5 s (3 taps + typing) |
| Reliable reminders | Reminders delivered within tolerance (exact: ±1 min; inexact: ±15 min) | ≥ 99% on a Pixel device with the exact alarm permission granted |
| Fewer forgotten tasks | Share of tasks completed within 7 days of creation | Tracked locally (Stats in Phase 2) |
| Delight | Animations at 60/120 fps without jank | 0 dropped frames above 16 ms in the main flows (Macrobenchmark) |
| Stability | Crash-free sessions | ≥ 99.8% |
| Cold start | Time to first frame | < 800 ms on a mid-range device (baseline profiles) |

## 3. Non-goals (v1)

Collaboration or sharing, cloud sync, recurring tasks, calendar integration, location reminders, attachments, natural-language parsing, widgets, Wear OS, iOS.

## 4. Personas

See `01 §4.1`. Primary: **Forgetful Doer**.

---

## 5. Functional requirements

Priority: **M** = Must (v1), **S** = Should (v1 if time allows), **C** = Could (Phase 2).

### 5.1 Lists

| ID | Requirement | Pri |
|----|-------------|-----|
| FR-01 | The user can create a list with a **name** (1–40 chars, trimmed, not blank) and a **color** picked from 12 preset colors (`04 §2.3`). Default color = the next unused preset. | M |
| FR-02 | The user can optionally pick an **emoji icon** for a list (default: none; the list's first letter shows in a colored circle). | S |
| FR-03 | The user can rename a list, change its color or emoji, and delete it. Deleting asks for confirmation ("Delete 'Work' and its 12 tasks?") and offers Undo for 5 s. | M |
| FR-04 | The user can reorder lists on Home by long-press drag. | S |
| FR-05 | On first launch, a default list **"My Tasks"** (color Indigo) exists. The last remaining list cannot be deleted. | M |
| FR-06 | Each list card on Home shows: color, icon/initial, name, count of open tasks, a progress ring (completed ÷ total top-level tasks), and a small red dot if any open task is Urgent. | M |

### 5.2 Tasks

| ID | Requirement | Pri |
|----|-------------|-----|
| FR-10 | A **FAB (+)** on the List screen opens the **Quick Add sheet**: title field (auto-focused, keyboard open), priority chips, "Details" expander (notes, due date, cadence), and a Save button. Pressing Enter on the keyboard saves and **keeps the sheet open** for the next task (rapid entry). | M |
| FR-11 | A **"+" icon button on every parent-capable task row** (visible in the row's trailing area when the task is expanded, and always in Task Detail) adds a subtask to that task, using the same Quick Add sheet with the "Subtask of: X" header. | M |
| FR-12 | Task fields: title (1–200 chars, required), notes (0–2000 chars), priority, progress 0–100, due date (optional date, optional time), cadence override (optional), completed flag. | M |
| FR-13 | New tasks are inserted at the **top** of the list's open tasks by default (setting: Top/Bottom). New subtasks are inserted at the **bottom** of the parent's subtasks. | M |
| FR-14 | The user can edit every field in the **Task Detail bottom sheet** (opened with the row's › button). Changes save automatically (debounced 400 ms). No Save button. | M |
| FR-15 | The user can delete a task by swiping left or from the context menu or Task Detail. Deleting a parent deletes its subtasks. Undo for 5 s. | M |
| FR-16 | The user can move a task (with its subtasks) to another list from the context menu or Task Detail ("Move to…"). | S |
| FR-17 | A task title renders URLs as tappable links. | C |

### 5.3 Subtasks & hierarchy

| ID | Requirement | Pri |
|----|-------------|-----|
| FR-20 | A task can have **0..n subtasks**. A subtask **cannot** have subtasks (max depth 1). | M |
| FR-21 | A parent row shows an **expand/collapse chevron** and a counter "2/5". Collapsed state is remembered per task (`Task.isExpanded`). Default expanded. | M |
| FR-22 | **Drag a task onto another top-level task** (drop in the target row's middle zone) to make it a subtask. Rules and visuals in `08`. | M |
| FR-23 | **Drag a subtask out** to top level: drop it between top-level tasks, or drag it left by ≥ 48 dp. | M |
| FR-24 | Drag a task **right by ≥ 48 dp** while it sits directly under a top-level task to indent it as that task's last subtask (Google Tasks style). | S |
| FR-25 | A task that **has subtasks cannot become a subtask**. While dragging such a task, nest targets are not highlighted; a drop in a nest zone reorders instead, with a brief shake and haptic "reject". | M |
| FR-26 | The context menu offers "Make subtask of ›" (picker of top-level tasks in the list) and "Move to top level" as the accessible alternative to drag. | M |

### 5.4 Completion

| ID | Requirement | Pri |
|----|-------------|-----|
| FR-30 | **Tapping a task row toggles completion** (when the setting "Tap on task" = Complete, the default). The circular checkbox also toggles. | M |
| FR-31 | Completion animation: checkbox fills and morphs with a spring, the title strikes through left to right, and the row fades and collapses into the Completed section (`04 §6`). Haptic "confirm". A snackbar "Completed 'X'" with **Undo** shows for 4 s. | M |
| FR-32 | Each list has a **Completed section** below the open tasks: header "Completed (n)" with a chevron, **collapsed by default**, state remembered per list. Items are ordered by `completedAt` desc. | M |
| FR-33 | Tapping a completed task un-completes it: it returns to the open tasks at its previous `sortOrder` position and its reminders resume. | M |
| FR-34 | Completing a parent completes all its open subtasks (single transaction, single Undo). | M |
| FR-35 | Completed subtasks of an **open** parent stay under the parent, dimmed and struck through, after the open subtasks. They do **not** appear in the Completed section on their own. When the parent is completed, the whole group sits in the Completed section. | M |
| FR-36 | Completing the last open subtask shows the snackbar "All subtasks done — Complete 'Parent'?" with a [Complete] action. | M |
| FR-37 | Completed section overflow menu (⋮): "Delete all completed" (confirm dialog). | M |
| FR-38 | When a list goes from ≥ 1 open task to 0 open tasks, a **confetti burst** plays (respects reduced motion). | S |

### 5.5 Progress

| ID | Requirement | Pri |
|----|-------------|-----|
| FR-40 | Task Detail has a **progress slider** (0–100, step 5, haptic tick on each step, value label "40%"). | M |
| FR-41 | A task row shows progress as a thin bar under the title (hidden at 0%) and a small "40%" label. A parent shows the computed value. | M |
| FR-42 | For a parent, progress = average of subtasks' effective progress (completed subtask = 100), rounded to the nearest integer. The slider is disabled with the caption "Calculated from subtasks". | M |
| FR-43 | Releasing the slider at 100 marks the task complete (Undo). The stored `progress` keeps the pre-100 value in `progressBeforeComplete` so Undo and un-complete restore it. | M |

### 5.6 Priority

| ID | Requirement | Pri |
|----|-------------|-----|
| FR-50 | Priorities: **Urgent, High, Medium, Low, None** (default None, or the setting "Default priority for new tasks"). | M |
| FR-51 | Priority is chosen with color-coded chips in Quick Add and Task Detail, and with the context menu. | M |
| FR-52 | A task row shows priority as a colored **flag icon** next to the title and a colored checkbox ring (Urgent red, High orange, Medium blue, Low green, None = neutral outline). | M |
| FR-53 | Open tasks can be sorted per list by **My order** (manual, default) or **Priority** or **Due date**, from the list's overflow menu. Drag-and-drop works only in "My order"; in other modes, a long-press shows the tip "Switch to My order to rearrange". | S |

### 5.7 Reminders (core). Full spec in `07`

| ID | Requirement | Pri |
|----|-------------|-----|
| FR-60 | Every **incomplete** task whose effective cadence ≠ Off gets periodic reminder notifications. | M |
| FR-61 | Default cadence per priority: Urgent = 10 min, High = 1 h, Medium = 3 h, Low = twice daily (08:00 & 21:00), None = Off. Each is editable in Settings → Reminders. | M |
| FR-62 | Per-task **cadence override**: Default (follow priority), 10 min, 30 min, 1 h, 2 h, 3 h, 5 h, Twice daily, Off. | M |
| FR-63 | Interval reminders start one interval after the task is created, or after its priority/cadence last changed, or after it was un-completed (the **anchor**). They repeat every interval. | M |
| FR-64 | **Quiet hours** (default ON, 22:00–08:00). Interval reminders due inside quiet hours are postponed to quiet-hours end. Setting "Urgent tasks ignore quiet hours" (default OFF). Twice-daily reminders always fire at their set times. | M |
| FR-65 | Notification shows: title, list name + color, priority, progress, subtask count, and "Reminder #n". Actions: **Done**, **Snooze** (duration = setting, default 1 h). Tapping opens the task in the app. | M |
| FR-66 | Several tasks due at once post individual notifications inside a **group** with a summary ("4 tasks need you"). | M |
| FR-67 | **Snooze** from the notification, context menu or Task Detail: 15 min / 1 h / 3 h / Tomorrow 08:00 / Pick date & time. During a snooze, no reminders fire for the task. | M |
| FR-68 | A **due date with a time** posts a one-off "Due now" reminder at that time (in addition to the cadence, and ignoring quiet hours). A due date without a time posts one at the Low-cadence morning time (08:00) on that day. | M |
| FR-69 | Completing, deleting or setting cadence Off cancels pending reminders and removes the task's posted notification. | M |
| FR-70 | Reminders survive reboot, time or timezone change, app update, and process death. | M |
| FR-71 | A master switch "Pause all reminders" with durations (1 h, until tomorrow, until I resume). | S |
| FR-72 | Subtasks may have their own priority/cadence (default None → Off). A parent's notification includes "2/5 subtasks done". | M |

### 5.8 Home & navigation

| ID | Requirement | Pri |
|----|-------------|-----|
| FR-80 | The **Home** screen shows a greeting header ("Good morning" + date), a **"Focus now"** strip (up to 5 open Urgent/High tasks across all lists, horizontally scrollable cards), smart views (**Today**, **All tasks**), and a grid of **list cards** (2 columns on phones). | M |
| FR-81 | Smart view **Today**: open tasks due today or overdue, plus open Urgent tasks, grouped by list. Read-and-complete only; no reordering. | M |
| FR-82 | Smart view **All tasks**: every open task grouped by list. | S |
| FR-83 | A "New list" card or button at the end of the grid opens the Create List sheet. | M |
| FR-84 | Search (top app bar icon): full-text search over task title and notes across lists; results grouped by list; tapping one opens the list scrolled to it and briefly highlights it. | S |
| FR-85 | The List screen supports a **horizontal swipe between lists** (pager) in Home order, with a tab/indicator row of list color dots. | S |

### 5.9 Onboarding & permissions

| ID | Requirement | Pri |
|----|-------------|-----|
| FR-90 | A three-page onboarding on first launch: (1) Welcome and value, (2) "How nudges work" priority→cadence illustration, (3) Permissions: notifications (runtime) and exact alarms (settings deep link), each with a clear rationale and a "Skip" option. | M |
| FR-91 | **Reminder health** card in Settings (and a banner on Home when degraded) that checks: notifications allowed, channels not blocked, exact alarms allowed, battery optimization status. Each failing item has a Fix button. | M |

### 5.10 Settings

| ID | Requirement | Pri |
|----|-------------|-----|
| FR-100 | Appearance: Theme (System/Light/Dark), Dynamic color (Android 12+, default OFF so list colors stay the main visual), "Pure black dark mode" toggle. | M |
| FR-101 | Reminders: cadence per priority, twice-daily times (morning default 08:00, evening default 21:00), quiet hours on/off + start/end, Urgent ignores quiet hours, default snooze, pause all, reminder health. | M |
| FR-102 | Behavior: Tap on task (Complete / Open details), New tasks at (Top/Bottom), Default priority, Haptics on/off, Confirm before delete. | M |
| FR-103 | Data: Export JSON (SAF "create document"), Import JSON (replace or merge), Delete all data (typed confirmation "DELETE"). | M |
| FR-104 | About: version, open-source licenses, privacy statement ("Your data never leaves your device"). | M |

---

## 6. Scope summary

**v1 (MVP):** FR-01 to FR-104 marked M, and S where time allows.
**Phase 2:** cloud sync & account (`09`), home-screen widget (Glance), recurring tasks, natural-language quick add, escalation as due date nears, stats screen, Wear OS tile, quick-settings tile "Add task".
**Phase 3:** streaks and gamification, voice add, sharing lists, iOS (via KMP).

---

## 7. Non-functional requirements

| ID | Requirement |
|----|-------------|
| NFR-01 | **Performance:** 60/120 fps scrolling with 500 tasks in a list. Cold start < 800 ms (baseline profile). DB queries on the main path < 16 ms. |
| NFR-02 | **Reliability:** Reminders tolerate process death, reboot, time changes and app update. The reconciler worker runs every 15 min. |
| NFR-03 | **Offline:** 100% of features work offline. No INTERNET permission in v1. |
| NFR-04 | **Privacy:** No analytics or trackers in v1. Data is stored only on the device and in the user's Android backup (Auto Backup enabled, DB included). |
| NFR-05 | **Accessibility:** TalkBack labels, 48 dp targets, font scaling to 200% without clipping, contrast ≥ 4.5:1, reduced-motion support (system "Remove animations" → skip decorative animations), non-gesture alternatives for all gestures. |
| NFR-06 | **Battery:** At most one pending alarm. No foreground service. No wake locks beyond the receiver's `goAsync()` window. |
| NFR-07 | **Localization-ready:** all strings in `strings.xml`; plurals via `pluralStringResource`. English v1. 24 h / 12 h time format follows the system. |
| NFR-08 | **Security:** No exported components except the required receivers, which are guarded. `PendingIntent`s are immutable. Release builds use R8. |
| NFR-09 | **Code quality:** ktlint and detekt pass. Unit test coverage ≥ 80% for `core:domain`/`core:reminders`/`core:data`. |
| NFR-10 | **Size:** Release APK < 10 MB. |
| NFR-11 | **Compatibility:** Android 8.0 through 16. Edge-to-edge. Predictive back gesture supported. |

---

## 8. User stories with acceptance criteria (Gherkin)

**US-1 Quick capture**
```
Given I am on the "Work" list
When I tap the + FAB, type "Send invoice", tap the "High" chip and press Enter
Then "Send invoice" appears at the top of Work's open tasks with an orange flag
And the Quick Add sheet stays open with an empty, focused title field
And a reminder is scheduled for 1 hour from now
```

**US-2 Nag until done**
```
Given "Fix prod bug" has priority Urgent and was created at 10:00
And quiet hours are 22:00–08:00
When the time is 10:10
Then I receive a notification "Fix prod bug" with actions Done and Snooze
And at 10:20 I receive it again ("Reminder #2"), replacing the previous one
When I tap "Done" on the notification
Then the task is completed, the notification disappears and no further reminders fire
```

**US-3 Twice-daily**
```
Given "Call the bank" has priority Low
When the clock reaches 08:00 or 21:00 and the task is incomplete
Then I receive one notification for it
```

**US-4 Subtasks by drag**
```
Given top-level tasks "Read books" and "Atomic Habits" in the same list
When I long-press "Atomic Habits", drag it over the middle of "Read books" and hold for 250 ms
Then "Read books" highlights (tinted container + scale 1.02 + indented ghost preview)
When I release
Then "Atomic Habits" becomes the last subtask of "Read books", indented by 40 dp
And "Read books" shows "0/1" and an expand chevron
```

**US-5 Completion section**
```
Given "Buy milk" is open in "Home"
When I tap the row
Then it animates to completed and moves under "Completed (1)"
And a snackbar "Completed 'Buy milk'" with Undo appears for 4 s
```

**US-6 Progress**
```
Given "Write report" has no subtasks
When I open details and drag the slider to 60%
Then the row shows a 60% progress bar
Given "Read books" has 2 subtasks, one completed and one at 50%
Then "Read books" shows 75% and its slider is disabled
```

**US-7 Quiet hours**
```
Given "Pay rent" is High (every 1 h) and quiet hours are 22:00–08:00 (Urgent override off)
When the next reminder would be at 22:30
Then no notification fires until 08:00, when exactly one fires
And the following one is at 09:00
```

---

## 9. Open questions (resolved defaults, changeable later)

| Question | Default chosen |
|----------|----------------|
| App name | "Nudge". Package `app.nudge.reminders` (change before the first Play upload if needed) |
| Should a subtask with its own Urgent priority nag even if the parent is Low? | Yes. Each task is evaluated on its own |
| Max lists / tasks | No hard limit. UI tested with 50 lists × 500 tasks |
| Reminder sound | Channel default sound. The user can change it per channel via system settings (deep link from Settings) |
