package app.nudge.core.domain.order

/**
 * Midpoint ordering with `Double` sort keys (ADR-07, 06 §4). O(1) moves; renormalize when the gap
 * collapses below [MIN_GAP].
 */
object OrderingCalculator {
    const val GAP = 1024.0
    const val MIN_GAP = 1e-6

    fun top(minExisting: Double?): Double = (minExisting ?: GAP) - GAP

    fun bottom(maxExisting: Double?): Double = (maxExisting ?: 0.0) + GAP

    /** [before] = sortOrder of the item ABOVE, [after] = the item BELOW (nulls at the edges). */
    fun between(before: Double?, after: Double?): Double = when {
        before == null && after == null -> 0.0
        before == null -> after!! - GAP
        after == null -> before + GAP
        else -> (before + after) / 2.0
    }

    fun needsRenormalize(before: Double?, after: Double?): Boolean =
        before != null && after != null && (after - before) < MIN_GAP

    /** New sortOrders 0, GAP, 2*GAP … preserving the given order. */
    fun renormalize(ordered: List<String>): Map<String, Double> =
        ordered.mapIndexed { i, id -> id to i * GAP }.toMap()
}
