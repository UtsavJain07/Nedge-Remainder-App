package app.nudge.core.model

/**
 * Task priority (FR-50). Persisted as [level] — stable ints, never the ordinal.
 */
enum class Priority(val level: Int) {
    NONE(0),
    LOW(1),
    MEDIUM(2),
    HIGH(3),
    URGENT(4),
    ;

    companion object {
        fun fromLevel(level: Int): Priority = entries.firstOrNull { it.level == level } ?: NONE

        /** Display order in chip rows: most important first. */
        val chipOrder: List<Priority> = listOf(URGENT, HIGH, MEDIUM, LOW)
    }
}
