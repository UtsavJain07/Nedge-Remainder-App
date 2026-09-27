package app.nudge.core.model

/** The 12 preset list colors (04 §2.3), ARGB. Order matters: "next unused" picks in this order (FR-01). */
object ListColors {
    val presets: List<Int> = listOf(
        0xFF5C6BC0.toInt(), // Indigo
        0xFF8E6CEF.toInt(), // Violet
        0xFFEC407A.toInt(), // Pink
        0xFFEF5350.toInt(), // Red
        0xFFFF7043.toInt(), // Orange
        0xFF29B6F6.toInt(), // Cyan
        0xFF26A69A.toInt(), // Teal
        0xFF43A047.toInt(), // Green
        0xFF9CCC65.toInt(), // Lime
        0xFFFFB300.toInt(), // Amber
        0xFF8D6E63.toInt(), // Brown
        0xFF607D8B.toInt(), // Slate
    )

    val names: List<String> = listOf(
        "Indigo", "Violet", "Pink", "Red", "Orange", "Cyan", "Teal", "Green", "Lime", "Amber", "Brown", "Slate",
    )

    val DEFAULT: Int = presets.first()

    fun nextUnused(used: Collection<Int>): Int = presets.firstOrNull { it !in used } ?: presets[used.size % presets.size]
}
