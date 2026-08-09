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
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.zhiban.rebuild.ui.components.ZhiBanAlertDialog
import com.zhiban.rebuild.ui.components.ZhiBanHeaderIconAction
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
import kotlinx.coroutines.launch

internal val RelationBackground: Color @Composable get() = MaterialTheme.colorScheme.background
internal val RelationSurface: Color @Composable get() = MaterialTheme.colorScheme.surface
internal val RelationInk: Color @Composable get() = MaterialTheme.colorScheme.onBackground
internal val RelationMuted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
internal val RelationSoft: Color @Composable get() = MaterialTheme.colorScheme.surfaceVariant
internal val RelationLine: Color @Composable get() = MaterialTheme.colorScheme.outlineVariant
internal val RelationAccent: Color @Composable get() = MaterialTheme.colorScheme.primary
internal val RelationDanger: Color @Composable get() = MaterialTheme.colorScheme.error
internal val RelationTags = listOf("全部", "工作", "家人", "朋友", "客户")

@Composable
fun RelationTab(
    modifier: Modifier = Modifier,
    openInboxRequest: Long = 0L,
    onInboxRequestHandled: (Long) -> Unit = {},
    openCallNoteRequest: Long = 0L,
    onContactClick: (String) -> Unit = {},
    onOwnerClick: () -> Unit = {},
    onDiscoverClick: () -> Unit = {},
    onOpenAutoWrites: () -> Unit = {},
    isDataEmpty: Boolean = false,
    viewModel: RelationViewModel = hiltViewModel(),
    autoWriteViewModel: AutoWriteViewModel = hiltViewModel(),
) {
    if (isDataEmpty) {
        MainTabEmptyPage("relation", modifier)
        return
    }
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val relationships by viewModel.relationships.collectAsStateWithLifecycle()
    val relationshipEvents by viewModel.relationshipEvents.collectAsStateWithLifecycle()
    val rawContacts by viewModel.rawContacts.collectAsStateWithLifecycle()
    val ownerContactLinks by viewModel.ownerContactLinks.collectAsStateWithLifecycle()
    val mergeSuggestions by viewModel.mergeSuggestions.collectAsStateWithLifecycle()
    val notificationCandidates by viewModel.notificationCandidates.collectAsStateWithLifecycle()
    val pendingCallNotes by viewModel.pendingCallNotes.collectAsStateWithLifecycle()
    val cloudAsrAvailability by viewModel.cloudAsrAvailability.collectAsStateWithLifecycle()
    val ownerProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    val autoWriteState by autoWriteViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var tag by remember { mutableStateOf("全部") }
    var mode by remember { mutableStateOf("list") }
    var selected by remember { mutableStateOf<ContactEntity?>(null) }
    var editing by remember { mutableStateOf<ContactEntity?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ContactEntity?>(null) }
    var markingAsOwner by remember { mutableStateOf<ContactEntity?>(null) }
    var showRelationEditor by remember { mutableStateOf(false) }
    var deletingEdge by remember { mutableStateOf<RelationshipEdgeEntity?>(null) }
    var selectedEdge by remember { mutableStateOf<RelationshipEdgeEntity?>(null) }
    var addFactFor by remember { mutableStateOf<ContactEntity?>(null) }
    var addEventFor by remember { mutableStateOf<ContactEntity?>(null) }
    var editingEvent by remember { mutableStateOf<RelationshipEventWithParticipants?>(null) }
    var selectedEvent by remember { mutableStateOf<RelationshipEventWithParticipants?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var identityEditorFor by remember { mutableStateOf<ContactEntity?>(null) }
    var selectedMergeSuggestion by remember { mutableStateOf<ContactMergeSuggestion?>(null) }
    var showContactPermissionIntro by remember { mutableStateOf(false) }
    var showPermissionExplanation by remember { mutableStateOf(false) }
    var showNotificationCandidates by remember { mutableStateOf(false) }
    var selectedCallNote by remember { mutableStateOf<CallRecordEntity?>(null) }
    var correctingAutoWrite by remember { mutableStateOf<com.zhiban.rebuild.runtime.governance.AutoWriteReceiptRow?>(null) }
    var notificationAccessEnabled by remember {
        mutableStateOf(context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context))
    }
    var outgoingAccessibilityEnabled by remember {
        mutableStateOf(isOutgoingAccessibilityEnabled(context))
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showFeedback: (String) -> Unit = { message -> scope.launch { snackbarHostState.showSnackbar(message) } }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationAccessEnabled =
                    context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
                outgoingAccessibilityEnabled = isOutgoingAccessibilityEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(openInboxRequest) {
        if (openInboxRequest > 0L) {
            showNotificationCandidates = true
            onInboxRequestHandled(openInboxRequest)
        }
    }
    LaunchedEffect(openCallNoteRequest, pendingCallNotes) {
        if (openCallNoteRequest > 0L && selectedCallNote == null) {
            selectedCallNote = pendingCallNotes.firstOrNull()
        }
    }
    LaunchedEffect(autoWriteState.receipts.isNotEmpty()) {
        if (autoWriteViewModel.consumeFirstHintIfNeeded(autoWriteState.receipts.isNotEmpty())) {
            snackbarHostState.showSnackbar("知伴帮你整理了一条，可看可撤")
        }
    }
    val contactPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                showImportDialog = true
                viewModel.loadSystemContacts()
            } else {
                showPermissionExplanation = true
            }
        }
    val openContactImport = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            showImportDialog = true
            viewModel.loadSystemContacts()
        } else {
            showContactPermissionIntro = true
        }
    }

    val ownerContactSources = remember(rawContacts, ownerContactLinks) {
        val ownerSourceIds = ownerContactLinks.mapTo(hashSetOf()) { it.contactId }
        rawContacts.filter { it.contactId in ownerSourceIds }
    }
    val graphRelationships = remember(contacts, ownerContactSources, relationships) {
        withInferredCompanyRelationships(contacts, ownerContactSources, relationships)
    }
    val visible = remember(contacts, graphRelationships, query, tag) {
        contacts.filter { contact ->
            val matchesQuery = query.isBlank() || listOf(
                contact.displayName,
                contact.phone,
                contact.wechatId,
                contact.company,
                contact.title,
                contact.note,
            ).any { it?.contains(query, ignoreCase = true) == true }
            val matchesTag = contactMatchesRelationCategory(contact, tag, graphRelationships)
            matchesQuery && matchesTag
        }
    }

    Box(modifier.fillMaxSize().background(RelationBackground)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = ZhiBanTabHorizontalPadding,
                end = ZhiBanTabHorizontalPadding,
                top = ZhiBanTabTopPadding,
                bottom = ZhiBanTabBottomSpacer,
            ),
        ) {
            item {
                ZhiBanPrimaryTabHeader(
                    title = "关系",
                    subtitle = "${contacts.size} 位联系人",
                ) {
                    ZhiBanHeaderIconAction(
                        icon = Icons.Outlined.NotificationsNone,
                        contentDescription = "待确认内容",
                        onClick = { showNotificationCandidates = true },
                        tint = if (notificationCandidates.isNotEmpty()) RelationAccent else RelationInk,
                    )
                    ZhiBanHeaderIconAction(
                        icon = Icons.Outlined.PhoneAndroid,
                        contentDescription = "从手机通讯录导入",
                        onClick = openContactImport,
                        tint = RelationInk,
                    )
                    ZhiBanHeaderIconAction(
                        icon = Icons.Outlined.Add,
                        contentDescription = "添加联系人",
                        onClick = {
                            editing = null
                            showEditor = true
                        },
                        tint = RelationInk,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
            if (pendingCallNotes.isNotEmpty()) {
                item {
                    val call = pendingCallNotes.first()
                    val contactName = call.linkedContactId?.let { id ->
                        contacts.firstOrNull { it.contactId == id }?.displayName
                    }
                    Row(
                        Modifier.fillMaxWidth().zhiBanCardSurface(RelationSurface)
                            .clickable { selectedCallNote = call }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Call, contentDescription = null, tint = RelationAccent)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "补充刚才的通话要点",
                                color = RelationInk,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                contactName ?: "未匹配联系人 · 可直接记录",
                                color = RelationMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text("记录", color = RelationAccent, style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
            val actionableAutoWrites = autoWriteState.receipts.filter { it.undoState == "AVAILABLE" }.take(3)
            if (actionableAutoWrites.isNotEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().zhiBanCardSurface(RelationSurface)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        actionableAutoWrites.forEach { receipt ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = RelationAccent)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        autoWriteActionTitle(receipt.presentationType),
                                        color = RelationInk,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        listOfNotNull(
                                            receipt.contactName,
                                            receipt.confidence?.let { "判断 ${(it * 100).toInt()}%" },
                                        ).joinToString(" · "),
                                        color = RelationMuted,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                TextButton(onClick = { correctingAutoWrite = receipt }) { Text("纠正", color = RelationInk) }
                                TextButton(onClick = { autoWriteViewModel.undo(receipt.changeId) }) {
                                    Text("撤销", color = RelationAccent)
                                }
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().defaultMinSize(minHeight = ZhiBanSize.TouchTarget)
                                .clickable(onClick = onOpenAutoWrites).padding(top = 4.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            Text(
                                "共 ${autoWriteState.receipts.count { it.undoState == "AVAILABLE" }} 条 · 全部",
                                color = RelationAccent,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
            item {
                ZhiBanSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "搜索姓名、电话、公司或备注",
                )
                Spacer(Modifier.height(14.dp))
            }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(ZhiBanSize.Control)
                        .clip(RoundedCornerShape(ZhiBanRadius.Full))
                        .background(RelationSoft),
                ) {
                    listOf("联系人" to "list", "关系图" to "graph").forEach { (label, value) ->
                        Box(
                            Modifier.weight(1f).fillMaxHeight().clickable { mode = value },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier.fillMaxSize().padding(ZhiBanSpacing.Xs)
                                    .clip(RoundedCornerShape(ZhiBanRadius.Full))
                                    .background(if (mode == value) RelationSurface else Color.Transparent),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    label,
                                    color = if (mode == value) RelationInk else RelationMuted,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    RelationTags.forEach { label ->
                        Box(
                            Modifier.weight(1f).defaultMinSize(minHeight = ZhiBanSize.TouchTarget)
                                .clickable { tag = label },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(ZhiBanRadius.Full))
                                    .background(if (tag == label) RelationAccent else RelationSoft)
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    label,
                                    color = if (tag ==
                                        label
                                    ) {
                                        Color.White
                                    } else {
                                        RelationMuted
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
            if (mergeSuggestions.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().zhiBanCardSurface(RelationSurface)
                            .clickable { selectedMergeSuggestion = mergeSuggestions.first() }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "发现可能重复的联系人",
                                color = RelationInk,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                "${mergeSuggestions.size} 组资料等待你确认，不会自动合并",
                                color = RelationMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text("查看", color = RelationInk, style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
            if (mode == "list") {
                if (tag == "全部" && ownerProfile.matchesOwnerQuery(query)) {
                    item {
                        OwnerContactRow(ownerProfile, onOwnerClick)
                        if (visible.isNotEmpty()) {
                            Box(Modifier.fillMaxWidth().padding(start = 58.dp).height(1.dp).background(RelationLine))
                        }
                    }
                    ownerContactLinks.forEach { link ->
                        val linkedContact = rawContacts.firstOrNull { it.contactId == link.contactId }
                        if (linkedContact != null) {
                            item(key = "owner-link-${link.contactId}") {
                                Row(
                                    Modifier.fillMaxWidth().padding(start = 58.dp, top = 6.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "已将 ${linkedContact.displayName} 归入本人",
                                        color = RelationMuted,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = { viewModel.undoContactIsOwner(link.contactId) }) {
                                        Text("撤销", color = RelationInk)
                                    }
                                }
                            }
                        }
                    }
                }
                if (contacts.isEmpty() && query.isBlank() && tag == "全部") {
                    item {
                        RelationEmpty(
                            searching = false,
                            onImport = openContactImport,
                            onAdd = {
                                editing = null
                                showEditor = true
                            },
                        )
                    }
                } else if (visible.isEmpty()) {
                    if (!(tag == "全部" && ownerProfile.matchesOwnerQuery(query))) {
                        item {
                            RelationEmpty(
                                searching = query.isNotBlank() || tag != "全部",
                                onImport = openContactImport,
                                onAdd = {
                                    editing = null
                                    showEditor = true
                                },
                            )
                        }
                    }
                } else {
                    items(visible.size, key = { visible[it].contactId }) { index ->
                        ContactRow(visible[index]) {
                            selected = visible[index]
                            onContactClick(visible[index].contactId)
                        }
                        if (index != visible.lastIndex) {
                            Box(Modifier.fillMaxWidth().padding(start = 58.dp).height(1.dp).background(RelationLine))
                        }
                    }
                }
            } else {
                item {
                    RelationshipGraphState(
                        owner = ownerProfile,
                        contacts = visible,
                        edges = graphRelationships,
                        events = relationshipEvents,
                        canAddRelationship = contacts.isNotEmpty(),
                        activeFilter = tag.takeUnless { it == "全部" } ?: query.takeIf(String::isNotBlank),
                        onAdd = { showRelationEditor = true },
                        onInspect = { selectedEdge = it },
                        onInspectEvent = { selectedEvent = it },
                        onDelete = { deletingEdge = it },
                    )
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = ZhiBanTabBottomSpacer),
        )
    }

    selected?.let { contact ->
        val aliases by viewModel.aliases.collectAsStateWithLifecycle()
        val platformIdentities by viewModel.platformIdentities.collectAsStateWithLifecycle()
        val mergeLinks by viewModel.mergeLinks.collectAsStateWithLifecycle()
        val factFlow = remember(contact.contactId) { viewModel.contactFacts(contact.contactId) }
        val facts by factFlow.collectAsStateWithLifecycle(initialValue = emptyList())
        val callFlow = remember(contact.contactId) { viewModel.contactCalls(contact.contactId) }
        val recentCalls by callFlow.collectAsStateWithLifecycle(initialValue = emptyList())
        val opportunityFlow = remember(contact.contactId) { viewModel.contactOpportunities(contact.contactId) }
        val contactOpportunities by opportunityFlow.collectAsStateWithLifecycle(initialValue = emptyList())
        val enrichmentFlow = remember(contact.contactId) { viewModel.contactEnrichment(contact.contactId) }
        val enrichmentSuggestions by enrichmentFlow.collectAsStateWithLifecycle(initialValue = emptyList())
        ContactDetailDialog(
            contact = contact,
            showMarkAsOwner = contact.matchesOwnerProfile(ownerProfile) &&
                ownerContactLinks.none { it.contactId == contact.contactId },
            facts = facts,
            relatedEdges = graphRelationships.filter {
                it.fromContactId == contact.contactId ||
                    it.toContactId == contact.contactId
            },
            relatedEvents = relationshipEvents.filter { event ->
                event.participants.any {
                    it.contactId ==
                        contact.contactId
                }
            },
            recentCalls = recentCalls,
            crmOpportunities = contactOpportunities,
            enrichmentSuggestions = enrichmentSuggestions,
            contactNames = contacts.associate { it.contactId to it.displayName } +
                (RelationshipPersonIds.SELF to ownerProfile.relationshipLabel()),
            aliases = aliases.filter { it.contactId == contact.contactId },
            platformIdentities = platformIdentities.filter { it.contactId == contact.contactId },
            mergedSources = mergeLinks.filter { it.canonicalContactId == contact.contactId }.mapNotNull { link ->
                rawContacts.firstOrNull { it.contactId == link.sourceContactId }?.let { link to it }
            },
            onDismiss = { selected = null },
            onEdit = {
                selected = null
                editing = contact
                showEditor = true
            },
            onMarkAsOwner = {
                selected = null
                markingAsOwner = contact
            },
            onDelete = {
                selected = null
                deleting = contact
            },
            onAddFact = { addFactFor = contact },
            onAddEvent = { addEventFor = contact },
            onAddIdentity = { identityEditorFor = contact },
            onInspectEvent = { selectedEvent = it },
            onDeleteFact = viewModel::deleteContactFact,
            onDeleteAlias = viewModel::deleteAlias,
            onDeletePlatformIdentity = viewModel::deletePlatformIdentity,
            onUndoMerge = { sourceId -> viewModel.undoMerge(sourceId) },
            onConfirmEnrichment = { candidate ->
                viewModel.confirmContactEnrichment(candidate) { error ->
                    error?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                }
            },
            onRejectEnrichment = { candidate -> viewModel.rejectContactEnrichment(candidate) },
            onSaveToPhone = {
                runCatching { context.startActivity(SystemContactWriteIntent.create(contact)) }
            },
            onCall = {
                contact.phone?.takeIf(String::isNotBlank)?.let { phone ->
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phone)}")))
                    }
                }
            },
            onMessage = {
                contact.phone?.takeIf(String::isNotBlank)?.let { phone ->
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(phone)}")))
                    }
                }
            },
        )
    }
    selectedMergeSuggestion?.let { suggestion ->
        ContactMergeReviewDialog(
            suggestion = suggestion,
            onDismiss = { selectedMergeSuggestion = null },
            onConfirm = { canonicalId, sourceId, result ->
                viewModel.confirmMerge(canonicalId, sourceId, suggestion.reason) { error ->
                    result(error)
                    if (error == null) {
                        selectedMergeSuggestion = null
                        showFeedback("联系人已合并，可在详情中撤销")
                    }
                }
            },
        )
    }
    identityEditorFor?.let { contact ->
        ContactIdentityEditorDialog(
            contact = contact,
            onDismiss = { identityEditorFor = null },
            onSaveAlias = { value, result ->
                viewModel.addAlias(contact.contactId, value) { error ->
                    result(error)
                    if (error == null) {
                        identityEditorFor = null
                        showFeedback("称呼已保存")
                    }
                }
            },
            onSavePlatform = { platform, handle, result ->
                viewModel.addPlatformIdentity(contact.contactId, platform, handle) { error ->
                    result(error)
                    if (error == null) {
                        identityEditorFor = null
                        showFeedback("联系方式已保存")
                    }
                }
            },
        )
    }
    if (showEditor) {
        ContactEditorDialog(
            contact = editing,
            onDismiss = { showEditor = false },
            onSave = { id, name, phone, wechat, company, title, selectedTag, note, result ->
                viewModel.save(id, name, phone, wechat, company, title, selectedTag, note) { error ->
                    result(error)
                    if (error == null) {
                        showEditor = false
                        showFeedback(if (id == null) "联系人已添加" else "联系人已保存")
                    }
                }
            },
        )
    }
    if (showImportDialog) {
        val importState by viewModel.importState.collectAsStateWithLifecycle()
        ContactImportDialog(
            state = importState,
            onDismiss = {
                showImportDialog = false
                viewModel.clearImportState()
            },
            onImport = viewModel::importSystemContacts,
        )
    }
    if (showContactPermissionIntro) {
        ZhiBanAlertDialog(
            onDismissRequest = { showContactPermissionIntro = false },
            title = { Text("导入手机联系人") },
            text = { Text("知伴只会读取你接下来主动选择的人，不会上传、修改或默认全选通讯录。下一步 Android 会询问通讯录权限。") },
            confirmButton = {
                TextButton(onClick = {
                    showContactPermissionIntro = false
                    contactPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                }) { Text("继续", color = RelationInk) }
            },
            dismissButton = {
                TextButton(onClick = { showContactPermissionIntro = false }) { Text("取消", color = RelationMuted) }
            },
            containerColor = RelationSurface,
        )
    }
    if (showPermissionExplanation) {
        ZhiBanAlertDialog(
            onDismissRequest = { showPermissionExplanation = false },
            title = { Text("需要通讯录权限") },
            text = { Text("知伴只读取你主动选择导入的联系人，不会自动上传或修改手机通讯录。你可以在系统设置中随时关闭权限。") },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionExplanation = false
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        },
                    )
                }) { Text("打开系统设置", color = RelationInk) }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionExplanation = false }) { Text("暂不导入", color = RelationMuted) }
            },
            containerColor = RelationSurface,
        )
    }
    if (showNotificationCandidates) {
        val enabledMessagePlatforms by viewModel.enabledMessagePlatforms.collectAsStateWithLifecycle()
        val outgoingCollectionEnabled by viewModel.outgoingMessageCollectionEnabled.collectAsStateWithLifecycle()
        NotificationCandidateDialog(
            enabled = notificationAccessEnabled,
            candidates = notificationCandidates,
            contacts = contacts,
            onEnable = {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
            onDismissCandidate = viewModel::dismissNotificationCandidate,
            onConfirmCandidate = viewModel::confirmNotificationCandidate,
            onCreateContact = viewModel::createContactFromNotification,
            onConfirmSchedule = viewModel::confirmNotificationSchedule,
            enabledPlatforms = enabledMessagePlatforms,
            onPlatformEnabled = viewModel::setMessagePlatformEnabled,
            outgoingCollectionEnabled = outgoingCollectionEnabled,
            outgoingAccessibilityEnabled = outgoingAccessibilityEnabled,
            onOutgoingCollectionEnabled = { enabled ->
                viewModel.setOutgoingMessageCollectionEnabled(enabled)
                if (enabled && !outgoingAccessibilityEnabled) {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            },
            onDismiss = { showNotificationCandidates = false },
        )
    }
    selectedCallNote?.let { call ->
        CallNoteDialog(
            call = call,
            contactName = call.linkedContactId?.let { id -> contacts.firstOrNull { it.contactId == id }?.displayName },
            cloudAsrAvailability = cloudAsrAvailability,
            onAllowCloudSpeech = viewModel::allowCloudSpeech,
            onTranscribe = viewModel::transcribeCallNote,
            onDismiss = { selectedCallNote = null },
            onSkip = {
                viewModel.dismissCallNote(call.callRecordId)
                selectedCallNote = null
            },
            onSave = { text, source, result ->
                viewModel.saveCallNote(call.callRecordId, text, source) { error ->
                    result(error)
                    if (error == null) {
                        selectedCallNote = null
                        showFeedback("通话备注已保存")
                    }
                }
            },
        )
    }
    deleting?.let { contact ->
        ZhiBanAlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除联系人？") },
            text = { Text("“${contact.displayName}”及其个人资料将从关系中移除。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(contact.contactId) {
                        deleting = null
                        showFeedback("联系人已删除")
                    }
                }) {
                    Text("删除", color = RelationDanger)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消", color = RelationInk) } },
            containerColor = RelationSurface,
        )
    }
    correctingAutoWrite?.let { receipt ->
        ZhiBanAlertDialog(
            onDismissRequest = { correctingAutoWrite = null },
            title = { Text("这条内容属于谁？") },
            text = {
                LazyColumn {
                    items(autoWriteState.contacts, key = ContactEntity::contactId) { contact ->
                        TextButton(
                            onClick = {
                                correctingAutoWrite = null
                                autoWriteViewModel.correctInteraction(receipt.changeId, contact.contactId)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(contact.displayName, color = RelationInk, modifier = Modifier.fillMaxWidth()) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { correctingAutoWrite = null }) { Text("取消", color = RelationInk) } },
            containerColor = RelationSurface,
        )
    }
    markingAsOwner?.let { contact ->
        ZhiBanAlertDialog(
            onDismissRequest = { markingAsOwner = null },
            title = { Text("这是你本人吗？") },
            text = { Text("确认后，“${contact.displayName}”会归入“我”的资料，不再作为另一位联系人出现在列表和关系图中。原始通讯录资料会保留，并可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.confirmContactIsOwner(contact.contactId) { error ->
                        if (error == null) {
                            markingAsOwner = null
                            showFeedback("已归入我的资料，可撤销")
                        }
                    }
                }) { Text("确认是我", color = RelationInk) }
            },
            dismissButton = {
                TextButton(onClick = { markingAsOwner = null }) { Text("不是", color = RelationMuted) }
            },
            containerColor = RelationSurface,
        )
    }
    if (showRelationEditor) {
        RelationshipEditorDialog(
            owner = ownerProfile,
            contacts = contacts,
            onDismiss = { showRelationEditor = false },
            onSave = { from, to, type, result ->
                viewModel.saveRelationship(from, to, type) { error ->
                    result(error)
                    if (error == null) {
                        showRelationEditor = false
                        showFeedback("关系已保存")
                    }
                }
            },
        )
    }
    deletingEdge?.let { edge ->
        val names = contacts.associate { it.contactId to it.displayName } +
            (RelationshipPersonIds.SELF to ownerProfile.relationshipLabel())
        ZhiBanAlertDialog(
            onDismissRequest = { deletingEdge = null },
            title = { Text("删除这条关系？") },
            text = {
                Text(
                    "${names[edge.fromContactId].orEmpty()} 与 ${names[edge.toContactId].orEmpty()} 的“${relationLabel(
                        edge.relationType,
                    )}”关系将被移除。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteRelationship(edge.edgeId) {
                        deletingEdge = null
                        showFeedback("关系已删除")
                    }
                }) {
                    Text("删除", color = RelationDanger)
                }
            },
            dismissButton = { TextButton(onClick = { deletingEdge = null }) { Text("取消", color = RelationInk) } },
            containerColor = RelationSurface,
        )
    }
    selectedEdge?.let { edge ->
        RelationshipEvidenceDialog(
            edge = edge,
            personNames = contacts.associate { it.contactId to it.displayName } +
                (RelationshipPersonIds.SELF to ownerProfile.relationshipLabel()),
            onDismiss = { selectedEdge = null },
            onUpdate = { type, result ->
                viewModel.updateRelationship(edge.edgeId, type) { error ->
                    result(error)
                    if (error == null) {
                        selectedEdge = null
                        showFeedback("关系已保存")
                    }
                }
            },
            onDelete = {
                selectedEdge = null
                deletingEdge = edge
            },
        )
    }
    addFactFor?.let { contact ->
        ContactFactEditorDialog(
            contact = contact,
            onDismiss = { addFactFor = null },
            onSave = { text, type, result ->
                viewModel.saveContactFact(contact.contactId, text, type) { error ->
                    result(error)
                    if (error == null) {
                        addFactFor = null
                        showFeedback("联系人信息已保存")
                    }
                }
            },
        )
    }
    val eventEditorSubject = addEventFor ?: editingEvent?.participants
        ?.firstOrNull { it.participantRole == "SUBJECT" && it.contactId != null }
        ?.contactId?.let { id -> contacts.firstOrNull { it.contactId == id } }
    eventEditorSubject?.let { contact ->
        RelationshipEventEditorDialog(
            contacts = contacts,
            subject = contact,
            existing = editingEvent,
            onDismiss = {
                addEventFor = null
                editingEvent = null
            },
            onSave = { type, title, note, participants, result ->
                viewModel.saveRelationshipEvent(
                    editingEvent?.event?.eventId,
                    type,
                    title,
                    note,
                    participants,
                ) { error ->
                    result(error)
                    if (error == null) {
                        addEventFor = null
                        editingEvent = null
                        showFeedback("经历已保存")
                    }
                }
            },
        )
    }
    selectedEvent?.let { event ->
        RelationshipEventDetailDialog(
            value = event,
            onDismiss = { selectedEvent = null },
            onEdit = {
                selectedEvent = null
                editingEvent = event
            },
            onDelete = {
                viewModel.deleteRelationshipEvent(event.event.eventId) { selectedEvent = null }
            },
        )
    }
}

/** Title for an auto-write receipt shown inline on the relation page. */
private fun autoWriteActionTitle(presentationType: String): String = when (presentationType) {
    "INTERACTION_SUMMARY" -> "整理了一条互动摘要"
    "CONTACT_TAG" -> "补充了联系人标签"
    "CRM_LEAD_CANDIDATE" -> "发现了一条候选线索"
    "CRM_ACTIVITY" -> "记录了一次客户互动"
    "CRM_NEXT_ACTION" -> "创建了下一步动作"
    else -> "完成了一条内部整理"
}
