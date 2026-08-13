package com.zhiban.rebuild.ui.tabs

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.OwnerContactLinkEntity
import com.zhiban.rebuild.data.contact.RelationshipEdgeEntity
import com.zhiban.rebuild.data.contact.RelationshipEventWithParticipants
import com.zhiban.rebuild.data.contact.RelationshipPersonIds
import com.zhiban.rebuild.runtime.personalization.UserProfile

internal data class RelationDetailState(
    val selected: ContactEntity?,
    val ownerProfile: UserProfile,
    val ownerContactLinks: List<OwnerContactLinkEntity>,
    val contacts: List<ContactEntity>,
    val rawContacts: List<ContactEntity>,
    val graphRelationships: List<RelationshipEdgeEntity>,
    val relationshipEvents: List<RelationshipEventWithParticipants>,
)

internal data class RelationDetailActions(
    val onDismiss: () -> Unit,
    val onEdit: (ContactEntity) -> Unit,
    val onMarkAsOwner: (ContactEntity) -> Unit,
    val onDelete: (ContactEntity) -> Unit,
    val onAddFact: (ContactEntity) -> Unit,
    val onAddEvent: (ContactEntity) -> Unit,
    val onAddIdentity: (ContactEntity) -> Unit,
    val onInspectEvent: (RelationshipEventWithParticipants) -> Unit,
    val onRequestPhoneSync: (ContactEntity) -> Unit,
)

@Composable
internal fun RelationDetailOverlay(state: RelationDetailState, actions: RelationDetailActions, viewModel: RelationViewModel, context: Context) {
    val contact = state.selected ?: return
    val aliases by viewModel.aliases.collectAsStateWithLifecycle()
    val platformIdentities by viewModel.platformIdentities.collectAsStateWithLifecycle()
    val mergeLinks by viewModel.mergeLinks.collectAsStateWithLifecycle()
    val facts by remember(contact.contactId) { viewModel.contactFacts(contact.contactId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val recentCalls by remember(contact.contactId) { viewModel.contactCalls(contact.contactId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val opportunities by remember(contact.contactId) { viewModel.contactOpportunities(contact.contactId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val enrichment by remember(contact.contactId) { viewModel.contactEnrichment(contact.contactId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    ContactDetailDialog(
        contact = contact,
        showMarkAsOwner = contact.matchesOwnerProfile(state.ownerProfile) &&
            state.ownerContactLinks.none { it.contactId == contact.contactId },
        facts = facts,
        relatedEdges = state.graphRelationships.filter {
            it.fromContactId == contact.contactId || it.toContactId == contact.contactId
        },
        relatedEvents = state.relationshipEvents.filter { event ->
            event.participants.any { it.contactId == contact.contactId }
        },
        recentCalls = recentCalls,
        crmOpportunities = opportunities,
        enrichmentSuggestions = enrichment,
        contactNames = state.contacts.associate { it.contactId to it.displayName } +
            (RelationshipPersonIds.SELF to state.ownerProfile.relationshipLabel()),
        aliases = aliases.filter { it.contactId == contact.contactId },
        platformIdentities = platformIdentities.filter { it.contactId == contact.contactId },
        mergedSources = mergeLinks.filter { it.canonicalContactId == contact.contactId }.mapNotNull { link ->
            state.rawContacts.firstOrNull { it.contactId == link.sourceContactId }?.let { link to it }
        },
        onDismiss = actions.onDismiss,
        onEdit = { actions.onEdit(contact) },
        onMarkAsOwner = { actions.onMarkAsOwner(contact) },
        onDelete = { actions.onDelete(contact) },
        onAddFact = { actions.onAddFact(contact) },
        onAddEvent = { actions.onAddEvent(contact) },
        onAddIdentity = { actions.onAddIdentity(contact) },
        onInspectEvent = actions.onInspectEvent,
        onDeleteFact = viewModel::deleteContactFact,
        onDeleteAlias = viewModel::deleteAlias,
        onDeletePlatformIdentity = viewModel::deletePlatformIdentity,
        onUndoMerge = viewModel::undoMerge,
        onConfirmEnrichment = { candidate ->
            viewModel.confirmContactEnrichment(candidate) { error ->
                error?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
            }
        },
        onRejectEnrichment = viewModel::rejectContactEnrichment,
        onSaveToPhone = { actions.onRequestPhoneSync(contact) },
        onCall = { launchContactIntent(context, Intent.ACTION_DIAL, "tel", contact.phone) },
        onMessage = { launchContactIntent(context, Intent.ACTION_SENDTO, "smsto", contact.phone) },
    )
}

private fun launchContactIntent(context: Context, action: String, scheme: String, value: String?) {
    value?.takeIf(String::isNotBlank)?.let { destination ->
        runCatching {
            context.startActivity(Intent(action, Uri.parse("$scheme:${Uri.encode(destination)}")))
        }
    }
}
