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
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhiban.rebuild.R
import com.zhiban.rebuild.ui.components.ZhiBanAlertDialog
import com.zhiban.rebuild.ui.components.ZhiBanHeaderIconAction
import com.zhiban.rebuild.ui.components.ZhiBanTopBar
import com.zhiban.rebuild.ui.components.localizedQuantity
import com.zhiban.rebuild.ui.icons.ReplyGlyph
import com.zhiban.rebuild.ui.icons.ZhiBanReplyIcon
import com.zhiban.rebuild.ui.theme.*
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.launch

private val AgentAccent = ZhiBanTerracotta

@Composable
internal fun PermissionSettingsDialog(permissionName: String, onDismiss: () -> Unit, onOpenSettings: () -> Unit) {
    ZhiBanAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("允许知伴使用你的$permissionName？") },
        text = { Text("需要在 Android 系统设置中为知伴开启${permissionName}权限。开启后请返回知伴继续使用。") },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = { TextButton(onClick = onOpenSettings) { Text("打开系统设置") } },
    )
}

/**
 * Renders the persistent microphone status banner above the input area.
 *
 * Slice 1 (#t41): when a capture permission is PERMANENTLY_DENIED, the
 * banner becomes actionable — tapping the "前往设置" suffix routes the
 * user to the OS app-details page via AppSettingsOpener. For all other
 * states (DENIED/REQUESTABLE/UNKNOWN/GRANTED, capability warnings) the
 * banner stays informational only.
 */
@Composable fun MultimodalStatusBanner(state: MultimodalUiState, onOpenAppSettings: () -> Unit = {}) {
    val micDenied = state.microphonePermission == DevicePermissionState.PERMANENTLY_DENIED
    val message = when {
        micDenied -> "麦克风权限已被永久拒绝，请前往系统设置开启"

        state.microphonePermission == DevicePermissionState.DENIED -> "未授予麦克风权限，仍可继续文字对话"

        state.capability.values.any { it == ProviderCapabilityState.EXPIRED } -> "多模态能力已过期，请重新验证服务能力"

        state.capability.values.any { it == ProviderCapabilityState.FAILED } -> "暂时无法验证多模态能力，不会上传附件"

        // B1: PROBING capability state is no longer surfaced as a banner
        else -> null
    }
    message?.let {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                .background(ZhiBanTerracottaSoft, RoundedCornerShape(ZhiBanRadius.Small))
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(it, Modifier.weight(1f), color = ZhiBanTextPrimary, style = MaterialTheme.typography.bodySmall)
            if (micDenied) {
                TextButton(
                    onClick = onOpenAppSettings,
                    modifier = Modifier.defaultMinSize(minHeight = ZhiBanSize.TouchTarget),
                ) {
                    Text("前往设置", color = ZhiBanTerracotta, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
fun AgentTopBar(onBack: () -> Unit = {}, onMenu: () -> Unit = {}, onHistory: () -> Unit = {}) = ZhiBanTopBar(
    title = "问问",
    onBack = onBack,
    trailing = {
        ZhiBanHeaderIconAction(Icons.Outlined.Menu, "更多", onMenu)
        ZhiBanHeaderIconAction(Icons.Outlined.History, "对话历史", onHistory)
    },
)

@Composable fun MemoryHint(text: String, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(
            horizontal = 16.dp,
            vertical = 4.dp,
        ).background(ZhiBanTerracotta.copy(alpha = .10f), RoundedCornerShape(ZhiBanRadius.Medium)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("记忆 · $text", Modifier.weight(1f), color = ZhiBanTextSecondary, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onDismiss, modifier = Modifier.defaultMinSize(minHeight = ZhiBanSize.TouchTarget)) {
            Text("不使用", color = ZhiBanTerracotta, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable fun RecoveryBanner(messageCount: Int, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(
            onClick = onOpen,
        ).padding(
            horizontal = ZhiBanSpacing.Lg,
            vertical = ZhiBanSpacing.Xs,
        ).background(
            MaterialTheme.colorScheme.surface,
            RoundedCornerShape(ZhiBanRadius.Medium),
        ).padding(ZhiBanSpacing.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            localizedQuantity(R.plurals.message_count_continue, messageCount),
            Modifier.weight(1f),
            color = ZhiBanTextSecondary,
        )
        Text("查看", color = ZhiBanTerracotta, fontWeight = FontWeight.SemiBold)
    }
}

@Composable fun MessageList(
    state: AgentConversationUiState,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onResume: () -> Unit,
    onNavigateToSettings: () -> Unit = {},
    onCopyAssistant: () -> Unit = {},
    onPositiveFeedback: () -> Unit = {},
    onNegativeFeedback: () -> Unit = {},
    onReadAssistant: () -> Unit = {},
    onShareAssistant: () -> Unit = {},
    onUndo: () -> Unit = {},
    feedbackEnabled: Boolean = true,
    userAvatarBytes: ByteArray? = null,
    userAvatarLabel: String = "我",
) {
    val listState = rememberLazyListState()
    val scrollScope = androidx.compose.runtime.rememberCoroutineScope()
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val isNearBottom by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
            shouldAutoScrollToLatest(layout.totalItemsCount, lastVisible)
        }
    }
    val generatedArtifacts = state.artifacts.filter(AgentArtifactUi::isUserVisibleOutput)
    val persistedCurrentUserTurn = state.hasPersistedCurrentTurn("user")
    val persistedCurrentAssistantTurn = state.hasPersistedCurrentTurn("assistant")
    val pendingUserMessage = state.userMessage?.takeUnless { persistedCurrentUserTurn }
    val latestAssistant = state.messages.lastOrNull { it.role == "assistant" }?.text
    val pendingAssistantMessage = state.assistantMessage?.takeUnless {
        persistedCurrentAssistantTurn || it == latestAssistant
    }
    val showPlanningRow = state.stage == AgentConversationStage.PLANNING
    val showExecutingRow = state.stage == AgentConversationStage.EXECUTING &&
        !state.showPlan && state.assistantMessage.isNullOrBlank()
    val latestConversationItemIndex = state.messages.lastIndex +
        listOfNotNull(
            pendingUserMessage,
            true.takeIf { showPlanningRow || showExecutingRow },
            pendingAssistantMessage,
        ).size
    var readerWasNearBottomBeforeIme by remember {
        androidx.compose.runtime.mutableStateOf(true)
    }
    var feedbackSelection by remember(state.assistantMessage, state.messages.lastOrNull()?.turnId) {
        androidx.compose.runtime.mutableStateOf<Boolean?>(null)
    }
    LaunchedEffect(isNearBottom, imeVisible) {
        if (!imeVisible) readerWasNearBottomBeforeIme = isNearBottom
    }
    LaunchedEffect(imeVisible) {
        if (imeVisible && readerWasNearBottomBeforeIme && latestConversationItemIndex >= 0) {
            // Let the IME inset resize the list before anchoring the newest content.
            repeat(2) { androidx.compose.runtime.withFrameNanos { } }
            listState.scrollToItem(latestConversationItemIndex)
        }
    }
    LaunchedEffect(
        state.messages.size,
        state.userMessage,
        state.assistantMessage,
        state.stage,
        state.showPlan,
    ) {
        // Wait until LazyColumn has measured the newly added reply/action row, then
        // keep the active turn visible. Without this, attachment replies can finish
        // below the fold and look as though image/file recognition never ran.
        androidx.compose.runtime.withFrameNanos { }
        val count = listState.layoutInfo.totalItemsCount
        if (count > 0 && isNearBottom) {
            listState.animateScrollToItem(count - 1)
        }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 20.dp),
        ) {
            items(state.messages, key = AgentConversationMessageUi::turnId) { message ->
                if (message.role == "user") {
                    UserMessageBubble(message.text, userAvatarBytes, userAvatarLabel)
                } else {
                    AssistantMessageBubble(message.text)
                }
            }
            pendingUserMessage?.let {
                item { UserMessageBubble(it, userAvatarBytes, userAvatarLabel) }
            }
            if (showPlanningRow) {
                item {
                    Column {
                        AssistantMessageBubble("知伴正在思考…")
                        Row {
                            if (state.canCancel) TextButton(onClick = onCancel) { Text("取消") }
                            if (state.canResume) TextButton(onClick = onResume) { Text("继续") }
                        }
                    }
                }
            }
            if (showExecutingRow) {
                item {
                    Column {
                        AssistantMessageBubble("知伴正在完成操作…")
                        Row {
                            if (state.canCancel) TextButton(onClick = onCancel) { Text("取消") }
                            if (state.canResume) TextButton(onClick = onResume) { Text("继续") }
                        }
                    }
                }
            }
            pendingAssistantMessage?.let { item { AssistantMessageBubble(it) } }
            if (state.showPlan) {
                state.plan?.let {
                    item { AgentPlanCard(it, state.stage, onConfirm, onReject, onCancel) }
                }
            }
            if (state.stage in
                setOf(
                    AgentConversationStage.FAILED_RETRYABLE,
                    AgentConversationStage.FAILED_FINAL,
                    AgentConversationStage.REJECTED,
                    AgentConversationStage.CANCELLED,
                )
            ) {
                item {
                    ToolResultCard(
                        state.stage,
                        state.safeMessage,
                        state.safeFailureCode,
                        state.isCredentialMissing,
                        onRetry,
                        onNavigateToSettings,
                    )
                }
            }
            if (generatedArtifacts.isNotEmpty()) {
                item {
                    GeneratedArtifactsCard(generatedArtifacts)
                }
            }
            if (state.stage == AgentConversationStage.SUCCEEDED) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val copyIndex = if (state.canUndo) 1 else 0
                        val speakIndex = copyIndex + 1 + if (feedbackEnabled) 2 else 0
                        val compactActionCount = speakIndex + 1
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (state.canUndo) {
                                ReplyVectorAction(
                                    Icons.AutoMirrored.Outlined.Undo,
                                    "撤销刚才的更改",
                                    compactReplyIconOffset(0, compactActionCount),
                                    onUndo,
                                )
                            }
                            ReplyAction(
                                ReplyGlyph.COPY,
                                "复制",
                                iconOffset = compactReplyIconOffset(copyIndex, compactActionCount),
                                onClick = onCopyAssistant,
                            )
                            if (feedbackEnabled) {
                                ReplyAction(
                                    glyph = ReplyGlyph.POSITIVE,
                                    label = "有帮助",
                                    state = feedbackActionState(feedbackSelection, positive = true),
                                    iconOffset = compactReplyIconOffset(copyIndex + 1, compactActionCount),
                                ) {
                                    if (feedbackSelection == null) {
                                        feedbackSelection = true
                                        onPositiveFeedback()
                                    }
                                }
                                ReplyAction(
                                    glyph = ReplyGlyph.NEGATIVE,
                                    label = "需改进",
                                    state = feedbackActionState(feedbackSelection, positive = false),
                                    iconOffset = compactReplyIconOffset(copyIndex + 2, compactActionCount),
                                ) {
                                    if (feedbackSelection == null) {
                                        feedbackSelection = false
                                        onNegativeFeedback()
                                    }
                                }
                            }
                            ReplyAction(
                                ReplyGlyph.SPEAK,
                                "朗读",
                                iconOffset = compactReplyIconOffset(speakIndex, compactActionCount),
                                onClick = onReadAssistant,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        ReplyAction(ReplyGlyph.SHARE, "分享", onClick = onShareAssistant)
                    }
                }
            }
        }
        ScrollToBottomFAB(
            visible = listState.canScrollForward,
            modifier = Modifier.align(Alignment.BottomEnd),
            onClick = {
                scrollScope.launch {
                    val count = listState.layoutInfo.totalItemsCount
                    if (count >
                        0
                    ) {
                        listState.animateScrollToItem(count - 1)
                    }
                }
            },
        )
    }
}

private fun AgentConversationUiState.hasPersistedCurrentTurn(role: String): Boolean {
    val runId = runtimeRunId ?: return false
    val expectedTurnId = "turn-$runId-$role"
    return messages.any { message -> message.role == role && message.turnId == expectedTurnId }
}

@Composable
private fun GeneratedArtifactsCard(artifacts: List<AgentArtifactUi>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(ZhiBanRadius.Card),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = ZhiBanSpacing.Lg, vertical = ZhiBanSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Sm),
        ) {
            Text(
                "知伴生成的文件",
                style = MaterialTheme.typography.labelLarge,
                color = ZhiBanTextSecondary,
            )
            artifacts.forEach { artifact ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.AttachFile,
                        contentDescription = null,
                        modifier = Modifier.size(ZhiBanIconSize.Field),
                        tint = ZhiBanTextSecondary,
                    )
                    Spacer(Modifier.width(ZhiBanSpacing.Sm))
                    Text(
                        artifact.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ZhiBanTextPrimary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private enum class ReplyActionState { DEFAULT, SELECTED, DISABLED }

private fun feedbackActionState(selection: Boolean?, positive: Boolean): ReplyActionState = when {
    selection == null -> ReplyActionState.DEFAULT
    selection == positive -> ReplyActionState.SELECTED
    else -> ReplyActionState.DISABLED
}

private fun compactReplyIconOffset(index: Int, count: Int): Dp = (((count - 1) - (2 * index)) * 3).dp

@Composable
private fun ReplyAction(glyph: ReplyGlyph, label: String, state: ReplyActionState = ReplyActionState.DEFAULT, iconOffset: Dp = 0.dp, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = state != ReplyActionState.DISABLED,
        modifier = Modifier.size(ZhiBanIconContainer.TouchTarget),
    ) {
        val tint = when {
            state == ReplyActionState.SELECTED -> AgentAccent
            state == ReplyActionState.DEFAULT -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .82f)
            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .30f)
        }
        ZhiBanReplyIcon(
            glyph = glyph,
            label = label,
            color = tint,
            modifier = Modifier.offset(x = iconOffset).size(ZhiBanIconSize.Action),
        )
    }
}

@Composable
private fun ReplyVectorAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, iconOffset: Dp = 0.dp, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(ZhiBanIconContainer.TouchTarget),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .82f),
            modifier = Modifier.offset(x = iconOffset).size(ZhiBanIconSize.Action),
        )
    }
}

@Composable fun UserMessageBubble(text: String, avatarBytes: ByteArray? = null, avatarLabel: String = "我") = Row(
    Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.Top,
) {
    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
        Text(
            text,
            Modifier.widthIn(max = 320.dp)
                .background(ZhiBanTerracottaSoft, RoundedCornerShape(ZhiBanRadius.Input))
                .padding(horizontal = ZhiBanSpacing.Lg, vertical = 11.dp),
            color = ZhiBanTextPrimary,
        )
    }
    Spacer(Modifier.width(10.dp))
    UserConversationAvatar(avatarBytes, avatarLabel)
}

@Composable
private fun UserConversationAvatar(avatarBytes: ByteArray?, fallbackLabel: String) {
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(ZhiBanTerracottaSoft)
            .semantics { contentDescription = "我的头像" },
        contentAlignment = Alignment.Center,
    ) {
        if (avatarBytes != null) {
            coil.compose.AsyncImage(
                model = avatarBytes,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                fallbackLabel,
                color = ZhiBanTerracotta,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable fun AssistantMessageBubble(text: String) = Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
    ConversationAvatar("知", ZhiBanTerracotta, MaterialTheme.colorScheme.onPrimary)
    Spacer(Modifier.width(ZhiBanSpacing.Sm))
    AgentRichResponse(text, Modifier.weight(1f).padding(top = 3.dp))
}

@Composable
private fun ConversationAvatar(label: String, background: Color, foreground: Color) {
    Box(
        Modifier.size(28.dp).clip(CircleShape).background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = foreground,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable fun AgentPlanCard(plan: AgentPlanUi, stage: AgentConversationStage, onConfirm: () -> Unit, onReject: () -> Unit, onCancel: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().semantics {
            liveRegion = LiveRegionMode.Polite
        },
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(ZhiBanRadius.Card),
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                if (stage ==
                    AgentConversationStage.EXECUTING
                ) {
                    "正在执行计划"
                } else {
                    "计划：${plan.title}"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = ZhiBanTextPrimary,
            )
            if (plan.subject.isNotBlank() &&
                plan.subject != plan.title
            ) {
                Spacer(Modifier.height(12.dp))
                Text(plan.subject, fontWeight = FontWeight.SemiBold, color = ZhiBanTextPrimary)
            }
            if (plan.schedule.isNotBlank()) Text(plan.schedule, color = ZhiBanTextSecondary)
            if (plan.reminder.isNotBlank()) Text(plan.reminder, color = ZhiBanTextSecondary)
            if (plan.details.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    plan.details,
                    Modifier.fillMaxWidth().background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(ZhiBanRadius.Medium),
                    ).padding(ZhiBanSpacing.Md),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ZhiBanTextPrimary,
                )
            }
            if (plan.recipient.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text("收件人", style = MaterialTheme.typography.labelMedium, color = ZhiBanTextSecondary)
                Text(plan.recipient, style = MaterialTheme.typography.bodyLarge, color = ZhiBanTextPrimary)
            }
            if (plan.message.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text("将发送", style = MaterialTheme.typography.labelMedium, color = ZhiBanTextSecondary)
                Text(
                    plan.message,
                    Modifier.fillMaxWidth().background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(ZhiBanRadius.Medium),
                    ).padding(ZhiBanSpacing.Md),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ZhiBanTextPrimary,
                )
                Spacer(Modifier.height(8.dp))
                Text("确认后会打开目标应用，仍需由你完成最后发送。", style = MaterialTheme.typography.bodySmall, color = ZhiBanTextSecondary)
            }
            Spacer(Modifier.height(16.dp))
            when (stage) {
                AgentConversationStage.AWAITING_CONFIRMATION -> Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onConfirm,
                        colors = ButtonDefaults.buttonColors(containerColor = ZhiBanTerracotta),
                    ) {
                        Text("确认执行")
                    }
                    TextButton(onClick = onReject) { Text("拒绝") }
                }

                AgentConversationStage.EXECUTING -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = ZhiBanTerracotta)
                    Spacer(Modifier.width(10.dp))
                    Text("正在安全写入，请稍候…", Modifier.weight(1f), color = ZhiBanTextSecondary)
                    TextButton(onClick = onCancel) { Text("取消") }
                }

                else -> Unit
            }
        }
    }
}

@Composable fun ToolResultCard(
    stage: AgentConversationStage,
    safeMessage: String?,
    safeFailureCode: String? = null,
    isCredentialMissing: Boolean = false,
    onRetry: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
) {
    val (title, tint) = when (stage) {
        AgentConversationStage.SUCCEEDED -> "回复完成" to SuccessText

        AgentConversationStage.FAILED_RETRYABLE ->
            "暂时无法完成" to
                WarningText

        AgentConversationStage.FAILED_FINAL -> "无法完成这次操作" to FailureText

        AgentConversationStage.REJECTED ->
            "已拒绝" to
                Gray500

        else -> "已取消" to Gray500
    }
    // B1 followup (per architect 693): differentiate CREDENTIAL_MISSING
    // from real PROVIDER_UNREACHABLE so the user can self-recover via
    // settings instead of an ineffective retry.
    val credentialHint = if (isCredentialMissing &&
        stage == AgentConversationStage.FAILED_FINAL
    ) {
        "前往 我的 → 智能体设置 → 大模型连接 完成配置"
    } else {
        null
    }
    Row(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(tint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("!", color = tint, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(top = 2.dp, end = 8.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = tint)
            credentialHint?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = ZhiBanTextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            safeMessage?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = ZhiBanTextSecondary)
            }
            if (stage == AgentConversationStage.FAILED_RETRYABLE || isCredentialMissing) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isCredentialMissing) {
                        OutlinedButton(onClick = onNavigateToSettings) { Text("去设置") }
                    } else {
                        OutlinedButton(onClick = onRetry) { Text("重试") }
                    }
                }
            }
        }
    }
}
