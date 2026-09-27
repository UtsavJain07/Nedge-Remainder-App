package app.nudge.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nudge.core.domain.reminder.ReminderHealthMonitor
import app.nudge.core.domain.usecase.UpdateSettingsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/** FR-90, 03 §3.1 / §4.1: marks onboarding done and re-checks reminder health on "Start". */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val updateSettings: UpdateSettingsUseCase,
    private val health: ReminderHealthMonitor,
) : ViewModel() {
    private var finishing = false

    fun finish(onDone: () -> Unit) {
        if (finishing) return
        finishing = true
        viewModelScope.launch {
            updateSettings { it.copy(onboardingDone = true) }
            health.refresh()
            onDone()
        }
    }
}
