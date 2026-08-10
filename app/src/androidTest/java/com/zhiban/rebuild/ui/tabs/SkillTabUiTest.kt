package com.zhiban.rebuild.ui.tabs

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.zhiban.rebuild.ui.theme.ZhiBanTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SkillTabUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun sceneCapabilitiesAreVisibleAndOpenTheirDestinations() {
        val crmOpened = AtomicInteger()
        val lifeOpened = AtomicInteger()
        compose.setContent {
            ZhiBanTheme {
                SkillTab(
                    onOpenCrm = { crmOpened.incrementAndGet() },
                    onOpenLifeAssistant = { lifeOpened.incrementAndGet() },
                )
            }
        }

        compose.onNodeWithText("能力").assertExists()
        compose.onNodeWithContentDescription("进入个人 CRM").assertHasClickAction().performClick()
        compose.onNodeWithContentDescription("进入生活助理").assertHasClickAction().performClick()

        assertEquals(1, crmOpened.get())
        assertEquals(1, lifeOpened.get())
    }
}
