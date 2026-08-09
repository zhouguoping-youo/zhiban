package com.zhiban.rebuild.ui.agent

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhiban.rebuild.ui.theme.*
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.launch

private val AgentAccent = ZhiBanTerracotta

@Composable
fun AgentConversationScreen(
    state: AgentConversationUiState,
    inputText: String = "",
    onConfirm: () -> Unit = {},
    onReject: () -> Unit = {},
    onRetry: () -> Unit = {},
    onCancel: () -> Unit = {},
    onResume: () -> Unit = {},
    onUndo: () -> Unit = {},
    onCopyAssistant: () -> Unit = {},
    onReadAssistant: () -> Unit = {},
    onShareAssistant: () -> Unit = {},
    onPositiveFeedback: () -> Unit = {},
    onNegativeFeedback: () -> Unit = {},
    onDismissMemory: () -> Unit = {},
    onOpenRecovery: () -> Unit = {},
    onSend: (String) -> Unit = {},
    onInputChange: (String) -> Unit = {},
    onRequestPermission: (AgentPermissionUi) -> Unit = {},
    multimodalState: MultimodalUiState = MultimodalUiState(),
    voiceInputLevel: Float = 0f,
    onPickImage: () -> Unit = {},
    onCapturePhoto: () -> Unit = {},
    onPickFile: () -> Unit = {},
    onToggleRecording: () -> Unit = {},
    onAttachmentAction: (String, AttachmentAction) -> Unit = { _, _ -> },
    onVoiceCancel: () -> Unit = {},
    onVoiceRetry: () -> Unit = {},
    onStartRealtimeVoice: () -> Unit = {},
    // Slice 1 (#t41): mic permission flow — when banner shows PERMANENTLY_DENIED,
    // route user to OS app-details page (AppSettingsOpener).
    onOpenAppSettings: () -> Unit = {},
    onBackToHome: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onManagePlugins: () -> Unit = {},
    onWorkTaskClick: (String) -> Unit = {},
    // Per architect 695: model selection moved into the input bar. The
    // Screen exposes the inline label + a single tappable that opens a
    // single-section popup. Single popup holds both model + level rows.
    inlineModelLabel: String = "M2.7 智能/高",
    onModelLabelClick: () -> Unit = {},
    availableModels: List<String> = listOf("M2.7", "M3-pro", "M2.7-fast"),
    availableLevels: List<String> = listOf("深入", "标准", "快速"),
    onModelSelect: (String) -> Unit = {},
    onLevelSelect: (String) -> Unit = {},
    conversationHistory: List<com.zhiban.rebuild.runtime.store.ConversationSummary> = emptyList(),
    onLoadHistory: () -> Unit = {},
    onOpenConversation: (String) -> Unit = {},
    onDeleteConversation: (String) -> Unit = {},
    onNewConversation: () -> Unit = {},
) {
    var permissionDialogDismissed by androidx.compose.runtime.remember(multimodalState.microphonePermission) {
        androidx.compose.runtime.mutableStateOf(false)
    }
    var modelPickerOpen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        var drawerOpen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        var historyOpen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        AgentTopBar(
            onBack = onBackToHome,
            onMenu = { drawerOpen = true },
            onHistory = {
                onLoadHistory()
                historyOpen = true
            },
        )
        if (drawerOpen) {
            MoreDrawer(
                onDismiss = { drawerOpen = false },
                onOpenHistory = {
                    drawerOpen = false
                    onLoadHistory()
                    historyOpen = true
                },
                onNewConversation = {
                    drawerOpen = false
                    onNewConversation()
                },
                onNavigateToSettings = onNavigateToSettings,
            )
        }
        if (historyOpen) {
            ConversationHistoryDialog(
                items = conversationHistory,
                onOpen = {
                    historyOpen = false
                    onOpenConversation(it)
                },
                onDelete = onDeleteConversation,
                onDismiss = { historyOpen = false },
            )
        }
        state.memoryHint?.let { MemoryHint(it, onDismissMemory) }
        state.recoveredMessageCount?.let { RecoveryBanner(it, onOpenRecovery) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // Per architect 807 派单 T + 老周 806: 彻底取消任何前置画面
            // (无 hero / 无 chat hint / 无 work idle 任务建议). tap 问问
            // → 直接 AssistantChat 空 input 容器, 不显示任何 hint.
            // Work idle 任务建议若需保留改为 tap ⚡ 唤起 (未来派单).
            MessageList(
                state, onConfirm, onReject, onRetry, onCancel, onResume,
                onNavigateToSettings,
                onCopyAssistant, onReadAssistant, onShareAssistant, onUndo,
                onPositiveFeedback, onNegativeFeedback,
            )
            if (state.stage == AgentConversationStage.EMPTY && state.userMessage == null &&
                state.assistantMessage == null
            ) {
                Text(
                    "有什么可以帮忙的？",
                    Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
        state.permission?.let { PermissionRationale(it) { onRequestPermission(it) } }
        MultimodalStatusBanner(multimodalState, onOpenAppSettings = onOpenAppSettings)
        if (multimodalState.microphonePermission == DevicePermissionState.PERMANENTLY_DENIED &&
            !permissionDialogDismissed
        ) {
            PermissionSettingsDialog("麦克风", onDismiss = {
                permissionDialogDismissed = true
            }, onOpenSettings = onOpenAppSettings)
        }
        AttachmentStatusStrip(multimodalState.attachments, onAttachmentAction)
        if (multimodalState.transcription.phase in
            setOf(TranscriptionPhase.RECORDING, TranscriptionPhase.UPLOADING, TranscriptionPhase.TRANSCRIBING)
        ) {
            VoiceInputBar(
                multimodalState.transcription.copy(inputLevel = voiceInputLevel),
                onCancel = onVoiceCancel,
                onStop = onToggleRecording,
            )
        } else {
            if (multimodalState.transcription.phase in setOf(TranscriptionPhase.FAILED, TranscriptionPhase.CANCELLED)) {
                TranscriptionStatus(multimodalState.transcription, onRetry = onVoiceRetry, onDelete = onVoiceCancel)
            }
            MessageInput(
                value = inputText, enabled = state.isInputEnabled, onValueChange = onInputChange, onSend = onSend,
                attachmentPrompt = attachmentPrompt(multimodalState.attachments),
                isRecording = multimodalState.transcription.phase == TranscriptionPhase.RECORDING,
                onPickImage = onPickImage, onCapturePhoto = onCapturePhoto,
                onPickFile = onPickFile, onToggleRecording = onToggleRecording,
                onStartRealtimeVoice = onStartRealtimeVoice,
                inlineModelLabel = inlineModelLabel,
                onModelLabelClick = {
                    modelPickerOpen = true
                    onModelLabelClick()
                },
                onLevelSelect = onLevelSelect,
                onManagePlugins = onManagePlugins,
            )
        }
    }
    if (modelPickerOpen) {
        ModelPickerSheet(
            currentLabel = inlineModelLabel,
            models = availableModels,
            levels = availableLevels,
            onDismiss = { modelPickerOpen = false },
            onModelSelect = {
                onModelSelect(it)
                modelPickerOpen = false
            },
            onLevelSelect = {
                onLevelSelect(it)
                modelPickerOpen = false
            },
        )
    }
}
