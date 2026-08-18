package com.zhiban.rebuild.ui.tabs

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.RelationshipEdgeEntity
import com.zhiban.rebuild.data.contact.RelationshipEventWithParticipants
import com.zhiban.rebuild.data.contact.RelationshipPersonIds
import com.zhiban.rebuild.ui.components.ZhiBanAlertDialog

/** 关系 TAB 的边/事件对话框槽位(从 RelationTab 拆出,守 1000 有效行红线)。 */
internal data class RelationDialogSlots(
    val contacts: List<ContactEntity>,
    val ownerLabel: String,
    val viewModel: RelationViewModel,
    val showFeedback: (String) -> Unit,
    val deletingEdge: RelationshipEdgeEntity?,
    val setDeletingEdge: (RelationshipEdgeEntity?) -> Unit,
    val selectedEdge: RelationshipEdgeEntity?,
    val setSelectedEdge: (RelationshipEdgeEntity?) -> Unit,
    val addFactFor: ContactEntity?,
    val clearAddFactFor: () -> Unit,
    val addEventFor: ContactEntity?,
    val setAddEventFor: (ContactEntity?) -> Unit,
    val editingEvent: RelationshipEventWithParticipants?,
    val setEditingEvent: (RelationshipEventWithParticipants?) -> Unit,
    val selectedEvent: RelationshipEventWithParticipants?,
    val setSelectedEvent: (RelationshipEventWithParticipants?) -> Unit,
)

@Composable
internal fun RelationEdgeDialogs(slots: RelationDialogSlots) {
    slots.deletingEdge?.let { edge ->
        val names = slots.contacts.associate { it.contactId to it.displayName } +
            (RelationshipPersonIds.SELF to slots.ownerLabel)
        ZhiBanAlertDialog(
            onDismissRequest = { slots.setDeletingEdge(null) },
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
                    slots.viewModel.deleteRelationship(edge.edgeId) {
                        slots.setDeletingEdge(null)
                        slots.showFeedback("关系已删除")
                    }
                }) {
                    Text("删除", color = RelationDanger)
                }
            },
            dismissButton = { TextButton(onClick = { slots.setDeletingEdge(null) }) { Text("取消", color = RelationInk) } },
            containerColor = RelationSurface,
        )
    }
    slots.selectedEdge?.let { edge ->
        RelationshipEvidenceDialog(
            edge = edge,
            personNames = slots.contacts.associate { it.contactId to it.displayName } +
                (RelationshipPersonIds.SELF to slots.ownerLabel),
            onDismiss = { slots.setSelectedEdge(null) },
            onUpdate = { type, result ->
                slots.viewModel.updateRelationship(edge.edgeId, type) { error ->
                    result(error)
                    if (error == null) {
                        slots.setSelectedEdge(null)
                        slots.showFeedback("关系已保存")
                    }
                }
            },
            onDelete = {
                slots.setSelectedEdge(null)
                slots.setDeletingEdge(edge)
            },
        )
    }
    slots.addFactFor?.let { contact ->
        ContactFactEditorDialog(
            contact = contact,
            onDismiss = { slots.clearAddFactFor() },
            onSave = { text, type, result ->
                slots.viewModel.saveContactFact(contact.contactId, text, type) { error ->
                    result(error)
                    if (error == null) {
                        slots.clearAddFactFor()
                        slots.showFeedback("联系人信息已保存")
                    }
                }
            },
        )
    }
}

@Composable
internal fun RelationEventDialogs(slots: RelationDialogSlots) {
    val eventEditorSubject = slots.addEventFor ?: slots.editingEvent?.participants
        ?.firstOrNull { it.participantRole == "SUBJECT" && it.contactId != null }
        ?.contactId?.let { id -> slots.contacts.firstOrNull { it.contactId == id } }
    eventEditorSubject?.let { contact ->
        RelationshipEventEditorDialog(
            contacts = slots.contacts,
            subject = contact,
            existing = slots.editingEvent,
            onDismiss = {
                slots.setAddEventFor(null)
                slots.setEditingEvent(null)
            },
            onSave = { type, title, note, participants, result ->
                slots.viewModel.saveRelationshipEvent(
                    slots.editingEvent?.event?.eventId,
                    type,
                    title,
                    note,
                    participants,
                ) { error ->
                    result(error)
                    if (error == null) {
                        slots.setAddEventFor(null)
                        slots.setEditingEvent(null)
                        slots.showFeedback("经历已保存")
                    }
                }
            },
        )
    }
    slots.selectedEvent?.let { event ->
        RelationshipEventDetailDialog(
            value = event,
            onDismiss = { slots.setSelectedEvent(null) },
            onEdit = {
                slots.setSelectedEvent(null)
                slots.setEditingEvent(event)
            },
            onDelete = {
                slots.viewModel.deleteRelationshipEvent(event.event.eventId) { slots.setSelectedEvent(null) }
            },
        )
    }
}
