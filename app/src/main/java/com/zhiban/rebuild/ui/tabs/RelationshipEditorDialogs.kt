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
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.ContactMergeLinkEntity
import com.zhiban.rebuild.data.contact.ContactPlatformIdentityEntity
import com.zhiban.rebuild.data.contact.RelationshipEdgeEntity
import com.zhiban.rebuild.data.contact.RelationshipEventWithParticipants
import com.zhiban.rebuild.data.contact.RelationshipPersonIds
import com.zhiban.rebuild.data.contact.SystemContactCandidate
import com.zhiban.rebuild.data.contact.SystemContactWriteIntent
import com.zhiban.rebuild.data.facts.FactEntity
import com.zhiban.rebuild.data.notification.NotificationCandidateEntity
import com.zhiban.rebuild.data.notification.OutgoingMessageAccessibilityService
import com.zhiban.rebuild.data.notification.ScheduleInsight
import com.zhiban.rebuild.relationship.RelationshipTaxonomy
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
internal fun RelationshipEditorDialog(
    owner: UserProfile,
    contacts: List<ContactEntity>,
    initialTargetId: String? = null,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, (String?) -> Unit) -> Unit,
) {
    val people = buildList {
        add(RelationshipPersonUi(RelationshipPersonIds.SELF, owner.displayNameOrMe(), true))
        contacts.forEach { add(RelationshipPersonUi(it.contactId, it.displayName, false)) }
    }
    var fromId by rememberSaveable(contacts, owner.name) { mutableStateOf(RelationshipPersonIds.SELF) }
    val initialTarget = contacts.firstOrNull { it.contactId == initialTargetId }
    var toId by rememberSaveable(contacts, initialTargetId) { mutableStateOf(initialTarget?.contactId.orEmpty()) }
    var fromQuery by rememberSaveable { mutableStateOf("") }
    var toQuery by rememberSaveable(initialTargetId) { mutableStateOf(initialTarget?.displayName.orEmpty()) }
    var chooseContactAsSource by rememberSaveable { mutableStateOf(false) }
    var type by rememberSaveable { mutableStateOf("") }
    var temporalState by rememberSaveable { mutableStateOf("CURRENT") }
    var error by remember { mutableStateOf<String?>(null) }
    val editorScrollState = rememberScrollState()
    LaunchedEffect(type, editorScrollState.maxValue) {
        if (type.isNotBlank()) editorScrollState.scrollTo(editorScrollState.maxValue)
    }
    fun filteredPeople(query: String, excludedId: String, includeOwner: Boolean): List<RelationshipPersonUi> {
        val clean = query.trim()
        return people.asSequence()
            .filter { it.personId != excludedId && (includeOwner || !it.isOwner) }
            .filter { person ->
                if (clean.isBlank()) {
                    true
                } else {
                    val contact = contacts.firstOrNull { it.contactId == person.personId }
                    listOf(
                        person.displayName,
                        contact?.phone,
                        contact?.company,
                        contact?.title,
                        contact?.note,
                    ).any { it?.contains(clean, ignoreCase = true) == true }
                }
            }
            .take(8)
            .toList()
    }
    ZhiBanDialogHost(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 640.dp)
                    .clip(RoundedCornerShape(ZhiBanRadius.Dialog))
                    .background(RelationSurface)
                    .padding(20.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Close, "关闭") }
                    Text(
                        "添加关系",
                        Modifier.weight(1f),
                        color = RelationInk,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(editorScrollState)
                        .padding(bottom = 8.dp),
                ) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "记录谁和谁",
                        color = RelationInk,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        if (chooseContactAsSource) {
                            "选择两位联系人"
                        } else {
                            "默认记录你与联系人的关系"
                        },
                        color = RelationMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    ZhiBanSegmentedControl(
                        options = listOf("我与联系人", "两位联系人"),
                        selectedIndex = if (chooseContactAsSource) 1 else 0,
                        onSelected = { index ->
                            if (index == 0) {
                                chooseContactAsSource = false
                                fromId = RelationshipPersonIds.SELF
                                fromQuery = ""
                                if (toId == RelationshipPersonIds.SELF) toId = ""
                                error = null
                            } else {
                                chooseContactAsSource = true
                                fromId = ""
                                error = null
                            }
                            type = ""
                            temporalState = "CURRENT"
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (chooseContactAsSource) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = fromQuery,
                            onValueChange = {
                                fromQuery = it
                                error = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("第一位联系人") },
                            placeholder = { Text("姓名、手机号、公司或职位") },
                            singleLine = true,
                        )
                        PersonChoiceRow(filteredPeople(fromQuery, toId, includeOwner = false), fromId) {
                            fromId = it
                            type = ""
                            temporalState = "CURRENT"
                            error = null
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        if (chooseContactAsSource) "选择另一位联系人" else "选择联系人",
                        color = RelationInk,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    OutlinedTextField(
                        value = toQuery,
                        onValueChange = {
                            toQuery = it
                            error = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("搜索联系人") },
                        placeholder = { Text("姓名、手机号、公司或职位") },
                        singleLine = true,
                    )
                    val targetPeople = filteredPeople(toQuery, fromId, includeOwner = chooseContactAsSource)
                    if (targetPeople.isEmpty()) {
                        Text(
                            "没有匹配的联系人",
                            color = RelationMuted,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    } else {
                        PersonChoiceRow(targetPeople, toId) { selectedId ->
                            toId = selectedId
                            type = ""
                            temporalState = "CURRENT"
                            error = null
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    val fromName = people.firstOrNull { it.personId == fromId }?.let {
                        if (it.isOwner) "我" else it.displayName
                    }
                    val toName = people.firstOrNull { it.personId == toId }?.let {
                        if (it.isOwner) "我" else it.displayName
                    }
                    Text(
                        if (fromName != null && toName != null) {
                            "${fromName}和${toName}是什么关系"
                        } else {
                            "选择关系"
                        },
                        color = RelationInk,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(8.dp))
                    RelationshipTypePicker(type) { selectedType ->
                        type = selectedType
                        temporalState = selectedType.takeIf(String::isNotBlank)
                            ?.let(RelationshipTaxonomy::temporalPolicy)
                            ?.defaultValue
                            ?: "CURRENT"
                        error = null
                    }
                    RelationshipTaxonomy.find(type)?.let { definition ->
                        Spacer(Modifier.height(12.dp))
                        Text(
                            definition.evidencePolicy.guidance,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(ZhiBanRadius.Card))
                                .background(RelationSoft)
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                .testTag("relationship-evidence-guidance"),
                            color = RelationMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        definition.temporalPolicy.title?.let { title ->
                            Spacer(Modifier.height(16.dp))
                            Text(
                                title,
                                color = RelationInk,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                definition.temporalPolicy.options.forEach { option ->
                                    ZhiBanChip(
                                        text = option.label,
                                        selected = temporalState == option.value,
                                        color = RelationAccent,
                                        onClick = { temporalState = option.value },
                                    )
                                }
                            }
                        }
                    }
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = RelationDanger, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(14.dp))
                val canSubmit = fromId.isNotBlank() && toId.isNotBlank() && fromId != toId && type.isNotBlank()
                Button(
                    onClick = {
                        if (fromId.isBlank() || toId.isBlank()) {
                            error = "请先添加至少一位联系人"
                        } else if (fromId == toId) {
                            error = "请选择两个不同的联系人"
                        } else if (type.isBlank()) {
                            error = "请选择关系类型"
                        } else {
                            onSave(fromId, toId, type, temporalState) { error = it }
                        }
                    },
                    Modifier.fillMaxWidth().height(ZhiBanSize.Control),
                    colors = ButtonDefaults.buttonColors(containerColor = RelationInk),
                    shape = RoundedCornerShape(ZhiBanRadius.Card),
                    enabled = canSubmit,
                ) {
                    Text(
                        when {
                            chooseContactAsSource && fromId.isBlank() -> "选择第一位联系人"
                            toId.isBlank() -> "选择联系人"
                            type.isBlank() -> "选择关系类型"
                            else -> "确认添加"
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun PersonChoiceRow(people: List<RelationshipPersonUi>, selectedId: String, onSelect: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        people.forEach { person ->
            val label = if (person.isOwner && person.displayName != "我") {
                "我（${person.displayName}）"
            } else if (person.isOwner) {
                "我"
            } else {
                person.displayName
            }
            ZhiBanChip(
                text = label,
                selected = selectedId == person.personId,
                color = RelationAccent,
                onClick = { onSelect(person.personId) },
            )
        }
    }
}

internal fun relationshipEventTypeLabel(type: String): String = when (type) {
    "INTRODUCTION" -> "介绍认识"
    "SHARED_PROJECT" -> "共同项目"
    "SHARED_EVENT" -> "共同活动"
    "FAMILY_MILESTONE" -> "家庭事件"
    else -> "其他经历"
}

internal fun relationshipEventRoleLabel(role: String): String = when (role) {
    "SUBJECT" -> "相关联系人"
    "INTRODUCER" -> "介绍人"
    "RECIPIENT" -> "认识的一方"
    "PARTICIPANT" -> "参与者"
    else -> "参与者"
}

@Composable
internal fun RelationshipEventRow(value: RelationshipEventWithParticipants, onClick: (RelationshipEventWithParticipants) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(ZhiBanRadius.Medium)).clickable { onClick(value) }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(RelationSoft), contentAlignment = Alignment.Center) {
            Text(
                "事",
                color = RelationInk,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
            Text(
                value.event.title,
                color = RelationInk,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${relationshipEventTypeLabel(value.event.eventType)} · ${value.participants.joinToString("、") {
                    it.displayNameSnapshot
                }}",
                color = RelationMuted,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text("查看", color = RelationMuted, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun RelationshipParticipantPicker(
    contacts: List<ContactEntity>,
    selected: ContactEntity?,
    query: String,
    required: Boolean,
    onQueryChange: (String) -> Unit,
    onSelect: (ContactEntity?) -> Unit,
) {
    if (selected != null) {
        Row(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 56.dp)
                .clip(RoundedCornerShape(ZhiBanRadius.Card))
                .background(RelationSoft)
                .padding(start = 12.dp, end = 4.dp)
                .testTag("relationship-participant-selected-${selected.contactId}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(RelationSurface),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    selected.displayName.take(1),
                    color = RelationInk,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(
                    selected.displayName,
                    color = RelationInk,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                selected.company?.takeIf(String::isNotBlank)?.let {
                    Text(
                        it,
                        color = RelationMuted,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            TextButton(onClick = { onSelect(null) }) {
                Text(if (required) "更换" else "移除", color = RelationAccent)
            }
        }
        return
    }

    if (!required) {
        Text(
            "只有我和TA",
            color = RelationMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 7.dp),
        )
    }
    ZhiBanSearchField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = if (required) "搜索介绍人" else "搜索参与人",
        modifier = Modifier.testTag("relationship-participant-search"),
    )
    val normalizedQuery = query.trim()
    val matches = remember(normalizedQuery, contacts) {
        if (normalizedQuery.isBlank()) {
            emptyList()
        } else {
            contacts.asSequence().filter { contact ->
                listOf(contact.displayName, contact.phone, contact.company, contact.note)
                    .filterNotNull()
                    .any { it.contains(normalizedQuery, ignoreCase = true) }
            }.take(6).toList()
        }
    }
    if (normalizedQuery.isNotBlank()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .clip(RoundedCornerShape(ZhiBanRadius.Card))
                .background(RelationSoft),
        ) {
            matches.forEachIndexed { index, contact ->
                if (index > 0) HorizontalDivider(color = RelationLine)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = ZhiBanSize.TouchTarget)
                        .clickable { onSelect(contact) }
                        .padding(horizontal = 12.dp)
                        .testTag("relationship-participant-${contact.contactId}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        contact.displayName,
                        color = RelationInk,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    contact.company?.takeIf(String::isNotBlank)?.let {
                        Text(
                            it,
                            color = RelationMuted,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (matches.isEmpty()) {
                Text(
                    "没有匹配的联系人",
                    color = RelationMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

@Composable
internal fun RelationshipEventEditorDialog(
    contacts: List<ContactEntity>,
    subject: ContactEntity,
    existing: RelationshipEventWithParticipants?,
    onDismiss: () -> Unit,
    onSave: (String, String, String?, List<RelationshipEventParticipantInput>, (String?) -> Unit) -> Unit,
) {
    val others = contacts.filterNot {
        it.contactId == subject.contactId || it.contactId == RelationshipPersonIds.SELF
    }
    val existingRelatedId = existing?.participants?.firstOrNull {
        it.contactId != null && it.contactId != subject.contactId
    }?.contactId.orEmpty()
    var type by remember(existing?.event?.eventId) { mutableStateOf(existing?.event?.eventType ?: "INTRODUCTION") }
    var relatedId by remember(existing?.event?.eventId) {
        mutableStateOf(if (existing != null) existingRelatedId else "")
    }
    var relatedQuery by remember(existing?.event?.eventId) { mutableStateOf("") }
    var title by remember(existing?.event?.eventId) { mutableStateOf(existing?.event?.title.orEmpty()) }
    var note by remember(existing?.event?.eventId) { mutableStateOf(existing?.event?.note.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val types = listOf("INTRODUCTION", "SHARED_PROJECT", "SHARED_EVENT", "FAMILY_MILESTONE", "OTHER")
    val related = others.firstOrNull { it.contactId == relatedId }
    fun suggestedTitle(): String = when (type) {
        "INTRODUCTION" -> related?.let { "${it.displayName}介绍我认识${subject.displayName}" } ?: "认识${subject.displayName}"
        "SHARED_PROJECT" -> "和${subject.displayName}的共同项目"
        "SHARED_EVENT" -> "和${subject.displayName}参加活动"
        "FAMILY_MILESTONE" -> "${subject.displayName}的重要家庭事件"
        else -> "和${subject.displayName}的一段经历"
    }
    ZhiBanDialogHost(onDismissRequest = onDismiss) {
        LazyColumn(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(ZhiBanRadius.Dialog)).background(RelationSurface),
            contentPadding = PaddingValues(20.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Close, "关闭") }
                    Text(
                        if (existing ==
                            null
                        ) {
                            "添加共同经历"
                        } else {
                            "编辑共同经历"
                        },
                        Modifier.weight(1f),
                        color = RelationInk,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text("记录你和联系人之间真实发生过的事情", color = RelationMuted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(16.dp))
                Text("经历类型", color = RelationMuted, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(7.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    types.forEach { value ->
                        ZhiBanChip(
                            text = relationshipEventTypeLabel(value),
                            selected = type == value,
                            color = RelationAccent,
                            onClick = {
                                type = value
                                relatedId = ""
                                relatedQuery = ""
                                title = ""
                                error = null
                            },
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    if (type == "INTRODUCTION") "介绍人" else "其他参与人（可选）",
                    color = RelationMuted,
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.height(7.dp))
                RelationshipParticipantPicker(
                    contacts = others,
                    selected = related,
                    query = relatedQuery,
                    required = type == "INTRODUCTION",
                    onQueryChange = { relatedQuery = it },
                    onSelect = { contact ->
                        relatedId = contact?.contactId.orEmpty()
                        relatedQuery = ""
                        title = ""
                        error = null
                    },
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        title = it
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("经历名称（可选）") },
                    placeholder = { Text(suggestedTitle()) },
                    singleLine = true,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("补充说明（可选）") },
                    minLines = 2,
                    maxLines = 4,
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = RelationDanger, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = {
                        if (type == "INTRODUCTION" && related == null) {
                            error = "介绍认识需要选择介绍人"
                        } else {
                            val roles = buildList {
                                add(
                                    RelationshipEventParticipantInput(
                                        "USER",
                                        null,
                                        if (type ==
                                            "INTRODUCTION"
                                        ) {
                                            "RECIPIENT"
                                        } else {
                                            "PARTICIPANT"
                                        },
                                        "我",
                                    ),
                                )
                                add(
                                    RelationshipEventParticipantInput(
                                        "CONTACT",
                                        subject.contactId,
                                        "SUBJECT",
                                        subject.displayName,
                                    ),
                                )
                                related?.let {
                                    add(
                                        RelationshipEventParticipantInput(
                                            "CONTACT",
                                            it.contactId,
                                            if (type ==
                                                "INTRODUCTION"
                                            ) {
                                                "INTRODUCER"
                                            } else {
                                                "PARTICIPANT"
                                            },
                                            it.displayName,
                                        ),
                                    )
                                }
                            }
                            onSave(type, title.ifBlank(::suggestedTitle), note, roles) { error = it }
                        }
                    },
                    Modifier.fillMaxWidth().height(ZhiBanSize.Control),
                    colors = ButtonDefaults.buttonColors(containerColor = RelationInk),
                    shape = RoundedCornerShape(ZhiBanRadius.Card),
                ) { Text("确认保存") }
            }
        }
    }
}

@Composable
internal fun RelationshipEventDetailDialog(value: RelationshipEventWithParticipants, onDismiss: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    ZhiBanDialogHost(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(ZhiBanRadius.Dialog)).background(RelationSurface).padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Close, "关闭") }
                Text(
                    "共同经历",
                    Modifier.weight(1f),
                    color = RelationInk,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                value.event.title,
                color = RelationInk,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                "${relationshipEventTypeLabel(value.event.eventType)} · 你确认的信息",
                color = RelationMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(16.dp))
            value.participants.forEach { participant ->
                Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        participant.displayNameSnapshot,
                        Modifier.weight(1f),
                        color = RelationInk,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        relationshipEventRoleLabel(participant.participantRole),
                        color = RelationMuted,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            value.event.note?.takeIf(String::isNotBlank)?.let {
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(ZhiBanRadius.Card)).background(RelationSoft).padding(14.dp)) {
                    Text(it, color = RelationInk, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("来源：你在联系人资料中添加", color = RelationMuted, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onEdit,
                Modifier.fillMaxWidth().height(ZhiBanSize.Control),
                colors = ButtonDefaults.buttonColors(containerColor = RelationInk),
                shape = RoundedCornerShape(ZhiBanRadius.Card),
            ) { Text("编辑这段经历") }
            TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text("删除这段经历", color = RelationDanger)
            }
        }
    }
}

@Composable
internal fun RelationshipEvidenceDialog(
    edge: RelationshipEdgeEntity,
    personNames: Map<String, String>,
    onDismiss: () -> Unit,
    onUpdate: (String, (String?) -> Unit) -> Unit,
    onDelete: () -> Unit,
) {
    var type by remember(edge.edgeId) { mutableStateOf(edge.relationType) }
    var error by remember { mutableStateOf<String?>(null) }
    val from = personNames[edge.fromContactId] ?: "未知联系人"
    val to = personNames[edge.toContactId] ?: "未知联系人"
    ZhiBanDialogHost(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 720.dp)
                .clip(RoundedCornerShape(ZhiBanRadius.Dialog))
                .background(RelationSurface)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Close, "关闭") }
                Text(
                    "关系详情",
                    Modifier.weight(1f),
                    color = RelationInk,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                "$from 与 $to",
                color = RelationInk,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (edge.userConfirmed) "由你确认 · 可修改" else "聊天中识别 · 待你确认",
                color = RelationMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(18.dp))
            Text("${from}和${to}是什么关系", color = RelationMuted, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(8.dp))
            RelationshipTypePicker(type, enabled = edge.userConfirmed) { selectedType ->
                type = selectedType
                error = null
            }
            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(ZhiBanRadius.Card)).background(RelationSoft).padding(14.dp)) {
                Column {
                    Text("来源", color = RelationMuted, style = MaterialTheme.typography.labelSmall)
                    Text(
                        if (edge.evidenceRefsJson.contains("USER_PROFILE")) {
                            "你在联系人资料中添加"
                        } else {
                            "知伴在聊天中提取，并保留原始记录"
                        },
                        color = RelationInk,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = RelationDanger, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(18.dp))
            if (edge.userConfirmed) {
                Button(
                    onClick = { onUpdate(type) { error = it } },
                    Modifier.fillMaxWidth().height(ZhiBanSize.Control),
                    colors = ButtonDefaults.buttonColors(containerColor = RelationInk),
                    shape = RoundedCornerShape(ZhiBanRadius.Card),
                ) { Text("保存修改") }
                TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text("删除这条关系", color = RelationDanger)
                }
            }
        }
    }
}
