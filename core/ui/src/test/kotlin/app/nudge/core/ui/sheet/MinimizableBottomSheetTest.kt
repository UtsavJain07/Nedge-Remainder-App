package app.nudge.core.ui.sheet

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import app.nudge.core.designsystem.theme.NudgeTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** v1.1: sheets minimize instead of closing; closing needs the caller's consent. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MinimizableBottomSheetTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var controller: MinimizableSheetController
    private var dismissed = false
    private var allowClose = true
    private var closeRequests = 0

    private fun setUp() {
        rule.setContent {
            NudgeTheme {
                controller = rememberMinimizableSheetController()
                var text by remember { mutableStateOf("") }
                MinimizableBottomSheet(
                    controller = controller,
                    peekTitle = text.ifBlank { "New task" },
                    onCloseRequest = {
                        closeRequests++
                        allowClose
                    },
                    onDismissed = { dismissed = true },
                    modifier = Modifier.testTag("sheet"),
                ) {
                    TextField(value = text, onValueChange = { text = it }, modifier = Modifier.testTag("field"))
                    Text("Save")
                }
            }
        }
        rule.waitForIdle()
    }

    private fun pressBack() {
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    @Test
    fun dragging_down_minimizes_and_keeps_the_input() {
        setUp()
        rule.onNodeWithTag("field").performTextInput("Buy milk")
        rule.onNodeWithTag("sheet").performTouchInput { swipeDown() }
        rule.waitForIdle()

        assertThat(controller.minimized).isTrue()
        assertThat(dismissed).isFalse()
        rule.onNodeWithTag("sheet_peek").assertIsDisplayed()
        // The draft survives in the (still mounted) field and is summarized in the peek bar.
        rule.onNodeWithTag("field").assert(hasText("Buy milk"))
        rule.onAllNodesWithText("Buy milk").assertCountEquals(2)
    }

    @Test
    fun tapping_the_peek_bar_restores_the_sheet() {
        setUp()
        rule.runOnIdle { controller.minimize() }
        rule.onNodeWithTag("sheet_peek").performClick()
        rule.waitForIdle()
        assertThat(controller.minimized).isFalse()
    }

    @Test
    fun back_minimizes_first_then_asks_to_close() {
        setUp()
        pressBack()
        assertThat(controller.minimized).isTrue()
        assertThat(closeRequests).isEqualTo(0)

        pressBack()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertThat(closeRequests).isEqualTo(1)
        assertThat(dismissed).isTrue()
    }

    @Test
    fun refused_close_keeps_the_sheet_minimized() {
        allowClose = false
        setUp()
        rule.runOnIdle { controller.minimize() }
        rule.onNodeWithTag("sheet_close").performClick()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()

        assertThat(closeRequests).isEqualTo(1)
        assertThat(dismissed).isFalse()
        assertThat(controller.minimized).isTrue()
    }

    @Test
    fun explicit_close_dismisses_without_asking() {
        setUp()
        rule.runOnIdle { controller.close() }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertThat(dismissed).isTrue()
        assertThat(closeRequests).isEqualTo(0)
    }
}
