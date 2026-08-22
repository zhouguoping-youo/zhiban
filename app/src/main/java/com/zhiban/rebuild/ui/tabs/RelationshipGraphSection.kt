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
import com.zhiban.rebuild.R
import com.zhiban.rebuild.data.agent.RelationshipEventParticipantInput
import com.zhiban.rebuild.data.calllog.CallRecordEntity
import com.zhiban.rebuild.data.contact.ContactAliasEntity
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.ContactMergeLinkEntity
import com.zhiban.rebuild.data.contact.ContactPlatformIdentityEntity
import com.zhiban.rebuild.data.contact.PersonEmploymentEpisodeEntity
import com.zhiban.rebuild.data.contact.RelationshipEdgeEntity
import com.zhiban.rebuild.data.contact.RelationshipEventWithParticipants
import com.zhiban.rebuild.data.contact.RelationshipPersonIds
import com.zhiban.rebuild.data.contact.SystemContactCandidate
import com.zhiban.rebuild.data.contact.SystemContactWriteIntent
import com.zhiban.rebuild.data.facts.FactEntity
import com.zhiban.rebuild.data.interaction.ContactInteractionIntensity
import com.zhiban.rebuild.data.notification.NotificationCandidateEntity
import com.zhiban.rebuild.data.notification.OutgoingMessageAccessibilityService
import com.zhiban.rebuild.data.notification.ScheduleInsight
import com.zhiban.rebuild.relationship.RelationshipGroup
import com.zhiban.rebuild.relationship.RelationshipTaxonomy
import com.zhiban.rebuild.runtime.input.asr.CloudAsrAvailability
import com.zhiban.rebuild.runtime.personalization.UserProfile
import com.zhiban.rebuild.ui.components.ZhiBanCompactEmptyState
import com.zhiban.rebuild.ui.components.ZhiBanPrimaryTabHeader
import com.zhiban.rebuild.ui.components.ZhiBanSearchField
import com.zhiban.rebuild.ui.components.ZhiBanTabBottomSpacer
import com.zhiban.rebuild.ui.components.ZhiBanTabHorizontalPadding
import com.zhiban.rebuild.ui.components.ZhiBanTabTopPadding
import com.zhiban.rebuild.ui.components.localizedQuantity
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
    historicalEdges: List<RelationshipEdgeEntity> = emptyList(),
    currentOwnerEmployment: PersonEmploymentEpisodeEntity? = null,
    ownerEmploymentHistoryCount: Int = 0,
    interactionIntensity: List<ContactInteractionIntensity> = emptyList(),
    relationshipEvents: List<RelationshipEventWithParticipants> = emptyList(),
    canAddRelationship: Boolean,
    activeFilter: String?,
    activeGroup: RelationshipGroup? = null,
    onAdd: () -> Unit,
    onEditOwnerEmployment: () -> Unit = {},
    onOpenContact: (String) -> Unit = {},
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
    val viewPathState = rememberSaveable(owner.name) {
        mutableStateOf(listOf(RelationshipPersonIds.SELF))
    }
    var selectedSection by rememberSaveable { mutableStateOf("graph") }
    var selectedPersonId by rememberSaveable { mutableStateOf<String?>(null) }
    val storedViewPath = viewPathState.value
    val viewPath = storedViewPath.takeIf { it.lastOrNull() in peopleById }
        ?: listOf(RelationshipPersonIds.SELF)
    if (viewPath != storedViewPath) viewPathState.value = viewPath
    val rootId = viewPath.lastOrNull() ?: RelationshipPersonIds.SELF
    val allValidEdges = remember(edges, historicalEdges, peopleById) {
        mergeCurrentAndHistoricalRelationships(edges, historicalEdges)
            .filter { it.fromContactId in peopleById && it.toContactId in peopleById }
    }
    val visibleEdges = remember(allValidEdges, rootId) {
        allValidEdges.filter { it.fromContactId == rootId || it.toContactId == rootId }
    }
    val graphProjection = remember(rootId, allValidEdges, peopleById, interactionIntensity) {
        projectRelationshipGraph(
            rootId = rootId,
            peopleIds = peopleById.keys,
            edges = allValidEdges,
            interactionIntensity = interactionIntensity,
        )
    }
    val displayedEdges = graphProjection.edges
    val root = peopleById.getValue(rootId)
    val graphEdges = displayedEdges
    val graphRootId = rootId
    val graphNeighborIds = remember(graphEdges, graphRootId, peopleById) {
        graphEdges
            .flatMap { listOf(it.fromContactId, it.toContactId) }
            .filter { it != graphRootId }
            .distinct()
            .sortedBy { peopleById[it]?.displayName.orEmpty() }
    }
    val graphNodeIds = remember(graphNeighborIds, graphRootId) { graphNeighborIds.toSet() + graphRootId }
    val visibleEdgesForGraph = remember(graphEdges, graphNodeIds) {
        graphEdges
            .filter { it.fromContactId in graphNodeIds && it.toContactId in graphNodeIds }
            .distinctBy { edge ->
                val ordered = listOf(edge.fromContactId, edge.toContactId).sorted()
                "${ordered[0]}::${ordered[1]}::${edge.relationType}"
            }
    }
    fun switchEgo(nextId: String) {
        if (nextId !in peopleById || nextId == rootId) return
        selectedPersonId = nextId
        val existingIndex = viewPath.indexOf(nextId)
        viewPathState.value = if (existingIndex >= 0) {
            viewPath.take(existingIndex + 1)
        } else {
            viewPath + nextId
        }
    }
    Column(
        Modifier.fillMaxWidth(),
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
                        "${localizedQuantity(R.plurals.contact_count, contacts.size)} · " +
                            localizedQuantity(R.plurals.relationship_count, allValidEdges.size)
                    } else {
                        localizedQuantity(R.plurals.direct_relationship_count, visibleEdges.size)
                    },
                    color = RelationMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (!root.isOwner) {
                TextButton(onClick = { viewPathState.value = listOf(RelationshipPersonIds.SELF) }) {
                    Text("回到我", color = RelationInk)
                }
            }
            TextButton(onClick = onAdd, enabled = canAddRelationship) {
                Text("添加关系", color = if (canAddRelationship) RelationInk else RelationMuted)
            }
        }
        if (viewPath.size > 1) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                viewPath.forEachIndexed { index, id ->
                    if (index > 0) Text(" / ", color = RelationMuted, style = MaterialTheme.typography.labelSmall)
                    TextButton(
                        onClick = { viewPathState.value = viewPath.take(index + 1) },
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
                    ) {
                        Text(peopleById[id]?.displayName ?: "我", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        RelationshipGraphContentTabs(
            selected = selectedSection,
            onSelected = { selectedSection = it },
        )
        if (root.isOwner && activeGroup == RelationshipGroup.WORK &&
            shouldShowOwnerEmploymentAnchor(owner.company, currentOwnerEmployment)
        ) {
            Spacer(Modifier.height(ZhiBanSpacing.Md))
            OwnerEmploymentAnchor(
                current = currentOwnerEmployment,
                pastCount = ownerEmploymentHistoryCount,
                onEdit = onEditOwnerEmployment,
            )
        }
        if (selectedSection == "timeline") {
            RelationshipTimeline(
                events = relationshipEvents,
                peopleById = peopleById,
                rootId = rootId,
            )
        } else if (contacts.isEmpty()) {
            ZhiBanCompactEmptyState(
                title = relationshipGraphEmptyMessage(activeFilter, activeGroup),
                icon = Icons.Rounded.Groups,
            )
        } else if (displayedEdges.isEmpty()) {
            ZhiBanCompactEmptyState(
                title = if (root.isOwner) {
                    "还没有与我相关的可靠关系"
                } else {
                    "这个人还没有已确认的关联联系人"
                },
                icon = Icons.Rounded.Groups,
                primaryLabel = if (root.isOwner && canAddRelationship) "添加关系" else null,
                onPrimary = if (root.isOwner && canAddRelationship) onAdd else null,
            )
        } else {
            Spacer(Modifier.height(12.dp))
            if (graphEdges.isNotEmpty()) {
                ForceRelationshipGraphCanvas(
                    rootId = graphRootId,
                    peopleById = peopleById,
                    edges = visibleEdgesForGraph,
                    presentationById = relationshipGraphPresentation(graphProjection),
                    onSelectContact = { selectedPersonId = it },
                    onSwitchEgo = ::switchEgo,
                    onSelectionChanged = { selectedPersonId = it },
                )
                selectedPersonId?.takeIf { it != RelationshipPersonIds.SELF }?.let { selectedId ->
                    val selectedPerson = peopleById[selectedId]
                    if (selectedPerson != null) {
                        Spacer(Modifier.height(ZhiBanSpacing.Md))
                        RelationshipGraphPersonCard(
                            person = selectedPerson,
                            relationship = visibleEdgesForGraph.firstOrNull {
                                (it.fromContactId == graphRootId && it.toContactId == selectedId) ||
                                    (it.toContactId == graphRootId && it.fromContactId == selectedId)
                            },
                            interaction = interactionIntensity.firstOrNull { it.contactId == selectedId },
                            onOpen = { onOpenContact(selectedId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RelationshipGraphContentTabs(selected: String, onSelected: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = ZhiBanSpacing.Md, bottom = ZhiBanSpacing.Sm)
            .height(48.dp)
            .clip(RoundedCornerShape(ZhiBanRadius.Medium))
            .background(RelationSoft)
            .padding(3.dp),
    ) {
        listOf("graph" to "关系图", "timeline" to "时间线").forEach { (value, label) ->
            val active = selected == value
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(ZhiBanRadius.Small))
                    .background(if (active) RelationSurface else Color.Transparent)
                    .clickable { onSelected(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (active) RelationAccent else RelationMuted,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                )
                if (active) {
                    Box(
                        Modifier.align(Alignment.BottomCenter).width(38.dp).height(2.dp)
                            .background(RelationAccent, RoundedCornerShape(ZhiBanRadius.Full)),
                    )
                }
            }
        }
    }
}

@Composable
private fun RelationshipTimeline(events: List<RelationshipEventWithParticipants>, peopleById: Map<String, RelationshipPersonUi>, rootId: String) {
    val visibleEvents = remember(events, rootId) {
        events.filter { event ->
            rootId == RelationshipPersonIds.SELF || event.participants.any { it.contactId == rootId }
        }.sortedByDescending { it.event.occurredAtEpochMs ?: it.event.updatedAtEpochMs }
    }
    if (visibleEvents.isEmpty()) {
        Text(
            "还没有可追溯的共同经历",
            modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
            color = RelationMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    Column(
        Modifier.fillMaxWidth().zhiBanCardSurface(RelationSurface).padding(horizontal = 16.dp),
    ) {
        visibleEvents.take(20).forEachIndexed { index, value ->
            if (index > 0) HorizontalDivider(color = RelationLine)
            Column(Modifier.fillMaxWidth().padding(vertical = 13.dp)) {
                Text(
                    value.event.title.ifBlank { relationshipEventTypeLabel(value.event.eventType) },
                    color = RelationInk,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                val participantNames = value.participants.mapNotNull { participant ->
                    participant.contactId?.let { peopleById[it]?.displayName } ?: participant.displayNameSnapshot
                }.filter(String::isNotBlank).distinct().joinToString(" · ")
                val occurredAt = value.event.occurredAtEpochMs ?: value.event.updatedAtEpochMs
                Text(
                    listOf(
                        DateTimeFormatter.ofPattern("yyyy年M月d日").format(
                            Instant.ofEpochMilli(occurredAt).atZone(ZoneId.systemDefault()),
                        ),
                        participantNames,
                    ).filter(String::isNotBlank).joinToString(" · "),
                    color = RelationMuted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun RelationshipGraphPersonCard(
    person: RelationshipPersonUi,
    relationship: RelationshipEdgeEntity?,
    interaction: ContactInteractionIntensity?,
    onOpen: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().zhiBanCardSurface(RelationSurface).clickable(onClick = onOpen)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(RelationSoft),
            contentAlignment = Alignment.Center,
        ) {
            Text(person.displayName.take(1), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(person.displayName, color = RelationInk, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val lastInteraction = interaction?.lastInteractionAtEpochMs?.let {
                relationshipInteractionRecency(it, System.currentTimeMillis())
            }
            Text(
                listOfNotNull(relationship?.displayRelationLabel(), lastInteraction, person.company)
                    .distinct().take(2).joinToString(" · ").ifBlank { "查看关系档案" },
                color = RelationMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text("查看档案", color = RelationAccent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * The profile company is the user's authoritative current-company input.  A temporal employment
 * episode is optional enrichment, so it must not trigger the same completion prompt when the
 * profile already has a company value.
 */
internal fun shouldShowOwnerEmploymentAnchor(ownerCompany: String, currentEmployment: PersonEmploymentEpisodeEntity?): Boolean =
    ownerCompany.isBlank() && currentEmployment == null

private fun relationshipGraphEmptyMessage(activeFilter: String?, group: RelationshipGroup?): String = when {
    group != null -> "还没有可靠关系 · ${RelationshipTaxonomy.groupGuidance(group)}"
    activeFilter != null -> "没有找到与“$activeFilter”匹配的关系"
    else -> "还没有与我相关的可靠关系"
}

internal data class RelationshipPersonUi(
    val personId: String,
    val displayName: String,
    val isOwner: Boolean,
    val company: String? = null,
    val title: String? = null,
)

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
                color = if (person.isOwner) MaterialTheme.colorScheme.onPrimary else RelationInk,
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
