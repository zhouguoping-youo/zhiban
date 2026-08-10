package com.zhiban.rebuild.ui.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import com.zhiban.rebuild.data.notification.NotificationCandidateEntity
import com.zhiban.rebuild.data.notification.NotificationInsights
import com.zhiban.rebuild.data.notification.ScheduleInsight
import com.zhiban.rebuild.ui.theme.ZhiBanTheme
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AgentConversationScreenE2ETest {
    @get:Rule val compose = createComposeRule()

    @Test fun longPressAssistantMessageOnlyShowsUsefulReplyActions() {
        val state = AgentConversationUiState(
            messages = listOf(AgentConversationMessageUi(turnId = "t1", role = "assistant", text = "这是知伴的回答")),
        )
        compose.setContent {
            ZhiBanTheme {
                AgentConversationScreen(
                    state = state,
                )
            }
        }

        // Feedback belongs to completed replies, not the long-press menu.
        assertTrue(compose.onAllNodesWithText("点赞").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("这是知伴的回答").performTouchInput { longClick() }
        compose.onNodeWithText("复制").assertIsDisplayed()
        compose.onNodeWithText("分享").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("点赞").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("点踩").fetchSemanticsNodes().isEmpty())
    }

    @Test fun unifiedAgentInputAndAttachmentMenuAreOperable() {
        val sent = AtomicReference<String>()
        val picked = AtomicReference<String>()
        compose.setContent {
            ZhiBanTheme {
                var input by remember { mutableStateOf("") }
                AgentConversationScreen(
                    state = AgentConversationUiState(),
                    inputText = input,
                    onInputChange = { input = it },
                    onSend = sent::set,
                    onPickImage = { picked.set("image") },
                    onCapturePhoto = { picked.set("camera") },
                    onPickFile = { picked.set("file") },
                )
            }
        }

        compose.onNodeWithContentDescription("消息输入框").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("帮我安排会议")
        compose.onNodeWithContentDescription("发送").assertIsDisplayed().performClick()
        assertEquals("帮我安排会议", sent.get())

        compose.onNodeWithText("问问").assertIsDisplayed()
        compose.onNodeWithText("有什么可以帮忙的？").assertDoesNotExist()

        compose.onNodeWithContentDescription("添加附件").performClick()
        compose.onNodeWithText("相机").assertIsDisplayed()
        compose.onNodeWithText("照片").assertIsDisplayed().performClick()
        assertEquals("image", picked.get())

        compose.onNodeWithContentDescription("添加附件").performClick()
        compose.onNodeWithText("相机").performClick()
        assertEquals("camera", picked.get())

        compose.onNodeWithContentDescription("添加附件").performClick()
        compose.onNodeWithText("文件").performClick()
        assertEquals("file", picked.get())
    }

    @Test fun emptyConversationSuggestionsRunTheSelectedTask() {
        val selected = AtomicReference<String>()
        compose.setContent {
            ZhiBanTheme {
                AgentConversationScreen(
                    state = AgentConversationUiState(),
                    onWorkTaskClick = selected::set,
                )
            }
        }

        compose.onNodeWithText("看今天").assertIsDisplayed().performClick()
        assertEquals("帮我看看今天最重要的安排", selected.get())
        compose.onNodeWithText("找联系人").assertIsDisplayed().performClick()
        assertEquals("帮我找一个联系人", selected.get())
        compose.onNodeWithText("记下一步").assertIsDisplayed().performClick()
        assertEquals("帮我记录一个下一步动作", selected.get())
    }

    @Test fun uncertainRecognizedScheduleCanBeConfirmedWithoutLeavingConversation() {
        val confirmed = AtomicReference<String>()
        val dismissed = AtomicReference<String>()
        val candidate = NotificationCandidateEntity(
            candidateId = "candidate-1",
            sourceKey = "source-1",
            packageName = "com.tencent.mm",
            appLabel = "微信",
            title = "项目群",
            body = "老周，下周三下午三点开会",
            postedAtEpochMs = 1_700_000_000_000L,
            senderName = "王敏",
            suggestedContactId = "contact-1",
            suggestedContactConfidence = 0.94,
            insightJson = NotificationInsights(
                ScheduleInsight(
                    title = "项目会议",
                    startAtEpochMs = 1_700_100_000_000L,
                    confidence = 0.94,
                ),
            ).toJsonOrNull(),
        )
        compose.setContent {
            ZhiBanTheme {
                AgentConversationScreen(
                    state = AgentConversationUiState(),
                    perceptionCandidates = listOf(candidate),
                    onConfirmPerception = { confirmed.set(it.candidateId) },
                    onDismissPerception = dismissed::set,
                )
            }
        }

        compose.onNodeWithText("识别到一项安排").assertIsDisplayed()
        compose.onNodeWithText("王敏 · 项目会议").assertIsDisplayed()
        compose.onNodeWithText("确认安排").assertIsDisplayed().performClick()
        assertEquals("candidate-1", confirmed.get())
        compose.onNodeWithText("忽略").performClick()
        assertEquals("candidate-1", dismissed.get())
    }

    @Test fun unsentDraftSurvivesSavedInstanceStateRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            ZhiBanTheme {
                var input by rememberConversationDraftState()
                AgentConversationScreen(
                    state = AgentConversationUiState(),
                    inputText = input,
                    onInputChange = { input = it },
                )
            }
        }

        compose.onNodeWithContentDescription("消息输入框").performClick()
        compose.onNodeWithContentDescription("消息输入框").performTextInput("尚未发送的草稿")
        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("尚未发送的草稿").assertIsDisplayed()
    }

    @Test fun approvalReplyActionsErrorRecoveryAndBackCallbacksAreOperable() {
        val confirm = AtomicInteger()
        val reject = AtomicInteger()
        val back = AtomicInteger()
        val copy = AtomicInteger()
        val undo = AtomicInteger()
        val positive = AtomicInteger()
        val screenState = mutableStateOf(
            AgentConversationUiState(
                stage = AgentConversationStage.AWAITING_CONFIRMATION,
                userMessage = "明天下午开会",
                plan = AgentPlanUi("创建日程", "项目会", "明天 15:00", "提前 10 分钟"),
            ),
        )
        compose.setContent {
            ZhiBanTheme {
                AgentConversationScreen(
                    state = screenState.value,
                    onConfirm = { confirm.incrementAndGet() },
                    onReject = { reject.incrementAndGet() },
                    onBackToHome = { back.incrementAndGet() },
                    onCopyAssistant = { copy.incrementAndGet() },
                    onPositiveFeedback = { positive.incrementAndGet() },
                    onUndo = { undo.incrementAndGet() },
                )
            }
        }
        compose.onNodeWithText("确认执行").assertIsDisplayed().performClick()
        compose.onNodeWithText("拒绝").performClick()
        compose.onNodeWithText("我").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        assertEquals(1, confirm.get())
        assertEquals(1, reject.get())
        assertEquals(1, back.get())

        compose.runOnUiThread {
            screenState.value = AgentConversationUiState(
                stage = AgentConversationStage.SUCCEEDED,
                assistantMessage = "日程已经创建。",
                artifacts = listOf(
                    AgentArtifactUi("a1", "本地图片.jpg", "image/jpeg", 1024, AgentArtifactKind.ATTACHMENT),
                    AgentArtifactUi("a2", "会议纪要.pdf", "application/pdf", 2048, AgentArtifactKind.GENERATED_FILE),
                ),
                canUndo = true,
            )
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("撤销刚才的更改").performClick()
        compose.onNodeWithContentDescription("复制").performClick()
        compose.onNodeWithContentDescription("有帮助").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("有帮助").performClick()
        compose.onNodeWithContentDescription("需改进").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("本次对话的文件").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("本地图片.jpg").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("会议纪要.pdf").fetchSemanticsNodes().isNotEmpty())
        assertEquals(1, undo.get())
        assertEquals(1, copy.get())
        assertEquals(1, positive.get())
    }

    @Test fun negativeFeedbackActionCallsTheRealCallback() {
        val negative = AtomicInteger()
        compose.setContent {
            ZhiBanTheme {
                AgentConversationScreen(
                    state = AgentConversationUiState(
                        stage = AgentConversationStage.SUCCEEDED,
                        assistantMessage = "这条回答需要改进。",
                    ),
                    onNegativeFeedback = { negative.incrementAndGet() },
                )
            }
        }

        compose.onNodeWithContentDescription("需改进").assertIsDisplayed().performClick()
        assertEquals(1, negative.get())
    }

    @Test fun feedbackActionsFollowTheMyPagePreference() {
        compose.setContent {
            ZhiBanTheme {
                AgentConversationScreen(
                    state = AgentConversationUiState(
                        stage = AgentConversationStage.SUCCEEDED,
                        assistantMessage = "已完成。",
                    ),
                    feedbackEnabled = false,
                )
            }
        }

        assertTrue(compose.onAllNodesWithContentDescription("有帮助").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithContentDescription("需改进").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithContentDescription("复制").assertIsDisplayed()
    }

    @Test fun historyNewConversationAndPluginManagementAreOperable() {
        val opened = AtomicReference<String>()
        val deleted = AtomicReference<String>()
        val newCount = AtomicInteger()
        val pluginManagementCount = AtomicInteger()
        compose.setContent {
            ZhiBanTheme {
                AgentConversationScreen(
                    state = AgentConversationUiState(),
                    conversationHistory = listOf(
                        com.zhiban.rebuild.runtime.store.ConversationSummary("s1", "帮我安排明天下午的会议", 10),
                    ),
                    onOpenConversation = opened::set,
                    onDeleteConversation = deleted::set,
                    onNewConversation = { newCount.incrementAndGet() },
                    onManagePlugins = { pluginManagementCount.incrementAndGet() },
                )
            }
        }

        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("新建对话").performClick()
        assertEquals(1, newCount.get())

        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("对话历史").performClick()
        compose.onNodeWithText("帮我安排明天下午的会议").assertIsDisplayed().performClick()
        assertEquals("s1", opened.get())

        compose.onNodeWithContentDescription("添加附件").performClick()
        compose.onNodeWithText("插件").performClick()
        assertEquals(1, pluginManagementCount.get())
    }

    @Test fun modelPickerVoiceCompletionAndHistoryDeletionRequireWorkingActions() {
        val level = AtomicReference<String>()
        val voiceComplete = AtomicInteger()
        val deleted = AtomicReference<String>()
        val multimodal = mutableStateOf(
            MultimodalUiState(
                transcription = TranscriptionUiState(TranscriptionPhase.RECORDING, inputLevel = 0.5f),
            ),
        )
        compose.setContent {
            ZhiBanTheme {
                AgentConversationScreen(
                    state = AgentConversationUiState(),
                    inlineModelLabel = "step-3.5-flash 智能/高",
                    availableModels = listOf("step-3.5-flash"),
                    availableLevels = listOf("高", "中", "快速"),
                    onLevelSelect = level::set,
                    conversationHistory = listOf(
                        com.zhiban.rebuild.runtime.store.ConversationSummary("s-delete", "需要删除的对话", 10),
                    ),
                    onDeleteConversation = deleted::set,
                    multimodalState = multimodal.value,
                    onToggleRecording = { voiceComplete.incrementAndGet() },
                )
            }
        }

        compose.onNodeWithContentDescription("完成录音并转写").assertIsDisplayed().performClick()
        assertEquals(1, voiceComplete.get())
        compose.runOnUiThread { multimodal.value = MultimodalUiState() }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("消息输入框").performClick()
        compose.onNodeWithContentDescription("选择模型与思考强度").performClick()
        compose.onNodeWithText("模型").assertIsDisplayed()
        compose.onNodeWithText("中").performClick()
        assertEquals("中", level.get())

        compose.onNodeWithContentDescription("对话历史").performClick()
        compose.onNodeWithContentDescription("删除对话").performClick()
        compose.onNodeWithText("删除这段对话？").assertIsDisplayed()
        assertEquals(null, deleted.get())
        compose.onNodeWithText("删除").performClick()
        assertEquals("s-delete", deleted.get())
    }

    @Test fun attachmentWithoutTextExposesSendAndDispatchesAnAnalysisRequest() {
        val sent = AtomicReference<String>()
        compose.setContent {
            ZhiBanTheme {
                AgentConversationScreen(
                    state = AgentConversationUiState(),
                    multimodalState = MultimodalUiState(
                        attachments = AttachmentBatchUiState(
                            listOf(
                                AttachmentUiState(
                                    attachmentId = "image-1",
                                    displayName = "照片.jpg",
                                    modality = InputModality.IMAGE,
                                    sourceUri = "content://test/image-1",
                                ),
                            ),
                        ),
                    ),
                    onSend = sent::set,
                )
            }
        }

        compose.onNodeWithContentDescription("发送").assertIsDisplayed().performClick()
        assertEquals("请识别并分析这张图片。", sent.get())
    }

    @Test fun recordingAndPermanentPermissionStatesRenderActionableControls() {
        val settings = AtomicInteger()
        val voiceCancel = AtomicInteger()
        compose.setContent {
            ZhiBanTheme {
                AgentConversationScreen(
                    state = AgentConversationUiState(),
                    multimodalState = MultimodalUiState(
                        microphonePermission = DevicePermissionState.PERMANENTLY_DENIED,
                        transcription = TranscriptionUiState(TranscriptionPhase.RECORDING),
                    ),
                    onOpenAppSettings = {
                        settings.incrementAndGet()
                    },
                    onVoiceCancel = { voiceCancel.incrementAndGet() },
                )
            }
        }
        compose.onNodeWithText("打开系统设置").assertIsDisplayed().performClick()
        assertEquals(1, settings.get())
        compose.onNodeWithContentDescription("录音中").assertIsDisplayed()
        compose.onNodeWithContentDescription("停止录音并转写").assertIsDisplayed()
        compose.onNodeWithContentDescription("取消录音").performClick()
        assertEquals(1, voiceCancel.get())
    }

    @Test fun providerNetworkAndFinalErrorsExposeOnlyValidRecoveryActions() {
        val retry = AtomicInteger()
        val settings = AtomicInteger()
        val screenState = mutableStateOf(
            AgentConversationUiState(
                stage = AgentConversationStage.FAILED_FINAL,
                safeMessage = "尚未配置大模型服务，请先完成连接设置。",
                safeFailureCode = "PROVIDER_NOT_CONFIGURED",
                isCredentialMissing = true,
            ),
        )
        compose.setContent {
            ZhiBanTheme {
                AgentConversationScreen(
                    state = screenState.value,
                    onRetry = {
                        retry.incrementAndGet()
                    },
                    onNavigateToSettings = { settings.incrementAndGet() },
                )
            }
        }

        compose.onNodeWithText("去设置").assertIsDisplayed().performClick()
        compose.onNodeWithText("重试").assertDoesNotExist()
        compose.onNodeWithText("PROVIDER_UNREACHABLE").assertDoesNotExist()
        assertEquals(1, settings.get())

        compose.runOnUiThread {
            screenState.value = AgentConversationUiState(
                stage = AgentConversationStage.FAILED_RETRYABLE,
                safeMessage = "当前没有网络，仍可查看本地日程、联系人和记忆。连接网络后可重试对话。",
                safeFailureCode = "NETWORK_OFFLINE",
            )
        }
        compose.waitForIdle()
        compose.onNodeWithText("重试").assertIsDisplayed().performClick()
        compose.onNodeWithText("INSUFFICIENT_QUOTA").assertDoesNotExist()
        assertEquals(1, retry.get())

        compose.runOnUiThread {
            screenState.value = AgentConversationUiState(
                stage = AgentConversationStage.FAILED_FINAL,
                safeMessage = "AI 没有生成可安全执行的操作，请换一种说法重新发送。",
                safeFailureCode = "INVALID_TOOL_CALL",
            )
        }
        compose.waitForIdle()
        compose.onNodeWithText("重试").assertDoesNotExist()
        compose.onNodeWithText("AI 没有生成可安全执行的操作，请换一种说法重新发送。").assertIsDisplayed()
    }

    @Test fun attachmentAndTranscriptionFailuresExposeWorkingRecoveryControls() {
        val action = AtomicReference<AttachmentAction>()
        val voiceRetry = java.util.concurrent.atomic.AtomicBoolean(false)
        val voiceDelete = java.util.concurrent.atomic.AtomicBoolean(false)
        val multimodal = mutableStateOf(
            MultimodalUiState(
                attachments = AttachmentBatchUiState(
                    listOf(
                        AttachmentUiState(
                            "a1",
                            "项目资料.pdf",
                            InputModality.FILE,
                            phase = AttachmentPhase.FAILED,
                            retryable = true,
                            safeMessage = "上传失败，请检查网络",
                        ),
                    ),
                ),
                transcription = TranscriptionUiState(
                    phase = TranscriptionPhase.FAILED,
                    safeMessage = "网络连接失败，录音已保留",
                    // Mic batch retains the recorded file, so it offers 删除录音.
                    originalAudioRetained = true,
                ),
            ),
        )
        compose.setContent {
            ZhiBanTheme {
                AgentConversationScreen(
                    state = AgentConversationUiState(),
                    multimodalState = multimodal.value,
                    onAttachmentAction = { _, value -> action.set(value) },
                    onVoiceRetry = { voiceRetry.set(true) },
                    onVoiceCancel = { voiceDelete.set(true) },
                )
            }
        }

        compose.onNodeWithText("上传失败，请检查网络").assertIsDisplayed()
        compose.onNodeWithText("网络连接失败，录音已保留").assertIsDisplayed()
        compose.onAllNodesWithText("重试")[0].performClick()
        assertEquals(AttachmentAction.RETRY, action.get())
        compose.onAllNodesWithText("重试")[1].performClick()
        compose.onNodeWithText("删除录音").performClick()
        assertTrue(voiceRetry.get())
        assertTrue(voiceDelete.get())

        compose.runOnUiThread {
            multimodal.value = multimodal.value.copy(
                attachments = AttachmentBatchUiState(
                    listOf(
                        AttachmentUiState("a1", "项目资料.pdf", InputModality.FILE, phase = AttachmentPhase.URI_EXPIRED),
                    ),
                ),
            )
        }
        compose.waitForIdle()
        compose.onNodeWithText("文件已失效，请重新选择").assertIsDisplayed()
        compose.onNodeWithText("重新选择").performClick()
        assertEquals(AttachmentAction.RESELECT, action.get())
    }

    // Realtime voice retains no recording, so its failure strip must offer a neutral 关闭 (dismiss),
    // never 删除录音 — otherwise the user is told to delete a recording that does not exist.
    @Test fun realtimeVoiceFailureOffersDismissNotDeleteRecording() {
        val voiceDelete = java.util.concurrent.atomic.AtomicBoolean(false)
        val multimodal = mutableStateOf(
            MultimodalUiState(
                transcription = TranscriptionUiState(
                    phase = TranscriptionPhase.FAILED,
                    safeMessage = "请先在隐私与权限中允许语音识别上云",
                    originalAudioRetained = false,
                ),
            ),
        )
        compose.setContent {
            ZhiBanTheme {
                AgentConversationScreen(
                    state = AgentConversationUiState(),
                    multimodalState = multimodal.value,
                    onVoiceCancel = { voiceDelete.set(true) },
                )
            }
        }

        compose.onNodeWithText("请先在隐私与权限中允许语音识别上云").assertIsDisplayed()
        compose.onAllNodesWithText("删除录音").assertCountEquals(0)
        compose.onNodeWithText("关闭").assertIsDisplayed().performClick()
        assertTrue(voiceDelete.get())
    }

    @Test fun structuredLongReplyRendersHeadingsListsCodeAndAutomaticallyShowsLatestContent() {
        val longTail = (1..60).joinToString("\n") { "- 第 $it 条可核对结果" }
        compose.setContent {
            ZhiBanTheme {
                AgentConversationScreen(
                    state = AgentConversationUiState(
                        stage = AgentConversationStage.SUCCEEDED,
                        assistantMessage = "# 执行结果\n\n这是 **重要结论**。\n\n```json\n{\"status\":\"ok\"}\n```\n$longTail",
                    ),
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("执行结果").assertExists()
        compose.onNodeWithText("{\"status\":\"ok\"}").assertExists()
        compose.onNodeWithText("第 60 条可核对结果").assertIsDisplayed()
        compose.onNodeWithContentDescription("滚动到最新").assertDoesNotExist()
    }
}
