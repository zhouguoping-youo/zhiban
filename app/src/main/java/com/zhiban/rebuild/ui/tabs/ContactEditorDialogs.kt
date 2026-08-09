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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
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
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.ContactMergeLinkEntity
import com.zhiban.rebuild.data.contact.ContactPlatformIdentityEntity
import com.zhiban.rebuild.data.contact.RelationshipEdgeEntity
import com.zhiban.rebuild.data.contact.RelationshipEventWithParticipants
import com.zhiban.rebuild.data.contact.RelationshipPersonIds
import com.zhiban.rebuild.data.contact.SystemContactCandidate
import com.zhiban.rebuild.data.contact.SystemContactWriteIntent
import com.zhiban.rebuild.data.notification.NotificationCandidateEntity
import com.zhiban.rebuild.data.notification.OutgoingMessageAccessibilityService
import com.zhiban.rebuild.data.notification.ScheduleInsight
import com.zhiban.rebuild.runtime.context.FactEntity
import com.zhiban.rebuild.runtime.input.asr.CloudAsrAvailability
import com.zhiban.rebuild.runtime.personalization.UserProfile
import com.zhiban.rebuild.ui.components.ZhiBanPrimaryTabHeader
import com.zhiban.rebuild.ui.components.ZhiBanSearchField
import com.zhiban.rebuild.ui.components.ZhiBanTabBottomSpacer
import com.zhiban.rebuild.ui.components.ZhiBanTabHorizontalPadding
import com.zhiban.rebuild.ui.components.ZhiBanTabTopPadding
import com.zhiban.rebuild.ui.components.zhiBanCardSurface
import com.zhiban.rebuild.ui.settings.AutoWriteViewModel
import com.zhiban.rebuild.ui.theme.DangerRed
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
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
internal fun ContactImportDialog(state: ContactImportUiState, onDismiss: () -> Unit, onImport: (Set<String>) -> Unit) {
    var selected by remember(state.contacts) {
        mutableStateOf(state.contacts.map(SystemContactCandidate::sourceId).toSet())
    }
    Dialog(onDismissRequest = { if (!state.isImporting) onDismiss() }) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 680.dp)
                .clip(RoundedCornerShape(ZhiBanRadius.Dialog))
                .background(RelationSurface)
                .padding(20.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onDismiss,
                    enabled = !state.isImporting,
                    modifier = Modifier.size(ZhiBanIconContainer.TouchTarget),
                ) {
                    Icon(Icons.Rounded.Close, "关闭")
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "从手机通讯录导入",
                        color = RelationInk,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (state.rowsRead > 0) {
                        Text(
                            "手机返回 ${state.contacts.size} 位联系人",
                            color = RelationMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            when {
                state.isLoading -> {
                    Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = RelationInk, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("正在读取手机通讯录…", color = RelationMuted)
                        }
                    }
                }

                state.resultMessage != null -> {
                    Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                        Text(state.resultMessage, color = RelationInk, style = MaterialTheme.typography.bodyLarge)
                    }
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth().height(ZhiBanSize.Control),
                        colors = ButtonDefaults.buttonColors(containerColor = RelationInk),
                        shape = RoundedCornerShape(ZhiBanRadius.Card),
                    ) { Text("完成") }
                }

                else -> {
                    state.error?.let {
                        Text(it, color = RelationDanger, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(10.dp))
                    }
                    if (state.contacts.isEmpty()) {
                        Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("没有可导入的联系人", color = RelationInk, style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "手机系统可能没有向知伴返回联系人",
                                    color = RelationMuted,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    } else {
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                selected = if (selected.size == state.contacts.size) {
                                    emptySet()
                                } else {
                                    state.contacts.map(SystemContactCandidate::sourceId).toSet()
                                }
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = selected.size == state.contacts.size,
                                onCheckedChange = null,
                            )
                            Text("全选", modifier = Modifier.weight(1f), color = RelationInk)
                            Text(
                                "已选 ${selected.size} 位",
                                color = RelationMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(RelationLine))
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                            items(state.contacts.size, key = { state.contacts[it].sourceId }) { index ->
                                val contact = state.contacts[index]
                                val checked = contact.sourceId in selected
                                Row(
                                    Modifier.fillMaxWidth().clickable {
                                        selected =
                                            if (checked) selected - contact.sourceId else selected + contact.sourceId
                                    }.padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(checked = checked, onCheckedChange = null)
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            contact.displayName,
                                            color = RelationInk,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        val detail = listOfNotNull(contact.phones.firstOrNull(), contact.company)
                                            .joinToString(" · ").ifBlank { "没有电话或公司信息" }
                                        Text(
                                            detail,
                                            color = RelationMuted,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                        Text(
                            "只会保存你勾选的联系人；不会修改手机通讯录。",
                            color = RelationMuted,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 10.dp),
                        )
                        Button(
                            onClick = { onImport(selected) },
                            enabled = selected.isNotEmpty() && !state.isImporting,
                            modifier = Modifier.fillMaxWidth().height(ZhiBanSize.Control),
                            colors = ButtonDefaults.buttonColors(containerColor = RelationInk),
                            shape = RoundedCornerShape(ZhiBanRadius.Card),
                        ) {
                            if (state.isImporting) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(20.dp),
                                )
                            } else {
                                Text("导入 ${selected.size} 位联系人")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ContactEditorDialog(
    contact: ContactEntity?,
    onDismiss: () -> Unit,
    onSave: (String?, String, String?, String?, String?, String?, String?, String?, (String?) -> Unit) -> Unit,
) {
    var name by remember(contact?.contactId) { mutableStateOf(contact?.displayName.orEmpty()) }
    var phone by remember(contact?.contactId) { mutableStateOf(contact?.phone.orEmpty()) }
    var wechat by remember(contact?.contactId) { mutableStateOf(contact?.wechatId.orEmpty()) }
    var company by remember(contact?.contactId) { mutableStateOf(contact?.company.orEmpty()) }
    var title by remember(contact?.contactId) { mutableStateOf(contact?.title.orEmpty()) }
    var tag by remember(contact?.contactId) { mutableStateOf(contact?.firstKnownTag() ?: "朋友") }
    var note by remember(contact?.contactId) { mutableStateOf(contact?.note.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 20.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .heightIn(max = 720.dp)
                    .clip(RoundedCornerShape(ZhiBanRadius.Dialog))
                    .background(RelationSurface),
                contentPadding = PaddingValues(20.dp),
            ) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDismiss, modifier = Modifier.size(ZhiBanIconContainer.TouchTarget)) {
                            Icon(Icons.Rounded.Close, "关闭")
                        }
                        Text(
                            if (contact ==
                                null
                            ) {
                                "添加联系人"
                            } else {
                                "编辑联系人"
                            },
                            Modifier.weight(1f),
                            color = RelationInk,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
                item {
                    ContactField("姓名", name, {
                        name = it
                        error = null
                    })
                    Spacer(Modifier.height(9.dp))
                }
                item {
                    ContactField("手机号（可选）", phone, { phone = it })
                    Spacer(Modifier.height(9.dp))
                }
                item {
                    ContactField("微信号（可选）", wechat, { wechat = it })
                    Spacer(Modifier.height(9.dp))
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        OutlinedTextField(company, {
                            company = it
                        }, Modifier.weight(1f), label = { Text("公司") }, singleLine = true)
                        OutlinedTextField(title, {
                            title = it
                        }, Modifier.weight(1f), label = { Text("职位") }, singleLine = true)
                    }
                    Spacer(Modifier.height(12.dp))
                }
                item {
                    Text("关系", color = RelationMuted, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(7.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        RelationTags.drop(1).forEach {
                            Text(
                                it,
                                color = if (tag == it) Color.White else RelationMuted,
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                                    .clip(RoundedCornerShape(24.dp)).background(
                                        if (tag ==
                                            it
                                        ) {
                                            RelationAccent
                                        } else {
                                            RelationSoft
                                        },
                                    )
                                    .clickable { tag = it }.padding(horizontal = 13.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                item {
                    OutlinedTextField(note, {
                        note = it
                    }, Modifier.fillMaxWidth(), label = { Text("备注（可选）") }, minLines = 2, maxLines = 4)
                    error?.let {
                        Spacer(Modifier.height(7.dp))
                        Text(it, color = RelationDanger, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(18.dp))
                    Button(
                        onClick = {
                            if (name.isBlank()) {
                                error = "请输入联系人姓名"
                            } else {
                                onSave(contact?.contactId, name, phone, wechat, company, title, tag, note) { error = it }
                            }
                        },
                        Modifier.fillMaxWidth().height(ZhiBanSize.Control),
                        colors = ButtonDefaults.buttonColors(containerColor = RelationInk),
                        shape = RoundedCornerShape(ZhiBanRadius.Card),
                    ) { Text("保存") }
                }
            }
        }
    }
}

@Composable
internal fun ContactField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(value, onValueChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true)
}

internal fun ContactEntity.firstKnownTag(): String? = tagsJson.firstKnownTag()
internal fun String.firstKnownTag(): String? = RelationTags.drop(1).firstOrNull { contains(it, ignoreCase = true) }
