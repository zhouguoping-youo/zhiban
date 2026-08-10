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
import com.zhiban.rebuild.ui.components.ZhiBanDialogHost
import com.zhiban.rebuild.ui.components.ZhiBanPrimaryTabHeader
import com.zhiban.rebuild.ui.components.ZhiBanSearchField
import com.zhiban.rebuild.ui.components.ZhiBanTabBottomSpacer
import com.zhiban.rebuild.ui.components.ZhiBanTabHorizontalPadding
import com.zhiban.rebuild.ui.components.ZhiBanTabTopPadding
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
    ZhiBanDialogHost(onDismissRequest = onDismiss) {
        LazyColumn(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(ZhiBanRadius.Dialog)).background(RelationSurface),
            contentPadding = PaddingValues(20.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Close, "关闭") }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.DeleteOutline, "删除联系人", tint = RelationMuted)
                    }
                }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size(64.dp).clip(CircleShape).background(RelationSoft),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            contact.displayName.take(1),
                            color = RelationInk,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    contact.displayName,
                    Modifier.fillMaxWidth(),
                    color = RelationInk,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                contact.firstKnownTag()?.let {
                    Text(
                        it,
                        Modifier.fillMaxWidth(),
                        color = RelationMuted,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = onEdit,
                    Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = RelationInk),
                    shape = RoundedCornerShape(ZhiBanRadius.Dialog),
                ) {
                    Text("编辑基本资料")
                }
                if (showMarkAsOwner) {
                    TextButton(onClick = onMarkAsOwner, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text("与我的资料合并", color = RelationInk)
                    }
                }
                contact.phone?.takeIf(String::isNotBlank)?.let {
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ContactActionButton(Icons.Rounded.Call, "打电话", onCall, Modifier.weight(1f))
                        ContactActionButton(Icons.Rounded.Sms, "发短信", onMessage, Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(22.dp))
                ProfileSectionHeader("身份与称呼", "添加", onAddIdentity)
                if (aliases.isEmpty() && platformIdentities.isEmpty() && contact.wechatId.isNullOrBlank()) {
                    Text(
                        "添加常用称呼或社交账号，知伴才能把不同来源识别为同一个人",
                        color = RelationMuted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                } else {
                    aliases.forEach { alias ->
                        IdentityValueRow(
                            label = when (alias.aliasType) {
                                "NICKNAME" -> "昵称"
                                else -> "常用称呼"
                            },
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
                        }?.let {
                            IdentityValueRow("微信", it, null)
                        }
                    platformIdentities.forEach { identity ->
                        IdentityValueRow(
                            platformLabel(identity.platform),
                            identity.handle,
                            onDelete = { onDeletePlatformIdentity(identity.identityId) },
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                if (mergedSources.isNotEmpty()) {
                    ProfileSectionHeader("已合并的资料")
                    mergedSources.forEach { (link, source) ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    source.displayName,
                                    color = RelationInk,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    "${link.reason} · 原始资料仍完整保留",
                                    color = RelationMuted,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            TextButton(onClick = { onUndoMerge(source.contactId) }) {
                                Text("恢复", color = RelationInk)
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
                ProfileSectionHeader("你确认的信息", "添加", onAddFact)
                if (facts.isEmpty()) {
                    Text(
                        "还没有经过你确认的信息",
                        color = RelationMuted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    facts.forEach { fact ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 9.dp),
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
                                    "已确认 · ${factTypeLabel(fact.factType)} · ${sourceLabel(fact.sourceType)}",
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
                Spacer(Modifier.height(14.dp))
                if (enrichmentSuggestions.isNotEmpty()) {
                    ProfileSectionHeader("知伴建议")
                    enrichmentSuggestions.forEach { candidate ->
                        ContactEnrichmentRow(
                            candidate = candidate,
                            onConfirm = { onConfirmEnrichment(candidate) },
                            onReject = { onRejectEnrichment(candidate) },
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                }
                val openCrmOpportunities = crmOpportunities.filter { it.status == "OPEN" }
                if (openCrmOpportunities.isNotEmpty()) {
                    ProfileSectionHeader("进行中的商机")
                    openCrmOpportunities.forEach { opportunity ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("contact-crm-opp-${opportunity.opportunityId}"),
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
                    Spacer(Modifier.height(14.dp))
                }
                ProfileSectionHeader("相关联系人")
                if (relatedEdges.isEmpty()) {
                    Text(
                        "暂无已确认或可推测的关联",
                        color = RelationMuted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                } else {
                    relatedEdges.forEach { edge ->
                        val otherId = if (edge.fromContactId ==
                            contact.contactId
                        ) {
                            edge.toContactId
                        } else {
                            edge.fromContactId
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
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
                Spacer(Modifier.height(14.dp))
                ProfileSectionHeader("共同经历", "添加", onAddEvent)
                if (relatedEvents.isEmpty()) {
                    Text(
                        "暂无共同经历",
                        color = RelationMuted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                } else {
                    relatedEvents.forEach { event -> RelationshipEventRow(event, onInspectEvent) }
                }
                Spacer(Modifier.height(14.dp))
                ProfileSectionHeader("最近互动")
                if (recentCalls.isEmpty()) {
                    Text(
                        "暂无已关联的通话记录",
                        color = RelationMuted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                } else {
                    recentCalls.take(5).forEach { call ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
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
                Spacer(Modifier.height(14.dp))
                ProfileSectionHeader("联系方式")
                val hasContactDetails = listOf(
                    contact.phone,
                    contact.wechatId,
                    contact.company,
                    contact.title,
                    contact.note,
                ).any {
                    !it.isNullOrBlank()
                }
                if (!hasContactDetails) {
                    Text(
                        "暂未添加联系方式",
                        color = RelationMuted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                } else {
                    DetailValue("手机", contact.phone)
                    DetailValue("微信", contact.wechatId)
                    DetailValue(
                        "公司",
                        listOfNotNull(contact.company, contact.title).joinToString(" · ").takeIf(String::isNotBlank),
                    )
                    DetailValue("备注", contact.note)
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onSaveToPhone, modifier = Modifier.fillMaxWidth()) {
                    Text("保存到手机通讯录", color = RelationInk)
                }
                Text(
                    "将打开手机系统联系人页面，由你检查并确认保存",
                    color = RelationMuted,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
internal fun ContactActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .defaultMinSize(minHeight = ZhiBanSize.TouchTarget)
            .clip(RoundedCornerShape(ZhiBanRadius.Dialog))
            .background(RelationSoft)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = RelationInk, modifier = Modifier.size(ZhiBanIconSize.Inline))
        Spacer(Modifier.width(8.dp))
        Text(label, color = RelationInk, style = MaterialTheme.typography.labelLarge)
    }
}

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
                    Text(suggestion.reason, color = RelationMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "选择合并后保留显示的主资料",
                color = RelationInk,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(8.dp))
            listOf(suggestion.first, suggestion.second).forEach { contact ->
                val selected = canonicalId == contact.contactId
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(ZhiBanRadius.Card))
                        .background(if (selected) RelationSoft else Color.Transparent)
                        .clickable {
                            canonicalId = contact.contactId
                            error = null
                        }
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
                onClick = {
                    val sourceId = if (canonicalId ==
                        suggestion.first.contactId
                    ) {
                        suggestion.second.contactId
                    } else {
                        suggestion.first.contactId
                    }
                    saving = true
                    onConfirm(canonicalId, sourceId) {
                        saving = false
                        error = it
                    }
                },
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

@Composable
internal fun ContactIdentityEditorDialog(
    contact: ContactEntity,
    onDismiss: () -> Unit,
    onSaveAlias: (String, (String?) -> Unit) -> Unit,
    onSavePlatform: (String, String, (String?) -> Unit) -> Unit,
) {
    var mode by remember { mutableStateOf("ALIAS") }
    var platform by remember { mutableStateOf("WECHAT") }
    var value by remember { mutableStateOf("") }
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
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(ZhiBanRadius.Input)).background(RelationSoft).padding(4.dp)) {
                listOf("常用称呼" to "ALIAS", "社交账号" to "PLATFORM").forEach { (label, itemMode) ->
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(ZhiBanRadius.Card))
                            .background(if (mode == itemMode) RelationSurface else Color.Transparent)
                            .clickable {
                                mode = itemMode
                                value = ""
                                error = null
                            }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            color = if (mode ==
                                itemMode
                            ) {
                                RelationInk
                            } else {
                                RelationMuted
                            },
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
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
                        Text(
                            label,
                            color = if (platform == id) MaterialTheme.colorScheme.onPrimary else RelationMuted,
                            modifier = Modifier.clip(RoundedCornerShape(ZhiBanRadius.Card))
                                .background(if (platform == id) RelationAccent else RelationSoft)
                                .clickable {
                                    platform = id
                                    error = null
                                }
                                .padding(horizontal = 13.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelMedium,
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

@Composable
internal fun ContactFactEditorDialog(contact: ContactEntity, onDismiss: () -> Unit, onSave: (String, String, (String?) -> Unit) -> Unit) {
    var text by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("CONTACT_MEMORY") }
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
                    Text(
                        label,
                        color = if (type == value) MaterialTheme.colorScheme.onPrimary else RelationMuted,
                        modifier = Modifier.clip(RoundedCornerShape(ZhiBanRadius.Card))
                            .background(if (type == value) RelationAccent else RelationSoft)
                            .clickable { type = value }.padding(horizontal = 13.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
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
    else -> "字段"
}

internal fun enrichmentValueSummary(proposedValueJson: String): String {
    val obj = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(proposedValueJson).jsonObject
    }.getOrNull() ?: return proposedValueJson.take(60)
    val displayKeys = if (obj["canonicalName"] != null) {
        listOf("canonicalName", "registrationStatus", "creditCode", "registeredAddress")
    } else {
        listOf("company", "title", "phone", "email", "wechatId", "formattedAddress")
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
    "canonicalName", "company" -> "公司"
    "registrationStatus" -> "状态"
    "creditCode" -> "统一信用代码"
    "registeredAddress", "formattedAddress" -> "地址"
    "title" -> "职位"
    "phone" -> "电话"
    "email" -> "邮箱"
    "wechatId" -> "微信"
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
