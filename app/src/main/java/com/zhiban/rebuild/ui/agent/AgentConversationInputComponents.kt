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

internal fun shouldAutoScrollToLatest(totalItems: Int, lastVisibleIndex: Int): Boolean = totalItems == 0 || lastVisibleIndex >= totalItems - 2

@Composable fun MessageInput(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onSend: (String) -> Unit,
    attachmentPrompt: String? = null,
    isRecording: Boolean = false,
    onPickImage: () -> Unit = {},
    onCapturePhoto: () -> Unit = {},
    onPickFile: () -> Unit = {},
    onToggleRecording: () -> Unit = {},
    onStartRealtimeVoice: () -> Unit = {},
    // Per architect 695: inline model label between text input and mic
    // that opens a single-section popup (models + levels).
    inlineModelLabel: String = "M2.7 智能/高",
    onModelLabelClick: () -> Unit = {},
    onLevelSelect: (String) -> Unit = {},
    onManagePlugins: () -> Unit = {},
) {
    val visibleModelLabel = compactInlineModelLabel(inlineModelLabel)
    var attachmentSheetOpen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var inputFocused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val inputFocusRequester = androidx.compose.runtime.remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val canSend = value.isNotBlank() || !attachmentPrompt.isNullOrBlank()
    LaunchedEffect(inputFocused) {
        if (inputFocused) {
            inputFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }
    val micControl: @Composable () -> Unit = {
        Box(
            Modifier.size(ZhiBanSize.TouchTarget).clip(CircleShape).clickable(enabled = enabled) {
                onToggleRecording()
            },
            contentAlignment = Alignment.Center,
        ) {
            if (isRecording) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Box(
                        Modifier.size(
                            width = 3.dp,
                            height = 8.dp,
                        ).background(ZhiBanTerracotta, RoundedCornerShape(2.dp)),
                    )
                    Box(
                        Modifier.size(
                            width = 3.dp,
                            height = 14.dp,
                        ).background(ZhiBanTerracotta, RoundedCornerShape(2.dp)),
                    )
                    Box(
                        Modifier.size(
                            width = 3.dp,
                            height = 10.dp,
                        ).background(ZhiBanTerracotta, RoundedCornerShape(2.dp)),
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Outlined.Mic,
                    contentDescription = "开始录音",
                    tint = ZhiBanTextPrimary,
                    modifier = Modifier.size(ZhiBanIconSize.Action),
                )
            }
        }
    }
    val primaryAction: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .size(ZhiBanSize.TouchTarget)
                .semantics { contentDescription = if (canSend) "发送" else "语音输入" }
                .clip(CircleShape)
                .clickable(enabled = enabled) {
                    if (canSend) {
                        onSend(value.trim().ifBlank { requireNotNull(attachmentPrompt) })
                        inputFocused = false
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                    } else {
                        onStartRealtimeVoice()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = AgentAccent,
                shadowElevation = 2.dp,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (canSend) {
                        Icon(
                            Icons.Outlined.ArrowUpward,
                            "发送",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(ZhiBanIconSize.Field),
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            listOf(7.dp, 12.dp, 16.dp, 10.dp).forEach { height ->
                                Box(
                                    Modifier
                                        .size(width = 2.dp, height = height)
                                        .background(MaterialTheme.colorScheme.onPrimary, RoundedCornerShape(2.dp)),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    val attachmentControl: @Composable () -> Unit = {
        Box(
            Modifier
                .size(ZhiBanSize.TouchTarget)
                .clip(CircleShape)
                .clickable(enabled = enabled) { attachmentSheetOpen = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = "添加附件",
                tint = ZhiBanTextPrimary,
                modifier = Modifier.size(ZhiBanIconSize.Action),
            )
        }
    }
    val collapsedInput: @Composable RowScope.() -> Unit = {
        Box(
            Modifier
                .weight(1f)
                .semantics { contentDescription = "消息输入框" }
                .clickable(enabled = enabled) { inputFocused = true }
                .height(ZhiBanSize.TouchTarget),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                value.ifEmpty { "问问知伴" },
                color = if (value.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
            )
        }
    }
    val modelControl: @Composable () -> Unit = {
        Text(
            visibleModelLabel,
            modifier = Modifier
                .semantics { contentDescription = "选择模型与思考强度" }
                .defaultMinSize(minHeight = ZhiBanSize.TouchTarget)
                .clickable(enabled = enabled) { onModelLabelClick() }
                .padding(horizontal = ZhiBanSpacing.Xs, vertical = ZhiBanSpacing.Md),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val useCompactControls = maxWidth <= 360.dp
        Surface(
            Modifier.fillMaxWidth().padding(
                start = ZhiBanSpacing.PageHorizontal,
                end = ZhiBanSpacing.PageHorizontal,
                top = ZhiBanSpacing.Sm,
                bottom = ZhiBanSpacing.Lg,
            )
                .imePadding(),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(if (inputFocused) ZhiBanRadius.Dialog else ZhiBanRadius.Full),
            tonalElevation = 0.dp,
            shadowElevation = 2.dp,
        ) {
            if (inputFocused) {
                Column(
                    Modifier.fillMaxWidth().heightIn(
                        min = 112.dp,
                        max = 208.dp,
                    ).padding(horizontal = ZhiBanSpacing.Md, vertical = ZhiBanSpacing.Sm),
                ) {
                    Box(
                        Modifier.fillMaxWidth().heightIn(
                            min = 52.dp,
                            max = 138.dp,
                        ).padding(horizontal = ZhiBanSpacing.Xs, vertical = 3.dp),
                    ) {
                        if (value.isEmpty()) Text("问问知伴", color = Gray500, style = MaterialTheme.typography.bodyLarge)
                        BasicTextField(
                            value = value,
                            onValueChange = onValueChange,
                            enabled = enabled,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = ZhiBanTextPrimary),
                            modifier = Modifier.fillMaxWidth().semantics {
                                contentDescription = "消息输入框"
                            }.focusRequester(inputFocusRequester).onFocusChanged {
                                if (it.isFocused) inputFocused = true
                            },
                            maxLines = 6,
                        )
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            attachmentSheetOpen = true
                        }, enabled = enabled, modifier = Modifier.size(ZhiBanSize.TouchTarget)) {
                            Icon(
                                Icons.Outlined.Add,
                                "添加附件",
                                tint = ZhiBanTextPrimary,
                                modifier = Modifier.size(ZhiBanIconSize.Action),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            visibleModelLabel,
                            modifier = Modifier
                                .semantics { contentDescription = "选择模型与思考强度" }
                                .defaultMinSize(minHeight = ZhiBanSize.TouchTarget)
                                .clickable(enabled = enabled) { onModelLabelClick() }
                                .padding(horizontal = ZhiBanSpacing.Sm, vertical = ZhiBanSpacing.Md),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                        )
                        micControl()
                        Spacer(Modifier.width(ZhiBanSpacing.Sm))
                        primaryAction()
                    }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().height(ZhiBanSize.BottomBar).padding(horizontal = ZhiBanSpacing.Sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Xs),
                ) {
                    attachmentControl()
                    collapsedInput()
                    if (!useCompactControls) modelControl()
                    micControl()
                    primaryAction()
                }
            }
        }
    }
    if (attachmentSheetOpen) {
        AttachmentPickerSheet(
            onDismiss = { attachmentSheetOpen = false },
            onPickImage = {
                attachmentSheetOpen = false
                onPickImage()
            },
            onCapturePhoto = {
                attachmentSheetOpen = false
                onCapturePhoto()
            },
            onPickFile = {
                attachmentSheetOpen = false
                onPickFile()
            },
            onOpenPlugins = {
                attachmentSheetOpen = false
                onManagePlugins()
            },
            onEnableSmart = {
                attachmentSheetOpen = false
                onLevelSelect("深入")
            },
        )
    }
}

internal fun attachmentPrompt(batch: AttachmentBatchUiState): String? {
    val sendable = batch.items.filter { it.phase in setOf(AttachmentPhase.SELECTED, AttachmentPhase.READY) }
    if (sendable.isEmpty()) return null
    val containsImage = sendable.any { it.modality == InputModality.IMAGE }
    val containsFile = sendable.any { it.modality == InputModality.FILE }
    return when {
        containsImage && containsFile -> "请识别图片并阅读文件，告诉我其中的重要内容。"
        containsImage -> "请识别并分析这张图片。"
        containsFile -> "请阅读并分析这个文件。"
        else -> "请分析我添加的附件。"
    }
}

private fun compactModelLabel(model: String) = when {
    model == "step-3.5-flash" -> "Step 3.5"
    else -> model
}

private fun compactInlineModelLabel(label: String): String {
    val model = label.substringBefore(' ').trim()
    val level = label.substringAfterLast('/').trim().takeIf { it != label }
        ?: label.substringAfter(' ', "").trim()
    val compactModel = when (model) {
        "step-3.5-flash" -> "3.5"
        else -> model.removeSuffix("-flash").removePrefix("step-")
    }
    return listOf(compactModel, level).filter { it.isNotBlank() && it != "智能" }.joinToString(" ")
}

@Composable
private fun AttachmentPickerSheet(
    onDismiss: () -> Unit,
    onPickImage: () -> Unit,
    onCapturePhoto: () -> Unit,
    onPickFile: () -> Unit,
    onOpenPlugins: () -> Unit,
    onEnableSmart: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().clickable(onClick = onDismiss), contentAlignment = Alignment.BottomStart) {
            Surface(
                Modifier
                    .padding(start = 22.dp, end = 16.dp, bottom = 112.dp)
                    .widthIn(max = 282.dp)
                    .fillMaxWidth()
                    .clickable { },
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(22.dp),
                shadowElevation = 10.dp,
            ) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    PickerMenuRow("相机", Icons.Outlined.CameraAlt) { onCapturePhoto() }
                    PickerMenuRow("照片", Icons.Outlined.Image) { onPickImage() }
                    PickerMenuRow("文件", Icons.Outlined.AttachFile) { onPickFile() }
                    PickerMenuRow("插件", Icons.Outlined.Extension, onClick = onOpenPlugins)
                    PickerMenuRow("智能", Icons.Outlined.AutoAwesome, selected = true, onClick = onEnableSmart)
                }
            }
        }
    }
}

@Composable
private fun PickerMenuRow(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(
                ZhiBanIconContainer.Compact,
            ).background(if (selected) ZhiBanTerracottaSoft else Gray100, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (selected) ZhiBanTerracotta else ZhiBanTextPrimary,
                modifier = Modifier.size(ZhiBanIconSize.Leading),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) ZhiBanTerracotta else ZhiBanTextPrimary,
        )
        if (selected) Text("✓", color = ZhiBanTerracotta, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
internal fun ModelPickerSheet(
    currentLabel: String,
    models: List<String>,
    levels: List<String>,
    onDismiss: () -> Unit,
    onModelSelect: (String) -> Unit,
    onLevelSelect: (String) -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().clickable(onClick = onDismiss), contentAlignment = Alignment.CenterEnd) {
            Surface(
                Modifier
                    .padding(start = 18.dp, end = 18.dp)
                    .widthIn(max = 258.dp)
                    .fillMaxWidth()
                    .clickable { },
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(22.dp),
                shadowElevation = 10.dp,
            ) {
                Column(
                    Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        "模型",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = ZhiBanTextPrimary,
                    )
                    models.forEach { model ->
                        Text(
                            model + if (currentLabel.startsWith(model)) "  ✓" else "",
                            Modifier.fillMaxWidth().clickable { onModelSelect(model) }.padding(vertical = 12.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (currentLabel.startsWith(model)) ZhiBanTextPrimary else ZhiBanTextSecondary,
                        )
                    }
                    HorizontalDivider(color = Gray500.copy(alpha = .2f))
                    Text(
                        "智能",
                        modifier = Modifier.padding(top = 3.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = ZhiBanTextSecondary,
                    )
                    levels.forEach { level ->
                        Row(
                            Modifier.fillMaxWidth().height(ZhiBanSize.TouchTarget).clickable {
                                onLevelSelect(level)
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                level,
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                                color = ZhiBanTextPrimary,
                            )
                            if (currentLabel.endsWith(level)) Text("✓", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable fun AttachmentStatusStrip(batch: AttachmentBatchUiState, onAction: (String, AttachmentAction) -> Unit) {
    if (batch.items.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        batch.items.forEach { item ->
            Row(
                Modifier.fillMaxWidth().background(
                    MaterialTheme.colorScheme.surface,
                    RoundedCornerShape(ZhiBanRadius.Medium),
                ).padding(ZhiBanSpacing.Md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        item.displayName,
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ZhiBanTextPrimary,
                    )
                    Text(
                        attachmentPhaseLabel(item),
                        style = MaterialTheme.typography.bodySmall,
                        color = ZhiBanTextSecondary,
                    )
                    if (item.phase in
                        setOf(AttachmentPhase.UPLOADING, AttachmentPhase.FINALIZING)
                    ) {
                        LinearProgressIndicator(progress = {
                            item.progress
                        }, Modifier.fillMaxWidth().padding(top = 4.dp), color = ZhiBanTerracotta)
                    }
                }
                item.actions.forEach { action ->
                    TextButton(onClick = {
                        onAction(item.attachmentId, action)
                    }) { Text(attachmentActionLabel(action)) }
                }
            }
        }
    }
}

private fun attachmentPhaseLabel(item: AttachmentUiState): String = when (item.phase) {
    AttachmentPhase.SELECTED -> if (item.modality == InputModality.FILE) "已选择 · 将识别 PDF 前 3 页" else "已选择"
    AttachmentPhase.PREFLIGHTING -> "正在检查"
    AttachmentPhase.READY -> "等待上传"
    AttachmentPhase.UPLOADING -> "上传中 ${(item.progress * 100).toInt()}%"
    AttachmentPhase.FINALIZING -> "正在完成"
    AttachmentPhase.COMPLETED -> "已完成"
    AttachmentPhase.FAILED -> item.safeMessage ?: "上传失败"
    AttachmentPhase.CANCELLING -> "正在取消"
    AttachmentPhase.CANCELLED -> "已取消"
    AttachmentPhase.URI_EXPIRED -> "文件已失效，请重新选择"
}

private fun attachmentActionLabel(action: AttachmentAction): String = when (action) {
    AttachmentAction.CANCEL -> "取消"
    AttachmentAction.RETRY -> "重试"
    AttachmentAction.DELETE -> "删除"
    AttachmentAction.RESELECT -> "重新选择"
}

@Composable
internal fun VoiceInputBar(state: TranscriptionUiState, onCancel: () -> Unit, onStop: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(30.dp),
        shadowElevation = 5.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onCancel,
                modifier = Modifier.semantics { contentDescription = "取消录音" },
            ) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = null,
                    tint = ZhiBanTextPrimary,
                    modifier = Modifier.size(ZhiBanIconSize.Action),
                )
            }
            if (state.phase == TranscriptionPhase.RECORDING) {
                LiveVoiceWaveform(
                    level = state.inputLevel,
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .semantics { contentDescription = "录音中" },
                )
            } else {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = ZhiBanTextSecondary)
                    Spacer(Modifier.width(14.dp))
                    Text("正在转录…", style = MaterialTheme.typography.titleMedium, color = ZhiBanTextSecondary)
                }
            }
            Surface(
                onClick = onStop,
                modifier = Modifier.semantics { contentDescription = "停止录音并转写" },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(17.dp).background(Gray500, RoundedCornerShape(4.dp)))
                }
            }
            Spacer(Modifier.width(8.dp))
            Surface(
                onClick = onStop,
                enabled = state.phase == TranscriptionPhase.RECORDING,
                shape = CircleShape,
                color = if (state.phase == TranscriptionPhase.RECORDING) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
            ) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.ArrowUpward,
                        "完成录音并转写",
                        tint = if (state.phase == TranscriptionPhase.RECORDING) {
                            MaterialTheme.colorScheme.surface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveVoiceWaveform(level: Float, modifier: Modifier = Modifier) {
    val animatedLevel by animateFloatAsState(
        targetValue = level.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 90),
        label = "microphone-level",
    )
    val motion by rememberInfiniteTransition(label = "voice-wave-motion").animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 820, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "voice-wave-phase",
    )
    val waveformColor = ZhiBanTerracotta.copy(alpha = 0.78f)
    Canvas(modifier) {
        val barCount = 27
        val barWidth = 3.dp.toPx()
        val gap = 2.5.dp.toPx()
        val totalWidth = barCount * barWidth + (barCount - 1) * gap
        val startX = (size.width - totalWidth) / 2f
        val center = (barCount - 1) / 2f
        val restingHeight = 4.dp.toPx()
        val availableHeight = (size.height - restingHeight).coerceAtLeast(0f)
        repeat(barCount) { index ->
            val distance = abs(index - center) / center
            val envelope = 0.56f + 0.44f * (1f - distance * distance)
            val texture =
                0.30f +
                    0.42f * abs(sin(index * 0.62f + motion)) +
                    0.28f * abs(sin(index * 1.37f - motion * 0.72f))
            val activeLevel = 0.10f + animatedLevel * 0.90f
            val barHeight = (restingHeight + availableHeight * activeLevel * envelope * texture)
                .coerceAtMost(size.height)
            val x = startX + index * (barWidth + gap)
            drawRoundRect(
                color = waveformColor,
                topLeft = Offset(x, (size.height - barHeight) / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

@Composable fun TranscriptionStatus(state: TranscriptionUiState, onRetry: () -> Unit = {}, onDelete: () -> Unit = {}) {
    when (state.phase) {
        TranscriptionPhase.IDLE -> Unit

        TranscriptionPhase.RECORDING -> Text(
            "正在录音，点按麦克风结束",
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            color = ZhiBanTerracotta,
        )

        TranscriptionPhase.TRANSCRIBING -> Text(
            state.partialText.ifBlank {
                "正在转写…"
            },
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            color = ZhiBanTextSecondary,
        )

        TranscriptionPhase.FINAL -> Text(
            "转写结果：${state.finalText}",
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            color = ZhiBanTextPrimary,
        )

        TranscriptionPhase.UPLOADING -> Text(
            "录音上传中…",
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            color = ZhiBanTextSecondary,
        )

        TranscriptionPhase.FAILED -> Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(state.safeMessage ?: "没有完成转写，录音已保留", Modifier.weight(1f), color = FailureText)
            if (state.retryable) TextButton(onClick = onRetry) { Text("重试") }
            // Realtime voice retains no recording, so offer a neutral dismiss; mic batch keeps the
            // file and can offer to delete it.
            TextButton(onClick = onDelete) { Text(if (state.originalAudioRetained) "删除录音" else "关闭") }
        }

        TranscriptionPhase.CANCELLED -> Text(
            "录音已取消",
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            color = ZhiBanTextSecondary,
        )
    }
}

@Composable fun ScrollToBottomFAB(visible: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    if (visible) {
        FloatingActionButton(
            onClick = onClick,
            modifier = modifier.padding(ZhiBanSpacing.Lg),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Icon(Icons.Outlined.KeyboardArrowDown, "滚动到最新", tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable fun PermissionRationale(permission: AgentPermissionUi, onGrant: () -> Unit) {
    val label = if (permission == AgentPermissionUi.MICROPHONE) "麦克风" else "附件"
    Row(
        Modifier.fillMaxWidth().padding(
            horizontal = ZhiBanSpacing.Md,
        ).background(ZhiBanTerracottaSoft, RoundedCornerShape(ZhiBanRadius.Card)).padding(ZhiBanSpacing.Lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("需要${label}权限才能继续", Modifier.weight(1f), color = ZhiBanTextPrimary)
        TextButton(onClick = onGrant, modifier = Modifier.defaultMinSize(minHeight = ZhiBanSize.TouchTarget)) {
            Text("授权", color = ZhiBanTerracotta, style = MaterialTheme.typography.labelLarge)
        }
    }
}
