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
    fun soleMatureCapabilityIsVisibleAndOpensCrm() {
        val opened = AtomicInteger()
        compose.setContent {
            ZhiBanTheme {
                SkillTab(onOpenCrm = { opened.incrementAndGet() })
            }
        }

        compose.onNodeWithText("能力").assertExists()
        compose.onNodeWithContentDescription("进入个人 CRM").assertHasClickAction().performClick()

        assertEquals(1, opened.get())
    }
}
