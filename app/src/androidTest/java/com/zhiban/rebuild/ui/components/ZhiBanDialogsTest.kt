package com.zhiban.rebuild.ui.components

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.zhiban.rebuild.ui.theme.ZhiBanTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ZhiBanDialogsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun alertDialogKeepsDecisionContentAndActionsOperable() {
        val confirmed = AtomicInteger()
        compose.setContent {
            ZhiBanTheme {
                ZhiBanAlertDialog(
                    onDismissRequest = {},
                    title = { Text("删除日程？") },
                    text = { Text("删除后无法恢复") },
                    dismissButton = { TextButton(onClick = {}) { Text("取消") } },
                    confirmButton = {
                        TextButton(onClick = { confirmed.incrementAndGet() }) { Text("删除") }
                    },
                )
            }
        }

        compose.onNodeWithText("删除日程？").assertIsDisplayed()
        compose.onNodeWithText("删除后无法恢复").assertIsDisplayed()
        compose.onNodeWithText("删除").performClick()
        assertEquals(1, confirmed.get())
    }

    @Test fun taskDialogUsesTheSharedHeaderAndCloseAction() {
        val closed = AtomicInteger()
        compose.setContent {
            ZhiBanTheme {
                ZhiBanTaskDialog(onDismissRequest = { closed.incrementAndGet() }) {
                    ZhiBanDialogHeader(
                        title = "编辑联系人",
                        subtitle = "基本资料",
                        onDismiss = { closed.incrementAndGet() },
                    )
                    Text("姓名")
                }
            }
        }

        compose.onNodeWithText("编辑联系人").assertIsDisplayed()
        compose.onNodeWithText("基本资料").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭").performClick()
        assertEquals(1, closed.get())
    }
}
