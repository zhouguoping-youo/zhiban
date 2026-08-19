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
import com.zhiban.rebuild.data.facts.FactEntity
import com.zhiban.rebuild.data.notification.NotificationCandidateEntity
import com.zhiban.rebuild.data.notification.OutgoingMessageAccessibilityService
import com.zhiban.rebuild.data.notification.ScheduleInsight
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
internal fun ContactDetailDialog(
    contact: ContactEntity,
    showMarkAsOwner: Boolean,
    facts: List<FactEntity>,
    aliases: List<ContactAliasEntity>,
    platformIdentities: List<ContactPlatformIdentityEntity>,
    mergedSources: List<Pair<ContactMergeLinkEntity, ContactEntity>>,
    relatedEdges: List<RelationshipEdgeEntity>,
    relatedEvents: List<RelationshipEventWithParticipants>,
    recentCalls: List<CallRecordEntity>,
    crmOpportunities: List<CrmOpportunityEntity>,
    enrichmentSuggestions: List<ContactEnrichmentCandidateEntity>,
    contactNames: Map<String, String>,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onMarkAsOwner: () -> Unit,
    onDelete: () -> Unit,
    onAddFact: () -> Unit,
    onAddEvent: () -> Unit,
    onAddIdentity: () -> Unit,
    onInspectEvent: (RelationshipEventWithParticipants) -> Unit,
    onDeleteFact: (String) -> Unit,
    onDeleteAlias: (String) -> Unit,
    onDeletePlatformIdentity: (String) -> Unit,
    onUndoMerge: (String) -> Unit,
    onConfirmEnrichment: (ContactEnrichmentCandidateEntity) -> Unit,
    onRejectEnrichment: (ContactEnrichmentCandidateEntity) -> Unit,
    onSaveToPhone: () -> Unit,
    onCall: () -> Unit,
    onMessage: () -> Unit,
) {
    val openCrmOpportunities = crmOpportunities.filter { it.status == "OPEN" }
    val hasRelationshipContext = relatedEdges.isNotEmpty() || relatedEvents.isNotEmpty()
    val hasProfileDetails = listOf(
        contact.phone,
        contact.wechatId,
        contact.company,
        contact.title,
        contact.note,
    ).any { !it.isNullOrBlank() } || aliases.isNotEmpty() || platformIdentities.isNotEmpty()

    ZhiBanTaskDialog(onDismissRequest = onDismiss, maxHeight = 720.dp) {
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .testTag("contact-detail-content"),
            contentPadding = PaddingValues(bottom = ZhiBanSpacing.Lg),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Close, "关闭") }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.DeleteOutline, "删除联系人", tint = RelationMuted)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(58.dp).clip(CircleShape).background(RelationSoft),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            contact.displayName.take(1),
                            color = RelationInk,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(
                            contact.displayName,
                            color = RelationInk,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            // Match the list row: wrap long names to 2 lines first; only clip beyond that.
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        contact.firstKnownTag()?.let {
                            Text(
                                it,
                                color = RelationMuted,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    TextButton(onClick = onEdit, modifier = Modifier.height(ZhiBanSize.TouchTarget)) {
                        Text("编辑", color = RelationAccent)
                    }
                }
                if (contact.phone?.isNotBlank() == true) {
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ContactActionButton(Icons.Rounded.Call, "电话", onCall, Modifier.weight(1f))
                        ContactActionButton(Icons.Rounded.Sms, "短信", onMessage, Modifier.weight(1f))
                        ContactActionButton(Icons.Outlined.Add, "记一条", onAddFact, Modifier.weight(1f))
                    }
                } else {
                    Spacer(Modifier.height(14.dp))
                    ContactActionButton(Icons.Outlined.Add, "记录重要信息", onAddFact, Modifier.fillMaxWidth())
                }
                if (showMarkAsOwner) {
                    TextButton(onClick = onMarkAsOwner, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text("与我的资料合并", color = RelationInk)
                    }
                }
                ContactDetailDivider()
            }

            item {
                ContactDetailSection(
                    title = "资料",
                    action = "添加账号",
                    onAction = onAddIdentity,
                    modifier = Modifier.testTag("contact-detail-profile"),
                ) {
                    if (!hasProfileDetails) {
                        ContactDetailEmptyAction("添加联系方式或公司", onEdit)
                    } else {
                        DetailValue("手机", contact.phone)
                        aliases.forEach { alias ->
                            IdentityValueRow(
                                label = if (alias.aliasType == "NICKNAME") "昵称" else "常用称呼",
                                value = alias.alias,
                                onDelete = { onDeleteAlias(alias.aliasId) },
                            )
                        }
                        contact.wechatId?.takeIf(String::isNotBlank)
                            ?.takeUnless { wechat ->
                                platformIdentities.any {
                                    it.platform == "WECHAT" &&
                                        it.normalizedHandle == wechat.trim().trimStart('@').lowercase()
                                }
                            }?.let { IdentityValueRow("微信", it, null) }
                        platformIdentities.forEach { identity ->
                            IdentityValueRow(
                                platformLabel(identity.platform),
                                identity.handle,
                                onDelete = { onDeletePlatformIdentity(identity.identityId) },
                            )
                        }
                        DetailValue(
                            "公司全称",
                            listOfNotNull(contact.company, contact.title)
                                .joinToString(" · ")
                                .takeIf(String::isNotBlank),
                        )
                        DetailValue("备注", contact.note)
                    }
                }
            }

            if (mergedSources.isNotEmpty()) {
                item {
                    ContactDetailSection("已合并资料") {
                        mergedSources.forEach { (link, source) ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(source.displayName, color = RelationInk, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "${link.reason} · 原资料已保留",
                                        color = RelationMuted,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                TextButton(onClick = { onUndoMerge(source.contactId) }) {
                                    Text("恢复", color = RelationInk)
                                }
                            }
                        }
                    }
                }
            }

            if (facts.isNotEmpty()) {
                item {
                    ContactDetailSection("重要信息", "添加", onAddFact) {
                        facts.forEach { fact ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        fact.textContent,
                                        color = RelationInk,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        "${factTypeLabel(fact.factType)} · ${sourceLabel(fact.sourceType)}",
                                        color = RelationMuted,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                IconButton(onClick = { onDeleteFact(fact.factId) }, modifier = Modifier.size(48.dp)) {
                                    Icon(
                                        Icons.Rounded.DeleteOutline,
                                        "删除这条记忆",
                                        tint = RelationMuted,
                                        modifier = Modifier.size(ZhiBanIconSize.Inline),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (enrichmentSuggestions.isNotEmpty()) {
                item {
                    ContactDetailSection("知伴建议") {
                        enrichmentSuggestions.forEach { candidate ->
                            ContactEnrichmentRow(
                                candidate = candidate,
                                onConfirm = { onConfirmEnrichment(candidate) },
                                onReject = { onRejectEnrichment(candidate) },
                            )
                        }
                    }
                }
            }

            if (openCrmOpportunities.isNotEmpty()) {
                item {
                    ContactDetailSection("进行中的商机") {
                        openCrmOpportunities.forEach { opportunity ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 7.dp)
                                    .testTag("contact-crm-opp-${opportunity.opportunityId}"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        opportunity.title,
                                        color = RelationInk,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        "${crmStageLabel(opportunity.stage)} · ${formatCrmMoney(opportunity.valueMinor, opportunity.currencyCode)}",
                                        color = RelationMuted,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                Text(
                                    "${opportunity.probabilityPercent}%",
                                    color = RelationInk,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }
            }

            item {
                ContactDetailSection(
                    title = "关系与经历",
                    action = "添加".takeIf { hasRelationshipContext },
                    onAction = onAddEvent.takeIf { hasRelationshipContext },
                    modifier = Modifier.testTag("contact-detail-relationships"),
                ) {
                    if (!hasRelationshipContext) {
                        ContactDetailEmptyAction("添加一段共同经历", onAddEvent)
                    } else {
                        if (relatedEdges.isNotEmpty()) {
                            ContactDetailSubheading("关联的人")
                            relatedEdges.forEach { edge ->
                                val otherId = if (edge.fromContactId == contact.contactId) {
                                    edge.toContactId
                                } else {
                                    edge.fromContactId
                                }
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        contactNames[otherId].orEmpty(),
                                        color = RelationInk,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        if (edge.isInferredEvidenceRelationship()) {
                                            "${relationLabel(edge.relationType)} · ${edge.inferredEvidenceLabel()}"
                                        } else {
                                            relationLabel(edge.relationType)
                                        },
                                        color = RelationMuted,
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }
                        }
                        if (relatedEvents.isNotEmpty()) {
                            ContactDetailSubheading("共同经历")
                            relatedEvents.forEach { event -> RelationshipEventRow(event, onInspectEvent) }
                        }
                    }
                }
            }

            if (recentCalls.isNotEmpty()) {
                item {
                    ContactDetailSection("最近互动") {
                        recentCalls.take(5).forEach { call ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Rounded.Call,
                                    contentDescription = null,
                                    tint = RelationMuted,
                                    modifier = Modifier.size(20.dp),
                                )
                                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                    Text(
                                        callDirectionLabel(call.direction),
                                        color = RelationInk,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        formatCallTime(call.startedAtEpochMs),
                                        color = RelationMuted,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                Text(
                                    formatCallDuration(call.durationSeconds),
                                    color = RelationMuted,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }
            }

            item {
                ContactDetailDivider()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 64.dp)
                        .clip(RoundedCornerShape(ZhiBanRadius.Card))
                        .clickable(onClick = onSaveToPhone)
                        .padding(horizontal = 4.dp)
                        .testTag("contact-detail-sync"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(RelationSoft),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.PhoneAndroid,
                            contentDescription = null,
                            tint = RelationInk,
                            modifier = Modifier.size(ZhiBanIconSize.Inline),
                        )
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("同步到手机通讯录", color = RelationInk, style = MaterialTheme.typography.bodyMedium)
                        Text("写入前可预览", color = RelationMuted, style = MaterialTheme.typography.labelSmall)
                    }
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = RelationMuted,
                        modifier = Modifier.size(ZhiBanIconSize.Inline),
                    )
                }
            }
        }
    }
}

@Composable
private fun ContactDetailSection(
    title: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(top = 18.dp)) {
        ProfileSectionHeader(title, action, onAction)
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun ContactDetailSubheading(text: String) {
    Text(
        text,
        color = RelationMuted,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun ContactDetailEmptyAction(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = ZhiBanSize.TouchTarget)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Add,
            contentDescription = null,
            tint = RelationAccent,
            modifier = Modifier.size(ZhiBanIconSize.Inline),
        )
        Text(
            label,
            color = RelationInk,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

@Composable
private fun ContactDetailDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(top = 18.dp),
        color = RelationLine,
    )
}

@Composable
internal fun ContactActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .defaultMinSize(minHeight = ZhiBanSize.TouchTarget)
            .clip(RoundedCornerShape(ZhiBanRadius.Dialog))
            .background(RelationSoft)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = RelationInk, modifier = Modifier.size(ZhiBanIconSize.Inline))
        Spacer(Modifier.width(6.dp))
        // Single line keeps a row of these action buttons the same height; ellipsis is the
        // narrow-screen fallback instead of one button wrapping taller than its neighbours.
        Text(label, color = RelationInk, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun IdentityValueRow(label: String, value: String, onDelete: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = RelationMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(72.dp))
        Text(value, color = RelationInk, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (onDelete != null) {
            IconButton(onClick = onDelete, modifier = Modifier.size(ZhiBanIconContainer.TouchTarget)) {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    "删除$label",
                    tint = RelationMuted,
                    modifier = Modifier.size(ZhiBanIconSize.Inline),
                )
            }
        }
    }
}

internal fun platformLabel(platform: String): String = when (platform) {
    "WECHAT" -> "微信"
    "WE_COM" -> "企业微信"
    "FEISHU" -> "飞书"
    "DINGTALK" -> "钉钉"
    "SKYPE" -> "Skype"
    "GOOGLE_CHAT" -> "Google Chat"
    "JABBER" -> "Jabber"
    "DOUYIN" -> "抖音"
    "XIAOHONGSHU" -> "小红书"
    "QQ" -> "QQ"
    "WEIBO" -> "微博"
    else -> "其他账号"
}

@Composable
internal fun ProfileSectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            color = RelationInk,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (action != null && onAction != null) {
            TextButton(onClick = onAction) { Text(action, color = RelationInk) }
        }
    }
}

internal fun sourceLabel(source: String): String = when (source) {
    "USER_CONFIRMED" -> "你确认的信息"
    "USER_CONFIRMED_NOTIFICATION" -> "你确认这条对话记录"
    "USER_CONFIRMED_MEMORY" -> "记忆任务确认"
    "AGENT_DOMAIN_WRITE" -> "聊天中确认"
    "USER_PROFILE" -> "你在联系人资料中添加"
    "USER_CALL_NOTE" -> "你保存的通话备注"
    else -> "知伴记录"
}

internal fun callDirectionLabel(direction: String): String = when (direction) {
    "INCOMING" -> "呼入通话"
    "OUTGOING" -> "呼出通话"
    "MISSED" -> "未接来电"
    "REJECTED" -> "已拒接"
    "BLOCKED" -> "已拦截"
    "VOICEMAIL" -> "语音信箱"
    else -> "通话记录"
}

internal fun formatCallTime(epochMs: Long): String = Instant.ofEpochMilli(epochMs)
    .atZone(ZoneId.systemDefault())
    .format(DateFormats.MonthDayTimePadded)

internal fun formatCallDuration(seconds: Long): String = when {
    seconds <= 0 -> "未接通"
    seconds < 60 -> "${seconds}秒"
    else -> "${seconds / 60}分${seconds % 60}秒"
}

internal fun factTypeLabel(type: String): String = when (type) {
    "IMPORTANT_DATE" -> "重要日期"
    "COMMUNICATION_PREFERENCE" -> "沟通偏好"
    "CURRENT_MATTER" -> "当前事项"
    "CALL_NOTE" -> "通话备注"
    else -> "基本信息"
}

internal fun enrichmentFieldLabel(fieldKind: String): String = when (fieldKind) {
    "ORGANIZATION" -> "单位"
    "EMPLOYMENT" -> "职位"
    "ADDRESS" -> "地址"
    "COMMUNICATION_METHOD" -> "联系方式"
    "RESPONSIBILITIES" -> "职责"
    "RELATIONSHIP" -> "关系"
    else -> "字段"
}

internal fun enrichmentValueSummary(proposedValueJson: String): String {
    val obj = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(proposedValueJson).jsonObject
    }.getOrNull() ?: return proposedValueJson.take(60)
    val displayKeys = if (obj["canonicalName"] != null) {
        listOf("canonicalName", "registrationStatus", "creditCode", "registeredAddress")
    } else {
        listOf("company", "title", "phone", "email", "wechatId", "responsibilities", "formattedAddress", "relationLabel")
    }
    return displayKeys
        .mapNotNull { key ->
            (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                ?.takeIf(String::isNotBlank)
                ?.let { "${enrichmentValueLabel(key)}：$it" }
        }
        .joinToString(" · ")
        .ifBlank { proposedValueJson.take(60) }
}

private fun enrichmentValueLabel(key: String): String = when (key) {
    "canonicalName", "company" -> "公司全称"
    "registrationStatus" -> "状态"
    "creditCode" -> "统一信用代码"
    "registeredAddress", "formattedAddress" -> "地址"
    "title" -> "职位"
    "phone" -> "电话"
    "email" -> "邮箱"
    "wechatId" -> "微信"
    "responsibilities" -> "职责"
    "relationLabel" -> "关系"
    else -> key
}

@Composable
internal fun ContactEnrichmentRow(candidate: ContactEnrichmentCandidateEntity, onConfirm: () -> Unit, onReject: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            enrichmentFieldLabel(candidate.fieldKind),
            color = RelationMuted,
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            enrichmentValueSummary(candidate.proposedValueJson),
            color = RelationInk,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            "置信度 ${(candidate.confidence * 100).roundToInt()}% · ${candidate.sourceRef ?: "知伴"} · 确认后才会写入",
            color = RelationMuted,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 2.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onReject) { Text("拒绝", color = RelationMuted) }
            TextButton(onClick = onConfirm) { Text("确认", color = RelationInk) }
        }
    }
}

@Composable
internal fun DetailValue(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Text(label, color = RelationMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(54.dp))
        Text(value, color = RelationInk, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
