package com.zhiban.rebuild.ui.tabs

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.zhiban.rebuild.ui.theme.ZhiBanTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LifeAssistantPageTest {
    @get:Rule
    val compose = createComposeRule()

    @Test fun commitmentIsVisibleAndCanBeAddedToCalendar() {
        val confirmations = AtomicInteger()
        val item = LifeAssistantItem(
            id = "commitment:c-1",
            kind = LifeAssistantItemKind.COMMITMENT,
            title = "明天下午见面",
            contactId = "contact-1",
            contactName = "李雷",
            eventAtEpochMs = System.currentTimeMillis() + 24 * 60 * 60 * 1_000L,
            durationMinutes = 60,
            sourceLabel = "微信",
            evidence = "明天下午三点见",
            confidence = 0.92,
            candidateId = "c-1",
        )
        compose.setContent {
            ZhiBanTheme {
                LifeAssistantWorkbench(
                    state = LifeAssistantState(listOf(item), isLoading = false),
                    onBack = {},
                    onOpenAll = {},
                    onOpenItem = {},
                    onAskAgent = {},
                    onOpenRelations = {},
                    onConfirm = { confirmations.incrementAndGet() },
                    onDismissMessage = {},
                )
            }
        }

        compose.onNodeWithText("生活助理").assertExists()
        compose.onNodeWithText("明天下午见面").assertExists()
        compose.onNodeWithText("加入日历").performClick()

        assertEquals(1, confirmations.get())
    }

    @Test fun emptyWorkbenchHasOneClearContactAction() {
        val opens = AtomicInteger()
        compose.setContent {
            ZhiBanTheme {
                LifeAssistantWorkbench(
                    state = LifeAssistantState(isLoading = false),
                    onBack = {},
                    onOpenAll = {},
                    onOpenItem = {},
                    onAskAgent = {},
                    onOpenRelations = { opens.incrementAndGet() },
                    onConfirm = {},
                    onDismissMessage = {},
                )
            }
        }

        compose.onNodeWithTag("life-empty-workbench").assertExists()
        compose.onNodeWithText("从重要的人开始").assertExists()
        compose.onNodeWithText("查看联系人").performClick()
        assertEquals(1, opens.get())
    }
}
