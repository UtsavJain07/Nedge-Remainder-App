# Nudge — the to-do app that doesn't let you forget

Native Android to-do & reminder app. Every incomplete task keeps **nudging you at an interval set by its
priority** (Urgent every 10 min, High hourly, Medium every 3 h, Low at 8 AM & 9 PM) until it's done.
Lists with colors, one level of subtasks, drag-to-nest, progress, quiet hours, snooze and a Material 3
Expressive-style UI.

The full product and engineering spec lives in [`docs/`](docs/00-README.md) (PRD, UX, design system,
architecture, data model, reminder engine, drag & drop, testing, release).

## Build & run

Requirements: JDK 17, Android SDK with platform 37 (compile) / 36 (target).

```bash
./gradlew assembleDebug                 # app/build/outputs/apk/debug/app-debug.apk
./gradlew test testDebugUnitTest        # all unit + Robolectric tests
./gradlew lintDebug                     # Android lint (CI gate)
./gradlew assembleRelease bundleRelease # R8-minified; signed when keystore env vars are set
```

Release signing reads `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` from the
environment (CI) or `local.properties`. The keystore is never committed (see `docs/12-…`).

Debug builds have **Settings › Debug tools**: dispatch now, scheduled reminders, time travel,
reset anchors and **Load demo data**.

## Architecture

Clean architecture + MVVM/UDF, multi-module, Hilt, Room (source of truth), DataStore, WorkManager.

```
app ── feature:{onboarding, home, list, taskdetail, quickadd, smartview, search, settings}
        └── core:ui (TaskRow, drag-and-drop engine) ── core:designsystem (theme, motion, components)
core:domain (pure Kotlin: rules, ordering, tree, use cases) ── core:model, core:common
core:data (repositories, JSON backup) ── core:database (Room + FTS) ── core:datastore
core:reminders (ReminderCalculator, single-alarm dispatcher, notifications, receivers, workers)
core:testing-jvm / core:testing (fakes, in-memory repositories, builders)
```

**Reminder engine:** exactly one pending `AlarmManager` alarm (the earliest reminder). Each fire
dispatches every due task in one batch, advances state and re-arms. A 15-minute WorkManager reconciler,
boot/time/timezone/update receivers and a cold-start reschedule make it survive process death and reboots.
All timing decisions live in the pure `ReminderCalculator` (cases C1–C25 in `docs/07` are unit-tested).

## Tests

293 JVM/Robolectric tests: calculator C1–C25 + quiet hours, dispatcher D1–D7 and user stories
US-2/US-3/US-7 end-to-end on a test clock, domain rules (ordering, tree, completion cascade, move
integrity), Room repositories incl. FTS and backup round-trips, drag-and-drop logic (T4–T6).

## Deviations from the spec (and why)

| Spec | Implemented | Reason |
|------|-------------|--------|
| compileSdk 36 | compileSdk **37**, targetSdk 36 | Latest stable androidx core 1.19 / Compose UI 1.12 require compileSdk 37. Runtime behavior still targets 36. |
| `MaterialExpressiveTheme`, `MotionScheme.expressive()`, `MaterialShapes` | `MaterialTheme` + our own Expressive spring tokens (`NudgeExpressiveMotionScheme`) + soft-burst morph built on `graphics-shapes` | These APIs are internal in the latest stable material3 (1.4.0); 1.5.0-alpha would move all of Compose to alpha. One-line switch when 1.5 is stable. |
| `core:testing` (Android) | plus `core:testing-jvm` | Pure-JVM fakes so `core:domain` tests stay JVM-only. |
| ktlint + detekt | Android lint in CI | Not configured yet. |

## Status vs. `docs/10-Implementation-Plan.md`

Done: M0–M7 and most of M8 (animations A1–A20, haptics, reduced motion, TalkBack custom actions).
Open items:
- M8: tablet list-detail layout (S), a full TalkBack / 200% font audit.
- M9: baseline profile + Macrobenchmark, Compose UI tests for US-1/US-4/US-5/US-6 (verified manually
  on an Android 16 emulator), store assets.
- "Confirm before delete" is stored in settings but not yet enforced by the swipe/menu delete paths.
