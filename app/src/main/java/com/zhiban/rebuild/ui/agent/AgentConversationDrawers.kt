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
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.zhiban.rebuild.ui.components.ZhiBanAlertDialog
import com.zhiban.rebuild.ui.components.ZhiBanBottomSheet
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
    com.zhiban.rebuild.ui.components.ZhiBanTaskDialog(
        onDismissRequest = onDismiss,
        maxWidth = 480.dp,
        maxHeight = 560.dp,
    ) {
        com.zhiban.rebuild.ui.components.ZhiBanDialogHeader("对话历史", onDismiss)
        Spacer(Modifier.height(ZhiBanSpacing.Md))
        if (items.isEmpty()) {
            Text("还没有历史对话", color = ZhiBanTextSecondary)
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Sm),
            ) {
                items(items, key = { it.sessionId }) { item ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { onOpen(item.sessionId) },
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .42f),
                        shape = RoundedCornerShape(ZhiBanRadius.Card),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(
                                start = ZhiBanSpacing.Lg,
                                top = ZhiBanSpacing.Md,
                                bottom = ZhiBanSpacing.Md,
                                end = ZhiBanSpacing.Xs,
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.preview,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = ZhiBanTextPrimary,
                                    maxLines = 2,
                                )
                                Spacer(Modifier.height(ZhiBanSpacing.Xs))
                                Text(
                                    "历史对话",
                                    color = ZhiBanTextSecondary,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            IconButton(
                                onClick = { pendingDeleteId = item.sessionId },
                                modifier = Modifier.size(ZhiBanIconContainer.TouchTarget),
                            ) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    "删除对话",
                                    tint = ZhiBanTextSecondary,
                                    modifier = Modifier.size(ZhiBanIconSize.Inline),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    pendingDeleteId?.let { sessionId ->
        ZhiBanAlertDialog(
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
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreDrawer(
    onDismiss: () -> Unit,
    onOpenHistory: () -> Unit,
    onNewConversation: () -> Unit = {
    },
    onNavigateToSettings: () -> Unit,
) {
    ZhiBanBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ZhiBanSpacing.PageHorizontal)
                .padding(bottom = ZhiBanSpacing.Xxl),
        ) {
            Text(
                "更多",
                style = MaterialTheme.typography.titleLarge,
                color = ZhiBanTextPrimary,
                modifier = Modifier.padding(bottom = ZhiBanSpacing.Sm),
            )
            DrawerAction(Icons.Outlined.Edit, "新建对话", onNewConversation)
            DrawerAction(Icons.Outlined.History, "对话历史", onOpenHistory)
            DrawerAction(Icons.Outlined.Settings, "设置") {
                onDismiss()
                onNavigateToSettings()
            }
        }
    }
}

@Composable
private fun DrawerAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = ZhiBanSize.ListRow)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(ZhiBanIconSize.Leading),
        )
        Spacer(Modifier.width(ZhiBanSpacing.Md))
        Text(label, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
    }
}
