package app.nudge.core.ui.dnd

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import app.nudge.core.model.MoveOperation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A laid-out row: main-axis offset + size in px, relative to the viewport. */
data class DndItemInfo(val key: Any, val offset: Int, val size: Int)

/** What the drag controller needs from the list; abstracted so T1–T3 can use a fake. */
interface DndLayout {
    fun visibleItems(): List<DndItemInfo>

    val viewportStart: Int
    val viewportEnd: Int

    suspend fun scrollBy(px: Float)

    /** Keep the scroll anchored before the first visible item moves (LazyColumn first-item quirk, 08 §3.3). */
    fun anchorFirstVisible()
}

class LazyListDndLayout(private val state: LazyListState) : DndLayout {
    override fun visibleItems(): List<DndItemInfo> = state.layoutInfo.visibleItemsInfo.map { DndItemInfo(it.key, it.offset, it.size) }

    override val viewportStart: Int get() = state.layoutInfo.viewportStartOffset
    override val viewportEnd: Int get() = state.layoutInfo.viewportEndOffset

    override suspend fun scrollBy(px: Float) {
        state.scrollBy(px)
    }

    override fun anchorFirstVisible() {
        state.requestScrollToItem(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
    }
}

/** Haptic hooks, so this class stays free of Compose haptics types. */
interface DndFeedback {
    fun dragStart()

    fun slotChanged()

    fun nestArmed()

    fun rejected()
}

/**
 * Drag-and-drop controller over the flattened open section (08 §2–§3): live reorder, nest dwell
 * (250 ms), reject shake, parent carries children, horizontal indent/outdent and auto-scroll.
 *
 * Implements FR-22..FR-25, DD-01..DD-12.
 */
@Stable
class DragDropState(
    private val layout: DndLayout,
    private val scope: CoroutineScope,
    private val density: Float,
    private val onMove: (MoveOperation) -> Unit,
    private val feedback: DndFeedback,
) {
    var draggingKey by mutableStateOf<String?>(null)
        private set
    var previewRows by mutableStateOf<List<DndRow>>(emptyList())
        private set
    var dragDelta by mutableStateOf(Offset.Zero)
        private set
    var nestTargetKey by mutableStateOf<String?>(null)
        private set
    var rejectTargetKey by mutableStateOf<String?>(null)
        private set

    /** Incremented on every reject so the target can replay its shake (A7). */
    var rejectTick by mutableIntStateOf(0)
        private set
    var hiddenChildCount by mutableIntStateOf(0)
        private set

    /** True while dragging and briefly after the drop until the DB catches up (08 §5.3). */
    var holdingPreview by mutableStateOf(false)
        private set

    val isDragging: Boolean get() = draggingKey != null
    val showPreview: Boolean get() = isDragging || holdingPreview

    private var baseRows: List<DndRow> = emptyList()
    private var startDepth = 0
    private var dwellJob: Job? = null
    private var dwellKey: String? = null
    private var autoScrollJob: Job? = null
    private var holdJob: Job? = null
    private var lastSlotOffset: Int? = null
    private val threshold = 48f * density

    fun isDragging(key: String) = draggingKey == key

    fun onDragStart(key: String, rows: List<DndRow>) {
        val row = rows.firstOrNull { it.key == key } ?: return
        holdJob?.cancel()
        holdingPreview = false
        draggingKey = key
        dragDelta = Offset.Zero
        baseRows = rows
        startDepth = row.depth
        val (preview, hidden) = DndLogic.startPreview(rows, key)
        previewRows = preview
        hiddenChildCount = hidden
        lastSlotOffset = layout.visibleItems().firstOrNull { it.key == key }?.offset
        feedback.dragStart()
        startAutoScroll()
    }

    fun onDrag(delta: Offset) {
        val key = draggingKey ?: return
        dragDelta += delta
        evaluate(key)
    }

    /**
     * Keeps the dragged row under the finger when its slot moves (reorder or scroll): called after
     * each layout pass with the row's new slot offset (08 §3.3).
     */
    fun onLayout() {
        val key = draggingKey ?: return
        val now = layout.visibleItems().firstOrNull { it.key == key }?.offset ?: return
        val before = lastSlotOffset
        if (before != null && before != now) dragDelta = dragDelta.copy(y = dragDelta.y - (now - before))
        lastSlotOffset = now
    }

    private fun evaluate(key: String) {
        val items = layout.visibleItems()
        val info = items.firstOrNull { it.key == key } ?: return
        val centerY = info.offset + info.size / 2f + dragDelta.y
        val previewByKey = previewRows.associateBy { it.key }
        val target = items.firstOrNull {
            it.key != key && previewByKey[it.key]?.isCompleted == false && centerY >= it.offset && centerY < it.offset + it.size
        }
        if (target == null) {
            cancelDwell()
            return
        }
        val rel = (centerY - target.offset) / target.size
        val t = previewByKey.getValue(target.key as String)
        val dragged = baseRows.first { it.key == key }

        if (DndLogic.isNestZone(t, rel)) {
            if (dwellKey != t.key) startDwell(t.key, canNest = !dragged.hasChildren)
            return // no reordering while in the nest zone
        }
        cancelDwell()

        val without = previewRows.filterNot { it.key == key }
        val targetIndex = without.indexOfFirst { it.key == t.key }
        val insertIndex = if (rel < 0.5f) targetIndex else targetIndex + 1
        val depth = DndLogic.resolveDepth(without, insertIndex, dragged, startDepth, dragDelta.x, threshold) ?: return
        val currentIndex = previewRows.indexOfFirst { it.key == key }
        val currentDepth = previewRows[currentIndex].depth
        val currentParent = previewRows[currentIndex].parentKey
        val newParent = DndLogic.parentFor(without, insertIndex, depth)
        if (insertIndex == currentIndex && depth == currentDepth && newParent == currentParent) return
        movePreview(key, insertIndex, depth)
        feedback.slotChanged()
    }

    /** Horizontal-only changes (indent/outdent in place) also need evaluating. */
    fun reevaluate() {
        draggingKey?.let(::evaluate)
    }

    private fun movePreview(key: String, index: Int, depth: Int) {
        // Anchor by index so LazyColumn doesn't follow the first visible key when it moves.
        layout.anchorFirstVisible()
        previewRows = DndLogic.move(previewRows, key, index, depth)
    }

    private fun startDwell(key: String, canNest: Boolean) {
        dwellKey = key
        dwellJob?.cancel()
        dwellJob = scope.launch {
            delay(DWELL_MS)
            if (canNest) {
                nestTargetKey = key
                feedback.nestArmed()
            } else {
                rejectTargetKey = key
                rejectTick++
                feedback.rejected()
                delay(360)
                rejectTargetKey = null
            }
        }
    }

    private fun cancelDwell() {
        dwellJob?.cancel()
        dwellJob = null
        dwellKey = null
        nestTargetKey = null
    }

    fun onDragEnd() {
        val key = draggingKey ?: return
        val op = DndLogic.dropOperation(previewRows, baseRows, key, nestTargetKey)
        if (op != null) {
            onMove(op)
            holdPreview()
        } else {
            holdingPreview = false
        }
        reset()
    }

    fun onDragCancel() {
        holdingPreview = false
        reset()
    }

    /** Keep rendering the preview until [dbRows] matches it or 500 ms pass (08 §5.3). */
    fun onDbRows(dbRows: List<DndRow>) {
        if (!holdingPreview) return
        val keys = previewRows.map { it.key }.toSet()
        val filtered = dbRows.filter { it.key in keys }.map { it.key to it.depth }
        if (filtered == previewRows.map { it.key to it.depth }) {
            holdingPreview = false
            holdJob?.cancel()
        }
    }

    private fun holdPreview() {
        holdingPreview = true
        holdJob?.cancel()
        holdJob = scope.launch {
            delay(HOLD_MS)
            holdingPreview = false
        }
    }

    private fun reset() {
        draggingKey = null
        dragDelta = Offset.Zero
        cancelDwell()
        rejectTargetKey = null
        hiddenChildCount = 0
        lastSlotOffset = null
        autoScrollJob?.cancel()
        autoScrollJob = null
    }

    private fun startAutoScroll() {
        autoScrollJob?.cancel()
        autoScrollJob = scope.launch {
            while (isActive && draggingKey != null) {
                delay(FRAME_MS)
                val key = draggingKey ?: break
                val info = layout.visibleItems().firstOrNull { it.key == key } ?: continue
                val pointerY = info.offset + info.size / 2f + dragDelta.y
                val speed = DndLogic.autoScrollSpeed(
                    pointerY,
                    layout.viewportStart.toFloat(),
                    layout.viewportEnd.toFloat(),
                    EDGE_DP * density,
                    density,
                )
                if (speed != 0f) {
                    layout.scrollBy(speed)
                    onLayout()
                    evaluate(key)
                }
            }
        }
    }

    companion object {
        const val DWELL_MS = 250L
        const val HOLD_MS = 500L
        const val EDGE_DP = 64f
        private const val FRAME_MS = 16L
    }
}
