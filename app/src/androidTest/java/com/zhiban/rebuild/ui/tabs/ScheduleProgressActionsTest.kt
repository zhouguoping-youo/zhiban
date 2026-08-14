package com.zhiban.rebuild.ui.tabs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.zhiban.rebuild.data.agent.ScheduleProjection
import com.zhiban.rebuild.runtime.input.asr.CloudAsrAvailability
import com.zhiban.rebuild.ui.theme.ZhiBanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ScheduleProgressActionsTest {
    @get:Rule
    val compose = createComposeRule()

    private val schedule = ScheduleProjection(
        id = "schedule-progress",
        title = "跟委内瑞拉客户开会",
        startAtEpochMs = System.currentTimeMillis() + 86_400_000L,
        durationMinutes = 60,
        note = null,
    )

    @Test
    fun compactScheduleRowShowsSingleLineProgressEntry() {
        var progressClicks = 0
        compose.setContent {
            ZhiBanTheme {
                Box(Modifier.width(320.dp)) {
                    ScheduleRow(
                        schedule = schedule,
                        onClick = {},
                        onProgress = { progressClicks++ },
                    )
                }
            }
        }

        compose.onNodeWithText("完成或延期").assertDoesNotExist()
        compose.onNodeWithText("更新进展").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, progressClicks) }
    }

    @Test
    fun progressDialogOffersCompletePostponeAndCancellation() {
        var selectedAction = ""
        compose.setContent {
            ZhiBanTheme {
                ScheduleCompletionDialog(
                    schedule = schedule,
                    actions = ScheduleCompletionActions(
                        onDismiss = {},
                        onComplete = { selectedAction = "complete" },
                        onPostpone = { selectedAction = "postpone" },
                        onCancelSchedule = { selectedAction = "cancel" },
                    ),
                )
            }
        }

        compose.onNodeWithText("延期").assertIsDisplayed()
        compose.onNodeWithText("标记完成").assertIsDisplayed()
        compose.onNodeWithText("取消日程").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("cancel", selectedAction) }
    }

    @Test
    fun voiceResultUsesInAppProviderStateInsteadOfLaunchingSystemRecognizer() {
        compose.setContent {
            ZhiBanTheme {
                ScheduleCompletionDialog(
                    schedule = schedule,
                    actions = ScheduleCompletionActions(
                        onDismiss = {},
                        onComplete = {},
                        onPostpone = {},
                        onCancelSchedule = {},
                    ),
                    voice = ScheduleOutcomeVoiceConfig(
                        availability = CloudAsrAvailability.PROVIDER_NOT_CONFIGURED,
                    ),
                )
            }
        }

        compose.onNodeWithContentDescription("语音填写结果").performClick()
        compose.onNodeWithText("请先在“我的”中连接模型服务").assertIsDisplayed()
    }
}
