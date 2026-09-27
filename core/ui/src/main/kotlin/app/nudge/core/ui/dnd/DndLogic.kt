package app.nudge.core.ui.dnd

import app.nudge.core.model.MoveOperation

/**
 * One visible row of the open section, in display order (08 §2). Built from `ListTree.flatten()`.
 * Completed subtasks of open parents appear here too, but are never draggable or drop targets.
 */
data class DndRow(
    val key: String,
    val depth: Int,
    val parentKey: String?,
    val hasChildren: Boolean,
    val isExpanded: Boolean,
    val isCompleted: Boolean = false,
)

/**
 * Pure drag-and-drop rules (08 §3.4, §3.5). No Compose, so T4–T6 run as plain JVM tests.
 */
object DndLogic {

    /**
     * Resolves the depth for inserting [dragged] at [index] of [rows] (the preview WITHOUT the dragged
     * row). [dxPx] is the horizontal drag offset; [thresholdPx] = 48 dp. Returns null for an illegal slot.
     */
    fun resolveDepth(rows: List<DndRow>, index: Int, dragged: DndRow, startDepth: Int, dxPx: Float, thresholdPx: Float): Int? {
        val prev = rows.getOrNull(index - 1)
        val next = rows.getOrNull(index)
        // Inserting before a subtask → must be inside that group.
        val minDepth = if (next != null && next.depth == 1) 1 else 0
        // Parents stay top-level; nothing above → top-level.
        val maxDepth = if (dragged.hasChildren || prev == null) 0 else 1
        if (minDepth > maxDepth) return null
        val preferred = startDepth + when {
            dxPx >= thresholdPx -> 1
            dxPx <= -thresholdPx -> -1
            else -> 0
        }
        return preferred.coerceIn(minDepth, maxDepth)
    }

    /** The parent for a row inserted at [index] with [depth] (08 §3.4). */
    fun parentFor(rows: List<DndRow>, index: Int, depth: Int): String? {
        if (depth == 0) return null
        val prev = rows.getOrNull(index - 1) ?: return null
        return if (prev.depth == 0) prev.key else prev.parentKey
    }

    /** Moves [key] to [index] (of the list without it) with the given depth/parent. */
    fun move(rows: List<DndRow>, key: String, index: Int, depth: Int): List<DndRow> {
        val dragged = rows.first { it.key == key }
        val without = rows.filterNot { it.key == key }
        val parent = parentFor(without, index, depth)
        val moved = dragged.copy(depth = depth, parentKey = parent)
        return without.toMutableList().apply { add(index.coerceIn(0, size), moved) }
    }

    /**
     * Builds the drop operation (08 §3.5), or null when the drop changes nothing.
     * Neighbours are taken from the OPEN siblings of the target group only (06 §4).
     */
    fun dropOperation(preview: List<DndRow>, base: List<DndRow>, key: String, nestTargetKey: String?): MoveOperation? {
        if (nestTargetKey != null) return MoveOperation.Nest(taskId = key, parentId = nestTargetKey)
        val d = preview.firstOrNull { it.key == key } ?: return null
        val parent = d.parentKey
        // A collapsed parent's children are not visible: depth 1 under it means "append as last child".
        if (parent != null) {
            val parentRow = preview.firstOrNull { it.key == parent }
            if (parentRow != null && !parentRow.isExpanded) {
                val before = base.firstOrNull { it.key == key }
                return if (before?.parentKey == parent) null else MoveOperation.Nest(taskId = key, parentId = parent)
            }
        }
        val (above, below) = neighbours(preview, key, d.depth, parent)
        val baseRow = base.firstOrNull { it.key == key } ?: return null
        if (baseRow.parentKey == parent && baseRow.depth == d.depth) {
            val (baseAbove, baseBelow) = neighbours(base, key, baseRow.depth, baseRow.parentKey)
            if (baseAbove == above && baseBelow == below) return null
        }
        return MoveOperation.Reorder(taskId = key, parentId = parent, aboveId = above, belowId = below)
    }

    private fun neighbours(rows: List<DndRow>, key: String, depth: Int, parent: String?): Pair<String?, String?> {
        val siblings = rows.filter { it.depth == depth && it.parentKey == parent && (!it.isCompleted || it.key == key) }
        val i = siblings.indexOfFirst { it.key == key }
        return siblings.getOrNull(i - 1)?.key to siblings.getOrNull(i + 1)?.key
    }

    /** Rows at drag start: the dragged parent's children are hidden from the preview (DD-07). */
    fun startPreview(rows: List<DndRow>, key: String): Pair<List<DndRow>, Int> {
        val r = rows.firstOrNull { it.key == key } ?: return rows to 0
        val children = rows.filter { it.parentKey == key }
        return if (r.hasChildren) rows - children.toSet() to children.size else rows to 0
    }

    /** Nest zone = middle 50% of a top-level, open target row (DD-04). */
    fun isNestZone(target: DndRow, relativeY: Float): Boolean =
        target.depth == 0 && !target.isCompleted && relativeY in 0.25f..0.75f

    /** DD-09 auto-scroll speed in px/frame: lerp(2, 20, proximity) scaled by density; negative = up. */
    fun autoScrollSpeed(pointerY: Float, viewportStart: Float, viewportEnd: Float, edgePx: Float, density: Float): Float {
        val topProximity = (edgePx - (pointerY - viewportStart)) / edgePx
        val bottomProximity = (edgePx - (viewportEnd - pointerY)) / edgePx
        return when {
            topProximity > 0f -> -lerp(2f, 20f, topProximity.coerceIn(0f, 1f)) * density
            bottomProximity > 0f -> lerp(2f, 20f, bottomProximity.coerceIn(0f, 1f)) * density
            else -> 0f
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}
