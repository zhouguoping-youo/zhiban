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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.zhiban.rebuild.data.contact.ContactEnrichmentCandidateEntity
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.ContactMaintenanceIssue
import com.zhiban.rebuild.data.contact.ContactMaintenanceItem
import com.zhiban.rebuild.data.contact.SourceIdentityEntity
import com.zhiban.rebuild.ui.components.ZhiBanBottomSheet
import com.zhiban.rebuild.ui.components.ZhiBanDialogHeader
import com.zhiban.rebuild.ui.components.ZhiBanLeadingIcon
import com.zhiban.rebuild.ui.components.ZhiBanPage
import com.zhiban.rebuild.ui.components.ZhiBanTopBar
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
    var selectedMerge by remember { mutableStateOf<ContactMergeSuggestion?>(null) }
    var reviewingEnrichment by remember { mutableStateOf(false) }
    var enrichmentError by remember { mutableStateOf<String?>(null) }
    ZhiBanPage {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = ZhiBanSpacing.Xxxl),
            verticalArrangement = Arrangement.spacedBy(ZhiBanSpacing.Md),
        ) {
            item { ZhiBanTopBar(title = "联系人维护", onBack = onBack) }
            if (overview.needsAttentionCount == 0 && unresolvedIdentities.isEmpty()) {
                item {
                    Text(
                        "联系人资料已整理",
                        modifier = Modifier.fillMaxWidth().padding(ZhiBanSpacing.Xl),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            } else {
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
                                onClick = { selectedMerge = suggestions.firstOrNull() },
                            )
                        }
                        if (overview.enrichmentReviewCount > 0) {
                            MaintenanceActionRow(
                                icon = Icons.Outlined.AutoAwesome,
                                title = "资料待核实",
                                detail = "${overview.enrichmentReviewCount} 条建议",
                                onClick = { reviewingEnrichment = true },
                            )
                        }
                        if (unresolvedIdentities.isNotEmpty()) {
                            MaintenanceActionRow(
                                icon = Icons.Outlined.AlternateEmail,
                                title = "社交身份待关联",
                                detail = "${unresolvedIdentities.size} 个账号或群昵称",
                                onClick = { onAsk(unresolvedIdentityPrompt(unresolvedIdentities.first())) },
                            )
                        }
                    }
                }
                items(
                    overview.items.filter { it.issues.isNotEmpty() }.take(MAX_VISIBLE_ITEMS),
                    key = { it.contact.contactId },
                ) { item ->
                    ContactMaintenanceRow(item, onAsk)
                }
            }
        }
    }
    selectedMerge?.let { suggestion ->
        ContactMergeReviewDialog(
            suggestion = suggestion,
            onDismiss = { selectedMerge = null },
            onConfirm = { canonicalId, sourceId, onResult ->
                viewModel.confirmMerge(canonicalId, sourceId, suggestion.reason) { error ->
                    onResult(error)
                    if (error == null) selectedMerge = null
                }
            },
        )
    }
    if (reviewingEnrichment) {
        ContactEnrichmentReviewSheet(
            candidates = enrichmentCandidates,
            contacts = rawContacts,
            error = enrichmentError,
            actions = ContactEnrichmentReviewActions(
                onDismiss = {
                    reviewingEnrichment = false
                    enrichmentError = null
                },
                onConfirm = { candidate ->
                    viewModel.confirmContactEnrichment(candidate) { error -> enrichmentError = error }
                },
                onReject = viewModel::rejectContactEnrichment,
            ),
        )
    }
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
                subtitle = if (candidates.isEmpty()) "全部处理完成" else "${candidates.size} 条建议",
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
