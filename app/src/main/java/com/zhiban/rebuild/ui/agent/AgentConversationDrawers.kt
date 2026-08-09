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
internal fun ConversationHistoryDialog(
    items: List<com.zhiban.rebuild.runtime.store.ConversationSummary>,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pendingDeleteId by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(ZhiBanRadius.Dialog),
            shadowElevation = 10.dp,
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text(
                    "对话历史",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = ZhiBanTextPrimary,
                )
                Spacer(Modifier.height(16.dp))
                if (items.isEmpty()) {
                    Text("还没有历史对话", color = ZhiBanTextSecondary)
                } else {
                    LazyColumn(
                        Modifier.fillMaxWidth().heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(items, key = { it.sessionId }) { item ->
                            Surface(
                                modifier = Modifier.fillMaxWidth().clickable { onOpen(item.sessionId) },
                                color = ZhiBanWarmBackground,
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(
                                        start = 16.dp,
                                        top = 12.dp,
                                        bottom = 12.dp,
                                        end = 6.dp,
                                    ),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            item.preview,
                                            fontWeight = FontWeight.SemiBold,
                                            color = ZhiBanTextPrimary,
                                            maxLines = 2,
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            "历史对话",
                                            color = ZhiBanTextSecondary,
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                    IconButton(onClick = { pendingDeleteId = item.sessionId }) {
                                        Icon(
                                            Icons.Outlined.Delete,
                                            "删除对话",
                                            tint = ZhiBanTextSecondary,
                                            modifier = Modifier.size(ZhiBanIconSize.Action),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("关闭") }
            }
        }
    }
    pendingDeleteId?.let { sessionId ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("删除这段对话？") },
            text = { Text("删除后无法恢复。") },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) { Text("取消") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteId = null
                        onDelete(sessionId)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("删除")
                }
            },
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
        )
    }
}

@Composable fun MoreDrawer(
    onDismiss: () -> Unit,
    onOpenHistory: () -> Unit,
    onNewConversation: () -> Unit = {
    },
    onNavigateToSettings: () -> Unit,
) {
    // Per architect 759 派单 H: ⋯ 抽屉 = 对话历史 + 设置入口.
    // ModalBottomSheet with ZhiBan surface (米色, 16dp 圆角).
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .padding(horizontal = 20.dp)
                    .clickable { /* swallow click */ },
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(20.dp),
                shadowElevation = 8.dp,
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(
                        "更多",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = ZhiBanTextPrimary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                    androidx.compose.material3.HorizontalDivider(
                        color = ZhiBanTextSecondary.copy(alpha = 0.2f),
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onNewConversation),
                        color = Color.Transparent,
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Outlined.Edit,
                                contentDescription = null,
                                tint = ZhiBanTextSecondary,
                                modifier = Modifier.size(ZhiBanIconSize.Leading),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text("新建对话", color = ZhiBanTextPrimary, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    androidx.compose.material3.HorizontalDivider(color = ZhiBanTextSecondary.copy(alpha = 0.1f))
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenHistory),
                        color = Color.Transparent,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Outlined.History,
                                contentDescription = null,
                                tint = ZhiBanTextSecondary,
                                modifier = Modifier.size(ZhiBanIconSize.Leading),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text("对话历史", color = ZhiBanTextPrimary, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    androidx.compose.material3.HorizontalDivider(
                        color = ZhiBanTextSecondary.copy(alpha = 0.1f),
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable {
                            onDismiss()
                            onNavigateToSettings()
                        },
                        color = Color.Transparent,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Outlined.Settings,
                                contentDescription = null,
                                tint = ZhiBanTextSecondary,
                                modifier = Modifier.size(ZhiBanIconSize.Leading),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text("设置", color = ZhiBanTextPrimary, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }
}
