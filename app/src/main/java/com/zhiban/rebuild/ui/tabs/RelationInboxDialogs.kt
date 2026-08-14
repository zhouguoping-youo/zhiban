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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhiban.rebuild.R
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
import com.zhiban.rebuild.ui.components.ZhiBanAlertDialog
import com.zhiban.rebuild.ui.components.ZhiBanChip
import com.zhiban.rebuild.ui.components.ZhiBanDialogHost
import com.zhiban.rebuild.ui.components.ZhiBanPrimaryTabHeader
import com.zhiban.rebuild.ui.components.ZhiBanSearchField
import com.zhiban.rebuild.ui.components.ZhiBanTabBottomSpacer
import com.zhiban.rebuild.ui.components.ZhiBanTabHorizontalPadding
import com.zhiban.rebuild.ui.components.ZhiBanTabTopPadding
import com.zhiban.rebuild.ui.components.localizedQuantity
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

@Composable
internal fun CallNoteDialog(
    call: CallRecordEntity,
    contactName: String?,
    cloudAsrAvailability: CloudAsrAvailability,
    onAllowCloudSpeech: () -> Unit,
    onTranscribe: (File, (String?, String?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onSkip: () -> Unit,
    onSave: (String, String, (String?) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    var text by remember(call.callRecordId) { mutableStateOf("") }
    var source by remember(call.callRecordId) { mutableStateOf("TYPED") }
    var error by remember(call.callRecordId) { mutableStateOf<String?>(null) }
    var recorder by remember(call.callRecordId) { mutableStateOf<MediaRecorder?>(null) }
    var recordingFile by remember(call.callRecordId) { mutableStateOf<File?>(null) }
    var retryFile by remember(call.callRecordId) { mutableStateOf<File?>(null) }
    var transcribing by remember(call.callRecordId) { mutableStateOf(false) }
    var showCloudConsent by remember(call.callRecordId) { mutableStateOf(false) }

    fun beginCloudRecording() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return
        val file = File(context.cacheDir, "call-notes/call_${System.currentTimeMillis()}.ogg").apply {
            parentFile?.mkdirs()
        }
        val next = acquireStartedResource(
            create = ::MediaRecorder,
            start = { mediaRecorder ->
                mediaRecorder.apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.OGG)
                    setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                    setAudioChannels(1)
                    setAudioSamplingRate(16_000)
                    setOutputFile(file.absolutePath)
                    prepare()
                    start()
                }
            },
            release = MediaRecorder::release,
        ).getOrElse {
            file.delete()
            error = "无法开始录音，请手动输入"
            return
        }
        recordingFile = file
        recorder = next
        error = null
    }

    val microphonePermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) beginCloudRecording() else error = "需要麦克风权限才能录入语音"
        }
    val systemSpeech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val recognized = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!recognized.isNullOrBlank()) {
                text = recognized
                source = "SYSTEM_SPEECH"
                error = null
            }
        }
    }

    fun transcribe(file: File) {
        transcribing = true
        onTranscribe(file) { result, failure ->
            transcribing = false
            if (result != null) {
                retryFile = null
                text = result
                source = "CLOUD_ASR"
                error = null
            } else {
                retryFile = file
                error = failure ?: "语音识别失败，请重试或手动输入"
            }
        }
    }

    fun requestVoiceInput() {
        retryFile?.takeIf(File::isFile)?.let {
            transcribe(it)
            return
        }
        when (cloudAsrAvailability) {
            CloudAsrAvailability.CONSENT_REQUIRED -> showCloudConsent = true

            CloudAsrAvailability.AVAILABLE -> {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    beginCloudRecording()
                } else {
                    microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }

            CloudAsrAvailability.UNSUPPORTED_PROVIDER,
            CloudAsrAvailability.PROVIDER_NOT_CONFIGURED,
            -> runCatching {
                systemSpeech.launch(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "说出这次通话的关键要点")
                    },
                )
            }.onFailure { error = "当前设备没有可用的语音识别，请手动输入" }
        }
    }

    fun stopAndTranscribe() {
        val active = recorder ?: return
        val file = recordingFile
        val stopped = runCatching { active.stop() }.isSuccess
        runCatching { active.release() }
        recorder = null
        recordingFile = null
        if (!stopped || file == null || !file.isFile || file.length() == 0L) {
            file?.delete()
            error = "录音没有有效内容，请重试"
            return
        }
        transcribe(file)
    }

    fun dismissAndDeleteTemporaryAudio() {
        retryFile?.delete()
        recordingFile?.delete()
        onDismiss()
    }

    DisposableEffect(recorder) {
        onDispose(
            capturedResourceDisposer(recorder) { active ->
                runCatching { active.stop() }
                runCatching { active.release() }
            },
        )
    }

    ZhiBanDialogHost(onDismissRequest = { if (recorder == null && !transcribing) dismissAndDeleteTemporaryAudio() }) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(ZhiBanRadius.Dialog)).background(RelationSurface).padding(20.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = ::dismissAndDeleteTemporaryAudio, enabled = recorder == null && !transcribing) {
                    Icon(Icons.Rounded.Close, "关闭")
                }
                Column(Modifier.weight(1f)) {
                    Text("记录通话要点", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(contactName ?: "未匹配联系人", color = RelationMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    source = "TYPED"
                    error = null
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("例如：确认下周给出报价，周五前发方案") },
                minLines = 4,
                maxLines = 8,
                enabled = recorder == null && !transcribing,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { if (recorder == null) requestVoiceInput() else stopAndTranscribe() },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                enabled = !transcribing,
                colors = ButtonDefaults.buttonColors(containerColor = RelationSoft, contentColor = RelationInk),
            ) {
                Text(
                    when {
                        transcribing -> "正在识别…"
                        recorder != null -> "结束录音并识别"
                        retryFile != null -> "重新识别这段录音"
                        else -> "用语音补充"
                    },
                )
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = RelationDanger, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    if (text.isBlank()) {
                        error = "请先填写通话要点"
                    } else {
                        onSave(text, source) {
                            error = it
                            if (it == null) retryFile?.delete()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(ZhiBanSize.Control),
                enabled = recorder == null && !transcribing,
                colors = ButtonDefaults.buttonColors(containerColor = RelationInk),
                shape = RoundedCornerShape(ZhiBanRadius.Card),
            ) { Text("保存备注") }
            TextButton(onClick = {
                retryFile?.delete()
                onSkip()
            }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("这次不记录", color = RelationMuted)
            }
        }
    }

    if (showCloudConsent) {
        ZhiBanAlertDialog(
            onDismissRequest = { showCloudConsent = false },
            title = { Text("允许语音识别上云？") },
            text = { Text("只有你点击录音后生成的这段语音会发送给当前模型服务做转写。知伴不会录制电话通话，也不会在后台持续录音。你可以随时在“我的－隐私与权限”关闭。") },
            confirmButton = {
                TextButton(onClick = {
                    onAllowCloudSpeech()
                    showCloudConsent = false
                }) { Text("允许", color = RelationInk) }
            },
            dismissButton = {
                TextButton(onClick = { showCloudConsent = false }) { Text("暂不", color = RelationMuted) }
            },
            containerColor = RelationSurface,
        )
    }
}

@Composable
internal fun NotificationCandidateDialog(
    enabled: Boolean,
    candidates: List<NotificationCandidateEntity>,
    contacts: List<ContactEntity>,
    onEnable: () -> Unit,
    onDismissCandidate: (String) -> Unit,
    onConfirmCandidate: (String, String, (String?) -> Unit) -> Unit,
    onCreateContact: (String, String, (String?) -> Unit) -> Unit,
    onConfirmSchedule: (String, (String?) -> Unit) -> Unit,
    enabledPlatforms: Set<String>,
    onPlatformEnabled: (String, Boolean) -> Unit,
    outgoingCollectionEnabled: Boolean,
    outgoingAccessibilityEnabled: Boolean,
    onOutgoingCollectionEnabled: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var linking by remember { mutableStateOf<NotificationCandidateEntity?>(null) }
    var showCollectionSettings by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    ZhiBanDialogHost(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(ZhiBanRadius.Dialog)).background(RelationSurface).padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(ZhiBanIconContainer.TouchTarget)) {
                    Icon(Icons.Rounded.Close, "关闭")
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            linking != null -> "选择其他联系人"
                            showCollectionSettings -> "消息感知设置"
                            else -> "待确认"
                        },
                        color = RelationInk,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        when {
                            linking != null -> "只有当前匹配不正确时才需要重新选择"
                            showCollectionSettings -> "选择知伴可以整理的消息来源"
                            candidates.isEmpty() -> "仅显示知伴无法确定的内容"
                            else -> localizedQuantity(R.plurals.needs_confirmation_count, candidates.size)
                        },
                        color = RelationMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (linking == null) {
                    IconButton(
                        onClick = { showCollectionSettings = !showCollectionSettings },
                        modifier = Modifier.size(ZhiBanIconContainer.TouchTarget),
                    ) {
                        Icon(
                            if (showCollectionSettings) Icons.Outlined.Done else Icons.Outlined.Settings,
                            if (showCollectionSettings) "完成设置" else "消息感知设置",
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (linking == null && showCollectionSettings) {
                Text("采集来源", color = RelationMuted, style = MaterialTheme.typography.labelSmall)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                        "WECHAT" to "微信",
                        "SMS" to "短信",
                        "QQ" to "QQ",
                        "TIM" to "TIM",
                        "FEISHU" to "飞书",
                        "LARK" to "Lark",
                        "WEWORK" to "企业微信",
                        "DINGTALK" to "钉钉",
                    ).forEach { (platform, label) ->
                        ZhiBanChip(
                            text = label,
                            selected = platform in enabledPlatforms,
                            color = RelationAccent,
                            onClick = { onPlatformEnabled(platform, platform !in enabledPlatforms) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(ZhiBanRadius.Card))
                        .background(RelationSoft)
                        .clickable {
                            onOutgoingCollectionEnabled(
                                !(outgoingCollectionEnabled && outgoingAccessibilityEnabled),
                            )
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "识别我发出的消息",
                            color = RelationInk,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            when {
                                !outgoingCollectionEnabled -> "关闭"
                                outgoingAccessibilityEnabled -> "已开启 · 仅识别刚刚发送的内容"
                                else -> "还需在系统中开启“知伴发出消息感知”"
                            },
                            color = RelationMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Checkbox(
                        checked = outgoingCollectionEnabled && outgoingAccessibilityEnabled,
                        onCheckedChange = {
                            onOutgoingCollectionEnabled(
                                !(outgoingCollectionEnabled && outgoingAccessibilityEnabled),
                            )
                        },
                    )
                }
                Text(
                    "不会替你点击发送或读取历史；微信隐藏文字时，只在知伴发起发送后做一次本机识别，截图不保存。",
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                    color = RelationMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.height(8.dp))
            }
            if (showCollectionSettings && linking == null) {
                TextButton(
                    onClick = { showCollectionSettings = false },
                    modifier = Modifier.fillMaxWidth().height(ZhiBanSize.TouchTarget),
                ) {
                    Text("完成", color = RelationInk)
                }
            } else if (linking != null) {
                val candidate = requireNotNull(linking)
                Text(
                    listOfNotNull(candidate.title, candidate.body).joinToString("：").ifBlank { candidate.appLabel },
                    color = RelationInk,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
                if (contacts.isEmpty()) {
                    Text("还没有联系人，请先在关系页添加或导入联系人。", color = RelationMuted, style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                        items(contacts.size, key = { contacts[it].contactId }) { index ->
                            val contact = contacts[index]
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    error = null
                                    onConfirmCandidate(candidate.candidateId, contact.contactId) { result ->
                                        if (result == null) {
                                            val pendingSchedule = ScheduleInsight.from(candidate) != null &&
                                                candidate.createdScheduleId == null
                                            if (pendingSchedule) {
                                                onConfirmSchedule(candidate.candidateId) { scheduleError ->
                                                    error = scheduleError
                                                    if (scheduleError == null) linking = null
                                                }
                                            } else {
                                                linking = null
                                            }
                                        } else {
                                            error = result
                                        }
                                    }
                                }.padding(vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier.size(38.dp).clip(CircleShape).background(RelationSoft),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        contact.displayName.take(1),
                                        color = RelationInk,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        contact.displayName,
                                        color = RelationInk,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    contact.company?.let {
                                        Text(it, color = RelationMuted, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                Text("关联", color = RelationInk, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
                error?.let { Text(it, color = RelationDanger, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = {
                    linking = null
                    error = null
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("返回候选", color = RelationMuted)
                }
            } else if (candidates.isNotEmpty()) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(candidates.size, key = { candidates[it].candidateId }) { index ->
                        val item = candidates[index]
                        val suggestedContact = contacts.firstOrNull { it.contactId == item.suggestedContactId }
                        val matchedContact = contacts.firstOrNull {
                            it.contactId == (item.linkedContactId ?: item.suggestedContactId)
                        }
                        val schedule = ScheduleInsight.from(item)
                        val review = buildNotificationCandidateReview(item, matchedContact?.displayName)
                        val needsContact = item.linkedContactId == null && item.senderName != null
                        val needsSchedule = schedule != null && item.createdScheduleId == null
                        val canUseSuggestion = needsContact && suggestedContact != null
                        val canCreateContact = needsContact && !item.isGroupChat
                        val primaryLabel = when {
                            canUseSuggestion && needsSchedule -> "确认关联并加入日历"
                            canUseSuggestion -> "关联为 ${suggestedContact.displayName}"
                            canCreateContact && needsSchedule -> "新建联系人并加入日历"
                            canCreateContact -> "新建联系人"
                            needsContact -> "选择联系人"
                            needsSchedule -> "确认加入日历"
                            else -> null
                        }
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = ZhiBanSpacing.Md),
                            verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Xs),
                        ) {
                            Text(
                                "知伴整理",
                                color = RelationAccent,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                review.headline,
                                color = RelationInk,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                review.reason,
                                color = RelationMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            review.contactLine?.let {
                                Text(it, color = RelationInk, style = MaterialTheme.typography.bodyMedium)
                            }
                            review.scheduleLine?.let {
                                Text(it, color = RelationInk, style = MaterialTheme.typography.bodyMedium)
                            }
                            Text(
                                listOf(
                                    item.appLabel,
                                    if (item.direction == "OUTGOING") "我发出" else "收到",
                                    item.conversationTitle,
                                ).filterNotNull().joinToString(" · "),
                                color = RelationMuted,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            review.evidence?.let {
                                Text(
                                    "依据 · $it",
                                    color = RelationMuted,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (primaryLabel != null) {
                                Spacer(Modifier.height(ZhiBanSpacing.Xs))
                                Button(
                                    onClick = {
                                        error = null
                                        when {
                                            canUseSuggestion && needsSchedule -> onConfirmCandidate(
                                                item.candidateId,
                                                requireNotNull(suggestedContact).contactId,
                                            ) { contactError ->
                                                if (contactError == null) {
                                                    onConfirmSchedule(item.candidateId) { error = it }
                                                } else {
                                                    error = contactError
                                                }
                                            }

                                            canUseSuggestion -> onConfirmCandidate(
                                                item.candidateId,
                                                requireNotNull(suggestedContact).contactId,
                                            ) { error = it }

                                            canCreateContact -> onCreateContact(
                                                item.candidateId,
                                                requireNotNull(item.senderName),
                                            ) { contactError ->
                                                if (contactError == null && needsSchedule) {
                                                    onConfirmSchedule(item.candidateId) { error = it }
                                                } else {
                                                    error = contactError
                                                }
                                            }

                                            needsContact -> {
                                                linking = item
                                            }

                                            needsSchedule -> onConfirmSchedule(item.candidateId) { error = it }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().height(ZhiBanSize.Control),
                                    shape = RoundedCornerShape(ZhiBanRadius.Card),
                                    colors = ButtonDefaults.buttonColors(containerColor = RelationInk),
                                ) {
                                    Text(primaryLabel)
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                TextButton(onClick = { onDismissCandidate(item.candidateId) }) {
                                    Text("忽略", color = RelationMuted)
                                }
                                if (needsContact && (canUseSuggestion || canCreateContact)) {
                                    TextButton(onClick = {
                                        linking = item
                                        error = null
                                    }) {
                                        Text(
                                            if (canUseSuggestion) "不是这个人" else "关联已有联系人",
                                            color = RelationInk,
                                        )
                                    }
                                }
                            }
                            error?.let { Text(it, color = RelationDanger, style = MaterialTheme.typography.bodySmall) }
                        }
                        if (index !=
                            candidates.lastIndex
                        ) {
                            Box(Modifier.fillMaxWidth().height(1.dp).background(RelationLine))
                        }
                    }
                }
                if (!enabled) {
                    Text(
                        "通知读取尚未开启；手动分享仍可正常使用。",
                        color = RelationMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else if (!enabled) {
                Text(
                    "开启后，知伴会自动整理新消息；无法确定时再请你确认。",
                    color = RelationMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = onEnable,
                    modifier = Modifier.fillMaxWidth().height(ZhiBanSize.Control),
                    colors = ButtonDefaults.buttonColors(containerColor = RelationInk),
                    shape = RoundedCornerShape(ZhiBanRadius.Card),
                ) { Text("开启消息感知") }
            } else if (candidates.isEmpty()) {
                Text(
                    "暂无待确认内容",
                    modifier = Modifier.fillMaxWidth().padding(vertical = 44.dp),
                    color = RelationMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

internal fun formatMessageSchedule(epochMs: Long): String = DateFormats.MonthDayTime
    .format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

internal fun isOutgoingAccessibilityEnabled(context: android.content.Context): Boolean {
    val component = ComponentName(context, OutgoingMessageAccessibilityService::class.java)
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ).orEmpty()
    return enabled.split(':').any {
        ComponentName.unflattenFromString(it) == component
    }
}

internal fun UserProfile.displayNameOrMe(): String = preferredName.trim().ifBlank { name.trim() }.ifBlank { "我" }

internal fun UserProfile.relationshipLabel(): String = displayNameOrMe().takeIf { it != "我" }?.let { "$it（我）" } ?: "我"

internal fun UserProfile.matchesOwnerQuery(query: String): Boolean {
    if (query.isBlank()) return true
    return listOf(name, preferredName, phone, wechatId, douyinId, "我", "本人", "我的资料")
        .any { it.contains(query, ignoreCase = true) }
}

internal fun ContactEntity.matchesOwnerProfile(profile: UserProfile): Boolean {
    val contactName = displayName.trim()
    val ownerNames = listOf(profile.name, profile.preferredName)
        .map(String::trim)
        .filter(String::isNotBlank)
    val nameMatches = ownerNames.any { it.equals(contactName, ignoreCase = true) }

    val contactPhone = phone.orEmpty().filter(Char::isDigit)
    val ownerPhone = profile.phone.filter(Char::isDigit)
    val phoneMatches = contactPhone.isNotBlank() && ownerPhone.isNotBlank() && contactPhone == ownerPhone

    val contactWechat = wechatId.orEmpty().trim()
    val ownerWechat = profile.wechatId.trim()
    val wechatMatches = contactWechat.isNotBlank() && ownerWechat.isNotBlank() &&
        contactWechat.equals(ownerWechat, ignoreCase = true)

    return nameMatches || phoneMatches || wechatMatches
}
