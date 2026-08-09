package com.zhiban.rebuild.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhiban.rebuild.ui.agent.*
import com.zhiban.rebuild.ui.theme.*

private enum class PrototypeState { EMPTY, CONFIRM, EXECUTING, SUCCESS, ERROR }

@Composable
fun AgentVisualPrototypePage(state: String, onBack: () -> Unit) {
    if (state.startsWith("mm_")) {
        AgentConversationScreen(
            state = AgentConversationUiState(),
            multimodalState = multimodalFixture(state.removePrefix("mm_")),
        )
        return
    }
    val fixture = runCatching { PrototypeState.valueOf(state.uppercase()) }.getOrDefault(PrototypeState.EMPTY)
    Column(Modifier.fillMaxSize().background(ZhiBanWarmBackground).statusBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp)) {
            Text(
                "问问",
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = ZhiBanTextPrimary,
            )
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val contentWidth = if (maxWidth >= 840.dp) 720.dp else maxWidth
            Column(
                Modifier.width(contentWidth).fillMaxHeight().align(Alignment.Center).padding(
                    horizontal = if (maxWidth >=
                        600.dp
                    ) {
                        24.dp
                    } else {
                        16.dp
                    },
                    vertical = 12.dp,
                ),
            ) {
                if (fixture == PrototypeState.EMPTY) EmptyContent() else ConversationContent(fixture)
            }
        }
        Composer(enabled = fixture != PrototypeState.EXECUTING)
        PrototypeBottomBar()
    }
}

private fun multimodalFixture(key: String): MultimodalUiState {
    val verified = InputModality.entries.associateWith { ProviderCapabilityState.VERIFIED }
    val permission = when (key.removePrefix("permission_")) {
        "requestable" -> DevicePermissionState.REQUESTABLE
        "denied" -> DevicePermissionState.DENIED
        "permanent" -> DevicePermissionState.PERMANENTLY_DENIED
        "granted" -> DevicePermissionState.GRANTED
        else -> DevicePermissionState.UNKNOWN
    }
    val capability = when (key.removePrefix("capability_")) {
        "verified" -> ProviderCapabilityState.VERIFIED
        "expired" -> ProviderCapabilityState.EXPIRED
        "failed" -> ProviderCapabilityState.FAILED
        else -> ProviderCapabilityState.PROBING
    }
    val attachmentPhase = runCatching {
        AttachmentPhase.valueOf(key.removePrefix("attachment_").uppercase())
    }.getOrNull()
    val transcription = when (key) {
        "transcription_partial" -> TranscriptionUiState(TranscriptionPhase.TRANSCRIBING, partialText = "明天下午…")

        "transcription_final" -> TranscriptionUiState(
            TranscriptionPhase.FINAL,
            finalText = "明天下午三点安排会议",
            originalAudioRetained = true,
        )

        else -> TranscriptionUiState()
    }
    val attachment = attachmentPhase?.let { phase ->
        AttachmentUiState(
            attachmentId = "fixture",
            displayName = if (phase == AttachmentPhase.URI_EXPIRED) "已失效的文件.pdf" else "项目资料.pdf",
            modality = InputModality.FILE,
            phase = phase,
            bytesSent = if (phase == AttachmentPhase.UPLOADING) 45 else 0,
            totalBytes = 100,
            retryable = phase == AttachmentPhase.FAILED,
            safeMessage = if (phase == AttachmentPhase.FAILED) "文件超过当前服务限额（413）" else null,
        )
    }
    return MultimodalUiState(
        cameraPermission = permission,
        microphonePermission = permission,
        capability = if (key.startsWith("capability_")) {
            InputModality.entries.associateWith {
                capability
            }
        } else {
            verified
        },
        attachments = AttachmentBatchUiState(listOfNotNull(attachment)),
        transcription = transcription,
    )
}

@Composable private fun EmptyContent() {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(96.dp).clip(CircleShape).background(ZhiBanTerracotta.copy(alpha = .14f)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(68.dp).clip(CircleShape).background(ZhiBanTerracotta),
                contentAlignment = Alignment.Center,
            ) {
                Text("知", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "开始和知伴聊聊",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = ZhiBanTextPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Text("试试：帮我建一个日程", color = ZhiBanTextSecondary)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("创建日程", "回忆记忆", "取消上次").forEach { label ->
                Text(
                    label,
                    Modifier.background(
                        ZhiBanCard,
                        RoundedCornerShape(20.dp),
                    ).border(
                        1.dp,
                        ZhiBanTerracotta.copy(alpha = .22f),
                        RoundedCornerShape(20.dp),
                    ).padding(horizontal = 14.dp, vertical = 10.dp),
                    color = ZhiBanTerracotta,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable private fun ConversationContent(state: PrototypeState) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                "帮我安排明天上午的团队周会",
                Modifier.background(ZhiBanTerracotta, RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp)).padding(16.dp),
                color = Color.White,
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(ZhiBanTerracotta),
                contentAlignment = Alignment.Center,
            ) {
                Text("知", color = Color.White, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                when (state) {
                    PrototypeState.SUCCESS -> "已经为你处理好了。"
                    PrototypeState.ERROR -> "这次没有完成，你可以重试。"
                    else -> "我帮你整理了这些："
                },
                color = ZhiBanTextPrimary,
            )
        }
        Spacer(Modifier.height(12.dp))
        Surface(
            color = ZhiBanCard,
            shape = RoundedCornerShape(18.dp),
            shadowElevation = 2.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(18.dp)) {
                val title = when (state) {
                    PrototypeState.EXECUTING -> "正在创建日程"
                    PrototypeState.SUCCESS -> "日程创建成功"
                    PrototypeState.ERROR -> "暂时无法创建"
                    else -> "计划：创建日程"
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = when (state) {
                        PrototypeState.ERROR -> Color(0xFF9B3D32)
                        PrototypeState.SUCCESS -> Color(0xFF397A50)
                        else -> ZhiBanTextPrimary
                    },
                )
                Spacer(Modifier.height(12.dp))
                Text("团队周会", fontWeight = FontWeight.SemiBold, color = ZhiBanTextPrimary)
                Text("明天 10:00 · 60 分钟", color = ZhiBanTextSecondary)
                Text("提醒：提前 10 分钟", color = ZhiBanTextSecondary)
                Spacer(Modifier.height(18.dp))
                when (state) {
                    PrototypeState.CONFIRM -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = {
                        }, colors = ButtonDefaults.buttonColors(containerColor = ZhiBanTerracotta)) {
                            Text("确认创建")
                        }
                        OutlinedButton(onClick = {}) { Text("修改") }
                        TextButton(onClick = {}) { Text("拒绝") }
                    }

                    PrototypeState.EXECUTING -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = ZhiBanTerracotta)
                        Spacer(Modifier.width(10.dp))
                        Text("正在安全写入，请稍候…", color = ZhiBanTextSecondary)
                    }

                    PrototypeState.SUCCESS -> Button(onClick = {
                    }, colors = ButtonDefaults.buttonColors(containerColor = ZhiBanTerracotta)) { Text("查看日历") }

                    PrototypeState.ERROR -> Button(onClick = {
                    }, colors = ButtonDefaults.buttonColors(containerColor = ZhiBanTerracotta)) { Text("重试") }

                    else -> Unit
                }
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable private fun Composer(enabled: Boolean) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).imePadding(),
        shape = RoundedCornerShape(28.dp),
        color = ZhiBanCard,
        shadowElevation = 6.dp,
    ) {
        Row(
            Modifier.heightIn(min = 60.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {}) { Icon(Icons.Filled.Add, "添加附件", tint = ZhiBanTextSecondary) }
            Text("问问知伴...", Modifier.weight(1f), color = if (enabled) ZhiBanTextSecondary else Gray500)
            IconButton(onClick = {}) { Icon(Icons.Filled.Mic, "语音", tint = ZhiBanTextSecondary) }
            Box(
                Modifier.size(42.dp).clip(CircleShape).background(if (enabled) ZhiBanTerracotta else Gray500),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, "发送", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable private fun PrototypeBottomBar() {
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp).background(ZhiBanCard).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf("今日" to "日", "关系" to "联", "问问" to "问", "能力" to "能", "我的" to "我").forEach { (label, icon) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(
                        if (label ==
                            "问问"
                        ) {
                            ZhiBanTerracotta.copy(alpha = .14f)
                        } else {
                            Color.Transparent
                        },
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        icon,
                        color = if (label ==
                            "问问"
                        ) {
                            ZhiBanTerracotta
                        } else {
                            Gray500
                        },
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    label,
                    fontSize = 11.sp,
                    color = if (label ==
                        "问问"
                    ) {
                        ZhiBanTerracotta
                    } else {
                        Gray500
                    },
                )
            }
        }
    }
}
