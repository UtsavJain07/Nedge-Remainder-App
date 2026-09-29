# Nudge — Documentation Index

> **Nudge** is the working name for a native Android to-do and reminder app. It keeps **reminding you about unfinished tasks at an interval set by each task's priority** until you finish them. Lists, subtasks, drag-to-nest, progress sliders and a lively Material 3 Expressive UI sit on top of that reminder engine.

These documents are the **single source of truth** for building the app. They are written so that a human developer or an LLM coding agent can build the whole app **without asking questions**. Where something could be read more than one way, the documents pick one answer and say so.

---

## 0. Changes after v1.0 review (v1.1) — these override the documents below

| # | Change | Replaces |
|---|--------|----------|
| 1 | **Adaptive layout:** 2-column Home grid from 360 dp phones up to 5–6 columns on tablets; Today/All always share one row; reading content capped at 720 dp (Home 1040 dp) and centered on tablets/landscape; safe-area insets respected; nothing scrolls under the status bar; greeting shrinks instead of wrapping. | 03 §8, 03 §3.2 grid (`Adaptive(160dp)`) |
| 2 | **Tapping a task opens its details; only the circle completes it.** The "Tap on task" setting is removed. | FR-30, FR-102 (tap setting), 01 §4.4 #1, 03 §3.3 row gestures |
| 3 | **Lists have no icon picker** (name + color only). Existing emojis still display. | FR-02, 03 §3.6 |
| 4–5 | **New list and New task sheets minimize instead of closing** when dragged down, tapped outside or on Back; the draft is kept in a compact bar (tap / swipe up to continue). From the bar, closing asks to discard unsaved input. | 03 §3.4, 03 §3.6 |

## 1. Reading order

| # | Document | What it answers | Read when |
|---|----------|-----------------|-----------|
| 00 | `00-README.md` | Index, glossary, rules for AI agents | First |
| 01 | `01-Research-and-Brainstorm.md` | Market research, competitor features, brainstorm, key decisions and why | For context |
| 02 | `02-PRD.md` | Product requirements, user stories, acceptance criteria (IDs `FR-xx`, `NFR-xx`) | Before any code |
| 03 | `03-UX-Flows-and-Screens.md` | Information architecture, every screen, every flow, gestures, empty/error states | Building UI |
| 04 | `04-Design-System-and-Motion.md` | Colors, typography, shapes, spacing, **exact animation specs** | Building UI |
| 05 | `05-Technical-Architecture.md` | Tech stack, modules, layers, packages, dependency versions, coding rules | Project setup |
| 06 | `06-Data-Model-and-Persistence.md` | Room schema, DAOs, queries, ordering algorithm, migrations, DataStore settings | Data layer |
| 07 | `07-Reminder-Engine.md` | **Core feature.** Priority→cadence, scheduling algorithm, alarms, notifications, permissions, edge cases | Reminders |
| 08 | `08-Drag-and-Drop-Spec.md` | Long-press drag, reorder, **drop onto a task to make it a subtask**, promote/demote | List screen |
| 09 | `09-Backend-and-Sync.md` | v1 local-first "backend" + Phase 2 cloud sync (Supabase) API and protocol | Phase 2 |
| 10 | `10-Implementation-Plan.md` | Milestones, ordered task breakdown, Definition of Done per milestone | Planning / executing |
| 11 | `11-Testing-and-QA.md` | Test strategy, required test cases, manual QA checklist | Every milestone |
| 12 | `12-Build-Release-Deployment.md` | Gradle, signing, CI/CD (GitHub Actions), Play Store / sideload release | Release |

---

## 2. One-paragraph product summary

A person who keeps forgetting their tasks creates **lists** (each with a name and color). In each list they add **tasks**, and can nest **subtasks** under a task (e.g. "Read books" → "Atomic Habits", "Deep Work"). Every task has a **priority** (Urgent / High / Medium / Low / None) and a **progress %** set with a slider. Priority sets how often the app **reminds** you while the task is incomplete: Urgent every 10 min, High every hour, Medium every 3 h, Low twice a day at 8 AM and 9 PM. You can override this per task (10 m / 30 m / 1 h / 2 h / 3 h / 5 h / twice daily / off). Tapping a task completes it, and it moves to that list's **Completed** section. Long-press drags a task to reorder it, and dropping it **onto** another task makes it a subtask.

---

## 3. Glossary (use these exact terms in code and UI)

| Term | Meaning | Code name |
|------|---------|-----------|
| List | A named, colored container of tasks | `TaskList` |
| Task | A to-do item. May be top-level or a subtask | `Task` |
| Parent task | A top-level task that has one or more subtasks | `Task` with children |
| Subtask | A task whose `parentId != null`. **Max nesting depth = 1** | `Task` with `parentId` |
| Priority | `URGENT`, `HIGH`, `MEDIUM`, `LOW`, `NONE` | `Priority` enum |
| Cadence | How often reminders repeat, e.g. `EVERY_10_MIN`, `TWICE_DAILY` | `ReminderCadence` enum |
| Cadence override | A per-task cadence that replaces the priority's default | `Task.cadenceOverride` |
| Nag / Reminder | A notification posted because a task is incomplete and its next reminder time has passed | — |
| Snooze | Pause a task's reminders until a given time | `Task.snoozedUntil` |
| Quiet hours | A daily window in which interval reminders are held back | `Settings.quietHours` |
| Dispatcher | The single-alarm component that fires due reminders | `ReminderDispatcher` |
| Completed section | Collapsible area at the bottom of a list showing completed top-level tasks | — |
| Effective progress | Manual `progress` for a task without subtasks. For a parent, the average of its subtasks' effective progress (completed = 100) | `effectiveProgress()` |

---

## 4. Rules for AI coding agents building from these docs

1. **Follow the documents literally.** If code and docs disagree, the docs win. If two docs disagree, the more specific one wins, in this order: 07/08 > 06 > 05 > 03/04 > 02 > 01.
2. **Build in the order in `10-Implementation-Plan.md`.** Each milestone must compile, pass its tests, and meet its Definition of Done before the next one starts.
3. **Do not add features** outside the PRD's v1 scope (`02-PRD.md §6`). Phase 2 items are listed so the architecture leaves room for them, not so they get built now.
4. **Do not swap the tech stack** (`05 §2`). Use the latest *stable* release of each library at build time (never alpha, unless the docs name one). If a named API has been renamed, use its direct successor and leave a `// NOTE:` comment.
5. **Every requirement has an ID** (`FR-xx`, `NFR-xx`, `RE-xx`, `DD-xx`). Cite the ID in commit messages and in KDoc on the main class that implements it.
6. **The reminder engine is the heart of the product.** Its pure calculation logic (`ReminderCalculator`) must be 100% unit-tested with the cases in `07 §12` and `11 §3`.
7. **Never block the main thread.** All DB and IO work runs through coroutines on injected dispatchers.
8. **Accessibility is not optional.** Every interactive element needs a content description, a 48 dp minimum touch target, and a TalkBack alternative for every gesture (see `03 §9`).
9. When done with a milestone, update `CHANGELOG.md` in the repo root and tick its checklist in `10-Implementation-Plan.md`.

---

## 5. Decisions at a glance

| Topic | Decision |
|-------|----------|
| Platform | Native Android, Kotlin, Jetpack Compose, Material 3 Expressive |
| Min / Target SDK | minSdk 26 (Android 8.0), targetSdk 36 (Android 16), compileSdk 36 |
| Architecture | Clean architecture + MVVM / unidirectional data flow, multi-module, Hilt DI |
| Storage | Room (SQLite) as the source of truth; Preferences DataStore for settings |
| "Backend" v1 | None. Fully offline, local-first. JSON export/import + Android Auto Backup |
| Backend Phase 2 | Supabase (Postgres + Auth + RLS) with a delta-sync protocol. Schema is sync-ready from day 1 (UUIDs, `updatedAt`, soft deletes) |
| Reminders | A single `AlarmManager` alarm for the earliest due reminder (the "dispatcher" pattern). Exact alarms when permitted, inexact fallback, WorkManager safety net every 15 min, reschedule on boot, time change and app update |
| Subtask depth | 1 level (same as Google Tasks) |
| Tap on task | Toggles completion (as the user asked); the trailing "›" button opens details |
| Long-press | Starts drag. Drop onto the middle of a task makes it a subtask |
| Ordering | `sortOrder: Double` with midpoint insertion and occasional renormalization |
