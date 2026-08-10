package com.zhiban.rebuild.ui.tabs

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.zhiban.rebuild.ui.theme.ZhiBanTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class EventPlanningPageTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun emptyWorkbenchUsesOnePrimaryActionInsideTheSharedSceneCard() {
        val creates = AtomicInteger()
        compose.setContent {
            ZhiBanTheme {
                EventPlanningWorkbench(
                    state = EventPlanningState(isLoading = false),
                    onBack = {},
                    onCreate = { creates.incrementAndGet() },
                    onOpenAll = {},
                    onOpenPlan = {},
                    onDismissMessage = {},
                )
            }
        }

        compose.onNodeWithText("聚会、探望与出行").assertExists()
        compose.onNodeWithTag("event-empty-workbench").assertExists()
        compose.onAllNodesWithText("开始安排").assertCountEquals(1)
        compose.onNodeWithText("开始安排").performClick()
        assertEquals(1, creates.get())
    }
}
