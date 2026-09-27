# 01 — Market Research & Brainstorm

## 1. Problem statement (from the user's brief)

> "I usually forget a lot… all the tasks that I've been assigned, all the things that I need to do. I forget almost all of them."

The core problem is **forgetting incomplete tasks**, not organizing them. Most to-do apps remind you **once**, at a due time, and then go quiet. A forgetful user dismisses that one notification and the task is lost. The product has to **keep bringing the task back**, more often for more important tasks, until it is done. It must also stay pleasant enough that the user does not uninstall it.

**Design tension to solve:** persistent reminders vs. notification fatigue. Most brainstorm decisions below come back to this.

---

## 2. Competitive landscape

### 2.1 Feature matrix

| App | Lists & colors | Subtasks | Drag to reorder | Drag to nest | Progress % | Priority | Repeating "nag" until done | Completed section | UI notes |
|-----|---------------|----------|-----------------|--------------|-----------|----------|---------------------------|-------------------|---------|
| **Google Tasks** | Lists (tabs), no colors | 1 level | ✅ | ✅ (drag right to indent) | ❌ | Star only | ❌ (one time-based reminder) | ✅ collapsible "Completed (n)" | Very clean, minimal Material You. **User's named reference.** |
| **Microsoft To Do** | Lists + themes/colors + emoji | "Steps" (1 level, checklist) | ✅ | ❌ | ❌ | Important star | ❌ | ✅ | "My Day" smart list, backgrounds per list |
| **TickTick** | Lists, folders, colors | Multi-level | ✅ | ✅ | ✅ (0–100 in steps) | 4 levels (color-coded flags) | Partial ("constant reminder"/annoying alert on premium) | ✅ | Feature-dense, Pomodoro, habits |
| **Todoist** | Projects with colors | Multi-level | ✅ | ✅ (drag right) | ❌ | P1–P4 (red/orange/blue/grey) | ❌ (premium location/time reminders only) | Hidden by default | Natural-language quick add, "karma" |
| **Any.do** | Lists, colors | 1 level | ✅ | ❌ | ❌ | Flag | Daily planner prompt | ✅ | Daily "plan your day" ritual |
| **Due (iOS)** | Minimal | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ **Auto-snooze**: re-reminds every 1/5/10/15/30/60 min until done | Log | The gold standard for nagging |
| **Tasks.org (Android, OSS)** | Lists, colors, icons | Multi-level | ✅ | ✅ | ❌ | 4 levels | Random reminders, repeat alerts | ✅ | Material, highly configurable |
| **Apple Reminders** | Lists + colors + icons | 1 level | ✅ | ✅ (drag onto) | ❌ | !, !!, !!! | Early reminders only | ✅ toggle | Colorful list cards on home |
| **Things 3 (iOS)** | Areas/projects | Checklists | ✅ | ❌ | Project pie | ❌ | ❌ | Logbook | Best-in-class animation polish |

### 2.2 What we borrow from each

| From | Borrow |
|------|--------|
| **Google Tasks** | Overall simplicity. One-level subtasks. "Completed (n)" collapsible section. Drag right to indent. Bottom-sheet quick add. |
| **Due** | **Auto-snooze / nagging model**: the reminder repeats at an interval until you act. Snooze and Done actions straight from the notification. |
| **Todoist / TickTick** | Four color-coded priority levels (Urgent red, High orange, Medium blue, Low green). Quick-add chips for priority. |
| **Apple Reminders** | Home screen of **colored list cards** with counts. Dropping a task **onto** another task nests it (the user asked for exactly this). |
| **Microsoft To Do** | Each list has its own color and emoji, and that color themes the list screen. |
| **TickTick** | Progress % on tasks (a slider in steps). |
| **Things 3** | Completion animation that feels rewarding (check morph, strike-through, row collapse). |

### 2.3 Gaps in the market that Nudge fills

1. **No mainstream Android app does priority-driven repeated nagging.** Due does nagging but is iOS-only, has no priority mapping and no subtasks. TickTick's constant reminder is premium and time-based.
2. **Progress % together with subtasks.** For a task with subtasks, progress should be *computed* from them.
3. **Fixed-time daily sweeps** ("remind me of everything low priority at 8 AM and 9 PM") as a priority cadence. Competitors have daily summaries, but they are not per-task.

---

## 3. Platform research (constraints that shape the design)

| Constraint | Impact | Our answer |
|-----------|--------|-----------|
| **Exact alarms denied by default** for new installs targeting Android 13+ (Android 14 behavior). `SCHEDULE_EXACT_ALARM` needs the user to grant it. `USE_EXACT_ALARM` is restricted by Play policy to alarm-clock and calendar apps. | 10-minute reminders need exact timing, or Doze delays them. | Declare `SCHEDULE_EXACT_ALARM`. Ask for it in onboarding with an explanation. If it is denied, fall back to inexact `setAndAllowWhileIdle` and show a "Reminders may be delayed" health banner. See `07 §6`. |
| **Doze mode.** `setExactAndAllowWhileIdle` fires at most about once per 9 minutes per app while the device is dozing. | One alarm per task would collide. | **Single-alarm dispatcher**: only one pending alarm (the earliest). Each fire handles every due task and batches their notifications. |
| **POST_NOTIFICATIONS** runtime permission (Android 13+). | Nothing works without it. | Onboarding step, plus a persistent in-app banner if it is denied. |
| **Notification cooldown** (Android 15+). Repeated notifications from one app are progressively quieted. | Re-nags may arrive silently. | Group reminders into one summary. **Update** the existing notification for a task (same ID) rather than stacking new ones. Use separate channels per priority so Urgent keeps high importance. Accept that the OS may mute; the badge and shade entry still persist. |
| **OEM battery killers** (Xiaomi, Oppo, Vivo, Samsung "deep sleep"). | Alarms get cleared when the app is swiped away. | WorkManager 15-minute reconciler as a safety net. A Settings "Reminder health" screen with OEM-specific guidance (dontkillmyapp.com) and a button to open battery optimization settings. |
| **Reboot, time change, app update** clear alarms. | Reminders silently die. | `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED` (optional), `TIME_SET`, `TIMEZONE_CHANGED` and `MY_PACKAGE_REPLACED` receivers all call `rescheduleAll()`. |
| **Compose reorder libraries** (e.g. `sh.calvin.reorderable`) support reordering, **not nesting**. | Drop-onto-task-to-nest needs a custom build. | A custom drag controller over a *flattened* list model. See `08`. |
| **Material 3 Expressive** is stable in Compose (spring-based motion scheme, shape morphing, new components). | Gives the "modern, aesthetic, eye-catching" look the user asked for. | Adopt `MaterialExpressiveTheme` / `MotionScheme.expressive()`. See `04`. |

---

## 4. Brainstorm

### 4.1 Personas

- **Primary: "Forgetful Doer"** (the user). Juggles work assignments and personal errands. Checks the phone often. Wants to be *pushed*. Will tolerate frequent nags for truly urgent things, but will uninstall if spammed at night.
- **Secondary: "Casual Planner".** Wants a pretty Google-Tasks-like list with light reminders. Uses priority Low/None mostly.

### 4.2 Jobs to be done

1. "When I'm handed a task, I want to capture it in under 5 seconds so I don't lose it."
2. "When I'm busy, I want the app to keep poking me about what matters most until I do it."
3. "When a task is big, I want to break it into pieces and see how far along I am."
4. "When I finish something, I want it to feel good and get out of my way."
5. "At night, I want peace unless something is truly urgent."

### 4.3 Ideas considered

| Idea | Verdict | Reason |
|------|---------|--------|
| Priority → default cadence mapping | ✅ v1 | Core ask |
| Per-task cadence override | ✅ v1 | The user listed 10 m / 1 h / 2 h / 3 h / 5 h / 8 AM + 9 PM. Five priorities can't hold six cadences, so override is needed |
| Quiet hours (default 22:00–08:00) | ✅ v1 | Prevents uninstall-level annoyance. Urgent may break through (setting) |
| Notification actions: Done / Snooze | ✅ v1 | Due-style. Acting without opening the app |
| Grouped notifications (one summary) | ✅ v1 | Against spam and cooldown |
| Escalation (nags speed up as a due date nears) | ⏳ Phase 2 | Nice, but it adds complexity |
| Due dates | ✅ v1 (optional field) | Market standard; shown as a chip and used by the "Today" smart view. A due-time reminder fires once at the due time |
| Recurring tasks (every Monday…) | ⏳ Phase 2 | Not requested; data model leaves room |
| Natural-language quick add ("call mom tomorrow 5pm !1") | ⏳ Phase 2 | |
| Home-screen widget (Glance) | ⏳ Phase 2 | High value for forgetful users, but after the core |
| Cloud sync / multi-device | ⏳ Phase 2 | Schema is sync-ready now |
| Multi-level subtasks | ❌ | Google Tasks proves one level is enough. Keeps drag-and-drop understandable |
| Gamification (streaks, karma) | ⏳ Phase 3 | |
| Voice add / Assistant | ⏳ Phase 3 | |
| Confetti on list completion | ✅ v1 | "Eye-catching animations" ask; cheap to do with Canvas |
| Swipe gestures (right = complete, left = delete) | ✅ v1 | Market standard; also works as the accessible alternative to tap |

### 4.4 Resolving conflicts in the brief

1. **"Click on a task → mark completed"** vs. needing a way to open the task to edit priority, slider and so on.
   **Decision:** A tap on the row toggles completion (with a 4 s Undo snackbar). A trailing **chevron button (›)** on every row opens the Task Detail sheet. A tap on the title text of an *already selected* row does not open edit mode. There is a Settings option "Tap on task: Complete (default) / Open details" for users who prefer the Google Tasks behavior.

2. **Long-press is drag**, so it cannot also open a context menu.
   **Decision:** Long-press followed by movement = drag. Long-press released **without moving** (under 8 dp) = open a context menu (Edit, Priority ›, Snooze ›, Make subtask/Promote, Move to list ›, Delete).

3. **Manual progress slider vs. subtasks.**
   **Decision:** A task **without** subtasks has a manual slider (0–100, step 5). A task **with** subtasks shows a *computed* progress ring, with the slider disabled and the caption "Calculated from subtasks".

4. **Sliding progress to 100%.**
   **Decision:** Releasing the slider at 100 marks the task complete (Undo snackbar). Uncompleting a task restores its previous progress value.

5. **Completing a parent that has open subtasks.**
   **Decision:** It completes the parent and all its open subtasks in one transaction, with a single Undo that restores the exact previous state.

6. **Completing the last open subtask.**
   **Decision:** The parent is **not** auto-completed. A snackbar says "All subtasks done — Complete 'Read books'?" with a [Complete] action. The parent's reminders keep going until it is completed.

7. **The six intervals the user listed vs. priority levels.**
   **Decision:** Five priorities, each with a default cadence (editable in Settings):

   | Priority | Color | Default cadence |
   |----------|-------|-----------------|
   | Urgent | Red | Every 10 min |
   | High | Orange | Every 1 hour |
   | Medium | Blue | Every 3 hours |
   | Low | Green | Twice daily, 08:00 and 21:00 |
   | None | Grey | Off |

   A per-task override offers: 10 min, 30 min, 1 h, 2 h, 3 h, 5 h, twice daily, off.

---

## 5. Key architectural decisions (ADR summary)

| ADR | Decision | Alternatives rejected | Why |
|-----|----------|----------------------|-----|
| ADR-01 | Native Kotlin + Jetpack Compose | Flutter, React Native, KMP | The core value is OS-level alarm and notification reliability plus rich animation. Native gives first-class APIs (AlarmManager, receivers, channels, Compose motion) with no bridge. Android-only target |
| ADR-02 | Local-first, no server in v1 | Firebase-first | Personal app, instant UX, offline by nature, zero cost, privacy. Reminders must work with no network anyway |
| ADR-03 | Room as source of truth; UI observes `Flow`s | In-memory state | Reactive, survives process death, testable |
| ADR-04 | Single-alarm dispatcher | One alarm per task; WorkManager periodic only | One pending alarm avoids Doze throttling collisions, is easy to reschedule on boot, and batches notifications. WorkManager's minimum is 15 min, so it can't do 10-minute cadence |
| ADR-05 | One level of subtasks | N levels | UX clarity, simpler drag-and-drop, matches reference app |
| ADR-06 | Custom drag-and-drop over a flattened list | Library | Libraries can't nest |
| ADR-07 | `sortOrder: Double` midpoint ordering | Integer positions; LexoRank strings | O(1) moves with no mass updates; simpler than LexoRank; renormalize when the gap is under 1e-6 |
| ADR-08 | Supabase for Phase 2 sync | Firebase Firestore, custom Ktor server | Postgres + RLS + auth out of the box, SQL mirrors Room, open source, generous free tier |
| ADR-09 | Multi-module (core/feature) | Single module | Scales, builds faster, enforces boundaries |
| ADR-10 | Hilt for DI | Koin, manual | Standard, compile-time safe, WorkManager and ViewModel integration |

---

## 6. Sources

- Android Developers: [Schedule exact alarms are denied by default](https://developer.android.com/about/versions/14/changes/schedule-exact-alarms), [Schedule alarms](https://developer.android.com/develop/background-work/services/alarms)
- Google Play [policy on the Exact Alarm API](https://orangeoma.zendesk.com/hc/en-us/articles/9110068699548-Google-Play-policy-on-use-of-Exact-Alarm-API)
- Android 15 notification cooldown: [Android Central](https://www.androidcentral.com/apps-software/android-15-notification-cooldown), [Android Authority](https://www.androidauthority.com/notification-cooldown-android-15-qpr1-3481384/)
- Due auto-snooze: [AppleInsider](https://appleinsider.com/articles/18/08/29/hands-on-due-3-for-ios-is-a-reminder-app-that-wont-ever-let-you-forget), [Peer Reviewed](https://www.peerreviewed.io/blog/2019/8/2/due-an-excellent-reminder-app-for-students)
- Google Tasks features: [2sync guide](https://2sync.com/blog/google-tasks-complete-guide), [Google Play listing](https://play.google.com/store/apps/details?id=com.google.android.apps.tasks)
- Compose reordering: [Calvin-LL/Reorderable](https://github.com/Calvin-LL/Reorderable)
- Material 3 Expressive in Compose: [m3.material.io](https://m3.material.io/develop/android/jetpack-compose), [Motion physics blog](https://m3.material.io/blog/m3-expressive-motion-theming), [Compose Material 3 releases](https://developer.android.com/jetpack/androidx/releases/compose-material3)
