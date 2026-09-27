# 08 — Drag & Drop: Reorder, Nest, Un-nest

Module: `:core:ui` (`app.nudge.core.ui.dnd`). Requirements: FR-22 to FR-26, FR-53. Animations: A5, A6, A7 (`04`).

Off-the-shelf Compose reorder libraries support **reordering only**. Dropping **onto** a task to nest it needs a custom controller. This spec defines one that works over the **flattened list** from `06 §5`.

---

## 1. Scope & rules

| ID | Rule |
|----|------|
| DD-01 | Drag is enabled only in the **List screen**, only for **open** items, and only when `list.sortMode == MY_ORDER`. |
| DD-02 | Start: **long-press (400 ms, the system default)** on any part of a row, then move. A long-press released with total movement < 8 dp opens the context menu instead. |
| DD-03 | **Reorder** by dragging vertically. Other rows animate out of the way live (`Modifier.animateItem`). |
| DD-04 | **Nest**: hover the dragged row's center over the **middle 50%** of a **top-level** target row for **250 ms** (the "dwell"). The target shows the nest highlight (A6). Release → the dragged task becomes the **last** subtask of the target. |
| DD-05 | A task **with subtasks** can't be nested or become a subtask. There is no nest highlight; after the dwell a reject shake (A7) plays once on the target. |
| DD-06 | **Indent/outdent by horizontal drag:** an x offset ≥ +48 dp prefers depth 1, and ≤ −48 dp prefers depth 0 (resolved against legal depths, §3.4). |
| DD-07 | Dragging a **parent** carries its subtasks. At drag start its children are hidden from the preview, and the dragged row shows a badge "+n". |
| DD-08 | Drop targets never include the Completed section. Dragging below the last open item clamps to the last slot. |
| DD-09 | Auto-scroll when the pointer is within **64 dp** of the list viewport's top/bottom edge. Speed = `lerp(2, 20, proximity)` px per frame (scaled by density). |
| DD-10 | The pager (`HorizontalPager`) and swipe-to-dismiss are disabled while a drag is active. |
| DD-11 | Nesting shows the snackbar "Moved into 'Read books'" with Undo. Plain reorders do not show a snackbar. |
| DD-12 | Haptics: `LongPress` at drag start, `SegmentFrequentTick` on each slot change, `Confirm` when nest arms, `Reject` on rejection. |

---

## 2. Data structures

```kotlin
/** One visible row in the open section, in display order. Built from ListTree.flatten() (06 §5). */
data class DndRow(val key: String, val depth: Int, val parentKey: String?, val hasChildren: Boolean, val isExpanded: Boolean)

@Stable
class DragDropState(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val density: Density,
    private val onMove: (MoveOperation) -> Unit,        // → ViewModel → MoveTaskUseCase
    private val haptics: HapticFeedback,
) {
    var draggingKey by mutableStateOf<String?>(null); private set
    var previewRows by mutableStateOf<List<DndRow>>(emptyList()); private set   // current preview order (open section)
    var dragDelta by mutableStateOf(Offset.Zero); private set                     // finger offset relative to the dragged row's slot
    var nestTargetKey by mutableStateOf<String?>(null); private set              // armed nest target
    var rejectTargetKey by mutableStateOf<String?>(null); private set            // triggers shake
    var hiddenChildCount by mutableStateOf(0); private set

    private var baseRows: List<DndRow> = emptyList()   // rows at drag start (source of truth for "no change" detection)
    private var dwellJob: Job? = null
    private var dwellKey: String? = null

    fun onDragStart(key: String, rows: List<DndRow>) { … }
    fun onDrag(delta: Offset) { … }
    fun onDragEnd() { … }
    fun onDragCancel() { … }
    fun isDragging(key: String) = draggingKey == key
}
```

`ListContent` renders `previewRows` while a drag is active (and for up to 500 ms after the drop until the DB emission matches; §5.3). Otherwise it renders the ViewModel's rows.

---

## 3. Algorithm

### 3.1 Drag start

```
onDragStart(key, rows):
  draggingKey = key; dragDelta = Offset.Zero; baseRows = rows
  r = rows[key]
  if r.hasChildren && r.isExpanded:
      hiddenChildCount = count of rows with parentKey == key
      previewRows = rows - children(key)
  else previewRows = rows; hiddenChildCount = if r.hasChildren then childCount else 0
  haptic(LongPress)
```

### 3.2 On each drag delta

```
onDrag(delta):
  dragDelta += delta
  info = listState.layoutInfo.visibleItemsInfo.first { it.key == draggingKey } ?: return
  centerY = info.offset + info.size / 2 + dragDelta.y
  target = visibleItemsInfo.firstOrNull { it.key != draggingKey && it.key in previewKeys
                                           && centerY in it.offset until it.offset + it.size }
  updateAutoScroll(pointerY = centerY)
  if target == null: cancelDwell(); return

  rel = (centerY - target.offset) / target.size          // 0..1 within target row
  t = previewRows[target.key]
  dragged = baseRows[draggingKey]

  // --- Nest zone ---
  if t.depth == 0 && rel in 0.25..0.75:
      // canNest: parents can't become subtasks. Nesting into the task's own current parent is allowed (moves it to the end).
      if dwellKey != t.key: startDwell(t.key, canNest = !dragged.hasChildren)
      return                                             // no reordering while in the nest zone
  cancelDwell()

  // --- Reorder zones ---
  insertIndex = if rel < 0.5 then indexOf(target) else indexOf(target) + 1   // index in previewRows WITHOUT dragged
  depth = resolveDepth(insertIndex, dragged, dragDelta.x)                   // §3.4, null = illegal slot
  if depth == null: return
  if (insertIndex, depth) == current (index, depth) of dragged in preview: return
  movePreview(draggingKey, insertIndex, depth)          // §3.3
  haptic(SegmentFrequentTick)
```

`startDwell(key, canNest)`:
```
dwellKey = key; dwellJob?.cancel()
dwellJob = scope.launch {
  delay(250)
  if (canNest) { nestTargetKey = key; haptic(Confirm) }
  else { rejectTargetKey = key; haptic(Reject); delay(360); rejectTargetKey = null }
}
```
`cancelDwell()`: cancel the job, `dwellKey = null`, `nestTargetKey = null`.

### 3.3 Moving the preview while keeping the row under the finger

When the dragged row changes slot, the `LazyColumn` re-lays it out at a new offset. To keep it visually under the finger, compensate `dragDelta`:

```
movePreview(key, insertIndex, depth):
  before = layoutInfo offset of key
  previewRows = previewRows.remove(key).insert(insertIndex, dragged.copy(depth = depth, parentKey = parentFor(insertIndex, depth)))
  // after next layout pass (snapshotFlow on layoutInfo, or withFrameNanos):
  after = layoutInfo offset of key
  dragDelta = dragDelta.copy(y = dragDelta.y - (after - before))
```

The dragged row itself uses `Modifier.graphicsLayer { translationY = dragDelta.y; translationX = dragDelta.x.coerceIn(-48dp, 48dp) * 0.5f }` and **no** `animateItem` placement (disable placement animation for the dragged key, or it will fight the finger). Other rows keep `animateItem()`.

The visual indentation of the dragged row animates between 0 and 40 dp (`animateDpAsState(SpringSnappy)`) based on its preview depth.

If the first visible item is the dragged one and it moves, call `listState.requestScrollToItem(firstVisibleIndex, firstVisibleOffset)` to keep the scroll anchored. This is the known LazyColumn first-item quirk; the Reorderable library does the same.

### 3.4 Depth resolution

Given the preview **without** the dragged row, and an insert index `i`: `prev = rows[i-1]` (or null), `next = rows[i]` (or null).

```
resolveDepth(i, dragged, dx):
  minDepth = if next != null && next.depth == 1 then 1 else 0      // inserting before a subtask → must be inside that group
  maxDepth = if dragged.hasChildren || prev == null then 0 else 1   // parents stay top-level; nothing above → top-level
  if minDepth > maxDepth: return null                              // e.g. a parent dropped between two subtasks → illegal
  preferred = dragged.depthAtStart + when { dx >= 48dp -> 1; dx <= -48dp -> -1; else -> 0 }
  return preferred.coerceIn(minDepth, maxDepth)

parentFor(i, depth):
  if depth == 0: null
  else if prev.depth == 0: prev.key          // directly under a top-level row → first child of it
  else prev.parentKey                        // among subtasks → sibling
```

Consequences:
- Drag a subtask between two top-level rows with no x offset: `preferred = 1`, and `minDepth = 0, maxDepth = 1` → it stays depth 1 under `prev` if `prev` is top-level; this is the Google Tasks behavior. Drag left ≥ 48 dp → depth 0 (un-nest, FR-23).
- Drag a top-level task directly under another top-level task and move right ≥ 48 dp → becomes its first child (FR-24).
- Drag any task above the first row → depth 0.

> Note: if `prev` is a **collapsed** parent and depth 1 is chosen, the task becomes the **last** child of `prev` (append), because the children aren't visible.

### 3.5 Drop

```
onDragEnd():
  if nestTargetKey != null:
      op = MoveOperation.Nest(taskId = draggingKey, parentId = nestTargetKey)
  else:
      d = previewRows[draggingKey]; idx = indexOf(draggingKey)
      siblings = previewRows.filter { it.depth == d.depth && it.parentKey == d.parentKey }   // same group, preview order
      above = siblings.getOrNull(siblings.indexOf(d) - 1)?.key
      below = siblings.getOrNull(siblings.indexOf(d) + 1)?.key
      op = MoveOperation.Reorder(taskId = draggingKey, parentId = d.parentKey, aboveId = above, belowId = below)
      if op describes the same position as in baseRows: op = null
  op?.let(onMove)
  keepPreviewUntilDbMatches()      // §5.3
  reset(): draggingKey = null; dragDelta = Zero; cancelDwell(); hiddenChildCount = 0; stop auto-scroll
```

`onDragCancel()` = reset with no move, and the preview snaps back (springs back to the base order).

---

## 4. Visual states per row

| State | Visual |
|-------|--------|
| Idle | Normal row |
| Dragging (the dragged row) | A5: elevation 8 dp, scale 1.03, `surfaceContainerHighest`, 12 dp corners, z-index 1 (`Modifier.zIndex(1f)`), "+n" badge if children are hidden |
| Nest target armed | A6: `primaryContainer` 60%, scale 1.02, 2 dp `primary` border, ghost placeholder under the target (a 48 dp outlined rounded box indented 40 dp, dashed border, animated in with `animateContentSize`) |
| Reject | A7 shake on the target |
| Others | Shift with `animateItem(placementSpec = SpringBouncy)` |

---

## 5. Integration

### 5.1 Gesture wiring (inside each open `TaskRow`)

```kotlin
Modifier.pointerInput(item.key, dragEnabled) {
    if (!dragEnabled) return@pointerInput
    var total = Offset.Zero
    detectDragGesturesAfterLongPress(
        onDragStart = { total = Offset.Zero; dnd.onDragStart(item.key, currentRows()) },
        onDrag = { change, amount -> change.consume(); total += amount; dnd.onDrag(amount) },
        onDragEnd = { if (total.getDistance() < 8.dp.toPx()) { dnd.onDragCancel(); openContextMenu(item.key) } else dnd.onDragEnd() },
        onDragCancel = { dnd.onDragCancel() },
    )
}
```

The row's `clickable` (tap to complete) and the checkbox coexist with this, because `detectDragGesturesAfterLongPress` only consumes after the long-press timeout.

### 5.2 `MoveOperation` and repository semantics

```kotlin
sealed interface MoveOperation {
    val taskId: String
    data class Reorder(override val taskId: String, val parentId: String?, val aboveId: String?, val belowId: String?) : MoveOperation
    data class Nest(override val taskId: String, val parentId: String) : MoveOperation              // append as last child
    data class ToTopLevel(override val taskId: String) : MoveOperation                              // place right after its old parent
    data class ToList(override val taskId: String, val listId: String) : MoveOperation              // top of target list, top-level
}
```

`TaskRepository.move(op)` runs in one transaction:
1. Load the task and validate the rules in `06 §5` (same list; the parent is top-level and not the task itself; a task with children can't get a parent). Throw `IllegalMoveException` on a violation. The UI has already prevented it, so this is a safety net.
2. Compute the new `sortOrder` with `OrderingCalculator.between(above?.sortOrder, below?.sortOrder)`. For `Nest`, use `bottom(max sortOrder of the parent's children)`. Renormalize the group first if the gap is < 1e-6.
3. Set `parentId`, `sortOrder`, `updatedAt = now`. For `Nest`, also set the parent's `isExpanded = true`.
4. If the parent is **completed** and the task is open, reject (the UI never offers completed targets).
5. For `ToList`: set `listId` on the task and its children, `parentId = null`, and `sortOrder = top(min)`.
6. Return an `UndoSnapshot` with every row changed.
7. `reminderScheduler.onTasksChanged(ids)` is not needed for reorders (reminders don't depend on order). It is needed for `ToList` (the list name appears in notifications, though content is read at post time, so it's optional).

### 5.3 Avoiding flicker after drop

After `onMove`, the DB emits a new tree about 5–30 ms later. Keep rendering `previewRows` until the ViewModel's rows have the same key order and depths as the preview, **or** 500 ms pass. Then switch to the ViewModel rows. Implement this in `ListContent` with a `LaunchedEffect(vmRows)` check.

---

## 6. Accessibility alternatives (FR-26)

Each open row exposes `semantics { customActions = … }`:

| Action label | Operation |
|--------------|-----------|
| "Move up" | `Reorder` with above = the previous-previous sibling and below = the previous sibling |
| "Move down" | Symmetric |
| "Make subtask of <previous task title>" | `Nest(parentId = previous top-level task)` (only if legal) |
| "Move to top level" | `ToTopLevel` (subtasks only) |
| "Move to list…" | Opens the list picker → `ToList` |

The context menu shows the same actions ("Make subtask of ›" opens a picker of legal top-level tasks).

---

## 7. Tests

`DragDropStateTest` (JVM, with a fake layout info provider):
- T1: moving the center across the lower half of the next row → preview swaps and dragDelta is compensated.
- T2: hovering the middle zone of a top-level row for 249 ms → not armed; for 250 ms → armed; leaving the zone → disarmed.
- T3: the dragged row has children → hovering the middle zone → reject, not armed.
- T4: `resolveDepth` table (all combinations of prev/next depth, hasChildren, dx).
- T5: onDragEnd emits the correct `MoveOperation` for reorder, nest, un-nest and indent.
- T6: no-op drop → no operation emitted.

`TaskRepositoryMoveTest` (Room in-memory): the integrity rules, sortOrder midpoint, renormalize, Undo restores the exact rows.

Compose UI test `ListDragTest`: a long-press drag using `performTouchInput { down(); advanceEventTime(500); moveBy(…); up() }` nests "Atomic Habits" into "Read books".
