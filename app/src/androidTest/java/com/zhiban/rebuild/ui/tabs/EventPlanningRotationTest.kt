package com.zhiban.rebuild.ui.tabs

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import com.zhiban.rebuild.ui.theme.ZhiBanTheme
import org.junit.Rule
import org.junit.Test

/**
 * Rotation/config-change regression for the create-plan form. The typed draft is held in
 * rememberSaveable so recreating the composition must not wipe it.
 */
class EventPlanningRotationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun createPlanDraftSurvivesRecreation() {
        val restorationTester = StateRestorationTester(compose)
        restorationTester.setContent {
            ZhiBanTheme {
                CreateEventPlanDialog(onDismiss = {}, onCreate = {})
            }
        }

        compose.onNodeWithText("做什么").performTextInput("周三拜访周总")
        compose.onNodeWithText("地点（可选）").performTextInput("武汉")

        restorationTester.emulateSavedInstanceStateRestore()

        compose.onNode(hasText("周三拜访周总")).assertIsDisplayed()
        compose.onNode(hasText("武汉")).assertIsDisplayed()
    }
}
