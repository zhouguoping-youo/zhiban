package com.zhiban.rebuild.ui.tabs

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.zhiban.rebuild.ui.theme.ZhiBanTheme
import org.junit.Assert.assertEquals
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

    @Test
    fun guidanceShowsEvidenceAndOffersDirectAgentPreparation() {
        var prepareCount = 0
        var calendarCount = 0
        compose.setContent {
            ZhiBanTheme {
                CrmOpportunityGuidanceCard(
                    guidance = CrmOpportunityGuidanceUi(
                        title = "先完成逾期跟进",
                        summary = "确认预算",
                        evidence = "原定昨天，目前仍未完成",
                        dueAtEpochMs = 1,
                    ),
                    onPrepare = { prepareCount++ },
                    onCalendar = { calendarCount++ },
                )
            }
        }

        compose.onNodeWithTag("crm-opportunity-guidance").assertIsDisplayed()
        // 依据默认收起，先展开再断言（§八：证据不进详情首屏）。
        compose.onNodeWithText("依据：原定昨天，目前仍未完成").assertDoesNotExist()
        compose.onNodeWithText("查看依据").performClick()
        compose.onNodeWithText("依据：原定昨天，目前仍未完成").assertIsDisplayed()
        compose.onNodeWithTag("crm-opportunity-guidance-prepare").performClick()
        compose.onNodeWithText("查看日历").performClick()
        compose.runOnIdle {
            assertEquals(1, prepareCount)
            assertEquals(1, calendarCount)
        }
    }
}
