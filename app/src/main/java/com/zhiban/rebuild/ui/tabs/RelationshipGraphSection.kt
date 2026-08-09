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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
internal fun RelationshipGraphState(
    owner: UserProfile,
    contacts: List<ContactEntity>,
    edges: List<RelationshipEdgeEntity>,
    events: List<RelationshipEventWithParticipants>,
    canAddRelationship: Boolean,
    activeFilter: String?,
    onAdd: () -> Unit,
    onInspect: (RelationshipEdgeEntity) -> Unit,
    onInspectEvent: (RelationshipEventWithParticipants) -> Unit,
    onDelete: (RelationshipEdgeEntity) -> Unit,
) {
    val peopleById = remember(owner, contacts) {
        buildMap {
            put(
                RelationshipPersonIds.SELF,
                RelationshipPersonUi(
                    personId = RelationshipPersonIds.SELF,
                    displayName = owner.displayNameOrMe(),
                    isOwner = true,
                ),
            )
            contacts.forEach { contact ->
                put(
                    contact.contactId,
                    RelationshipPersonUi(
                        personId = contact.contactId,
                        displayName = contact.displayName,
                        isOwner = false,
                        company = contact.company,
                        title = contact.title,
                    ),
                )
            }
        }
    }
    var rootId by remember(contacts, owner.name) { mutableStateOf(RelationshipPersonIds.SELF) }
    if (rootId !in peopleById) rootId = RelationshipPersonIds.SELF
    val allValidEdges = remember(edges, peopleById) {
        edges.filter { it.fromContactId in peopleById && it.toContactId in peopleById }
    }
    val inferredEdgesCount = remember(allValidEdges) {
        allValidEdges.count(RelationshipEdgeEntity::isInferredCompanyRelationship)
    }
    val visibleEdges = remember(allValidEdges, rootId) {
        allValidEdges.filter { it.fromContactId == rootId || it.toContactId == rootId }
    }
    val root = peopleById.getValue(rootId)
    val relatedContactIds = remember(visibleEdges, rootId) {
        visibleEdges.flatMap { edge -> listOf(edge.fromContactId, edge.toContactId) }
            .filter { it != rootId }
            .toSet()
    }
    val graphNeighborIds = remember(visibleEdges, rootId, peopleById) {
        visibleEdges
            .flatMap { listOf(it.fromContactId, it.toContactId) }
            .filter { it != rootId }
            .distinct()
            .sortedBy { peopleById[it]?.displayName.orEmpty() }
            .take(24)
    }
    val graphNodeIds = remember(graphNeighborIds, rootId) { graphNeighborIds.toSet() + rootId }
    val visibleEdgesForGraph = remember(allValidEdges, graphNodeIds) {
        allValidEdges
            .filter { it.fromContactId in graphNodeIds && it.toContactId in graphNodeIds }
            .distinctBy { edge ->
                val ordered = listOf(edge.fromContactId, edge.toContactId).sorted()
                "${ordered[0]}::${ordered[1]}::${edge.relationType}"
            }
    }
    val hiddenGraphNodesCount = (relatedContactIds.size - graphNeighborIds.size).coerceAtLeast(0)
    val relatedEvents = remember(root, events, relatedContactIds, rootId) {
        if (root.isOwner) {
            events.filter { event ->
                event.participants.any { participant -> participant.contactId in relatedContactIds }
            }
        } else {
            events.filter { event ->
                event.participants.any {
                    it.contactId == null || it.contactId == rootId || it.contactId in relatedContactIds
                }
            }
        }
    }
    Column(
        Modifier.fillMaxWidth().zhiBanCardSurface(RelationSurface).padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (root.isOwner) "我的关系图" else root.displayName,
                    color = RelationInk,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (root.isOwner) {
                        "${contacts.size} 位联系人 · ${allValidEdges.size} 条关联"
                    } else {
                        "${visibleEdges.size} 条直接关系"
                    },
                    color = RelationMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (!root.isOwner) {
                TextButton(onClick = { rootId = RelationshipPersonIds.SELF }) {
                    Text("回到我", color = RelationInk)
                }
            }
            TextButton(onClick = onAdd, enabled = canAddRelationship) {
                Text("添加关系", color = if (canAddRelationship) RelationInk else RelationMuted)
            }
        }
        if (contacts.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                if (activeFilter != null) {
                    "“$activeFilter”下还没有匹配的联系人或关系"
                } else {
                    "添加联系人后，可以确认你和对方的关系"
                },
                color = RelationMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        } else if (visibleEdges.isEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text(
                if (root.isOwner) {
                    "还没有确认你与联系人的关系"
                } else {
                    "这个人还没有已确认的关联联系人"
                },
                color = RelationMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            if (root.isOwner && allValidEdges.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(RelationLine))
                Spacer(Modifier.height(14.dp))
                Text(
                    "联系人之间的关系",
                    Modifier.fillMaxWidth(),
                    color = RelationInk,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                RelationshipRows(allValidEdges.take(12), peopleById, onInspect, onDelete) { rootId = it }
            }
        } else {
            Spacer(Modifier.height(12.dp))
            Text(
                "关系网络",
                Modifier.fillMaxWidth(),
                color = RelationInk,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(16.dp))
            if (visibleEdges.isNotEmpty()) {
                ForceRelationshipGraphCanvas(
                    rootId = rootId,
                    peopleById = peopleById,
                    edges = visibleEdgesForGraph,
                    onSelectContact = { rootId = it },
                )
                Spacer(Modifier.height(14.dp))
                if (hiddenGraphNodesCount > 0) {
                    Text(
                        "当前焦点还有 $hiddenGraphNodesCount 位联系人未铺开；点任意节点继续探索",
                        color = RelationMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    "关系清单",
                    Modifier.fillMaxWidth(),
                    color = RelationInk,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(6.dp))
                RelationshipRows(visibleEdges.take(12), peopleById, onInspect, onDelete, onSelectContact = {
                    rootId = it
                })
                Spacer(Modifier.height(12.dp))
            }
            if (allValidEdges.size > 12) {
                Text("仅展示 12 条关系，可在联系人页打开更多", color = RelationMuted, style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            if (inferredEdgesCount > 0) {
                "实线为已确认关系；虚线为同公司资料自动推测"
            } else {
                "只展示已保存、已确认且能追溯来源的关系"
            },
            color = RelationMuted,
            style = MaterialTheme.typography.labelSmall,
        )
        if (relatedEvents.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(RelationLine))
            Spacer(Modifier.height(14.dp))
            Text(
                "共同经历",
                Modifier.fillMaxWidth(),
                color = RelationInk,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            relatedEvents.take(5).forEach { event ->
                RelationshipEventRow(event, onInspectEvent)
            }
        }
    }
}

@Composable
internal fun RelationshipRows(
    edges: List<RelationshipEdgeEntity>,
    peopleById: Map<String, RelationshipPersonUi>,
    onInspect: (RelationshipEdgeEntity) -> Unit,
    onDelete: (RelationshipEdgeEntity) -> Unit,
    onSelectContact: (String) -> Unit,
) {
    edges.forEachIndexed { index, edge ->
        val from = peopleById.getValue(edge.fromContactId)
        val to = peopleById.getValue(edge.toContactId)
        RelationshipGraphCard(
            edge = edge,
            from = from,
            to = to,
            onInspect = onInspect,
            onDelete = onDelete,
            onSelectContact = onSelectContact,
        )
        if (index != edges.lastIndex) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .padding(horizontal = 10.dp)
                    .background(RelationLine),
            )
        }
    }
}

@Composable
internal fun RelationshipGraphCard(
    edge: RelationshipEdgeEntity,
    from: RelationshipPersonUi,
    to: RelationshipPersonUi,
    onInspect: (RelationshipEdgeEntity) -> Unit,
    onDelete: (RelationshipEdgeEntity) -> Unit,
    onSelectContact: (String) -> Unit,
) {
    val inferredFromCompany = edge.isInferredCompanyRelationship()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(RelationSoft)
            .clickable(enabled = !inferredFromCompany) { onInspect(edge) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GraphPersonAvatar(from) { onSelectContact(from.personId) }
        Spacer(Modifier.width(6.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 6.dp)) {
            Text("↔", color = RelationInk, style = MaterialTheme.typography.labelSmall)
            Text(
                relationLabel(edge.relationType),
                color = RelationInk,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (inferredFromCompany) {
                Text("同公司推测", color = RelationMuted, style = MaterialTheme.typography.labelSmall)
            } else if (!edge.userConfirmed) {
                Text("待确认", color = RelationMuted, style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.width(6.dp))
        GraphPersonAvatar(to) { onSelectContact(to.personId) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (from.displayName == to.displayName) to.displayName else "${from.displayName} · ${to.displayName}",
                color = RelationInk,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    inferredFromCompany -> "由联系人公司资料自动关联"
                    edge.userConfirmed -> "可点击查看"
                    else -> "待你确认"
                },
                color = RelationMuted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (!inferredFromCompany) {
            IconButton(
                onClick = { onDelete(edge) },
                modifier = Modifier.size(ZhiBanSize.TouchTarget),
                enabled = edge.userConfirmed,
            ) {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    "删除${from.displayName}和${to.displayName}的关系",
                    tint = RelationMuted,
                    modifier = Modifier.size(ZhiBanIconSize.Inline),
                )
            }
        }
    }
}

@Composable
internal fun GraphPersonAvatar(person: RelationshipPersonUi, onClick: () -> Unit) {
    Box(
        Modifier
            .size(ZhiBanSize.TouchTarget)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(38.dp).clip(CircleShape)
                .background(if (person.isOwner) RelationAccent else RelationSoft),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                person.displayName.take(1),
                color = if (person.isOwner) Color.White else RelationInk,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

internal data class RelationshipPersonUi(
    val personId: String,
    val displayName: String,
    val isOwner: Boolean,
    val company: String? = null,
    val title: String? = null,
)

@Composable
internal fun GraphCenterNode(person: RelationshipPersonUi) {
    Box(
        Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(if (person.isOwner) RelationAccent else RelationSurface)
            .border(
                width = if (person.isOwner) 0.dp else 1.dp,
                color = if (person.isOwner) Color.Transparent else RelationLine,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (person.isOwner) "我" else person.displayName.take(1),
            color = if (person.isOwner) Color.White else RelationInk,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun GraphPersonNode(person: RelationshipPersonUi, labelAbove: Boolean = false, onClick: (() -> Unit)? = null) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .width(76.dp)
            .defaultMinSize(minHeight = ZhiBanSize.TouchTarget)
            .semantics { contentDescription = "查看${person.displayName}" }
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 2.dp),
    ) {
        if (labelAbove) {
            GraphPersonName(person)
            Spacer(Modifier.height(6.dp))
        }
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (person.isOwner) RelationAccent else RelationSurface)
                .border(
                    width = if (person.isOwner) 0.dp else 1.dp,
                    color = if (person.isOwner) Color.Transparent else RelationLine,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                person.displayName.take(1),
                color = if (person.isOwner) Color.White else RelationInk,
                fontWeight = FontWeight.Medium,
            )
        }
        if (!labelAbove) {
            Spacer(Modifier.height(6.dp))
            GraphPersonName(person)
        }
    }
}

@Composable
internal fun GraphPersonName(person: RelationshipPersonUi) {
    Text(
        if (person.isOwner) "我" else person.displayName,
        color = RelationInk,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
