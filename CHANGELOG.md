# Changelog

All notable changes to this project are documented here ([Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
[SemVer](https://semver.org/)).

## [Unreleased]

### Changed (v1.1 review)
- Tapping a task opens its details; only the circle completes it. The "Tap on task" setting is removed.
- New list and New task sheets minimize to a compact bar instead of closing when dragged down, tapped
  outside or on Back; the draft is kept and closing from the bar asks before discarding.
- Lists are created with a name and color only (icon picker removed).
- Layout adapts to all screen sizes: two columns on 360 dp phones, more on tablets, centered readable
  width on tablets/landscape, safe-area insets, no content under the status bar, one-line greeting.

### Fixed
- Home content scrolled underneath the status bar.


### Added
- Lists with 12 colors and emoji, reorderable on Home; default "My Tasks" list (FR-01..FR-06).
- Tasks with notes, priority, progress, due date/time and per-task reminder cadence; Quick Add sheet
  with rapid Enter-to-save entry (FR-10..FR-16).
- One level of subtasks with computed parent progress; drag to reorder, drop onto a task to nest,
  drag left/right to un-nest/indent, plus context-menu and TalkBack alternatives (FR-20..FR-26).
- Tap-to-complete with animated check morph, strike-through, 600 ms hold, Completed section, Undo,
  swipe to complete/delete, confetti when a list is done (FR-30..FR-38).
- Priority-driven repeating reminders: single exact alarm dispatcher, quiet hours, twice-daily
  check-ins, due-time reminders, snooze, pause all, grouped notifications with Done/Snooze,
  survives reboot/time change/app update (FR-60..FR-72).
- Home dashboard (greeting, Focus now, Today/All smart views), search, onboarding with permission
  cards, reminder health screen, settings, JSON export/import, debug tools (FR-80..FR-104).
- CI (lint + tests + debug APK) and tag-triggered signed release workflow.
