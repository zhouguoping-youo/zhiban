package com.zhiban.rebuild.runtime.governance

import androidx.room.withTransaction
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.runtime.tool.sha256

internal object AutoWriteToolNames {
    const val INTERACTION_SUMMARY = "contact.interactionSummary.record"
    const val CONTACT_TAG_ADD = "contact.tag.add"
    const val CONTACT_IDENTITY_AUTO_LINK = "contact.identity.autoLink"
    const val SCHEDULE_CREATE = "calendar.schedule.autoCreate"
    const val MEMORY_UPSERT = "memory.upsert"
    const val CRM_LEAD_CANDIDATE = "crm.lead.createCandidate"
    const val CRM_ACTIVITY_APPEND = "crm.activity.append"
    const val CRM_NEXT_ACTION_CREATE = "crm.nextAction.create"

    /** User-confirmed acceptance of a CALL_FOLLOW_UP suggestion (writes a CALL activity; undoable). */
    const val CRM_SUGGESTION_ACCEPT_ACTIVITY = "crm.suggestion.acceptActivity"

    /** User-confirmed acceptance of a NEW_LEAD suggestion (creates a lead; undoable). */
    const val CRM_SUGGESTION_ACCEPT_LEAD = "crm.suggestion.acceptLead"

    val all = setOf(
        INTERACTION_SUMMARY,
        CONTACT_TAG_ADD,
        CONTACT_IDENTITY_AUTO_LINK,
        SCHEDULE_CREATE,
        MEMORY_UPSERT,
        CRM_LEAD_CANDIDATE,
        CRM_ACTIVITY_APPEND,
        CRM_NEXT_ACTION_CREATE,
        CRM_SUGGESTION_ACCEPT_ACTIVITY,
        CRM_SUGGESTION_ACCEPT_LEAD,
    )
}

internal object AutoWritePresentationRegistry {
    val toolNames: Set<String> = AutoWriteToolNames.all
}

internal data class AutoWriteAuditDraft(
    val changeId: String,
    val runtimeRunId: String?,
    val toolName: String,
    val idempotencyKey: String,
    val targetDomain: String,
    val targetId: String,
    val operation: String,
    val beforeDigest: String? = null,
    val afterDigest: String,
    val inversePayloadJson: String,
    val originType: String,
    val subjectContactId: String?,
    val sourceType: String,
    val sourceRef: String,
    val confidence: Double?,
    val presentationType: String,
    val correctionRoute: String,
    val createdAtEpochMs: Long,
)

internal suspend fun AgentDatabase.insertVisibleAutoWrite(draft: AutoWriteAuditDraft) {
    require(draft.toolName in AutoWriteToolNames.all)
    require(draft.inversePayloadJson != "{}" && draft.inversePayloadJson.isNotBlank())
    withTransaction {
        changeLogDao().insert(
            ChangeLogEntity(
                changeId = draft.changeId,
                runtimeRunId = draft.runtimeRunId,
                toolName = draft.toolName,
                idempotencyKey = draft.idempotencyKey,
                targetDomain = draft.targetDomain,
                targetId = draft.targetId,
                operation = draft.operation,
                beforeDigest = draft.beforeDigest,
                afterDigest = draft.afterDigest,
                inversePayloadJson = draft.inversePayloadJson,
                undoState = "AVAILABLE",
                createdAtEpochMs = draft.createdAtEpochMs,
                undoneAtEpochMs = null,
                originType = draft.originType,
            ),
        )
        changeLogDao().insertAutoWriteReceipt(
            AutoWriteReceiptEntity(
                changeId = draft.changeId,
                subjectContactId = draft.subjectContactId,
                sourceType = draft.sourceType,
                sourceRefDigest = sha256(draft.sourceRef),
                confidence = draft.confidence,
                presentationType = draft.presentationType,
                correctionRoute = draft.correctionRoute,
                reviewState = "UNREVIEWED",
                createdAtEpochMs = draft.createdAtEpochMs,
            ),
        )
    }
}
