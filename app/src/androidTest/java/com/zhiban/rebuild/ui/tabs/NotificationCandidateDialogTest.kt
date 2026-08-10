package com.zhiban.rebuild.ui.tabs

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.zhiban.rebuild.data.notification.NotificationCandidateEntity
import com.zhiban.rebuild.ui.theme.ZhiBanTheme
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NotificationCandidateDialogTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun unresolvedMessageShowsAgentConclusionBeforeRawCollectionSettings() {
        val created = AtomicReference<String>()
        val candidate = NotificationCandidateEntity(
            candidateId = "candidate-1",
            sourceKey = "source-1",
            packageName = "com.tencent.mm",
            appLabel = "微信",
            title = "王敏",
            body = "资料我收到了",
            postedAtEpochMs = 1_700_000_000_000L,
            senderName = "王敏",
        )
        compose.setContent {
            ZhiBanTheme {
                NotificationCandidateDialog(
                    enabled = true,
                    candidates = listOf(candidate),
                    contacts = emptyList(),
                    onEnable = {},
                    onDismissCandidate = {},
                    onConfirmCandidate = { _, _, done -> done(null) },
                    onCreateContact = { _, name, done ->
                        created.set(name)
                        done(null)
                    },
                    onConfirmSchedule = { _, done -> done(null) },
                    enabledPlatforms = emptySet(),
                    onPlatformEnabled = { _, _ -> },
                    outgoingCollectionEnabled = false,
                    outgoingAccessibilityEnabled = false,
                    onOutgoingCollectionEnabled = {},
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("知伴需要你确认").assertIsDisplayed()
        compose.onNodeWithText("识别到一位联系人，但身份还不唯一").assertIsDisplayed()
        compose.onNodeWithText("采集来源").assertDoesNotExist()
        compose.onNodeWithText("新建联系人").performClick()
        assertEquals("王敏", created.get())

        compose.onNodeWithContentDescription("消息感知设置").performClick()
        compose.onNodeWithText("采集来源").assertIsDisplayed()
    }
}
