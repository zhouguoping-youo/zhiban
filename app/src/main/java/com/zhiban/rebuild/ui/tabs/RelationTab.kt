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
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import com.zhiban.rebuild.data.agent.AndroidContactSyncPreview
import com.zhiban.rebuild.data.agent.RelationshipEventParticipantInput
import com.zhiban.rebuild.data.autowrite.AutoWriteReceiptRow
import com.zhiban.rebuild.data.calllog.CallRecordEntity
import com.zhiban.rebuild.data.contact.ContactAliasEntity
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.ContactMergeLinkEntity
import com.zhiban.rebuild.data.contact.ContactPlatformIdentityEntity
import com.zhiban.rebuild.data.contact.OwnerContactLinkEntity
import com.zhiban.rebuild.data.contact.RelationshipEdgeEntity
import com.zhiban.rebuild.data.contact.RelationshipEventWithParticipants
import com.zhiban.rebuild.data.contact.RelationshipPersonIds
import com.zhiban.rebuild.data.contact.SystemContactCandidate
import com.zhiban.rebuild.data.facts.FactEntity
import com.zhiban.rebuild.data.notification.NotificationCandidateEntity
import com.zhiban.rebuild.data.notification.OutgoingMessageAccessibilityService
import com.zhiban.rebuild.data.notification.ScheduleInsight
import com.zhiban.rebuild.relationship.RelationshipGroup
import com.zhiban.rebuild.runtime.input.asr.CloudAsrAvailability
import com.zhiban.rebuild.runtime.personalization.UserProfile
import com.zhiban.rebuild.ui.components.ZhiBanAlertDialog
import com.zhiban.rebuild.ui.components.ZhiBanHeaderIconAction
import com.zhiban.rebuild.ui.components.ZhiBanLeadingIcon
import com.zhiban.rebuild.ui.components.ZhiBanPrimaryTabHeader
import com.zhiban.rebuild.ui.components.ZhiBanSearchField
import com.zhiban.rebuild.ui.components.ZhiBanSegmentedControl
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
import kotlinx.coroutines.launch

internal val RelationBackground: Color @Composable get() = MaterialTheme.colorScheme.background
internal val RelationSurface: Color @Composable get() = MaterialTheme.colorScheme.surface
internal val RelationInk: Color @Composable get() = MaterialTheme.colorScheme.onBackground
internal val RelationMuted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
internal val RelationSoft: Color @Composable get() = MaterialTheme.colorScheme.surfaceVariant
internal val RelationLine: Color @Composable get() = MaterialTheme.colorScheme.outlineVariant
internal val RelationAccent: Color @Composable get() = MaterialTheme.colorScheme.primary
internal val RelationDanger: Color @Composable get() = MaterialTheme.colorScheme.error
internal val RelationFilters = listOf("全部") + RelationshipGroup.entries.map(RelationshipGroup::displayName)
internal val ContactCategoryTags = RelationshipGroup.entries.map(RelationshipGroup::displayName)

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
    onOpenContactMaintenance: () -> Unit = {},
    isDataEmpty: Boolean = false,
    viewModel: RelationViewModel = hiltViewModel(),
    autoWriteViewModel: AutoWriteViewModel = hiltViewModel(),
) {
    if (isDataEmpty) {
        MainTabEmptyPage("relation", modifier)
        return
    }
    val page by viewModel.pageSnapshot.collectAsStateWithLifecycle()
    val contacts = page.contacts
    val relationships = page.relationships
    val temporalRelationships = page.temporalRelationships
    val relationshipEvents = page.relationshipEvents
    val rawContacts = page.rawContacts
    val ownerContactLinks = page.ownerContactLinks
    val temporalEmployments = page.temporalEmployments
    val maintenanceOverview = page.maintenanceOverview
    val unresolvedSourceIdentities = page.unresolvedSourceIdentities
    val notificationCandidates = page.notificationCandidates
    val pendingCallNotes = page.pendingCallNotes
    val cloudAsrAvailability = page.cloudAsrAvailability
    val ownerProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    val replySuggestions by viewModel.replySuggestions.collectAsStateWithLifecycle()
    val autoWriteState by autoWriteViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var tag by remember { mutableStateOf("全部") }
    var mode by remember { mutableStateOf("list") }
    var selected by remember { mutableStateOf<ContactEntity?>(null) }
    // Form dialogs keep their open/target state as a saveable contact id (not the entity, which
    // is not saveable) and re-derive the entity from page.contacts, so an in-progress edit
    // survives a configuration change. pageSnapshot is a ViewModel StateFlow, so contacts is
    // populated synchronously on the first frame after rotation.
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val editing = editingId?.let { id -> contacts.firstOrNull { it.contactId == id } }
    var showEditor by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ContactEntity?>(null) }
    var markingAsOwner by remember { mutableStateOf<ContactEntity?>(null) }
    var showRelationEditor by rememberSaveable { mutableStateOf(false) }
    var showOwnerEmploymentEditor by remember { mutableStateOf(false) }
    var deletingEdge by remember { mutableStateOf<RelationshipEdgeEntity?>(null) }
    var selectedEdge by remember { mutableStateOf<RelationshipEdgeEntity?>(null) }
    var addFactForId by rememberSaveable { mutableStateOf<String?>(null) }
    val addFactFor = addFactForId?.let { id -> contacts.firstOrNull { it.contactId == id } }
    var addEventFor by remember { mutableStateOf<ContactEntity?>(null) }
    var editingEvent by remember { mutableStateOf<RelationshipEventWithParticipants?>(null) }
    var selectedEvent by remember { mutableStateOf<RelationshipEventWithParticipants?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var identityEditorForId by rememberSaveable { mutableStateOf<String?>(null) }
    val identityEditorFor = identityEditorForId?.let { id -> contacts.firstOrNull { it.contactId == id } }
    var showContactPermissionIntro by remember { mutableStateOf(false) }
    var showPermissionExplanation by remember { mutableStateOf(false) }
    var showNotificationCandidates by remember { mutableStateOf(false) }
    var selectedCallNote by remember { mutableStateOf<CallRecordEntity?>(null) }
    var correctingAutoWrite by remember { mutableStateOf<com.zhiban.rebuild.data.autowrite.AutoWriteReceiptRow?>(null) }
    var pendingPhoneSync by remember { mutableStateOf<AndroidContactSyncPreview?>(null) }
    var phoneSyncPermissionContact by remember { mutableStateOf<ContactEntity?>(null) }
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
    val writeContactPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val contact = phoneSyncPermissionContact
            phoneSyncPermissionContact = null
            if (granted && contact != null) {
                viewModel.prepareSystemContactSync(contact) { preview, error ->
                    pendingPhoneSync = preview
                    error?.let(showFeedback)
                }
            } else if (!granted) {
                showFeedback("需要修改通讯录权限才能回写")
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
    val graphRelationships = remember(contacts, ownerContactSources, relationships, temporalEmployments) {
        withInferredCompanyRelationships(
            contacts,
            ownerContactSources,
            relationships,
            temporalEmployments,
        )
    }
    val ownerEmploymentPersonIds = remember(ownerContactLinks) {
        ownerContactLinks.mapTo(hashSetOf(), OwnerContactLinkEntity::contactId) + RelationshipPersonIds.SELF
    }
    val ownerEmployments = remember(temporalEmployments, ownerEmploymentPersonIds) {
        temporalEmployments.filter { it.personId in ownerEmploymentPersonIds }
    }
    val currentOwnerEmployment = remember(ownerEmployments) {
        ownerEmployments.firstOrNull {
            it.currentState == "CURRENT" && it.verificationState == "USER_CONFIRMED"
        }
    }
    val ownerEmploymentHistoryCount = remember(ownerEmployments) {
        ownerEmployments.count { it.currentState == "PAST" || it.validToEpochMs != null }
    }
    val historyRelationships = remember(temporalRelationships) {
        historicalRelationshipEdges(temporalRelationships)
    }
    val maintenanceCount = maintenanceOverview.needsAttentionCount + unresolvedSourceIdentities.size
    val selectedRelationshipGroup = remember(tag) {
        RelationshipGroup.entries.firstOrNull { it.displayName == tag }
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
                    subtitle = localizedQuantity(R.plurals.contact_count, contacts.size),
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
                            editingId = null
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
                ZhiBanSegmentedControl(
                    options = listOf("联系人", "关系图"),
                    selectedIndex = if (mode == "list") 0 else 1,
                    onSelected = { mode = if (it == 0) "list" else "graph" },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(14.dp))
            }
            item {
                RelationshipCategoryFilter(
                    selected = tag,
                    options = RelationFilters,
                    onSelected = { tag = it },
                )
                Spacer(Modifier.height(18.dp))
            }
            if (maintenanceCount > 0) {
                item {
                    Row(
                        Modifier.fillMaxWidth().zhiBanCardSurface().clickable(onClick = onOpenContactMaintenance)
                            .padding(ZhiBanSpacing.Lg),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Md),
                    ) {
                        ZhiBanLeadingIcon(Icons.Outlined.AutoAwesome, contentDescription = null)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "联系人维护",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "$maintenanceCount 项待核实",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
            if (mode == "list") {
                if (shouldShowOwnerProfilePrompt(ownerProfile, tag, query)) {
                    item {
                        OwnerProfilePrompt(ownerProfile, onOwnerClick)
                        Spacer(Modifier.height(12.dp))
                    }
                }
                if (tag == "全部" && query.isBlank()) {
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
                                editingId = null
                                showEditor = true
                            },
                        )
                    }
                } else if (visible.isEmpty()) {
                    item {
                        RelationEmpty(
                            searching = query.isNotBlank() || tag != "全部",
                            onImport = openContactImport,
                            onAdd = {
                                editingId = null
                                showEditor = true
                            },
                        )
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
                        historicalEdges = historyRelationships,
                        currentOwnerEmployment = currentOwnerEmployment,
                        ownerEmploymentHistoryCount = ownerEmploymentHistoryCount,
                        events = relationshipEvents,
                        canAddRelationship = contacts.isNotEmpty(),
                        activeFilter = tag.takeUnless { it == "全部" } ?: query.takeIf(String::isNotBlank),
                        activeGroup = selectedRelationshipGroup,
                        onAdd = { showRelationEditor = true },
                        onInspect = { selectedEdge = it },
                        onInspectEvent = { selectedEvent = it },
                        onEditOwnerEmployment = { showOwnerEmploymentEditor = true },
                    )
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = ZhiBanTabBottomSpacer),
        )
    }

    RelationDetailOverlay(
        state = RelationDetailState(
            selected = selected,
            ownerProfile = ownerProfile,
            ownerContactLinks = ownerContactLinks,
            contacts = contacts,
            rawContacts = rawContacts,
            graphRelationships = graphRelationships,
            relationshipEvents = relationshipEvents,
        ),
        actions = RelationDetailActions(
            onDismiss = { selected = null },
            onEdit = { contact ->
                selected = null
                editingId = contact.contactId
                showEditor = true
            },
            onMarkAsOwner = { contact ->
                selected = null
                markingAsOwner = contact
            },
            onDelete = { contact ->
                selected = null
                deleting = contact
            },
            onAddFact = { addFactForId = it.contactId },
            onAddEvent = { addEventFor = it },
            onAddIdentity = { identityEditorForId = it.contactId },
            onInspectEvent = { selectedEvent = it },
            onRequestPhoneSync = { contact ->
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    viewModel.prepareSystemContactSync(contact) { preview, error ->
                        pendingPhoneSync = preview
                        error?.let(showFeedback)
                    }
                } else {
                    phoneSyncPermissionContact = contact
                    writeContactPermissionLauncher.launch(Manifest.permission.WRITE_CONTACTS)
                }
            },
        ),
        viewModel = viewModel,
        context = context,
    )
    pendingPhoneSync?.let { preview ->
        val hasConflicts = preview.plan.conflicts.isNotEmpty()
        ZhiBanAlertDialog(
            onDismissRequest = { pendingPhoneSync = null },
            title = { Text(if (hasConflicts) "需要先核对" else "更新手机通讯录") },
            text = {
                Text(
                    if (hasConflicts) {
                        "手机里的资料和知伴档案都发生过修改。为避免覆盖，知伴不会自动写入：\n${contactSyncSummary(preview)}"
                    } else {
                        contactSyncSummary(preview)
                    },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !hasConflicts,
                    onClick = {
                        viewModel.applySystemContactSync(preview) { result, error ->
                            pendingPhoneSync = null
                            if (error != null) {
                                showFeedback(error)
                            } else if (result != null) {
                                scope.launch {
                                    val snackbarResult = snackbarHostState.showSnackbar(
                                        message = result.message,
                                        actionLabel = result.operationId.takeIf(String::isNotBlank)?.let { "撤销" },
                                    )
                                    if (snackbarResult == SnackbarResult.ActionPerformed) {
                                        viewModel.undoSystemContactSync(result.operationId, showFeedback)
                                    }
                                }
                            }
                        }
                    },
                ) { Text("确认更新", color = if (hasConflicts) RelationMuted else RelationInk) }
            },
            dismissButton = {
                TextButton(onClick = { pendingPhoneSync = null }) { Text("取消", color = RelationMuted) }
            },
            containerColor = RelationSurface,
        )
    }
    identityEditorFor?.let { contact ->
        ContactIdentityEditorDialog(
            contact = contact,
            onDismiss = { identityEditorForId = null },
            onSaveAlias = { value, result ->
                viewModel.addAlias(contact.contactId, value) { error ->
                    result(error)
                    if (error == null) {
                        identityEditorForId = null
                        showFeedback("称呼已保存")
                    }
                }
            },
            onSavePlatform = { platform, handle, result ->
                viewModel.addPlatformIdentity(contact.contactId, platform, handle) { error ->
                    result(error)
                    if (error == null) {
                        identityEditorForId = null
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
            onSave = { id, name, phone, wechat, company, title, selectedTag, note, email, responsibilities, result ->
                viewModel.save(id, name, phone, wechat, company, title, selectedTag, note, email, responsibilities) { error ->
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
        ContactImportDialog(
            state = page.importState,
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
            text = { Text(CONTACT_IMPORT_PERMISSION_INTRO) },
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
        NotificationCandidateDialog(
            enabled = notificationAccessEnabled,
            candidates = notificationCandidates,
            contacts = contacts,
            replySuggestions = replySuggestions,
            onForwardReply = { model, draft ->
                viewModel.forwardReplySuggestion(model, draft) { error ->
                    error?.let(showFeedback)
                }
            },
            onDismissReply = { model -> viewModel.dismissReplySuggestion(model.candidateId) },
            onOptOutReply = { model ->
                model.contactId?.let {
                    viewModel.optOutReplySuggestion(it)
                    showFeedback("已不再为 ${model.contactName} 生成回复建议")
                }
            },
            onEnable = {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
            onDismissCandidate = viewModel::dismissNotificationCandidate,
            onMuteSender = { candidateId ->
                viewModel.muteNotificationSender(candidateId) { error ->
                    showFeedback(error ?: "已不再提醒该发送者，可在设置中解除")
                }
            },
            onConfirmCandidate = viewModel::confirmNotificationCandidate,
            onCreateContact = viewModel::createContactFromNotification,
            onConfirmSchedule = viewModel::confirmNotificationSchedule,
            enabledPlatforms = page.enabledMessagePlatforms,
            onPlatformEnabled = viewModel::setMessagePlatformEnabled,
            outgoingCollectionEnabled = page.outgoingMessageCollectionEnabled,
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
            onSave = { from, to, type, temporalState, result ->
                viewModel.saveRelationship(from, to, type, temporalState) { error ->
                    result(error)
                    if (error == null) {
                        showRelationEditor = false
                        showFeedback("关系已保存")
                    }
                }
            },
        )
    }
    if (showOwnerEmploymentEditor) {
        OwnerEmploymentEditorDialog(
            current = currentOwnerEmployment,
            onDismiss = { showOwnerEmploymentEditor = false },
            onSave = { company, title, result ->
                viewModel.saveOwnerCurrentEmployment(company, title) { error ->
                    result(error)
                    if (error == null) {
                        showOwnerEmploymentEditor = false
                        showFeedback("当前工作已更新，关系图正在重新整理")
                    }
                }
            },
        )
    }
    RelationEdgeDialogs(
        RelationDialogSlots(
            contacts = contacts,
            ownerLabel = ownerProfile.relationshipLabel(),
            viewModel = viewModel,
            showFeedback = showFeedback,
            deletingEdge = deletingEdge,
            setDeletingEdge = { deletingEdge = it },
            selectedEdge = selectedEdge,
            setSelectedEdge = { selectedEdge = it },
            addFactFor = addFactFor,
            clearAddFactFor = { addFactForId = null },
            addEventFor = addEventFor,
            setAddEventFor = { addEventFor = it },
            editingEvent = editingEvent,
            setEditingEvent = { editingEvent = it },
            selectedEvent = selectedEvent,
            setSelectedEvent = { selectedEvent = it },
        ),
    )
    RelationEventDialogs(
        RelationDialogSlots(
            contacts = contacts,
            ownerLabel = ownerProfile.relationshipLabel(),
            viewModel = viewModel,
            showFeedback = showFeedback,
            deletingEdge = deletingEdge,
            setDeletingEdge = { deletingEdge = it },
            selectedEdge = selectedEdge,
            setSelectedEdge = { selectedEdge = it },
            addFactFor = addFactFor,
            clearAddFactFor = { addFactForId = null },
            addEventFor = addEventFor,
            setAddEventFor = { addEventFor = it },
            editingEvent = editingEvent,
            setEditingEvent = { editingEvent = it },
            selectedEvent = selectedEvent,
            setSelectedEvent = { selectedEvent = it },
        ),
    )
}

internal const val CONTACT_IMPORT_PERMISSION_INTRO =
    "知伴会在本机读取通讯录并默认勾选，导入前你可以取消不需要的人；不会自动上传或修改手机通讯录。下一步 Android 会询问通讯录权限。"

private fun contactSyncSummary(preview: AndroidContactSyncPreview): String {
    val fieldNames = mapOf("displayName" to "姓名", "company" to "公司全称", "title" to "职位", "note" to "备注")
    val lines = buildList {
        preview.plan.scalarUpdates.forEach { (field, value) -> add("${fieldNames[field] ?: field}：${value.orEmpty()}") }
        preview.plan.phoneAdditions.forEach { add("新增手机：$it") }
        preview.plan.emailAdditions.forEach { add("新增邮箱：$it") }
        preview.plan.conflicts.forEach { conflict ->
            add("${fieldNames[conflict.field] ?: conflict.field}：手机为“${conflict.deviceValue.orEmpty()}”，知伴为“${conflict.desiredValue.orEmpty()}”")
        }
    }
    return if (lines.isEmpty()) "手机通讯录已经是最新" else lines.joinToString("\n")
}

/** Title for an auto-write receipt shown inline on the relation page. */
private fun autoWriteActionTitle(presentationType: String): String = when (presentationType) {
    "INTERACTION_SUMMARY" -> "整理了一条互动摘要"
    "CONTACT_TAG" -> "补充了联系人标签"
    "CONTACT_IDENTITY_LINK" -> "合并了重复联系人"
    "SCHEDULE_CREATE" -> "创建了一条日程"
    "MEMORY" -> "更新了一条长期记忆"
    "CRM_LEAD_CANDIDATE" -> "发现了一条候选线索"
    "CRM_ACTIVITY" -> "记录了一次客户互动"
    "CRM_NEXT_ACTION" -> "创建了下一步动作"
    else -> "完成了一条内部整理"
}
