package com.zhiban.rebuild.ui.tabs

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.zhiban.rebuild.runtime.personalization.UserProfile
import com.zhiban.rebuild.ui.theme.ZhiBanTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RelationEmptyStateTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun emptyStateOffersImportFirstAndManualAddSecond() {
        val imports = AtomicInteger()
        val additions = AtomicInteger()
        compose.setContent {
            ZhiBanTheme {
                RelationEmpty(
                    searching = false,
                    onImport = { imports.incrementAndGet() },
                    onAdd = { additions.incrementAndGet() },
                )
            }
        }

        compose.onNodeWithText("导入联系人").assertIsDisplayed().performClick()
        compose.onNodeWithText("手动添加").assertIsDisplayed().performClick()

        assertEquals(1, imports.get())
        assertEquals(1, additions.get())
    }

    @Test
    fun primaryImportActionRemainsAvailableInDarkTheme() {
        val imports = AtomicInteger()
        compose.setContent {
            ZhiBanTheme(darkTheme = true) {
                RelationEmpty(searching = false, onImport = { imports.incrementAndGet() }, onAdd = {})
            }
        }

        compose.onNodeWithText("导入联系人").assertIsDisplayed().performClick()
        assertEquals(1, imports.get())
    }

    @Test
    fun incompleteOwnerProfileShowsCompactActionablePrompt() {
        val opens = AtomicInteger()
        compose.setContent {
            ZhiBanTheme {
                OwnerProfilePrompt(
                    profile = UserProfile(phone = "13800000000"),
                    onClick = { opens.incrementAndGet() },
                )
            }
        }

        compose.onNodeWithText("完善我的资料").assertIsDisplayed().performClick()
        compose.onNodeWithText("补充微信号，帮助知伴识别你").assertIsDisplayed()
        assertEquals(1, opens.get())
    }
}
