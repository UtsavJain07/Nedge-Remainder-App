package app.nudge.feature.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.nudge.core.ui.nav.LocalNavAnimatedVisibilityScope
import kotlinx.serialization.Serializable

@Serializable
data object SettingsRoute

@Serializable
data object ReminderHealthRoute

@Serializable
internal data object SettingsRemindersRoute

@Serializable
internal data object DebugRoute

fun NavController.navigateToSettings() = navigate(SettingsRoute) { launchSingleTop = true }

fun NavController.navigateToReminderHealth() = navigate(ReminderHealthRoute) { launchSingleTop = true }

/**
 * Registers Settings, Settings › Reminders, Reminder health and — only when [showDebug] — the Debug
 * tools (FR-100..FR-104, 07 §10–§11).
 */
fun NavGraphBuilder.settingsGraph(navController: NavController, showDebug: Boolean, versionName: String) {
    val back: () -> Unit = { navController.popBackStack() }
    composable<SettingsRoute> {
        CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
            SettingsScreen(
                versionName = versionName,
                showDebug = showDebug,
                onBack = back,
                onOpenReminders = { navController.navigate(SettingsRemindersRoute) { launchSingleTop = true } },
                onOpenDebug = { navController.navigate(DebugRoute) { launchSingleTop = true } },
            )
        }
    }
    composable<SettingsRemindersRoute> {
        CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
            SettingsRemindersScreen(onBack = back, onOpenHealth = { navController.navigateToReminderHealth() })
        }
    }
    composable<ReminderHealthRoute> {
        CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
            ReminderHealthScreen(onBack = back)
        }
    }
    if (showDebug) {
        composable<DebugRoute> {
            CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                DebugScreen(onBack = back)
            }
        }
    }
}
