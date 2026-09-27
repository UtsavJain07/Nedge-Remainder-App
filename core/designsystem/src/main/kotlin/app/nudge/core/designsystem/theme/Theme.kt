package app.nudge.core.designsystem.theme

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme

/** Whether the current theme is dark (the scheme alone can't tell for seeded schemes). */
val LocalIsDarkTheme = staticCompositionLocalOf { false }

/** Pure-black dark mode setting, so nested seeded themes keep it (FR-100). */
val LocalPureBlack = staticCompositionLocalOf { false }

/**
 * The app theme (04 §1). [seedColor] tints a screen with a list's color (List screen only); the key
 * roles animate between seeds (A16).
 */
@Composable
fun NudgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    pureBlack: Boolean = false,
    seedColor: Color? = null,
    reducedMotion: Boolean = rememberSystemReducedMotion(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val base: ColorScheme = when {
        seedColor != null -> remember(seedColor, darkTheme) { seededScheme(seedColor, darkTheme) }
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> if (darkTheme) NudgeDarkScheme else NudgeLightScheme
    }
    val scheme = if (darkTheme && pureBlack) {
        base.copy(
            surface = Color.Black,
            background = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceDim = Color.Black,
        )
    } else {
        base
    }
    val animated = if (seedColor != null) scheme.animateKeyRoles() else scheme

    MaterialTheme(
        colorScheme = animated,
        typography = NudgeTypography,
        shapes = NudgeShapes,
    ) {
        CompositionLocalProvider(
            LocalMotionScheme provides NudgeExpressiveMotionScheme,
            LocalNudgeColors provides nudgeExtendedColors(darkTheme, animated.onSurfaceVariant, animated.surfaceContainer),
            LocalIsDarkTheme provides darkTheme,
            LocalPureBlack provides pureBlack,
            LocalReducedMotion provides reducedMotion,
        ) {
            content()
        }
    }
}

/** Re-themes [content] from a list color, keeping the outer dark / pure-black settings (List screen, 04 §1). */
@Composable
fun NudgeSeededTheme(seed: Color, content: @Composable () -> Unit) {
    NudgeTheme(
        darkTheme = LocalIsDarkTheme.current,
        pureBlack = LocalPureBlack.current,
        seedColor = seed,
        reducedMotion = LocalReducedMotion.current,
        content = content,
    )
}

/** Seed → TonalSpot scheme via materialkolor (04 §1). */
fun seededScheme(seed: Color, dark: Boolean): ColorScheme =
    dynamicColorScheme(seedColor = seed, isDark = dark, isAmoled = false, style = PaletteStyle.TonalSpot)

/** A16: key roles animate over 300 ms when the seed changes. */
@Composable
private fun ColorScheme.animateKeyRoles(): ColorScheme {
    val spec = tween<Color>(300)
    val primary by animateColorAsState(primary, spec, label = "primary")
    val onPrimary by animateColorAsState(onPrimary, spec, label = "onPrimary")
    val primaryContainer by animateColorAsState(primaryContainer, spec, label = "primaryContainer")
    val onPrimaryContainer by animateColorAsState(onPrimaryContainer, spec, label = "onPrimaryContainer")
    val surfaceContainer by animateColorAsState(surfaceContainer, spec, label = "surfaceContainer")
    val secondaryContainer by animateColorAsState(secondaryContainer, spec, label = "secondaryContainer")
    val surface by animateColorAsState(surface, spec, label = "surface")
    return copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        surfaceContainer = surfaceContainer,
        secondaryContainer = secondaryContainer,
        surface = surface,
        background = surface,
    )
}

/** Shortcut accessors. */
object NudgeTheme {
    val colors: NudgeExtendedColors
        @Composable get() = LocalNudgeColors.current

    val isDark: Boolean
        @Composable get() = LocalIsDarkTheme.current

    val reducedMotion: Boolean
        @Composable get() = LocalReducedMotion.current

    val scheme: ColorScheme
        @Composable get() = MaterialTheme.colorScheme

    val motion: NudgeMotionScheme
        @Composable get() = LocalMotionScheme.current
}
