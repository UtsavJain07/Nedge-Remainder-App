package app.nudge.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import app.nudge.BuildConfig
import app.nudge.TaskLink
import app.nudge.core.ui.nav.LocalSharedTransitionScope
import app.nudge.feature.home.HomeNavigation
import app.nudge.feature.home.HomeRoute
import app.nudge.feature.home.homeScreen
import app.nudge.feature.list.listScreen
import app.nudge.feature.list.navigateToList
import app.nudge.feature.onboarding.OnboardingRoute
import app.nudge.feature.onboarding.onboardingScreen
import app.nudge.feature.search.SearchRoute
import app.nudge.feature.search.navigateToSearch
import app.nudge.feature.search.searchScreen
import app.nudge.feature.settings.SettingsRoute
import app.nudge.feature.settings.navigateToReminderHealth
import app.nudge.feature.settings.navigateToSettings
import app.nudge.feature.settings.settingsGraph
import app.nudge.feature.smartview.navigateToSmartView
import app.nudge.feature.smartview.smartViewScreen
import kotlinx.coroutines.flow.StateFlow

/** Type-safe navigation graph (03 §2) with shared-element transitions (A8) and 03 §6.3 motion. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun NudgeNavHost(startOnboarding: Boolean, taskLinks: StateFlow<TaskLink?>, onTaskLinkConsumed: () -> Unit) {
    val navController = rememberNavController()
    val link by taskLinks.collectAsStateWithLifecycle()
    LaunchedEffect(link) {
        val l = link ?: return@LaunchedEffect
        navController.navigateToList(l.listId, highlightTaskId = l.taskId, openTaskId = l.taskId)
        onTaskLinkConsumed()
    }

    SharedTransitionLayout(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        CompositionLocalProvider(LocalSharedTransitionScope provides this) {
            NavHost(
                navController = navController,
                startDestination = if (startOnboarding) OnboardingRoute else HomeRoute,
                enterTransition = { enter() },
                exitTransition = { exit() },
                popEnterTransition = { popEnter() },
                popExitTransition = { popExit() },
            ) {
                onboardingScreen(onDone = {
                    navController.navigate(HomeRoute) { popUpTo(OnboardingRoute) { inclusive = true } }
                })
                homeScreen(
                    HomeNavigation(
                        openList = { navController.navigateToList(it) },
                        openSmartView = { navController.navigateToSmartView(it) },
                        openSearch = { navController.navigateToSearch() },
                        openSettings = { navController.navigateToSettings() },
                        openHealth = { navController.navigateToReminderHealth() },
                    ),
                )
                listScreen(onBack = { navController.popBackStack() })
                smartViewScreen(
                    onBack = { navController.popBackStack() },
                    onOpenList = { listId, taskId -> navController.navigateToList(listId, highlightTaskId = taskId) },
                )
                searchScreen(
                    onBack = { navController.popBackStack() },
                    onOpenResult = { listId, taskId -> navController.navigateToList(listId, highlightTaskId = taskId) },
                )
                settingsGraph(navController, showDebug = BuildConfig.DEBUG, versionName = BuildConfig.VERSION_NAME)
            }
        }
    }
}

private fun NavBackStackEntry.isSheetLike(): Boolean =
    destination.hasRoute(SettingsRoute::class) || destination.hasRoute(SearchRoute::class)

/** Default: fade 200 ms + slide 1/10 horizontally; Settings / Search: slide up 5% + fade (04 §6.3). */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.enter(): EnterTransition =
    if (targetState.isSheetLike()) {
        fadeIn(tween(200)) + slideInVertically(tween(250)) { it / 20 }
    } else {
        fadeIn(tween(200)) + slideInHorizontally(tween(250)) { it / 10 }
    }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.exit(): ExitTransition = fadeOut(tween(150))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.popEnter(): EnterTransition = fadeIn(tween(200))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.popExit(): ExitTransition =
    if (initialState.isSheetLike()) {
        fadeOut(tween(150)) + slideOutVertically(tween(200)) { it / 20 }
    } else {
        fadeOut(tween(150)) + slideOutHorizontally(tween(200)) { it / 10 }
    }
