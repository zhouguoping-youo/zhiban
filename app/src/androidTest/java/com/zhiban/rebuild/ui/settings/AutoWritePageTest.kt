package com.zhiban.rebuild.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.runtime.governance.AutoWriteReceiptRow
import com.zhiban.rebuild.ui.theme.ZhiBanTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutoWritePageTest {
    @get:Rule val compose = createComposeRule()

    private fun receipt(
        changeId: String,
        presentationType: String,
        correctionRoute: String,
        undoState: String = "AVAILABLE",
        reviewState: String = "UNREVIEWED",
    ) = AutoWriteReceiptRow(
        changeId = changeId,
        toolName = "t",
        targetDomain = "d",
        targetId = changeId,
        operation = "INSERT",
        undoState = undoState,
        subjectContactId = null,
        contactName = "张三",
        sourceType = "AGENT_INFERENCE",
        confidence = null,
        presentationType = presentationType,
        correctionRoute = correctionRoute,
        reviewState = reviewState,
        createdAtEpochMs = 1_700_000_000_000,
    )

    private fun render(
        receipts: List<AutoWriteReceiptRow>,
        undoSucceeds: Boolean = true,
    ) {
        compose.setContent {
            ZhiBanTheme {
                AutoWriteContent(
                    state = AutoWriteUiState(receipts = receipts),
                    onBack = {},
                    onUndo = { _, cb -> cb(undoSucceeds) },
                    onCorrectInteraction = { _, _, cb -> cb(true) },
                )
            }
        }
    }

    @Test fun screenDoesNotRepeatAutoWritePolicy() {
        render(emptyList())
        compose.onNodeWithText("知伴帮你自动整理的内容，可撤销或纠正。保留 90 天。").assertDoesNotExist()
    }

    @Test fun emptyStateUsesOneConciseMessage() {
        render(emptyList())
        compose.onNodeWithText("暂无自动整理").assertIsDisplayed()
        compose.onNodeWithText("知伴会在你收到消息或打完电话后，自动帮你整理记录。").assertDoesNotExist()
    }

    @Test fun interactionSummaryCorrectOpensContactPicker() {
        render(listOf(receipt("c1", "INTERACTION_SUMMARY", "CONTACT_PICKER")))
        compose.onNodeWithText("纠正").performClick()
        compose.onNodeWithText("这条互动属于谁？").assertIsDisplayed()
    }

    @Test fun nonInteractionCorrectShowsGuidanceSnackbarNotPicker() {
        render(listOf(receipt("c2", "CRM_LEAD_CANDIDATE", "CRM_CANDIDATE_POOL")))
        compose.onNodeWithText("纠正").performClick()
        compose.onNodeWithText("请到联系人或个人 CRM 页面修改这条内容").assertIsDisplayed()
        compose.onNodeWithText("这条互动属于谁？").assertDoesNotExist()
    }

    @Test fun undoSuccessShowsSnackbar() {
        render(listOf(receipt("c3", "CRM_ACTIVITY", "CRM_OPPORTUNITY_DETAIL")), undoSucceeds = true)
        compose.onNodeWithText("撤销").performClick()
        compose.onNodeWithText("已撤销").assertIsDisplayed()
    }

    @Test fun undoFailureShowsModifiedSnackbar() {
        render(listOf(receipt("c4", "CRM_ACTIVITY", "CRM_OPPORTUNITY_DETAIL")), undoSucceeds = false)
        compose.onNodeWithText("撤销").performClick()
        compose.onNodeWithText("内容已被修改，请用纠正处理").assertIsDisplayed()
    }
}
