package app.nudge

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nudge.core.designsystem.component.LocalHapticsEnabled
import app.nudge.core.designsystem.theme.NudgeTheme
import app.nudge.core.model.ThemeMode
import app.nudge.core.ui.snackbar.LocalSnackbar
import app.nudge.core.ui.snackbar.SnackbarDispatcher
import app.nudge.navigation.NudgeNavHost
import dagger.hilt.android.AndroidEntryPoint

/** Single activity (05 §1): splash, edge-to-edge, theme from settings, notification deep links. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { viewModel.uiState.value is MainUiState.Loading }
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val ready = state as? MainUiState.Ready ?: return@setContent
            val settings = ready.settings
            val dark = when (settings.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            val scope = rememberCoroutineScope()
            val snackbar = remember { SnackbarDispatcher(SnackbarHostState(), scope) }
            NudgeTheme(darkTheme = dark, dynamicColor = settings.dynamicColor, pureBlack = settings.pureBlack) {
                CompositionLocalProvider(
                    LocalSnackbar provides snackbar,
                    LocalHapticsEnabled provides settings.haptics,
                ) {
                    NudgeNavHost(
                        startOnboarding = !settings.onboardingDone,
                        taskLinks = viewModel.taskLink,
                        onTaskLinkConsumed = viewModel::consumeTaskLink,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }

    /** nudge://task/{taskId} (03 §2). Anything else is ignored. */
    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW || data.scheme != "nudge" || data.host != "task") return
        val taskId = data.lastPathSegment ?: return
        viewModel.openTask(taskId)
    }
}
