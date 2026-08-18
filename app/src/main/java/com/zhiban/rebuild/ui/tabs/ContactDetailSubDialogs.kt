package com.zhiban.rebuild.ui.tabs

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.provider.Settings
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhiban.rebuild.data.agent.RelationshipEventParticipantInput
import com.zhiban.rebuild.data.calllog.CallRecordEntity
import com.zhiban.rebuild.data.contact.ContactAliasEntity
import com.zhiban.rebuild.data.contact.ContactEnrichmentCandidateEntity
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.ContactMergeLinkEntity
import com.zhiban.rebuild.data.contact.ContactPlatformIdentityEntity
import com.zhiban.rebuild.data.contact.RelationshipEdgeEntity
import com.zhiban.rebuild.data.contact.RelationshipEventWithParticipants
import com.zhiban.rebuild.data.contact.RelationshipPersonIds
import com.zhiban.rebuild.data.contact.SystemContactCandidate
import com.zhiban.rebuild.data.contact.SystemContactWriteIntent
import com.zhiban.rebuild.data.crm.CrmOpportunityEntity
import com.zhiban.rebuild.data.notification.NotificationCandidateEntity
import com.zhiban.rebuild.data.notification.OutgoingMessageAccessibilityService
import com.zhiban.rebuild.data.notification.ScheduleInsight
import com.zhiban.rebuild.runtime.context.FactEntity
import com.zhiban.rebuild.runtime.input.asr.CloudAsrAvailability
import com.zhiban.rebuild.runtime.personalization.UserProfile
import com.zhiban.rebuild.ui.components.ZhiBanChip
import com.zhiban.rebuild.ui.components.ZhiBanDialogHost
import com.zhiban.rebuild.ui.components.ZhiBanPrimaryTabHeader
import com.zhiban.rebuild.ui.components.ZhiBanSearchField
import com.zhiban.rebuild.ui.components.ZhiBanSegmentedControl
import com.zhiban.rebuild.ui.components.ZhiBanTabBottomSpacer
import com.zhiban.rebuild.ui.components.ZhiBanTabHorizontalPadding
import com.zhiban.rebuild.ui.components.ZhiBanTabTopPadding
import com.zhiban.rebuild.ui.components.ZhiBanTaskDialog
import com.zhiban.rebuild.ui.components.zhiBanCardSurface
import com.zhiban.rebuild.ui.settings.AutoWriteViewModel
import com.zhiban.rebuild.ui.theme.DangerRed
import com.zhiban.rebuild.ui.theme.DateFormats
import com.zhiban.rebuild.ui.theme.ZhiBanCard
import com.zhiban.rebuild.ui.theme.ZhiBanDivider
import com.zhiban.rebuild.ui.theme.ZhiBanIconContainer
import com.zhiban.rebuild.ui.theme.ZhiBanIconSize
import com.zhiban.rebuild.ui.theme.ZhiBanRadius
import com.zhiban.rebuild.ui.theme.ZhiBanSize
import com.zhiban.rebuild.ui.theme.ZhiBanSpacing
import com.zhiban.rebuild.ui.theme.ZhiBanTerracotta
import com.zhiban.rebuild.ui.theme.ZhiBanTextPrimary
import com.zhiban.rebuild.ui.theme.ZhiBanTextSecondary
import com.zhiban.rebuild.ui.theme.ZhiBanWarmBackground
import com.zhiban.rebuild.ui.theme.ZhiBanWarmCanvas
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Composable
internal fun ContactMergeReviewDialog(suggestion: ContactMergeSuggestion, onDismiss: () -> Unit, onConfirm: (String, String, (String?) -> Unit) -> Unit) {
    fun completeness(contact: ContactEntity): Int = listOf(
        contact.phone,
        contact.email,
        contact.wechatId,
        contact.company,
        contact.title,
        contact.note,
    ).count { !it.isNullOrBlank() }
    var canonicalId by remember(suggestion) {
        mutableStateOf(
            if (completeness(suggestion.first) >= completeness(suggestion.second)) {
                suggestion.first.contactId
            } else {
                suggestion.second.contactId
            },
        )
    }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    ZhiBanDialogHost(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(ZhiBanRadius.Dialog)).background(RelationSurface).padding(20.dp)) {
            MergeDialogHeader(reason = suggestion.reason, onDismiss = onDismiss)
            Spacer(Modifier.height(14.dp))
            Spacer(Modifier.height(14.dp))
            Text(
                "选择合并后保留显示的主资料",
                color = RelationInk,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(8.dp))
            listOf(suggestion.first, suggestion.second).forEach { contact ->
                MergeContactChoiceRow(
                    contact = contact,
                    selected = canonicalId == contact.contactId,
                    onSelect = {
                        canonicalId = contact.contactId
                        error = null
                    },
                )
                Spacer(Modifier.height(6.dp))
            }
            MergeConfirmFooter(
                saving = saving,
                error = error,
                onConfirmClick = {
                    val sourceId = if (canonicalId == suggestion.first.contactId) suggestion.second.contactId else suggestion.first.contactId
                    saving = true
                    onConfirm(canonicalId, sourceId) {
                        saving = false
                        error = it
                    }
                },
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
internal fun ContactIdentityEditorDialog(
    contact: ContactEntity,
    onDismiss: () -> Unit,
    onSaveAlias: (String, (String?) -> Unit) -> Unit,
    onSavePlatform: (String, String, (String?) -> Unit) -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf("ALIAS") }
    var platform by rememberSaveable { mutableStateOf("WECHAT") }
    var value by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    ZhiBanDialogHost(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(ZhiBanRadius.Dialog)).background(RelationSurface).padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.Close, "关闭")
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "添加身份信息",
                        color = RelationInk,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "帮助知伴认出 ${contact.displayName}",
                        color = RelationMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            ZhiBanSegmentedControl(
                options = listOf("常用称呼", "社交账号"),
                selectedIndex = if (mode == "ALIAS") 0 else 1,
                onSelected = { index ->
                    mode = if (index == 0) "ALIAS" else "PLATFORM"
                    value = ""
                    error = null
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (mode == "PLATFORM") {
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    listOf(
                        "WECHAT" to "微信",
                        "WE_COM" to "企业微信",
                        "FEISHU" to "飞书",
                        "DINGTALK" to "钉钉",
                        "DOUYIN" to "抖音",
                        "XIAOHONGSHU" to "小红书",
                        "QQ" to "QQ",
                        "WEIBO" to "微博",
                        "OTHER" to "其他",
                    ).forEach { (id, label) ->
                        ZhiBanChip(
                            text = label,
                            selected = platform == id,
                            color = RelationAccent,
                            onClick = {
                                platform = id
                                error = null
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = value,
                onValueChange = {
                    value = it
                    error = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (mode == "ALIAS") "称呼" else "账号或用户名") },
                placeholder = { Text(if (mode == "ALIAS") "例如：王老师" else "输入对方在该平台的账号") },
                singleLine = true,
                enabled = !saving,
                shape = RoundedCornerShape(ZhiBanRadius.Input),
            )
            Text(
                if (mode == "ALIAS") {
                    "称呼可用于搜索和聊天中的身份识别"
                } else {
                    "只保存在这台设备，用于把不同来源归到同一个联系人"
                },
                color = RelationMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            error?.let {
                Text(
                    it,
                    color = RelationDanger,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = {
                    if (value.isBlank()) {
                        error = if (mode == "ALIAS") "请输入常用称呼" else "请输入账号或用户名"
                    } else {
                        saving = true
                        val result: (String?) -> Unit = {
                            saving = false
                            error = it
                        }
                        if (mode == "ALIAS") {
                            onSaveAlias(value, result)
                        } else {
                            onSavePlatform(platform, value, result)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(ZhiBanSize.Control),
                enabled = !saving,
                colors = ButtonDefaults.buttonColors(containerColor = RelationInk),
                shape = RoundedCornerShape(ZhiBanRadius.Card),
            ) {
                if (saving) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                } else {
                    Text("保存")
                }
            }
        }
    }
}

@Composable
internal fun ContactFactEditorDialog(contact: ContactEntity, onDismiss: () -> Unit, onSave: (String, String, (String?) -> Unit) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf("CONTACT_MEMORY") }
    var error by remember { mutableStateOf<String?>(null) }
    ZhiBanDialogHost(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(ZhiBanRadius.Dialog)).background(RelationSurface).padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Close, "关闭") }
                Text(
                    "知伴要记住什么",
                    color = RelationInk,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text("关于 ${contact.displayName}", color = RelationMuted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                listOf(
                    "CONTACT_MEMORY" to "基本信息",
                    "IMPORTANT_DATE" to "重要日期",
                    "COMMUNICATION_PREFERENCE" to "沟通偏好",
                    "CURRENT_MATTER" to "当前事项",
                ).forEach { (value, label) ->
                    ZhiBanChip(
                        text = label,
                        selected = type == value,
                        color = RelationAccent,
                        onClick = { type = value },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    error = null
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("例如：通常使用微信沟通") },
                minLines = 3,
                maxLines = 6,
            )
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = RelationDanger, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = {
                    if (text.isBlank()) {
                        error = "请输入需要记住的内容"
                    } else {
                        onSave(text, type) { error = it }
                    }
                },
                Modifier.fillMaxWidth().height(ZhiBanSize.Control),
                colors = ButtonDefaults.buttonColors(containerColor = RelationInk),
                shape = RoundedCornerShape(ZhiBanRadius.Card),
            ) { Text("确认保存") }
        }
    }
}

@Composable
private fun MergeDialogHeader(reason: String, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Rounded.Close, "关闭")
        }
        Column(Modifier.weight(1f)) {
            Text(
                "确认是否为同一个人",
                color = RelationInk,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(reason, color = RelationMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MergeContactChoiceRow(contact: ContactEntity, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(ZhiBanRadius.Card))
            .background(if (selected) RelationSoft else Color.Transparent)
            .clickable(onClick = onSelect)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(if (selected) RelationInk else RelationSoft),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                contact.displayName.take(1),
                color = if (selected) MaterialTheme.colorScheme.onPrimary else RelationInk,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                contact.displayName,
                color = RelationInk,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                listOfNotNull(contact.phone, contact.company, contact.wechatId).take(2).joinToString(" · ")
                    .ifBlank { if (selected) "将作为主资料" else "资料较少" },
                color = RelationMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) Text("主资料", color = RelationInk, style = MaterialTheme.typography.labelMedium)
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun MergeConfirmFooter(
    saving: Boolean,
    error: String?,
    onConfirmClick: () -> Unit,
    onDismiss: () -> Unit,
) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(ZhiBanRadius.Card)).background(RelationSoft).padding(14.dp)) {
            Text(
                "合并后只显示一个联系人，原始资料、记忆和关系不会删除。你可以随时在联系人详情中恢复。",
                color = RelationMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        error?.let {
            Text(
                it,
                color = RelationDanger,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = onConfirmClick,
            Modifier.fillMaxWidth().height(ZhiBanSize.Control),
            enabled = !saving,
            colors = ButtonDefaults.buttonColors(containerColor = RelationInk),
            shape = RoundedCornerShape(ZhiBanRadius.Card),
        ) {
            if (saving) {
                CircularProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
            } else {
                Text("确认合并")
            }
        }
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth(), enabled = !saving) {
            Text("不是同一个人", color = RelationMuted)
        }
}


