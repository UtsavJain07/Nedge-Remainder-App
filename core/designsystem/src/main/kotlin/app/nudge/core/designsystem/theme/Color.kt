package app.nudge.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import app.nudge.core.model.Priority

/** Brand seed "Nudge Blue" (04 §2.1). */
val BrandSeed = Color(0xFF4F5BD5)

/** Light scheme from the brand seed (TonalSpot), key roles per 04 §2.1. */
val NudgeLightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF4F5BD5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDFE0FF),
    onPrimaryContainer = Color(0xFF0B1464),
    inversePrimary = Color(0xFFBCC2FF),
    secondary = Color(0xFF5B5D72),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0E1F9),
    onSecondaryContainer = Color(0xFF181A2C),
    tertiary = Color(0xFF77536D),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD7F1),
    onTertiaryContainer = Color(0xFF2D1228),
    background = Color(0xFFFBF8FF),
    onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFFBF8FF),
    onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE3E1EC),
    onSurfaceVariant = Color(0xFF46464F),
    surfaceTint = Color(0xFF4F5BD5),
    inverseSurface = Color(0xFF303036),
    inverseOnSurface = Color(0xFFF2EFF7),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF777680),
    outlineVariant = Color(0xFFC7C5D0),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFBF8FF),
    surfaceDim = Color(0xFFDBD9E0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F2FA),
    surfaceContainer = Color(0xFFEFEDF4),
    surfaceContainerHigh = Color(0xFFE9E7EF),
    surfaceContainerHighest = Color(0xFFE4E1E9),
)

val NudgeDarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFBCC2FF),
    onPrimary = Color(0xFF1A2478),
    primaryContainer = Color(0xFF343FA6),
    onPrimaryContainer = Color(0xFFDFE0FF),
    inversePrimary = Color(0xFF4F5BD5),
    secondary = Color(0xFFC4C5DD),
    onSecondary = Color(0xFF2D2F42),
    secondaryContainer = Color(0xFF434659),
    onSecondaryContainer = Color(0xFFE0E1F9),
    tertiary = Color(0xFFE6BAD7),
    onTertiary = Color(0xFF44263D),
    tertiaryContainer = Color(0xFF5D3C55),
    onTertiaryContainer = Color(0xFFFFD7F1),
    background = Color(0xFF131318),
    onBackground = Color(0xFFE4E1E9),
    surface = Color(0xFF131318),
    onSurface = Color(0xFFE4E1E9),
    surfaceVariant = Color(0xFF46464F),
    onSurfaceVariant = Color(0xFFC7C5D0),
    surfaceTint = Color(0xFFBCC2FF),
    inverseSurface = Color(0xFFE4E1E9),
    inverseOnSurface = Color(0xFF303036),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF91909A),
    outlineVariant = Color(0xFF46464F),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF39383F),
    surfaceDim = Color(0xFF131318),
    surfaceContainerLowest = Color(0xFF0E0E13),
    surfaceContainerLow = Color(0xFF1B1B21),
    surfaceContainer = Color(0xFF1F1F25),
    surfaceContainerHigh = Color(0xFF2A292F),
    surfaceContainerHighest = Color(0xFF35343A),
)

/** Priority color + container (04 §2.2). */
@Immutable
data class PriorityColor(val color: Color, val container: Color)

/** Extended colors not in the M3 scheme (04 §2.2). */
@Immutable
data class NudgeExtendedColors(
    val urgent: PriorityColor,
    val high: PriorityColor,
    val medium: PriorityColor,
    val low: PriorityColor,
    val none: PriorityColor,
    val isDark: Boolean,
) {
    fun forPriority(p: Priority): PriorityColor = when (p) {
        Priority.URGENT -> urgent
        Priority.HIGH -> high
        Priority.MEDIUM -> medium
        Priority.LOW -> low
        Priority.NONE -> none
    }
}

fun nudgeExtendedColors(dark: Boolean, onSurfaceVariant: Color, surfaceContainer: Color) = if (dark) {
    NudgeExtendedColors(
        urgent = PriorityColor(Color(0xFFFF8A80), Color(0xFF5C1414)),
        high = PriorityColor(Color(0xFFFFB870), Color(0xFF5A3000)),
        medium = PriorityColor(Color(0xFF8AB8FF), Color(0xFF0F3566)),
        low = PriorityColor(Color(0xFF7DDB91), Color(0xFF0E4A1E)),
        none = PriorityColor(onSurfaceVariant, surfaceContainer),
        isDark = true,
    )
} else {
    NudgeExtendedColors(
        urgent = PriorityColor(Color(0xFFE5383B), Color(0xFFFFDAD6)),
        high = PriorityColor(Color(0xFFF77F00), Color(0xFFFFE0C2)),
        medium = PriorityColor(Color(0xFF2F80ED), Color(0xFFD6E6FF)),
        low = PriorityColor(Color(0xFF2BA84A), Color(0xFFD2F5D9)),
        none = PriorityColor(onSurfaceVariant, surfaceContainer),
        isDark = false,
    )
}

val LocalNudgeColors = staticCompositionLocalOf { nudgeExtendedColors(false, Color.Gray, Color.LightGray) }

/** List card container: the list color at 16% (light) / 24% (dark) over surfaceContainer (04 §2.3). */
fun listContainerColor(listColor: Color, surfaceContainer: Color, dark: Boolean): Color =
    listColor.copy(alpha = if (dark) 0.24f else 0.16f).compositeOver(surfaceContainer)

/** White or black, whichever contrasts more with [background]. */
fun contentColorOn(background: Color): Color = if (background.luminance() > 0.45f) Color(0xFF1B1B21) else Color.White
