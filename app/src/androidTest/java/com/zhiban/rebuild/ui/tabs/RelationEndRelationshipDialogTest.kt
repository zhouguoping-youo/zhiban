package com.zhiban.rebuild.ui.tabs

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.zhiban.rebuild.ui.theme.ZhiBanTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * 「结束当前关系」产品表达语义锁:底层行为是关闭 temporal episode、历史关系保留,
 * 所以弹窗只承诺结束、不承诺删除;「暂不」不触发任何写操作。
 */
class RelationEndRelationshipDialogTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun endDialogShowsDynamicNamesAndPromisesHistoryPreserved() {
        compose.setContent {
            ZhiBanTheme {
                EndRelationshipConfirmDialog("张三", "李四", "CUSTOMER", onConfirm = {}, onDismiss = {})
            }
        }

        compose.onNodeWithText("结束这段关系？").assertIsDisplayed()
        compose.onNodeWithText(
            "结束后，张三 与 李四 的“客户”关系将不再作为当前关系显示，" +
                "但会保留在关系历史中，并在关系图中以历史关系的虚线呈现。",
        ).assertIsDisplayed()
        compose.onNodeWithText("结束关系").assertIsDisplayed()
        compose.onNodeWithText("暂不").assertIsDisplayed()
    }

    @Test
    fun confirmRunsEndActionDismissLeavesRelationshipUntouched() {
        val confirmed = AtomicInteger()
        val dismissed = AtomicInteger()
        compose.setContent {
            ZhiBanTheme {
                EndRelationshipConfirmDialog(
                    "张三",
                    "李四",
                    "CUSTOMER",
                    onConfirm = confirmed::incrementAndGet,
                    onDismiss = dismissed::incrementAndGet,
                )
            }
        }

        compose.onNodeWithText("暂不").performClick()
        assertEquals(0, confirmed.get())
        assertEquals(1, dismissed.get())

        compose.onNodeWithText("结束关系").performClick()
        assertEquals(1, confirmed.get())
    }

    @Test
    fun successFeedbackSaysHistoryPreserved() {
        assertEquals("当前关系已结束，历史记录已保留", END_RELATIONSHIP_SUCCESS_FEEDBACK)
    }
}
