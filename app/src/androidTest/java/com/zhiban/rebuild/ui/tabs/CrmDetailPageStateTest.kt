package com.zhiban.rebuild.ui.tabs

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.zhiban.rebuild.ui.theme.ZhiBanTheme
import org.junit.Rule
import org.junit.Test

class CrmDetailPageStateTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun loadingStateExplainsWhatIsHappening() {
        compose.setContent {
            ZhiBanTheme {
                CrmDetailPageState(
                    title = "正在读取机会",
                    message = "稍等一下，知伴正在整理联系人和推进记录。",
                    loading = true,
                )
            }
        }

        compose.onNodeWithText("正在读取机会").assertIsDisplayed()
        compose.onNodeWithText("稍等一下，知伴正在整理联系人和推进记录。").assertIsDisplayed()
    }

    @Test
    fun failureStateIsNotAnEmptyScreen() {
        compose.setContent {
            ZhiBanTheme {
                CrmDetailPageState(
                    title = "暂时无法读取",
                    message = "机会详情读取失败，请稍后重试",
                )
            }
        }

        compose.onNodeWithText("暂时无法读取").assertIsDisplayed()
        compose.onNodeWithText("机会详情读取失败，请稍后重试").assertIsDisplayed()
    }
}
