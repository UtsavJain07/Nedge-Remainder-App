# 09 — Backend & Sync

## 1. v1: no server (local-first)

In v1 the app has **no remote backend and no INTERNET permission** (NFR-03, NFR-04). The "backend" is the on-device data layer:

| Concern | v1 implementation |
|---------|-------------------|
| Persistence | Room (`06`) |
| Business rules | `:core:domain` use cases + repository integrity checks |
| Background jobs | AlarmManager dispatcher + WorkManager (reconciler, purge) (`07`) |
| Backup | Android Auto Backup (Google account, automatic) + manual JSON export/import (`06 §10`) |
| Auth | None |

Why: a personal reminder app must work instantly and offline, reminders are computed on the device anyway, and there is zero cost and zero privacy risk.

**Sync-readiness already built into v1:** UUID string IDs, `createdAt`/`updatedAt` on every row, soft deletes (`deletedAt`) with a 30-day purge, repositories as the single write path, and reminder runtime state kept apart from user data.

---

## 2. Phase 2: cloud sync (multi-device + backup)

### 2.1 Stack

| Piece | Choice |
|-------|--------|
| Backend | **Supabase**: Postgres 15+, PostgREST API, Auth, Row Level Security, Realtime (optional) |
| Client SDK | `io.github.jan-tennert.supabase` (supabase-kt): `postgrest-kt`, `auth-kt`, `realtime-kt`, Ktor client engine `ktor-client-okhttp` |
| Auth methods | Google Sign-In through Android Credential Manager (ID token → `signInWith(IDToken)`), and email magic link |
| Sync runner | WorkManager `SyncWorker` (constraint `NetworkType.CONNECTED`) |
| Environments | `dev` and `prod` Supabase projects. Keys in `local.properties` → `BuildConfig` (the anon key is public by design; **never** ship the service-role key) |

Alternatives considered: Firebase Firestore (it would add a second offline cache on top of Room, and it's a NoSQL mismatch), or a custom Ktor + Postgres server (more ops for a personal app). Supabase keeps SQL parity with Room and makes RLS simple.

### 2.2 Postgres schema

```sql
create sequence public.sync_version_seq;

create table public.task_lists (
  id               uuid primary key,
  user_id          uuid not null references auth.users(id) on delete cascade,
  name             text not null check (char_length(name) between 1 and 40),
  color_argb       integer not null,
  emoji            text,
  sort_order       double precision not null,
  is_default       boolean not null default false,
  sort_mode        text not null default 'MY_ORDER',
  completed_expanded boolean not null default false,
  created_at       timestamptz not null,
  updated_at       timestamptz not null,          -- client edit time (LWW)
  deleted_at       timestamptz,
  server_version   bigint not null default nextval('public.sync_version_seq')
);

create table public.tasks (
  id               uuid primary key,
  user_id          uuid not null references auth.users(id) on delete cascade,
  list_id          uuid not null references public.task_lists(id) on delete cascade,
  parent_id        uuid references public.tasks(id) on delete cascade,
  title            text not null check (char_length(title) between 1 and 200),
  notes            text not null default '',
  priority         smallint not null default 0 check (priority between 0 and 4),
  cadence_override text,
  progress         smallint not null default 0 check (progress between 0 and 100),
  progress_before_complete smallint,
  is_completed     boolean not null default false,
  completed_at     timestamptz,
  due_date         date,
  due_minute_of_day smallint check (due_minute_of_day between 0 and 1439),
  sort_order       double precision not null,
  is_expanded      boolean not null default true,
  created_at       timestamptz not null,
  updated_at       timestamptz not null,
  deleted_at       timestamptz,
  server_version   bigint not null default nextval('public.sync_version_seq')
);

create index on public.tasks (user_id, server_version);
create index on public.task_lists (user_id, server_version);

-- bump server_version on every write
create or replace function public.bump_version() returns trigger language plpgsql as $$
begin new.server_version := nextval('public.sync_version_seq'); return new; end $$;
create trigger t_lists_version before insert or update on public.task_lists for each row execute function public.bump_version();
create trigger t_tasks_version before insert or update on public.tasks for each row execute function public.bump_version();

-- RLS
alter table public.task_lists enable row level security;
alter table public.tasks enable row level security;
create policy own_lists on public.task_lists for all using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy own_tasks on public.tasks      for all using (user_id = auth.uid()) with check (user_id = auth.uid());
```

**Not synced:** reminder runtime state (`reminder_*`, `next_reminder_*`, `snoozed_until`, `due_reminder_fired`). It is device-local, and each device computes its own schedule. User **settings** sync in a later iteration as a `user_settings` table (one jsonb row per user).

### 2.3 Push RPC (last-writer-wins by `updated_at`)

```sql
create or replace function public.push_changes(p_lists jsonb, p_tasks jsonb)
returns jsonb language plpgsql security invoker as $$
declare r jsonb;
begin
  for r in select * from jsonb_array_elements(p_lists) loop
    insert into public.task_lists as t (id, user_id, name, color_argb, emoji, sort_order, is_default, sort_mode,
                                        completed_expanded, created_at, updated_at, deleted_at)
    values ((r->>'id')::uuid, auth.uid(), r->>'name', (r->>'color_argb')::int, r->>'emoji', (r->>'sort_order')::float8,
            (r->>'is_default')::bool, r->>'sort_mode', (r->>'completed_expanded')::bool,
            (r->>'created_at')::timestamptz, (r->>'updated_at')::timestamptz, (r->>'deleted_at')::timestamptz)
    on conflict (id) do update set
      name = excluded.name, color_argb = excluded.color_argb, emoji = excluded.emoji, sort_order = excluded.sort_order,
      is_default = excluded.is_default, sort_mode = excluded.sort_mode, completed_expanded = excluded.completed_expanded,
      updated_at = excluded.updated_at, deleted_at = excluded.deleted_at
    where t.updated_at < excluded.updated_at and t.user_id = auth.uid();
  end loop;
  -- same pattern for p_tasks (all task columns)
  return jsonb_build_object('max_version', (select max(server_version) from public.tasks where user_id = auth.uid()));
end $$;
```

### 2.4 Client sync protocol

Local additions (Room migration v1→v2): `sync_state INTEGER NOT NULL DEFAULT 1` (0 = clean, 1 = dirty) on both tables. DataStore: `sync_cursor_lists: Long` and `sync_cursor_tasks: Long` (last pulled `server_version` **per table**; both tables share one sequence, so one cursor would skip rows), `sync_user_id`.

Every user-data write in the repositories sets `sync_state = 1` and `updated_at = now`. Reminder-state updates do **not** (`06 §3`).

```
SyncWorker.doWork():
  if not signed in: return success
  1. PUSH
     dirtyLists = listDao.dirty(); dirtyTasks = taskDao.dirty()           // include soft-deleted rows
     chunk by 200 → rpc("push_changes", {p_lists, p_tasks})
     on success: mark those rows clean ONLY IF their updated_at is unchanged since read (compare-and-set)
  2. PULL (lists first, then tasks)
     loop:
       rows = from("task_lists").select().gt("server_version", sync_cursor_lists).order("server_version").limit(500)
       apply each: local = get(id)
                   if local == null → insert (sync_state = 0)
                   else if local.sync_state == 0 or remote.updated_at >= local.updated_at → overwrite (sync_state = 0)
                   else keep local (it will be pushed next run)
       sync_cursor_lists = max(server_version seen); persist after each page
     same for tasks with sync_cursor_tasks
  3. REPAIR hierarchy: any task whose parent is missing/deleted → parent_id = null; any subtask whose parent has a parent → flatten
  4. reminderScheduler.rescheduleAll()   // remote changes may affect reminders
  return success   (network errors → Result.retry() with exponential backoff)
```

Triggers:
- `OneTimeWorkRequest` unique `"sync"`, `ExistingWorkPolicy.REPLACE`, initial delay 5 s after any local write (debounce).
- On app foreground.
- Periodic every 1 h.
- Optional: a Realtime channel while in the foreground, listening for `tasks`/`task_lists` changes for the user → enqueue a pull.

First sign-in on a device that has local data: offer **Merge** (push all local, then pull) or **Replace local with cloud**.

### 2.5 Multi-device reminders

Each device nags on its own. Phase 2 adds a per-device setting, "Send reminders on this device" (default ON), so the user can silence a tablet.

### 2.6 Security & privacy (Phase 2)

- RLS on every table. The function uses `security invoker`, so RLS applies.
- TLS only. The Supabase anon key is in BuildConfig.
- Account deletion in Settings: calls an Edge Function `delete-account` (service role, server-side) that deletes `auth.users` row → cascade.
- Privacy policy update: data is stored in Supabase (region choice: closest to the user, e.g. `ap-south-1`).
- Add the `INTERNET` and `ACCESS_NETWORK_STATE` permissions only in Phase 2.

### 2.7 Phase 2 API surface summary

| Operation | Endpoint |
|-----------|----------|
| Sign in (Google) | `auth.signInWith(IDToken) { idToken; provider = Google }` |
| Sign in (email) | `auth.signInWith(OTP) { email }` |
| Push | `POST /rest/v1/rpc/push_changes` |
| Pull lists | `GET /rest/v1/task_lists?server_version=gt.{listsCursor}&order=server_version&limit=500` |
| Pull tasks | `GET /rest/v1/tasks?server_version=gt.{tasksCursor}&order=server_version&limit=500` |
| Delete account | `POST /functions/v1/delete-account` |
