package com.zhiban.rebuild.ui.tabs

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.rule.GrantPermissionRule
import com.zhiban.rebuild.data.agent.ScheduleProjection
import com.zhiban.rebuild.runtime.input.asr.CloudAsrAvailability
import com.zhiban.rebuild.ui.theme.ZhiBanTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ScheduleOutcomeVoiceRecordingTest {
    @get:Rule
    val microphonePermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun availableProviderRecordsInAppThenWritesTranscriptionIntoOutcomeField() {
        var receivedRecordedAudio = false
        compose.setContent {
            ZhiBanTheme {
                ScheduleCompletionDialog(
                    schedule = ScheduleProjection(
                        id = "schedule-voice-recording",
                        title = "记录客户会议结果",
                        startAtEpochMs = System.currentTimeMillis(),
                        durationMinutes = 30,
                        note = null,
                    ),
                    actions = ScheduleCompletionActions(
                        onDismiss = {},
                        onComplete = {},
                        onPostpone = {},
                        onEdit = {},
                        onCancelSchedule = {},
                    ),
                    voice = ScheduleOutcomeVoiceConfig(
                        availability = CloudAsrAvailability.AVAILABLE,
                        onTranscribe = { audio, callback ->
                            receivedRecordedAudio = audio.isFile && audio.length() > 0L
                            audio.delete()
                            callback("客户同意下周确认方案", null)
                        },
                    ),
                )
            }
        }

        compose.onNodeWithContentDescription("语音填写结果").performClick()
        compose.onNodeWithText("正在录音，点停止后转成文字").assertIsDisplayed()
        Thread.sleep(MINIMUM_RECORDING_MS)
        compose.onNodeWithContentDescription("结束录音并识别").performClick()

        compose.onNodeWithText("客户同意下周确认方案").assertIsDisplayed()
        compose.runOnIdle { assertTrue(receivedRecordedAudio) }
    }

    private companion object {
        const val MINIMUM_RECORDING_MS = 600L
    }
}
