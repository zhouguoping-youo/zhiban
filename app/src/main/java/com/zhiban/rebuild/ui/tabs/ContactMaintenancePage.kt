package com.zhiban.rebuild.ui.tabs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhiban.rebuild.R
import com.zhiban.rebuild.data.completion.ContactCompletionDraft
import com.zhiban.rebuild.data.contact.ContactCompleteness
import com.zhiban.rebuild.data.contact.ContactEnrichmentCandidateEntity
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.ContactMaintenanceIssue
import com.zhiban.rebuild.data.contact.ContactMaintenanceItem
import com.zhiban.rebuild.data.contact.SourceIdentityEntity
import com.zhiban.rebuild.ui.components.ZhiBanAlertDialog
import com.zhiban.rebuild.ui.components.ZhiBanBottomSheet
import com.zhiban.rebuild.ui.components.ZhiBanDialogHeader
import com.zhiban.rebuild.ui.components.ZhiBanLeadingIcon
import com.zhiban.rebuild.ui.components.ZhiBanPage
import com.zhiban.rebuild.ui.components.ZhiBanTopBar
import com.zhiban.rebuild.ui.components.localizedQuantity
import com.zhiban.rebuild.ui.components.zhiBanCardSurface
import com.zhiban.rebuild.ui.theme.ZhiBanSpacing

private val EnrichmentReviewMaxHeight = 560.dp

internal data class ContactEnrichmentReviewActions(
    val onDismiss: () -> Unit,
    val onConfirm: (ContactEnrichmentCandidateEntity) -> Unit,
    val onReject: (ContactEnrichmentCandidateEntity) -> Unit,
)

@Composable
fun ContactMaintenancePage(onBack: () -> Unit, onAsk: (String) -> Unit, viewModel: RelationViewModel = hiltViewModel()) {
    val overview by viewModel.maintenanceOverview.collectAsStateWithLifecycle()
    val suggestions by viewModel.mergeSuggestions.collectAsStateWithLifecycle()
    val unresolvedIdentities by viewModel.unresolvedSourceIdentities.collectAsStateWithLifecycle()
    val enrichmentCandidates by viewModel.pendingEnrichment.collectAsStateWithLifecycle()
    val rawContacts by viewModel.rawContacts.collectAsStateWithLifecycle()
    val completableContacts by viewModel.completableContacts.collectAsStateWithLifecycle()
    var selectedMerge by remember { mutableStateOf<ContactMergeSuggestion?>(null) }
    var reviewingEnrichment by remember { mutableStateOf(false) }
    var enrichmentError by remember { mutableStateOf<String?>(null) }
    var completionDraft by remember { mutableStateOf<ContactCompletionDraft?>(null) }
    var completionCardError by remember { mutableStateOf<String?>(null) }
    var completionNotice by remember { mutableStateOf<String?>(null) }
    val prepareCompletion: (String) -> Unit = { contactId ->
        completionNotice = null
        viewModel.prepareCompletionOutreach(contactId) { draft, message ->
            if (draft != null) completionDraft = draft else completionNotice = message
        }
    }
    ZhiBanPage {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = ZhiBanSpacing.Xxxl),
            verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Md),
        ) {
            item { ZhiBanTopBar(title = "联系人维护", onBack = onBack) }
            if (overview.needsAttentionCount == 0 && unresolvedIdentities.isEmpty() && completableContacts.isEmpty()) {
                item {
                    Text(
                        "联系人资料已整理",
                        modifier = Modifier.fillMaxWidth().padding(ZhiBanSpacing.Xl),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            } else {
            maintenanceActionSection(
                overview = overview,
                suggestionsAvailable = suggestions.isNotEmpty(),
                unresolvedIdentityCount = unresolvedIdentities.size,
                onReviewDuplicates = { selectedMerge = suggestions.firstOrNull() },
                onReviewEnrichment = { reviewingEnrichment = true },
                onResolveIdentities = { onAsk(unresolvedIdentityPrompt(unresolvedIdentities.first())) },
            )
                items(
                    overview.items.filter { it.issues.isNotEmpty() }.take(MAX_VISIBLE_ITEMS),
                    key = { it.contact.contactId },
                ) { item ->
                    ContactMaintenanceRow(item, onAsk)
                }
                if (completableContacts.isNotEmpty()) {
                    item(key = "completion-header") {
                        Text(
                            "资料待补全",
                            modifier = Modifier.fillMaxWidth()
                                .padding(horizontal = ZhiBanSpacing.PageHorizontal)
                                .padding(top = ZhiBanSpacing.Md),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    items(
                        completableContacts.take(MAX_VISIBLE_ITEMS),
                        key = { "completion-${it.contact.contactId}" },
                    ) { completeness ->
                        ContactCompletionRow(
                            item = completeness,
                            onClick = { prepareCompletion(completeness.contact.contactId) },
                            modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
                        )
                    }
                }
            }
        }
    }
    MaintenanceDialogs(
        MaintenanceDialogSlots(
            selectedMerge = selectedMerge,
            setSelectedMerge = { selectedMerge = it },
            reviewingEnrichment = reviewingEnrichment,
            setReviewingEnrichment = { reviewingEnrichment = it },
            enrichmentError = enrichmentError,
            setEnrichmentError = { enrichmentError = it },
            enrichmentCandidates = enrichmentCandidates,
            rawContacts = rawContacts,
            completionDraft = completionDraft,
            setCompletionDraft = { completionDraft = it },
            completionCardError = completionCardError,
            setCompletionCardError = { completionCardError = it },
            completionNotice = completionNotice,
            setCompletionNotice = { completionNotice = it },
            viewModel = viewModel,
        ),
    )
}



@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ContactEnrichmentReviewSheet(
    candidates: List<ContactEnrichmentCandidateEntity>,
    contacts: List<ContactEntity>,
    error: String?,
    actions: ContactEnrichmentReviewActions,
) {
    val contactsById = remember(contacts) { contacts.associateBy(ContactEntity::contactId) }
    ZhiBanBottomSheet(onDismissRequest = actions.onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = ZhiBanSpacing.Xl)) {
            ZhiBanDialogHeader(
                title = "资料待核实",
                subtitle = if (candidates.isEmpty()) {
                    "全部处理完成"
                } else {
                    localizedQuantity(R.plurals.suggestion_count, candidates.size)
                },
                onDismiss = actions.onDismiss,
            )
            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = ZhiBanSpacing.Md, vertical = ZhiBanSpacing.Xs),
                )
            }
            ContactEnrichmentReviewList(
                candidates = candidates,
                contactsById = contactsById,
                maxHeight = EnrichmentReviewMaxHeight,
                onConfirm = actions.onConfirm,
                onReject = actions.onReject,
            )
        }
    }
}

@Composable
private fun ContactEnrichmentReviewList(
    candidates: List<ContactEnrichmentCandidateEntity>,
    contactsById: Map<String, ContactEntity>,
    maxHeight: Dp,
    onConfirm: (ContactEnrichmentCandidateEntity) -> Unit,
    onReject: (ContactEnrichmentCandidateEntity) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxWidth().heightIn(max = maxHeight),
        contentPadding = PaddingValues(bottom = ZhiBanSpacing.Xxxl),
        verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Sm),
    ) {
        items(candidates, key = ContactEnrichmentCandidateEntity::candidateId) { candidate ->
            Column(Modifier.fillMaxWidth().zhiBanCardSurface().padding(ZhiBanSpacing.Md)) {
                Text(
                    text = candidate.contactId?.let(contactsById::get)?.displayName ?: "联系人",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                ContactEnrichmentRow(
                    candidate = candidate,
                    onConfirm = { onConfirm(candidate) },
                    onReject = { onReject(candidate) },
                )
            }
        }
    }
}

@Composable
private fun ContactMaintenanceRow(item: ContactMaintenanceItem, onAsk: (String) -> Unit) {
    val primaryIssue = item.issues.first()
    val (icon, label) = when (primaryIssue) {
        ContactMaintenanceIssue.NO_REACHABLE_METHOD -> Icons.Outlined.LinkOff to "缺少联系方式"
        ContactMaintenanceIssue.STALE_PROFILE -> Icons.Outlined.PersonSearch to "资料较久未更新"
    }
    MaintenanceActionRow(
        icon = icon,
        title = item.contact.displayName,
        detail = label,
        onClick = { onAsk(verificationPrompt(item, primaryIssue)) },
        modifier = Modifier.padding(horizontal = ZhiBanSpacing.PageHorizontal),
    )
}

@Composable
private fun ContactCompletionRow(item: ContactCompleteness, onClick: () -> Unit, modifier: Modifier = Modifier) {
    MaintenanceActionRow(
        icon = Icons.Outlined.EditNote,
        title = item.contact.displayName,
        detail = "待补：${item.missingFields.joinToString("、") { it.label }}",
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
private fun MaintenanceActionRow(icon: ImageVector, title: String, detail: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().zhiBanCardSurface().clickable(onClick = onClick).padding(ZhiBanSpacing.Lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Md),
    ) {
        ZhiBanLeadingIcon(icon, contentDescription = null)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = "处理$title",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun verificationPrompt(item: ContactMaintenanceItem, issue: ContactMaintenanceIssue): String {
    val task = when (issue) {
        ContactMaintenanceIssue.NO_REACHABLE_METHOD -> "核实可用联系方式"
        ContactMaintenanceIssue.STALE_PROFILE -> "核实姓名、公司、职位和联系方式是否仍有效"
    }
    return "请帮我为联系人“${item.contact.displayName}”$task。先检查本地已有证据；无法确认时生成一条简短、自然的询问文案。不要猜测，对外发送前让我最后确认。"
}

private fun unresolvedIdentityPrompt(identity: SourceIdentityEntity): String = "请核实${identity.sourceType}里显示为“${identity.visibleHandle}”的身份属于谁。" +
    "先检查本地联系人和已有证据；证据不足时只问我一个最关键的问题，不能仅凭同名自动合并。"

private const val MAX_VISIBLE_ITEMS = 100

private fun LazyListScope.maintenanceActionSection(
    overview: com.zhiban.rebuild.data.contact.ContactMaintenanceOverview,
    suggestionsAvailable: Boolean,
    unresolvedIdentityCount: Int,
    onReviewDuplicates: () -> Unit,
    onReviewEnrichment: () -> Unit,
    onResolveIdentities: () -> Unit,
) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = ZhiBanSpacing.PageHorizontal),
                    verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Sm),
                ) {
                    if (overview.duplicateReviewCount > 0) {
                        MaintenanceActionRow(
                            icon = Icons.Outlined.PersonSearch,
                            title = "重复资料",
                            detail = "${overview.duplicateReviewCount} 组待确认",
                            onClick = onReviewDuplicates,
                        )
                    }
                    if (overview.enrichmentReviewCount > 0) {
                        MaintenanceActionRow(
                            icon = Icons.Outlined.AutoAwesome,
                            title = "资料待核实",
                            detail = localizedQuantity(R.plurals.suggestion_count, overview.enrichmentReviewCount),
                            onClick = onReviewEnrichment,
                        )
                    }
                    if (unresolvedIdentities.isNotEmpty()) {
                        MaintenanceActionRow(
                            icon = Icons.Outlined.AlternateEmail,
                            title = "社交身份待关联",
                            detail = localizedQuantity(R.plurals.account_or_group_count, unresolvedIdentityCount),
                            onClick = onResolveIdentities,
                        )
                    }
                }
            }
}

private data class MaintenanceDialogSlots(
    val selectedMerge: ContactMergeSuggestion?,
    val setSelectedMerge: (ContactMergeSuggestion?) -> Unit,
    val reviewingEnrichment: Boolean,
    val setReviewingEnrichment: (Boolean) -> Unit,
    val enrichmentError: String?,
    val setEnrichmentError: (String?) -> Unit,
    val enrichmentCandidates: List<ContactEnrichmentCandidateEntity>,
    val rawContacts: List<ContactEntity>,
    val completionDraft: ContactCompletionDraft?,
    val setCompletionDraft: (ContactCompletionDraft?) -> Unit,
    val completionCardError: String?,
    val setCompletionCardError: (String?) -> Unit,
    val completionNotice: String?,
    val setCompletionNotice: (String?) -> Unit,
    val viewModel: RelationViewModel,
)

@Composable
private fun MaintenanceDialogs(slots: MaintenanceDialogSlots) {
    slots.selectedMerge?.let { suggestion ->
        ContactMergeReviewDialog(
            suggestion = suggestion,
            onDismiss = { slots.setSelectedMerge(null) },
            onConfirm = { canonicalId, sourceId, onResult ->
                slots.viewModel.confirmMerge(canonicalId, sourceId, suggestion.reason) { error ->
                    onResult(error)
                    if (error == null) slots.setSelectedMerge(null)
                }
            },
        )
    }
    if (slots.reviewingEnrichment) {
        ContactEnrichmentReviewSheet(
            candidates = slots.enrichmentCandidates,
            contacts = slots.rawContacts,
            error = slots.enrichmentError,
            actions = ContactEnrichmentReviewActions(
                onDismiss = {
                    slots.setReviewingEnrichment(false)
                    slots.setEnrichmentError(null)
                },
                onConfirm = { candidate ->
                    slots.viewModel.confirmContactEnrichment(candidate) { error -> slots.setEnrichmentError(error) }
                },
                onReject = slots.viewModel::rejectContactEnrichment,
            ),
        )
    }
    slots.completionDraft?.let { draft ->
        ContactCompletionCard(
            draft = draft,
            error = slots.completionCardError,
            onConfirm = { finalText ->
                slots.viewModel.confirmCompletionOutreach(draft.requestId, finalText) { error ->
                    if (error == null) {
                        slots.setCompletionDraft(null)
                        slots.setCompletionCardError(null)
                    } else {
                        slots.setCompletionCardError(error)
                    }
                }
            },
            onCancel = {
                slots.viewModel.cancelCompletionOutreach(draft.requestId)
                slots.setCompletionDraft(null)
                slots.setCompletionCardError(null)
            },
        )
    }
    slots.completionNotice?.let { message ->
        ZhiBanAlertDialog(
            onDismissRequest = { slots.setCompletionNotice(null) },
            confirmButton = { TextButton(onClick = { slots.setCompletionNotice(null) }) { Text("知道了") } },
            title = { Text("资料待补全") },
            text = { Text(message) },
        )
    }
}


