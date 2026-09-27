# 07 — Reminder Engine (core feature)

Module: `:core:reminders`. Requirements covered: FR-60 to FR-72, NFR-02, NFR-06.

> **Goal:** every open task with an active cadence produces a notification at the right time, even if the app was killed, the phone rebooted, or the clock changed. At most **one** alarm is ever pending.

---

## 1. Components

```
                ┌───────────────────────────── writes (use cases) ───────────────────────────┐
                │ create / edit priority / cadence / due / snooze / complete / delete / move │
                └──────────────┬─────────────────────────────────────────────────────────────┘
                               ▼
┌──────────────────────── ReminderSchedulerImpl (Mutex) ────────────────────────┐
│ onTasksChanged(ids): recompute next for ids → save → armNextAlarm()           │
│ rescheduleAll():     recompute all open → save → armNextAlarm()               │
│ armNextAlarm():      MIN(next_reminder_at) → AlarmScheduler.set / cancel      │
└───────┬───────────────────────────────────────────────────────▲──────────────┘
        │ uses                                                    │
┌───────▼─────────────┐   ┌────────────────────┐   ┌────────────┴─────────────┐
│ ReminderCalculator  │   │ AlarmScheduler     │   │ ReminderDispatcher       │
│ (PURE, no Android)  │   │ (AlarmManager wrap)│   │ dispatchDue(now)         │
│ computeNext(...)    │   └─────────┬──────────┘   │ → NotificationPublisher  │
│ isInQuietHours(...) │             │ fires        │ → advance state → arm    │
└─────────────────────┘             ▼              └────────────▲─────────────┘
                        ReminderAlarmReceiver ──────────────────┘
SystemEventsReceiver (boot/time/tz/update/permission) ──► rescheduleAll()
ReconcilerWorker (every 15 min) ──────────────────────────► dispatchDue(now) + armNextAlarm()
NotificationActionReceiver (Done / Snooze) ───────────────► use cases ─► onTasksChanged
ReminderHealthChecker ────────────────────────────────────► Settings / Home banner
```

---

## 2. Cadence resolution

```
effectiveCadence(task) = task.cadenceOverride ?: settings.defaultCadence[task.priority]
```

| Priority | Default | Kind |
|----------|---------|------|
| URGENT | `EVERY_10_MIN` | Interval |
| HIGH | `EVERY_1_H` | Interval |
| MEDIUM | `EVERY_3_H` | Interval |
| LOW | `TWICE_DAILY` (08:00, 21:00) | Fixed time |
| NONE | `OFF` | — |

Override options: `EVERY_10_MIN, EVERY_30_MIN, EVERY_1_H, EVERY_2_H, EVERY_3_H, EVERY_5_H, TWICE_DAILY, OFF`.

Quiet-hours bypass is decided by **priority**, not cadence: `bypassQuiet = settings.urgentIgnoresQuietHours && task.priority == URGENT`.

---

## 3. State per task (columns, see `06 §2.2`)

| Field | Meaning |
|-------|---------|
| `reminderAnchorAt` | Start point for interval counting |
| `nextReminderAt` / `nextReminderKind` | The next time this task should notify, and why |
| `lastRemindedAt`, `reminderCount` | For "Reminder #n" and the "Nudged n times" label |
| `snoozedUntil` | While in the future, nothing fires before it. It fires **at** it |
| `dueReminderFired` | Whether the one-off due reminder has fired |

### 3.1 Anchor reset rules (in use cases, not in the calculator)

Set `anchorAt = now`, `reminderCount = 0`, `lastRemindedAt = null` when:
- the task is created
- the priority changes
- the cadence override changes
- the task is un-completed
- the global pause ends (for all tasks, via `rescheduleAll(resetAnchors = true)`)

Moving a task to another list, reordering, or editing title/notes/progress does **not** reset the anchor.

Set `dueReminderFired = false` when the due date/time changes, **unless** the new due instant is ≤ now, in which case set it to `true` (never fire for a past date the user just typed).

---

## 4. `ReminderCalculator` (pure, 100% unit-tested)

```kotlin
data class NextReminder(val at: Instant, val kind: ReminderKind)

class ReminderCalculator {

    fun computeNext(task: Task, settings: ReminderSettings, now: Instant, zone: ZoneId): NextReminder? {
        if (task.isCompleted) return null
        val paused = settings.pausedUntil
        if (paused != null && paused == Instant.MAX) return null

        // Earliest moment anything may fire.
        val floor = maxOf(now, task.reminder.snoozedUntil ?: Instant.MIN, paused ?: Instant.MIN)
        val candidates = mutableListOf<NextReminder>()
        val cadence = task.effectiveCadence(settings)
        val bypassQuiet = settings.urgentIgnoresQuietHours && task.priority == Priority.URGENT

        // (a) Snooze end: always fires exactly at snoozedUntil (if in future), regardless of cadence/quiet hours.
        task.reminder.snoozedUntil?.takeIf { it > now }?.let { candidates += NextReminder(it, ReminderKind.INTERVAL) }

        // (b) Interval cadence
        if (cadence.isInterval && (task.reminder.snoozedUntil == null || task.reminder.snoozedUntil <= now)) {
            val interval = Duration.ofMinutes(cadence.intervalMinutes!!.toLong())
            var t = task.reminder.anchorAt.plus(interval)
            if (t < floor) t = floor                                   // missed (device off / inexact) → catch up once, ASAP
            if (settings.quietHoursEnabled && !bypassQuiet && isInQuietHours(t, settings, zone)) {
                t = nextQuietEnd(t, settings, zone)
            }
            candidates += NextReminder(t, ReminderKind.INTERVAL)
        }

        // (c) Twice daily (fixed times), ignores quiet hours; missed occurrences are skipped (no catch-up)
        if (cadence == ReminderCadence.TWICE_DAILY) {
            candidates += NextReminder(nextFixedTime(floor, listOf(settings.morningTime, settings.eveningTime), zone), ReminderKind.FIXED_TIME)
        }

        // (d) One-off due reminder, ignores quiet hours and cadence (fires even if cadence OFF)
        if (task.dueDate != null && !task.reminder.dueReminderFired) {
            val dueAt = ZonedDateTime.of(task.dueDate, task.dueTime ?: settings.morningTime, zone).toInstant()
            candidates += NextReminder(maxOf(dueAt, floor), ReminderKind.DUE)
        }

        // Earliest wins; tie-break DUE > INTERVAL > FIXED_TIME
        return candidates.minWithOrNull(compareBy<NextReminder> { it.at }.thenBy { kindRank(it.kind) })
    }

    private fun kindRank(k: ReminderKind) = when (k) { ReminderKind.DUE -> 0; ReminderKind.INTERVAL -> 1; ReminderKind.FIXED_TIME -> 2 }

    fun isInQuietHours(t: Instant, s: ReminderSettings, zone: ZoneId): Boolean {
        val lt = t.atZone(zone).toLocalTime()
        return if (s.quietStart < s.quietEnd) lt >= s.quietStart && lt < s.quietEnd
        else lt >= s.quietStart || lt < s.quietEnd        // window crosses midnight (default 22:00–08:00)
    }

    /** First instant ≥ t at which local time == quietEnd. */
    fun nextQuietEnd(t: Instant, s: ReminderSettings, zone: ZoneId): Instant {
        val z = t.atZone(zone)
        var c = ZonedDateTime.of(z.toLocalDate(), s.quietEnd, zone)   // DST gap → shifted forward by java.time
        if (!c.toInstant().isAfter(t)) c = ZonedDateTime.of(z.toLocalDate().plusDays(1), s.quietEnd, zone)
        return c.toInstant()
    }

    /** First instant strictly after `after` whose local time is one of `times`. */
    fun nextFixedTime(after: Instant, times: List<LocalTime>, zone: ZoneId): Instant {
        val d = after.atZone(zone).toLocalDate()
        return (0..2L).asSequence()
            .flatMap { off -> times.asSequence().map { ZonedDateTime.of(d.plusDays(off), it, zone).toInstant() } }
            .filter { it.isAfter(after) }
            .min()
    }
}
```

**Precision:** truncate all computed instants to whole seconds.

---

## 5. Dispatch algorithm (`ReminderDispatcher.dispatchDue(now)`)

```
TOLERANCE = 60 s   // fire reminders up to 60 s early so near-simultaneous ones batch into one wake-up

dispatchDue(now):
  mutex.withLock {
    settings = settingsRepo.settings.first().reminders
    due = taskDao.dueReminders(until = now + TOLERANCE)        // open, not deleted, next ≤ now+60s
    if settings.pausedUntil > now: due = []                     // safety; calculator should already exclude
    updates = []
    for task in due:
        kind = task.nextReminderKind
        publisher.post(task, list, kind, count = task.reminderCount + 1, subtaskStats)   // §7
        newState = task.reminder.copy(
            lastRemindedAt = now,
            count = task.reminderCount + 1,
            dueReminderFired = task.dueReminderFired || kind == DUE,
            snoozedUntil = if (task.snoozedUntil != null && task.snoozedUntil <= now + TOLERANCE) null else task.snoozedUntil,
            anchorAt = if (kind == INTERVAL) now else task.anchorAt,   // interval restarts from actual fire time (see US-7)
        )
        next = calculator.computeNext(task.withReminder(newState), settings, now + TOLERANCE, zone)
        updates += ReminderStateUpdate(task.id, newState, next)
    taskRepo.updateReminderState(updates)          // single transaction, does NOT touch updatedAt
    publisher.updateGroupSummary()
    armNextAlarm()
  }
```

> Why `computeNext(..., now + TOLERANCE)`: this guarantees the next reminder is strictly after the one we just fired, even though we fired up to 60 s early.

### 5.1 `armNextAlarm()`

```kotlin
suspend fun armNextAlarm() {
    val earliest = taskRepo.earliestNextReminder()
    if (earliest == null) { alarmScheduler.cancel(); return }
    val at = maxOf(earliest, clock.now().plusSeconds(1))
    alarmScheduler.set(at)
}
```

### 5.2 `AlarmScheduler`

```kotlin
class AlarmScheduler @Inject constructor(@ApplicationContext ctx: Context, private val am: AlarmManager) {
    private val pi = PendingIntent.getBroadcast(ctx, REQ_DISPATCH,
        Intent(ctx, ReminderAlarmReceiver::class.java).setAction(ACTION_DISPATCH),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun canExact() = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()

    fun set(at: Instant) {
        val ms = at.toEpochMilli()
        if (canExact()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)      // inexact fallback (may be delayed)
    }
    fun cancel() = am.cancel(pi)
    companion object { const val REQ_DISPATCH = 1001; const val ACTION_DISPATCH = "app.nudge.action.DISPATCH" }
}
```

Wrap `setExactAndAllowWhileIdle` in `try/catch(SecurityException)`. If the permission was revoked in a race, fall back to inexact.

### 5.3 `ReminderSchedulerImpl`

```kotlin
override suspend fun onTasksChanged(taskIds: Collection<String>) = mutex.withLock {
    val s = settings().reminders; val now = clock.now()
    val updates = taskIds.mapNotNull { taskRepo.get(it) }.map { t ->
        val next = if (t.deletedAt != null) null else calculator.computeNext(t, s, now, clock.zone())
        ReminderStateUpdate(t.id, t.reminder, next)
    }
    taskRepo.updateReminderState(updates)
    updates.filter { it.next == null }.forEach { publisher.cancel(it.taskId) }   // completed/deleted/off
    armNextAlarm()
}

override suspend fun rescheduleAll() = mutex.withLock {
    channels.ensureCreated()
    // same as above for taskRepo.allOpenTasksWithReminders() + null-out any non-open task still holding nextReminderAt
}
```

---

## 6. Permissions & reliability

| Concern | Implementation |
|---------|----------------|
| `POST_NOTIFICATIONS` (API 33+) | Requested on onboarding page 3 via `rememberLauncherForActivityResult(RequestPermission())`. If it is permanently denied, "Fix" opens `Settings.ACTION_APP_NOTIFICATION_SETTINGS`. If notifications are disabled, the dispatcher still advances state (so there's no burst later). |
| Exact alarms (API 31+) | `Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${packageName}"))`. Listen to `AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` → `rescheduleAll()`. Also re-check `canScheduleExactAlarms()` in `MainActivity.onResume` and call `rescheduleAll()` if it changed. |
| Doze | `setExactAndAllowWhileIdle` (≈ once per 9 min while idle; the dispatcher batches, so it's fine). 60 s tolerance merges nearby reminders. |
| App standby buckets | A user-launched app with notifications stays active/working. No action needed. |
| Battery optimization | Health check: `PowerManager.isIgnoringBatteryOptimizations(pkg)`. The Fix button opens `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` (the list screen; **not** the direct request intent, which is Play-policy sensitive). |
| OEM killers | Detect `Build.MANUFACTURER` ∈ {xiaomi, redmi, poco, oppo, realme, vivo, oneplus, samsung, huawei, honor}. Show a tip with a link to `https://dontkillmyapp.com/<vendor>` (opened in the browser; needs no INTERNET permission). |
| Reboot | `BOOT_COMPLETED` → `rescheduleAll()`. |
| Time/zone change | `TIME_SET`, `TIMEZONE_CHANGED` → `rescheduleAll()` (fixed times and quiet hours are local wall-clock). |
| App update | `MY_PACKAGE_REPLACED` → `rescheduleAll()`. |
| Process death / alarm lost | `ReconcilerWorker`: `PeriodicWorkRequest(15, MINUTES)`, unique name `"reminder-reconciler"`, `ExistingPeriodicWorkPolicy.KEEP`. Its `doWork` runs `dispatcher.dispatchDue(now)` then `armNextAlarm()`. Enqueued in `NudgeApplication.onCreate`. |
| Cold start | `NudgeApplication.onCreate` launches `rescheduleAll()` on the application scope (cheap). |
| Receiver execution | `goAsync()`; the work is done in the application scope with `withTimeout(9_000)`; `pendingResult.finish()` in `finally`. |

---

## 7. Notifications (`NotificationPublisher`)

### 7.1 Channels (created in `rescheduleAll` and at app start; idempotent)

| Channel id | Name | Importance | Extras |
|------------|------|-----------|--------|
| `reminders_urgent` | Urgent reminders | HIGH | Vibration `[0, 250, 150, 250]`, light red |
| `reminders_high` | High priority reminders | HIGH | Default vibration |
| `reminders_medium` | Medium priority reminders | DEFAULT | |
| `reminders_low` | Low priority reminders | DEFAULT | |
| `reminders_due` | Due date reminders | HIGH | |
| `general` | General | LOW | Test nudge, info |

All reminder channels sit in the channel group `reminders` ("Reminders"). The channel is chosen by `kind == DUE ? due : priority`. A task with priority NONE but a cadence override uses `reminders_medium`.

### 7.2 Notification content

```
[icon] Nudge · Work                                       10:40
Fix prod bug                                                ← contentTitle (bold)
Urgent · 40% · 2/5 subtasks · Reminder #3                   ← contentText
"Check the logs on server 2 first…"                         ← BigTextStyle: notes (first 200 chars) if present
[ ✓ Done ]   [ ⏰ Snooze 1 h ]
```

- DUE kind: the title prefix is "Due now: ", and the text is "Due 5:00 PM · Work".
- FIXED_TIME kind: the subText is "Morning check-in" / "Evening check-in".

```kotlin
NotificationCompat.Builder(ctx, channelId)
    .setSmallIcon(R.drawable.ic_stat_nudge)                  // monochrome vector
    .setColor(list.colorArgb).setColorized(false)
    .setContentTitle(title).setContentText(text).setSubText(list.name)
    .setStyle(NotificationCompat.BigTextStyle().bigText(textWithNotes))
    .setCategory(NotificationCompat.CATEGORY_REMINDER)
    .setGroup(GROUP_KEY_REMINDERS)
    .setWhen(now.toEpochMilli()).setShowWhen(true)
    .setAutoCancel(true)
    .setOnlyAlertOnce(false)                                 // re-alert on each nag (same id replaces)
    .setContentIntent(openTaskPendingIntent(task.id))        // nudge://task/{id}, FLAG_IMMUTABLE
    .addAction(R.drawable.ic_check, getString(R.string.action_done), actionPI(task.id, ACTION_DONE))
    .addAction(R.drawable.ic_snooze, getString(R.string.action_snooze_fmt, snoozeLabel), actionPI(task.id, ACTION_SNOOZE))
    .build()
```

- **Notification ID:** `notificationIdFor(taskId) = (taskId.hashCode() and 0x7FFFFFFF).coerceAtLeast(10)`. IDs 1–9 are reserved (summary = 1, test = 2).
- **Re-nag = same ID**, so the shade holds one entry per task, and it updates.
- **Group summary** (id 1): posted when ≥ 2 reminder notifications are active (`NotificationManager.activeNotifications` filtered by group). `InboxStyle` lists up to 5 titles, title "N tasks need you", `setGroupSummary(true)`, `setGroupAlertBehavior(GROUP_ALERT_CHILDREN)`. Removed when fewer than 2 remain.
- PendingIntent request codes: open = `id`, done = `id * 31 + 1`, snooze = `id * 31 + 2` (Int overflow is fine; use `hashCode` math).
- Before posting, check `NotificationManagerCompat.from(ctx).areNotificationsEnabled()` and the API 33 permission (lint requires the check).

### 7.3 Actions (`NotificationActionReceiver`)

| Action | Behavior |
|--------|----------|
| `ACTION_DONE` | `ToggleCompleteUseCase(taskId, completed = true)` (completes open subtasks too) → cancel notification → update summary → `onTasksChanged` (inside the use case). If the task no longer exists: just cancel the notification. |
| `ACTION_SNOOZE` | `SnoozeUseCase(taskId, until = now + settings.defaultSnoozeMinutes)` → cancel notification. |
| Content tap | Activity deep link. No state change. |

### 7.4 Android 15+ notification cooldown

Nothing to disable. Our mitigations are grouping, one entry per task, separate channels, and the 60 s batching. Document in Help: "If repeated reminders become quiet, check Settings › Notifications › Notification cooldown."

---

## 8. Snooze (`SnoozeUseCase`)

Options: 15 min, 1 h, 3 h, Tomorrow (next day at `morningTime`), Custom (date + time pickers, must be in the future). It sets `snoozedUntil`, then `onTasksChanged`. "Cancel snooze" sets `snoozedUntil = null` and `anchorAt = now`.

## 9. Global pause (FR-71)

`settings.pausedUntil`: 1 h → now + 1 h. "Until tomorrow" → next `morningTime`. "Until I resume" → `Instant.MAX` (stored as `Long.MAX_VALUE`). While paused, the calculator returns `floor = pausedUntil`, so reminders resume at pause end. A timed pause needs no extra alarm, because the dispatcher's earliest time is ≥ pausedUntil. **Resume** (manual) → `pausedUntil = null`, `rescheduleAll(resetAnchors = true)`. Home shows the chip "Reminders paused until 3:00 PM [Resume]".

## 10. Reminder health (`ReminderHealthChecker`)

```kotlin
data class ReminderHealth(
    val notificationsEnabled: Boolean,
    val blockedChannels: List<String>,        // importance == NONE
    val exactAlarmsAllowed: Boolean,
    val ignoringBatteryOptimizations: Boolean,
    val oemTip: OemTip?,
) { val isDegraded get() = !notificationsEnabled || !exactAlarmsAllowed || blockedChannels.isNotEmpty() }
```

It is exposed as a `Flow` that re-checks on every `ON_RESUME` of the activity (`LifecycleEventEffect`). The **"Send test nudge in 10 s"** button in Health schedules a one-off exact alarm (separate PendingIntent, `REQ_TEST = 1002`) that posts a notification on the `general` channel.

## 11. Debug tools (debug builds only)

Settings › Debug: "Dispatch now", "Show scheduled reminders" (a table of task, next at, kind, count), "Next alarm at", "Time travel: advance fake clock by +10 min / +1 h" (debug `Clock` implementation with an offset), and "Reset all anchors".

---

## 12. Required calculator test cases (all must exist in `ReminderCalculatorTest`)

Zone `Asia/Kolkata` unless noted. Default settings: quiet hours 22:00–08:00, morning 08:00, evening 21:00.

| # | Given | Now | Expected next |
|---|-------|-----|---------------|
| C1 | URGENT, anchor 10:00 | 10:00 | 10:10 INTERVAL |
| C2 | HIGH, anchor 10:00 | 10:30 | 11:00 INTERVAL |
| C3 | MEDIUM (3 h), anchor 20:00 | 20:00 | 23:00 → in quiet → **08:00 next day** |
| C4 | URGENT, urgentIgnoresQuiet = true, anchor 21:55 | 21:55 | 22:05 |
| C5 | URGENT, urgentIgnoresQuiet = false, anchor 21:55 | 21:55 | 08:00 next day |
| C6 | LOW (twice daily) | 07:59 | 08:00 today FIXED_TIME |
| C7 | LOW | 08:00:30 | 21:00 today |
| C8 | LOW | 21:30 | 08:00 tomorrow |
| C9 | NONE, no due | any | null |
| C10 | NONE, due tomorrow 17:00 | 12:00 | tomorrow 17:00 DUE |
| C11 | NONE, due tomorrow, no time | 12:00 | tomorrow 08:00 DUE |
| C12 | HIGH anchor 10:00, due today 10:30 | 10:05 | 10:30 DUE (earlier than 11:00) |
| C13 | HIGH anchor 10:00, due 11:00 | 10:05 | 11:00 DUE (tie → DUE wins) |
| C14 | HIGH, snoozed until 15:00, anchor 10:00 | 12:00 | 15:00 INTERVAL (snooze end) |
| C15 | URGENT, anchor 09:00 (missed) | 10:00 | 10:00 (catch-up now) |
| C16 | Completed task | any | null |
| C17 | Global pause until 14:00, HIGH anchor 12:00 | 12:30 | 14:00 |
| C18 | Global pause MAX | any | null |
| C19 | Quiet hours 13:00–15:00 (same day window), HIGH anchor 12:30 | 12:30 | 15:00 |
| C20 | Quiet disabled, MEDIUM anchor 20:00 | 20:00 | 23:00 |
| C21 | Override EVERY_2_H on LOW task, anchor 10:00 | 10:00 | 12:00 INTERVAL |
| C22 | Override OFF on URGENT task | any | null (unless due) |
| C23 | DST: zone `America/New_York`, LOW, on 2026-03-08 at 01:30 (spring forward 02:00→03:00), morning 02:30 custom | 01:30 | 03:30 EDT (gap shifted forward) |
| C24 | Twice daily with `dueReminderFired = true` | 12:00 | 21:00 (due ignored) |
| C25 | Twice daily + snoozed until 22:30 | 12:00 | 22:30 (snooze end, earliest) — then next computed after snooze = 08:00 |

Dispatcher tests (Robolectric + fake clock + in-memory Room): D1 batching two tasks due 30 s apart → one dispatch, two notifications, one summary. D2 after dispatch, `anchorAt = now` and the next interval is correct. D3 a DUE fire sets `dueReminderFired`. D4 Done action completes the task and children and cancels the notification. D5 Snooze action. D6 `rescheduleAll` after a simulated boot arms the earliest. D7 cancel alarm when no tasks remain.
