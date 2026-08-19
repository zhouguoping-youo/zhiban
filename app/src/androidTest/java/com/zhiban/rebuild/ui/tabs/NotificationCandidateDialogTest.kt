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
                    replySuggestions = emptyList(),
                    onForwardReply = { _, _ -> },
                    onDismissReply = {},
                    onOptOutReply = {},
                    onEnable = {},
                    onDismissCandidate = {},
                    onMuteSender = {},
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

        compose.onNodeWithText("待确认").assertIsDisplayed()
        compose.onNodeWithText("还有 1 项需要你确认").assertIsDisplayed()
        compose.onNodeWithText("识别到一位联系人，但身份还不唯一").assertIsDisplayed()
        compose.onNodeWithText("采集来源").assertDoesNotExist()
        compose.onNodeWithText("新建联系人").performClick()
        assertEquals("王敏", created.get())

        compose.onNodeWithContentDescription("消息感知设置").performClick()
        compose.onNodeWithText("采集来源").assertIsDisplayed()
    }

    @Test
    fun replySuggestionGroupRendersCardAndForwardsChosenDraft() {
        val forwarded = AtomicReference<String>()
        val suggestion = ReplySuggestionCardModel(
            candidateId = "cand-1",
            contactId = "contact-1",
            platform = "WECHAT",
            contactName = "张三",
            incomingExcerpt = "明天上午的合同能发我一份吗？",
            drafts = listOf("好的张总，明早十点前发您", "收到，明天上午给您回复"),
            createdAtEpochMs = 1_700_000_000_000L,
        )
        compose.setContent {
            ZhiBanTheme {
                NotificationCandidateDialog(
                    enabled = true,
                    candidates = emptyList(),
                    contacts = emptyList(),
                    replySuggestions = listOf(suggestion),
                    onForwardReply = { model, draft -> forwarded.set("${model.contactName}|$draft") },
                    onDismissReply = {},
                    onOptOutReply = {},
                    onEnable = {},
                    onDismissCandidate = {},
                    onMuteSender = {},
                    onConfirmCandidate = { _, _, done -> done(null) },
                    onCreateContact = { _, _, done -> done(null) },
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

        compose.onNodeWithText("AI 回复建议").assertIsDisplayed()
        compose.onNodeWithText("收到，明天上午给您回复").performClick()
        compose.onNodeWithText("转发给 张三").performClick()
        assertEquals("张三|收到，明天上午给您回复", forwarded.get())
    }
}
