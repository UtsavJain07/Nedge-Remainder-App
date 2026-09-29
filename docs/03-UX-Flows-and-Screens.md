# 03 — UX: Information Architecture, Screens & Flows

The visual tokens (colors, type, shapes, motion) live in `04`. This document defines **what is on each screen and how it behaves**.

---

## 1. Design principles

1. **Capture first.** The + button is always one tap away, and the keyboard opens immediately.
2. **One tap completes.** The most frequent action gets the cheapest gesture.
3. **Color = context.** Each list's color tints its screen, so you always know where you are.
4. **Motion explains change.** Every state change (complete, nest, reorder, navigate) is animated so the user sees where things went. Motion is springy and fast (≤ 400 ms), never blocking.
5. **Calm by default, loud when it matters.** Priority colors are the only saturated accents on a task row.
6. **Every gesture has a button.** Swipe, drag and long-press all have a menu or button equivalent.

---

## 2. Information architecture

```
App
├── Onboarding (first launch only)
│   ├── 1 Welcome
│   ├── 2 How nudges work
│   └── 3 Permissions
├── Home  (start destination)
│   ├── Header: greeting, date, [Search] [Settings]
│   ├── Health banner (only when reminders are degraded)
│   ├── Focus now strip (Urgent/High open tasks)
│   ├── Smart views: Today · All tasks
│   ├── My lists grid (list cards) + "New list" card
│   └── FAB: "New task" → Quick Add (list picker defaults to last used list)
├── List screen (pager across lists)
│   ├── Top bar: back, list icon + name, [Sort] [⋮ Rename / Color / Delete / Delete completed]
│   ├── Open tasks (reorderable, nestable)
│   ├── Completed (n) section (collapsible)
│   └── FAB (+) → Quick Add
├── Smart view screen (Today / All tasks)
├── Task Detail (modal bottom sheet, expands to full screen)
├── Quick Add (modal bottom sheet above the keyboard)
├── Create/Edit List (modal bottom sheet)
├── Search (full screen)
└── Settings
    ├── Appearance
    ├── Reminders (cadences, twice-daily times, quiet hours, snooze, pause, health)
    ├── Behavior
    ├── Data (export/import/delete)
    └── About
```

**Navigation (type-safe Navigation Compose routes):**

| Route | Args | Presentation |
|-------|------|--------------|
| `Onboarding` | — | Full screen |
| `Home` | — | Full screen (start) |
| `ListRoute` | `listId: String`, `highlightTaskId: String?` | Full screen, shared-element transition from list card |
| `SmartViewRoute` | `type: SmartViewType (TODAY, ALL)` | Full screen |
| `SearchRoute` | — | Full screen, fade + slide up |
| `SettingsRoute` | — | Full screen, then nested `SettingsReminders`, etc. |
| Task Detail | `taskId` | `ModalBottomSheet` hosted by the calling screen (not a nav destination), so the list stays visible behind it |
| Quick Add | `listId`, `parentId?` | `ModalBottomSheet` |

**Deep links** (used by notifications): `nudge://task/{taskId}` → opens `ListRoute(listId = task.listId, highlightTaskId = taskId)` and then auto-opens Task Detail.

---

## 3. Screens

### 3.1 Onboarding (3 pages, `HorizontalPager`)

| Page | Content | Primary CTA |
|------|---------|-------------|
| 1 Welcome | Large animated illustration (Canvas: 3 stacked task cards bouncing in with staggered springs, then a check morphing in). Title "Never forget a task again". Body "Nudge keeps reminding you until it's done." | Next |
| 2 How nudges work | Four priority rows animate in, each with a colored flag + cadence ("Urgent → every 10 min", …, "Low → 8 AM & 9 PM"). Caption "You can change these anytime." | Next |
| 3 Permissions | Two cards: **Notifications** ("So we can nudge you") [Allow] and **Precise timing** ("So nudges arrive on time, even when your phone sleeps") [Allow]. Each card turns into a green ✓ once granted. | "Start" (enabled always; skipping is allowed) |

Page indicator: expressive dots that stretch into a pill for the current page. "Skip" text button top-right on pages 1–2.

On "Start": set `onboardingDone = true` and navigate to Home, popping Onboarding.

### 3.2 Home

```
┌───────────────────────────────────────┐
│ Good morning ☀️            [🔍] [⚙︎]   │  ← LargeTopAppBar that collapses on scroll
│ Sunday, 27 September                   │
├───────────────────────────────────────┤
│ ⚠ Reminders may be delayed  [Fix]      │  ← HealthBanner (only if degraded)
├───────────────────────────────────────┤
│ Focus now                              │
│ ┌────────┐ ┌────────┐ ┌────────┐ →     │  ← LazyRow of FocusCards (max 5)
│ │🔴 Fix  │ │🟠 Send │ │🟠 Call │       │     Tap = open task detail; check = complete
│ │prod bug│ │invoice │ │ bank   │       │
│ │Work ·40%│ │Work    │ │Personal│       │
│ └────────┘ └────────┘ └────────┘       │
├───────────────────────────────────────┤
│ ┌──────────────┐ ┌──────────────┐      │
│ │ 📅 Today   5 │ │ 📋 All    23 │      │  ← Smart view chips (tonal cards)
│ └──────────────┘ └──────────────┘      │
├───────────────────────────────────────┤
│ My lists                               │
│ ┌──────────────┐ ┌──────────────┐      │
│ │ 💼      ◔ 60%│ │ 🏠      ◑ 45%│      │  ← ListCard: container tinted with list color
│ │ Work         │ │ Home         │      │     (color at 16% on surface), icon, ring, name, count
│ │ 8 open    •  │ │ 3 open       │      │     red dot • = has open Urgent
│ └──────────────┘ └──────────────┘      │
│ ┌──────────────┐ ┌ ─ ─ ─ ─ ─ ─ ┐      │
│ │ 📚 Reading   │ │  + New list  │      │  ← dashed outline card
│ └──────────────┘ └ ─ ─ ─ ─ ─ ─ ┘      │
│                                  [ + ] │  ← Extended FAB "New task", shrinks to icon on scroll
└───────────────────────────────────────┘
```

- Greeting by hour: 05–11 "Good morning", 12–16 "Good afternoon", 17–21 "Good evening", 22–04 "Good night".
- Focus now: open tasks with priority URGENT or HIGH, sorted by priority, then `dueAt` asc (nulls last), then `createdAt` asc. Hidden when empty.
- List grid: `LazyVerticalGrid(GridCells.Adaptive(160.dp))`. Long-press a card → drag to reorder (FR-04), or release without moving → menu (Rename, Change color, Delete).
- Empty state (only "My Tasks" with 0 tasks): illustration + "Your mind, unburdened. Add your first task." with an arrow animating toward the FAB.
- Home FAB opens Quick Add with a **list selector chip** (defaults to the last used list).

### 3.3 List screen

```
┌───────────────────────────────────────┐
│ ←  💼 Work                  [⇅] [⋮]    │  ← TopAppBar tinted with list color container
│ ● ● ○ ● ●                              │  ← list dots (pager indicator in list colors)
├───────────────────────────────────────┤
│ ◯ 🚩 Fix prod bug             40%  ›   │  ← TaskRow (red ring for Urgent)
│   ▔▔▔▔▔▔▔▔▔▔                          │     progress bar
│ ◯ 🚩 Read books        ⌄ 1/3   [+] ›   │  ← Parent row: chevron, counter, add-subtask
│     ◯ Atomic Habits           50%  ›   │  ← Subtask rows (indent 40 dp, thinner type)
│     ◯ Deep Work                    ›   │
│     ◉ ~~Clean Code~~               ›   │  ← completed subtask (dimmed, struck)
│ ◯   Send invoice   📅 Tomorrow     ›   │
├───────────────────────────────────────┤
│ Completed (4)                     ⌄    │  ← collapsible header
│   ◉ ~~Buy milk~~                       │
│   …                                    │
│                                  [ + ] │  ← FAB in list color
└───────────────────────────────────────┘
```

**TaskRow anatomy (left → right):**

| Element | Size | Behavior |
|---------|------|----------|
| Drag affordance | Whole row (long-press) | Long-press 400 ms → drag (`08`) |
| Checkbox | 24 dp visual, 48 dp touch | Tap → toggle complete. Ring color = priority color |
| Priority flag | 16 dp | Hidden for None |
| Title | `bodyLarge` (subtask: `bodyMedium`), max 2 lines, ellipsis | Part of the row tap target |
| Meta line (optional) | `labelSmall` | Due chip ("Today 5 PM", red if overdue), cadence icon ⏰ + "10m", snooze icon "until 3 PM", notes icon 📝 |
| Progress bar | 3 dp tall under the text, width = % | Animated on change |
| Progress label | `labelMedium` "40%" | Hidden at 0 |
| Chevron ⌄ + counter | Parent only | Tap → expand/collapse (rotates 180°) |
| + add subtask | 40 dp icon button | Visible on parent rows **and** on top-level rows while the row is "focused" (last touched). Opens Quick Add with `parentId` |
| › open details | 40 dp icon button | Opens Task Detail |

**Row gestures:**

| Gesture | Result |
|---------|--------|
| Tap row | Open Task Detail (v1.1) |
| Tap circle | Toggle complete |
| Tap › | Open Task Detail |
| Long-press + move | Drag (reorder / nest / un-nest) |
| Long-press + release without moving | Context menu (`DropdownMenu` anchored to the row) |
| Swipe right (≥ 35% width) | Complete. Background = list color with a ✓ icon that scales in |
| Swipe left (≥ 35% width) | Delete. Background = error color with a 🗑 icon. Undo snackbar |

**Top bar actions:**
- `[⇅]` Sort: My order / Priority / Due date (FR-53).
- `[⋮]`: Rename list, Change color & icon, Delete completed tasks, Delete list.

**Pager:** `HorizontalPager` across lists in Home order. Swiping changes list, and the top bar crossfades its color (animated `Color` with a 300 ms tween). The pager is disabled while dragging a task.

**Empty list state:** centered illustration (Canvas: an empty checklist with a floating ✓) and "Nothing here yet. Tap + to add a task." When all tasks are completed: "All done! 🎉" + confetti (FR-38).

### 3.4 Quick Add sheet

```
┌───────────────────────────────────────┐
│ ─── (drag handle)                      │
│ [💼 Work ▾]   Subtask of "Read books"  │  ← list chip (Home entry only) / parent label
│ ┌───────────────────────────────────┐ │
│ │ What do you need to do?           │ │  ← TextField, autofocus, IME action "Done"
│ └───────────────────────────────────┘ │
│ (🔴 Urgent)(🟠 High)(🔵 Medium)(🟢 Low)│  ← FilterChips, single-select, toggle off = None
│ [📅 Date] [⏰ Reminder: 1 h] [📝 Notes] │  ← AssistChips open pickers inline
│                              [ Save ]  │  ← enabled when title not blank
└───────────────────────────────────────┘
```

- Keyboard "Done"/Enter → save, clear the field, keep focus, and play a small "task flew into list" animation (the chip shrinks and moves toward the list; see `04 §6`).
- Tap outside or swipe down with an empty field → dismiss. With text in the field → save as a draft? **No.** Dismissing with text shows "Discard task?" [Discard] [Keep editing].
- The "Reminder" chip label shows the effective cadence from the selected priority ("Reminder: every 1 h") and changes live when the priority chip changes. Tapping it opens the cadence picker (override).

### 3.5 Task Detail sheet

`ModalBottomSheet` with `skipPartiallyExpanded = false`. It opens at a partial height of about 60%, and dragging up makes it full screen.

Sections, top to bottom:
1. **Header row:** large checkbox, editable title (`headlineSmall`, multi-line), [⋮] (Move to list, Snooze, Delete).
2. **Breadcrumb:** "💼 Work › Read books" (for a subtask). Tapping the list chip moves it to another list.
3. **Priority:** segmented chip row (the 5 levels, including None).
4. **Progress:** slider + value, or the computed ring with the caption "Calculated from subtasks".
5. **Reminders card:**
   - "Repeats: Every 10 min (Urgent default)" → tap opens the cadence picker (radio list: Default (…), 10 min, 30 min, 1 h, 2 h, 3 h, 5 h, Twice daily, Off).
   - "Next nudge: in 7 min (10:40)", live, updates every minute.
   - Snooze row: [15 min] [1 h] [3 h] [Tomorrow] [Pick…]. When snoozed: "Snoozed until 3:00 PM [Cancel]".
   - "Nudged 5 times since 9:00" (reminderCount).
6. **Due date:** chip "Add due date" → Material 3 `DatePicker` dialog, then an optional `TimePicker`. Clear button.
7. **Subtasks** (top-level tasks only): an inline list of subtasks with checkboxes, and a "+ Add subtask" row with an inline text field (Enter adds another).
8. **Notes:** multi-line text field, "Add notes".
9. **Footer:** "Created 25 Sep, 10:12 · Updated 2 min ago".

All edits autosave (debounced 400 ms) through the ViewModel. The sheet has no Save button.

### 3.6 Create / Edit List sheet

- Name text field (autofocus), with a live preview card on top that shows the chosen color and emoji.
- Color picker: 12 swatches (48 dp circles) in a 6×2 grid. The selected one gets a check and a morphing outline ring.
- Icon: "Add emoji" → emoji grid (a curated set of 48 common emojis; no dependency on a full emoji picker) + "None".
- [Create] / [Save] button.

### 3.7 Smart views (Today / All)

A list grouped by list (sticky headers in list color: "💼 Work · 3"). Rows are the same `TaskRow`, with drag disabled. Tapping completes. › opens detail. The Today view also has an "Overdue" group at the top in red.

### 3.8 Search

A search field with autofocus. Results update as you type (debounce 200 ms, min 1 char), grouped by list. The match is highlighted in bold. Empty: "No tasks match 'foo'". Tapping a result navigates to `ListRoute(listId, highlightTaskId)`, and the row pulses twice in the list color.

### 3.9 Settings

Standard M3 preference screen built from `ListItem`s. Sections as in `02 §5.10`. The **Reminder health** screen:

```
Reminder health                    ✅ All good  /  ⚠ 2 issues
┌───────────────────────────────────────┐
│ ✅ Notifications allowed               │
│ ⚠ Precise alarms not allowed   [Fix]   │ → ACTION_REQUEST_SCHEDULE_EXACT_ALARM
│ ✅ Reminder channels enabled           │ → per channel status
│ ⚠ Battery optimization on      [Fix]   │ → ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
│ ℹ Samsung: add Nudge to "Never sleeping apps" [How?] │ (OEM-specific tip)
│ [Send test nudge in 10 s]              │
└───────────────────────────────────────┘
```

---

## 4. Key flows

### 4.1 First launch
```
Launch → DB seeded with "My Tasks" → Onboarding 1 → 2 → 3 (permissions)
→ Start → Home (empty state) → FAB → Quick Add (list = My Tasks)
```

### 4.2 Add a task (from a list)
```
List screen → FAB + → Quick Add opens with keyboard
→ type title → (optional) pick priority chip → Enter
→ repo.createTask() → Room insert → Flow emits → row animates in at the top (FR-13)
→ ReminderScheduler.onTaskChanged(taskId) → dispatcher alarm updated
→ sheet stays open for the next entry
```

### 4.3 Add a subtask
Either (a) the + on a parent row → Quick Add with the "Subtask of X" header, or (b) Task Detail → "+ Add subtask" inline, or (c) drag onto a task (`08`).

### 4.4 Complete a task
```
Tap row → optimistic UI state "completing" (animation starts immediately)
→ repo.setCompleted(id, true) [transaction: task + open subtasks, set completedAt, nextReminderAt = null]
→ ReminderScheduler.onTaskChanged → cancel posted notification
→ Snackbar "Completed 'X'" [Undo] 4 s → Undo → repo.restoreSnapshot(snapshot)
```

### 4.5 Reminder notification → Done
```
Alarm fires → ReminderReceiver → ReminderDispatcher.dispatchDue(now)
→ Notification "Fix prod bug · Reminder #3" [Done] [Snooze 1 h]
→ user taps Done → NotificationActionReceiver(ACTION_DONE, taskId)
→ repo.setCompleted(taskId, true) → cancel notification → reschedule dispatcher
```

### 4.6 Reminder notification → tap
```
Tap body → MainActivity with deep link nudge://task/{id}
→ ListRoute(listId, highlightTaskId = id) → row pulses → Task Detail opens
```

### 4.7 Drag to nest
See `08 §4`.

### 4.8 Delete a list
```
List ⋮ → Delete list → AlertDialog "Delete 'Work' and its 12 tasks?" [Cancel] [Delete]
→ soft delete list + tasks (deletedAt = now) → navigate back to Home
→ Snackbar "List deleted" [Undo] 5 s → Undo clears deletedAt
→ Purge worker hard-deletes rows with deletedAt older than 30 days
```

---

## 5. Empty, loading & error states

| Where | State | UI |
|-------|-------|----|
| Home | First run | Empty illustration + arrow to FAB |
| List | No tasks | Illustration + "Tap + to add a task" |
| List | All completed | "All done! 🎉" + confetti once |
| Today | Nothing due | "Nothing due today. Enjoy! ☕" |
| Search | No results | "No tasks match '…'" |
| Any | DB error (rare) | Snackbar "Something went wrong. Try again." + logged |
| Import | Invalid file | Dialog "This file isn't a Nudge backup." |
| Loading | Initial DB read | No spinners. Show the skeleton shimmer only if loading takes > 150 ms (Room is usually instant) |

---

## 6. Snackbar & Undo rules

- One snackbar at a time. A new one replaces the old one, and the replaced action is committed.
- Durations: complete 4 s, delete 5 s, move 4 s.
- Undo restores **exact** prior state (snapshot of affected rows taken before the mutation).
- Snackbars float above the FAB (`Scaffold` handles this).

---

## 7. Haptics

| Event | `HapticFeedbackType` |
|-------|----------------------|
| Complete | `Confirm` (API 30+) / `LongPress` fallback |
| Long-press drag start | `LongPress` |
| Drag crosses a slot / enters nest zone | `SegmentFrequentTick` / `TextHandleMove` fallback |
| Nest rejected | `Reject` / double `LongPress` fallback |
| Slider step | `SegmentTick` |
| Delete | `ContextClick` |

All haptics are gated by Settings → Haptics.

---

## 8. Responsive behavior

- Compact width (< 600 dp): layouts as drawn.
- Medium/Expanded (tablets, foldables): Home grid adapts (`GridCells.Adaptive(160.dp)`). The List screen uses list-detail with `ListDetailPaneScaffold` (Task Detail as the right pane instead of a sheet). This is an S requirement: if time is short, center the content with a max width of 640 dp.
- Landscape: same as above. Quick Add sheet max width 640 dp.

---

## 9. Accessibility

| Gesture | Accessible alternative |
|---------|------------------------|
| Tap row to complete | Checkbox with `Role.Checkbox` and state description "Completed"/"Not completed" |
| Long-press drag reorder | Custom accessibility actions on the row: "Move up", "Move down", "Make subtask of previous task", "Move to top level" |
| Swipe to delete | Custom action "Delete" + context menu |
| Drag to nest | Custom action + context menu "Make subtask of ›" |

- Rows merge their semantics: `"Fix prod bug, Urgent priority, 40 percent, due today, not completed"`.
- Animations respect `Settings.Global.ANIMATOR_DURATION_SCALE == 0` (see `04 §6.5`).
- Font scale up to 200%: rows grow in height, titles wrap to 3 lines, chips wrap (`FlowRow`).
- Color is never the only signal: priority also has a flag icon and a text label in detail and semantics.
