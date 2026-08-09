package com.zhiban.rebuild.data.agent

import androidx.room.withTransaction
import com.zhiban.rebuild.data.calendar.SystemCalendarEvent
import com.zhiban.rebuild.data.contact.ContactAddressEntity
import com.zhiban.rebuild.data.contact.ContactAliasEntity
import com.zhiban.rebuild.data.contact.ContactEmploymentEntity
import com.zhiban.rebuild.data.contact.ContactEnrichmentCandidateEntity
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.ContactFacetEntity
import com.zhiban.rebuild.data.contact.ContactImportantDateEntity
import com.zhiban.rebuild.data.contact.ContactMergeLinkEntity
import com.zhiban.rebuild.data.contact.ContactMethodEntity
import com.zhiban.rebuild.data.contact.ContactPlatformIdentityEntity
import com.zhiban.rebuild.data.contact.ContactRoleEntity
import com.zhiban.rebuild.data.contact.OrganizationEntity
import com.zhiban.rebuild.data.contact.OwnerContactLinkEntity
import com.zhiban.rebuild.data.contact.RelationshipEdgeEntity
import com.zhiban.rebuild.data.contact.RelationshipEventEntity
import com.zhiban.rebuild.data.contact.RelationshipEventParticipantEntity
import com.zhiban.rebuild.data.contact.RelationshipEventWithParticipants
import com.zhiban.rebuild.data.contact.RelationshipPersonIds
import com.zhiban.rebuild.data.contact.SystemContactCandidate
import com.zhiban.rebuild.data.contact.normalizeContactPhone
import com.zhiban.rebuild.data.crm.CrmActionStatus
import com.zhiban.rebuild.data.crm.CrmActivityEntity
import com.zhiban.rebuild.data.crm.CrmAgentSuggestionEntity
import com.zhiban.rebuild.data.crm.CrmDemoCleanupAuditEntity
import com.zhiban.rebuild.data.crm.CrmLeadEntity
import com.zhiban.rebuild.data.crm.CrmLeadStatus
import com.zhiban.rebuild.data.crm.CrmNextActionEntity
import com.zhiban.rebuild.data.crm.CrmOpportunityEntity
import com.zhiban.rebuild.data.crm.CrmOpportunityStage
import com.zhiban.rebuild.data.crm.CrmOpportunityStakeholderEntity
import com.zhiban.rebuild.data.crm.CrmRecordStatus
import com.zhiban.rebuild.data.crm.CrmStageHistoryEntity
import com.zhiban.rebuild.data.crm.CrmSuggestionStatus
import com.zhiban.rebuild.data.crm.CrmSuggestionType
import com.zhiban.rebuild.data.notification.MessageCollectionPreferences
import com.zhiban.rebuild.data.notification.NotificationCandidateEntity
import com.zhiban.rebuild.data.notification.NotificationInsightAnalyzer
import com.zhiban.rebuild.data.notification.ScheduleInsight
import com.zhiban.rebuild.runtime.context.FactEntity
import com.zhiban.rebuild.runtime.context.FactIndex
import com.zhiban.rebuild.runtime.governance.ActionDecision
import com.zhiban.rebuild.runtime.governance.ActionPolicy
import com.zhiban.rebuild.runtime.governance.AutoWriteAuditDraft
import com.zhiban.rebuild.runtime.governance.AutoWriteToolNames
import com.zhiban.rebuild.runtime.governance.ReversibleWriteReadiness
import com.zhiban.rebuild.runtime.governance.canonicalChangeDigest
import com.zhiban.rebuild.runtime.governance.insertVisibleAutoWrite
import com.zhiban.rebuild.runtime.tool.RuntimeToolRisk
import com.zhiban.rebuild.runtime.tool.RuntimeToolSpec
import com.zhiban.rebuild.runtime.tool.changeIdFor
import com.zhiban.rebuild.runtime.tool.sha256
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import org.json.JSONObject

data class CrmDemoCleanupSummary(val planned: Map<String, Int>, val deleted: Map<String, Int>) {
    val totalDeleted: Int get() = deleted.values.sum()
}

/** Input for converting a formal lead into an opportunity. Amount is in minor units (分). */
data class CrmLeadConversionInput(val title: String, val accountName: String, val valueMinor: Long?, val expectedCloseAtEpochMs: Long?)

internal class CrmAgentDataRepository(private val database: AgentDatabase) {
    private val schedules = database.scheduleDao()
    fun observeCrmLeads(): Flow<List<CrmLeadEntity>> = database.crmDao().observeLeads()

    fun observeCrmCandidateLeads(): Flow<List<CrmLeadEntity>> = database.crmDao().observeCandidateLeads()

    fun observeCrmOpportunities(): Flow<List<CrmOpportunityEntity>> = database.crmDao().observeOpportunities()

    fun observeCrmOpportunity(opportunityId: String): Flow<CrmOpportunityEntity?> = database.crmDao().observeOpportunity(opportunityId)

    fun observeCrmStakeholders(opportunityId: String): Flow<List<CrmOpportunityStakeholderEntity>> = database.crmDao().observeStakeholders(opportunityId)

    fun observeCrmActivities(opportunityId: String): Flow<List<CrmActivityEntity>> = database.crmDao().observeActivities(opportunityId)

    fun observeCrmPendingActions(): Flow<List<CrmNextActionEntity>> = database.crmDao().observePendingActions()

    /** Reactive (new-leads, activities) counters for the CRM dashboard since [sinceEpochMs]. */
    fun observeCrmDashboardCounts(sinceEpochMs: Long): Flow<Pair<Int, Int>> =
        database.crmDao().observeDashboardActivityCounts(sinceEpochMs).map { it.newLeadCount to it.activityCount }

    fun observeCrmActions(opportunityId: String): Flow<List<CrmNextActionEntity>> = database.crmDao().observeActions(opportunityId)

    fun observeCrmPendingSuggestions(): Flow<List<CrmAgentSuggestionEntity>> = database.crmDao().observePendingSuggestions(SUGGESTION_MIN_CONFIDENCE)

    fun observeCrmSuggestions(opportunityId: String): Flow<List<CrmAgentSuggestionEntity>> = database.crmDao().observeSuggestions(opportunityId)

    fun observeCrmStageHistory(opportunityId: String): Flow<List<CrmStageHistoryEntity>> = database.crmDao().observeStageHistory(opportunityId)

    fun observeCrmOpportunitiesByContact(contactId: String): Flow<List<CrmOpportunityEntity>> = database.crmDao().observeOpportunitiesByContact(contactId)

    fun observeCrmLeadsByContact(contactId: String): Flow<List<CrmLeadEntity>> = database.crmDao().observeLeadsByContact(contactId)

    suspend fun findOpenOpportunityForContact(contactId: String): CrmOpportunityEntity? = database.crmDao().findOpenOpportunityByContact(contactId)

    suspend fun findLeadByContact(contactId: String): CrmLeadEntity? = database.crmDao().findLeadByContact(contactId)

    /**
     * Creates a NEW lead for a contact when a high-confidence message match is confirmed and the
     * contact has no lead yet. Returns the new lead id, or null when a lead already exists. The caller
     * has already secured the user's confirmation, so this write is user-driven, not a silent auto-write.
     */
    suspend fun createLeadForContactIfAbsent(contactId: String, sourceRef: String?, nowEpochMs: Long = System.currentTimeMillis()): String? =
        database.withTransaction {
            if (database.crmDao().findLeadByContact(contactId) != null) return@withTransaction null
            val contact = database.contactDao().findById(contactId) ?: return@withTransaction null
            val leadId = "lead-${UUID.randomUUID()}"
            database.crmDao().insertLead(
                CrmLeadEntity(
                    leadId = leadId,
                    contactId = contactId,
                    displayNameSnapshot = contact.displayName,
                    companyNameSnapshot = contact.company,
                    status = CrmLeadStatus.NEW,
                    sourceType = "USER_CONFIRMED",
                    sourceRef = sourceRef,
                    fitSummary = null,
                    confidence = 1.0,
                    userConfirmed = true,
                    createdAtEpochMs = nowEpochMs,
                    updatedAtEpochMs = nowEpochMs,
                ),
            )
            leadId
        }

    suspend fun setCrmActionCompleted(actionId: String, completed: Boolean): Boolean = database.crmDao().updateActionStatus(
        actionId,
        if (completed) CrmActionStatus.COMPLETED else CrmActionStatus.PENDING,
        System.currentTimeMillis(),
    ) == 1

    suspend fun setCrmSuggestionStatus(suggestionId: String, accepted: Boolean): Boolean = database.crmDao().transitionSuggestionStatus(
        suggestionId,
        CrmSuggestionStatus.PENDING,
        if (accepted) CrmSuggestionStatus.ACCEPTED else CrmSuggestionStatus.DISMISSED,
        System.currentTimeMillis(),
    ) == 1

    /**
     * Suggests logging a follow-up CALL activity after a call with a contact who has an open
     * opportunity. Suggestion-only (user confirms before anything is written). Returns true when a
     * suggestion was created; false when the contact has no open opportunity or one is already pending.
     */
    suspend fun suggestCallFollowUpActivity(
        contactId: String,
        callRecordId: String,
        durationSeconds: Long,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Boolean = database.withTransaction {
        val evidenceRefsJson = buildJsonArray { add(JsonPrimitive(callRecordId)) }.toString()
        if (database.crmDao().countSuggestionsByEvidence(CrmSuggestionType.CALL_FOLLOW_UP, evidenceRefsJson) > 0) {
            return@withTransaction false
        }
        val opportunity = database.crmDao().findOpenOpportunityByContact(contactId) ?: return@withTransaction false
        if (database.crmDao().hasPendingSuggestionOfType(opportunity.opportunityId, CrmSuggestionType.CALL_FOLLOW_UP)) {
            return@withTransaction false
        }
        if (CALL_FOLLOW_UP_CONFIDENCE < SUGGESTION_MIN_CONFIDENCE) return@withTransaction false
        val contact = database.contactDao().findById(contactId)
        val name = contact?.displayName ?: opportunity.accountNameSnapshot
        database.crmDao().upsertSuggestions(
            listOf(
                CrmAgentSuggestionEntity(
                    suggestionId = "sug-${UUID.randomUUID()}",
                    opportunityId = opportunity.opportunityId,
                    contactId = contactId,
                    suggestionType = CrmSuggestionType.CALL_FOLLOW_UP,
                    title = "记录通话跟进",
                    summary = "刚和$name 通话 ${formatCallMinutes(durationSeconds)}，要不要记一条跟进记录？",
                    rationale = "通话后及时记录跟进有助于推进「${opportunity.title}」".take(500),
                    evidenceRefsJson = evidenceRefsJson,
                    confidence = CALL_FOLLOW_UP_CONFIDENCE,
                    proposedActionJson = null,
                    status = CrmSuggestionStatus.PENDING,
                    createdAtEpochMs = nowEpochMs,
                    updatedAtEpochMs = nowEpochMs,
                ),
            ),
        )
        true
    }

    /**
     * Suggests creating a lead for a contact matched by a high-confidence notification, when that
     * contact has no lead yet. Suggestion-only (user confirms before anything is written). Returns
     * true when a suggestion was created; false when the match is too weak, the contact already has
     * a lead, or a NEW_LEAD suggestion is already pending for the contact.
     */
    suspend fun suggestNewLeadFromNotification(contactId: String, candidateId: String, nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        database.withTransaction {
            val candidate = database.notificationCandidateDao().find(candidateId) ?: return@withTransaction false
            if (candidate.suggestedContactId != contactId) return@withTransaction false
            val confidence = candidate.suggestedContactConfidence
            if (confidence !in SUGGESTION_MIN_CONFIDENCE..1.0) return@withTransaction false
            val contact = database.contactDao().findById(contactId) ?: return@withTransaction false
            if (database.crmDao().findLeadByContact(contactId) != null) return@withTransaction false
            if (database.crmDao().hasPendingSuggestionOfTypeForContact(contactId, CrmSuggestionType.NEW_LEAD)) {
                return@withTransaction false
            }
            database.crmDao().upsertSuggestions(
                listOf(
                    CrmAgentSuggestionEntity(
                        suggestionId = "sug-${UUID.randomUUID()}",
                        opportunityId = null,
                        contactId = contactId,
                        suggestionType = CrmSuggestionType.NEW_LEAD,
                        title = "新建线索",
                        summary = "识别到「${contact.displayName}」的新消息，要不要把 TA 加为线索？".take(200),
                        rationale = "高置信通知匹配到联系人「${contact.displayName}」（置信度 ${"%.2f".format(confidence)}），TA 还没有线索。".take(500),
                        evidenceRefsJson = buildJsonArray { add(JsonPrimitive(candidate.candidateId)) }.toString(),
                        confidence = confidence,
                        proposedActionJson = null,
                        status = CrmSuggestionStatus.PENDING,
                        createdAtEpochMs = nowEpochMs,
                        updatedAtEpochMs = nowEpochMs,
                    ),
                ),
            )
            true
        }

    /** Marks stale PENDING suggestions EXPIRED. Returns the number of rows transitioned. */
    suspend fun expireStaleSuggestions(nowEpochMs: Long = System.currentTimeMillis()): Int =
        database.crmDao().expirePendingSuggestionsBefore(nowEpochMs - SUGGESTION_TTL_MS, nowEpochMs)

    /**
     * Accepts a pending call-follow-up suggestion: writes the CALL activity (undoable via ChangeLog)
     * and marks the suggestion accepted in one transaction. Returns false when the suggestion is
     * missing, no longer pending, or has no live opportunity.
     */
    suspend fun acceptCallFollowUpSuggestion(suggestionId: String, nowEpochMs: Long = System.currentTimeMillis()): Boolean = database.withTransaction {
        val suggestion = database.crmDao().findSuggestion(suggestionId) ?: return@withTransaction false
        if (suggestion.status != CrmSuggestionStatus.PENDING) return@withTransaction false
        if (suggestion.suggestionType != CrmSuggestionType.CALL_FOLLOW_UP) return@withTransaction false
        val opportunityId = suggestion.opportunityId ?: return@withTransaction false
        val opportunity = database.crmDao().findOpportunity(opportunityId) ?: return@withTransaction false
        val activity = CrmActivityEntity(
            activityId = "act-${UUID.randomUUID()}",
            opportunityId = opportunity.opportunityId,
            contactId = opportunity.primaryContactId,
            activityType = "CALL",
            title = suggestion.title,
            summary = suggestion.summary,
            occurredAtEpochMs = nowEpochMs,
            sourceType = "USER_CONFIRMED",
            sourceRef = suggestion.evidenceRefsJson,
            evidenceSummary = suggestion.rationale,
            userConfirmed = true,
            createdAtEpochMs = nowEpochMs,
        )
        database.crmDao().insertActivity(activity)
        if (
            database.crmDao().transitionSuggestionStatus(
                suggestionId,
                CrmSuggestionStatus.PENDING,
                CrmSuggestionStatus.ACCEPTED,
                nowEpochMs,
            ) != 1
        ) {
            return@withTransaction false
        }
        recordSuggestionAcceptAudit(
            toolName = AutoWriteToolNames.CRM_SUGGESTION_ACCEPT_ACTIVITY,
            suggestion = suggestion,
            afterDigest = canonicalChangeDigest(activity),
            inversePayloadJson = "{\"deleteActivityId\":\"${activity.activityId}\"}",
            subjectContactId = opportunity.primaryContactId,
            nowEpochMs = nowEpochMs,
        )
        true
    }

    /**
     * Accepts a pending new-lead suggestion: creates the lead (undoable via ChangeLog) and marks the
     * suggestion accepted in one transaction. Returns false when the suggestion is invalid, the
     * contact is gone, or a lead already exists for the contact.
     */
    suspend fun acceptNewLeadSuggestion(suggestionId: String, nowEpochMs: Long = System.currentTimeMillis()): Boolean = database.withTransaction {
        val suggestion = database.crmDao().findSuggestion(suggestionId) ?: return@withTransaction false
        if (suggestion.status != CrmSuggestionStatus.PENDING) return@withTransaction false
        if (suggestion.suggestionType != CrmSuggestionType.NEW_LEAD) return@withTransaction false
        val contactId = suggestion.contactId ?: return@withTransaction false
        val contact = database.contactDao().findById(contactId) ?: return@withTransaction false
        if (database.crmDao().findLeadByContact(contactId) != null) return@withTransaction false
        val lead = CrmLeadEntity(
            leadId = "lead-${UUID.randomUUID()}",
            contactId = contactId,
            displayNameSnapshot = contact.displayName,
            companyNameSnapshot = contact.company,
            status = CrmLeadStatus.NEW,
            sourceType = "USER_CONFIRMED",
            sourceRef = suggestion.evidenceRefsJson,
            fitSummary = suggestion.summary,
            confidence = suggestion.confidence,
            userConfirmed = true,
            createdAtEpochMs = nowEpochMs,
            updatedAtEpochMs = nowEpochMs,
        )
        database.crmDao().insertLead(lead)
        if (
            database.crmDao().transitionSuggestionStatus(
                suggestionId,
                CrmSuggestionStatus.PENDING,
                CrmSuggestionStatus.ACCEPTED,
                nowEpochMs,
            ) != 1
        ) {
            return@withTransaction false
        }
        recordSuggestionAcceptAudit(
            toolName = AutoWriteToolNames.CRM_SUGGESTION_ACCEPT_LEAD,
            suggestion = suggestion,
            afterDigest = canonicalChangeDigest(lead),
            inversePayloadJson = "{\"deleteLeadId\":\"${lead.leadId}\"}",
            subjectContactId = contactId,
            nowEpochMs = nowEpochMs,
        )
        true
    }

    /** Records the undoable ChangeLog entry for a user-confirmed suggestion acceptance. */
    private suspend fun recordSuggestionAcceptAudit(
        toolName: String,
        suggestion: CrmAgentSuggestionEntity,
        afterDigest: String,
        inversePayloadJson: String,
        subjectContactId: String?,
        nowEpochMs: Long,
    ) {
        val idempotencyKey = "crm-suggestion-accept:${suggestion.suggestionId}"
        database.insertVisibleAutoWrite(
            AutoWriteAuditDraft(
                changeId = changeIdFor(idempotencyKey),
                runtimeRunId = null,
                toolName = toolName,
                idempotencyKey = idempotencyKey,
                targetDomain = "CRM_SUGGESTION",
                targetId = suggestion.suggestionId,
                operation = "UPDATE",
                afterDigest = afterDigest,
                inversePayloadJson = inversePayloadJson,
                originType = "SYSTEM_PERCEPTION",
                subjectContactId = subjectContactId,
                sourceType = "USER_CONFIRMED",
                sourceRef = suggestion.evidenceRefsJson,
                confidence = suggestion.confidence,
                presentationType = "CRM_SUGGESTION",
                correctionRoute = "CRM_SUGGESTION_LIST",
                createdAtEpochMs = nowEpochMs,
            ),
        )
    }

    suspend fun updateCrmOpportunityStage(opportunityId: String, newStage: String, reason: String, nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        database.withTransaction {
            val current = database.crmDao().findOpportunity(opportunityId) ?: return@withTransaction false
            CrmOpportunityStage.requireTransitionAllowed(current.stage, newStage)
            if (current.stage == newStage) return@withTransaction true
            val recordStatus = when (newStage) {
                CrmOpportunityStage.WON -> CrmRecordStatus.WON
                CrmOpportunityStage.LOST -> CrmRecordStatus.LOST
                else -> CrmRecordStatus.OPEN
            }
            val probability = CrmOpportunityStage.probabilityPercent(newStage)
            check(
                database.crmDao().updateOpportunityStage(opportunityId, newStage, recordStatus, probability, nowEpochMs) ==
                    1,
            )
            database.crmDao().upsertStageHistory(
                listOf(
                    CrmStageHistoryEntity(
                        historyId = "stage-${UUID.randomUUID()}",
                        opportunityId = opportunityId,
                        fromStage = current.stage,
                        toStage = newStage,
                        reason = reason.take(500),
                        sourceType = "USER_CONFIRMED",
                        userConfirmed = true,
                        changedAtEpochMs = nowEpochMs,
                    ),
                ),
            )
            true
        }

    suspend fun qualifyCrmLead(leadId: String, nowEpochMs: Long = System.currentTimeMillis()): Boolean = database.withTransaction {
        val lead = database.crmDao().findLead(leadId) ?: return@withTransaction false
        if (lead.status != CrmLeadStatus.NEW && lead.status != CrmLeadStatus.CONTACTED) return@withTransaction false
        database.crmDao().upsertLeads(
            listOf(
                lead.copy(
                    status = CrmLeadStatus.QUALIFIED,
                    userConfirmed = true,
                    sourceType = "USER_CONFIRMED",
                    updatedAtEpochMs = nowEpochMs,
                ),
            ),
        )
        true
    }

    suspend fun disqualifyCrmLead(leadId: String, nowEpochMs: Long = System.currentTimeMillis()): Boolean = database.withTransaction {
        database.crmDao().disqualifyLead(leadId, nowEpochMs) == 1
    }

    /**
     * Converts a formal lead into an open opportunity in a single transaction: the lead becomes
     * CONVERTED, an opportunity + its initial stage-history row + a conversion activity are written.
     * Returns the new opportunity id, or null when the lead is missing or not in a convertible status.
     */
    suspend fun convertLeadToOpportunity(leadId: String, input: CrmLeadConversionInput, nowEpochMs: Long = System.currentTimeMillis()): String? =
        database.withTransaction {
            val lead = database.crmDao().findLead(leadId) ?: return@withTransaction null
            if (lead.status !in CrmLeadStatus.convertibleStatuses) return@withTransaction null
            require(input.title.isNotBlank()) { "机会标题不能为空" }
            require(input.accountName.isNotBlank()) { "客户名不能为空" }

            val opportunityId = "opp-${UUID.randomUUID()}"
            check(database.crmDao().markLeadConverted(leadId, nowEpochMs) == 1)
            database.crmDao().insertOpportunity(
                CrmOpportunityEntity(
                    opportunityId = opportunityId,
                    title = input.title.trim(),
                    accountNameSnapshot = input.accountName.trim(),
                    primaryContactId = lead.contactId,
                    sourceLeadId = lead.leadId,
                    stage = CrmOpportunityStage.LEAD,
                    status = CrmRecordStatus.OPEN,
                    valueMinor = input.valueMinor,
                    currencyCode = "CNY",
                    probabilityPercent = CrmOpportunityStage.probabilityPercent(CrmOpportunityStage.LEAD),
                    expectedCloseAtEpochMs = input.expectedCloseAtEpochMs,
                    productSummary = null,
                    needSummary = null,
                    lossReason = null,
                    sourceType = "USER_CONFIRMED",
                    createdAtEpochMs = nowEpochMs,
                    updatedAtEpochMs = nowEpochMs,
                ),
            )
            database.crmDao().insertStageHistory(
                CrmStageHistoryEntity(
                    historyId = "stage-${UUID.randomUUID()}",
                    opportunityId = opportunityId,
                    fromStage = null,
                    toStage = CrmOpportunityStage.LEAD,
                    reason = "由线索「${lead.displayNameSnapshot}」转化".take(500),
                    sourceType = "USER_CONFIRMED",
                    userConfirmed = true,
                    changedAtEpochMs = nowEpochMs,
                ),
            )
            database.crmDao().insertActivity(
                CrmActivityEntity(
                    activityId = "act-${UUID.randomUUID()}",
                    opportunityId = opportunityId,
                    contactId = lead.contactId,
                    activityType = "CONVERSION",
                    title = "线索转化为商机",
                    summary = "线索「${lead.displayNameSnapshot}」转化为商机「${input.title.trim()}」".take(200),
                    occurredAtEpochMs = nowEpochMs,
                    sourceType = "USER_CONFIRMED",
                    sourceRef = lead.leadId,
                    evidenceSummary = null,
                    userConfirmed = true,
                    createdAtEpochMs = nowEpochMs,
                ),
            )
            opportunityId
        }

    /**
     * contact names, dates, or fuzzy matching. A non-PII audit receipt records both counts.
     */
    suspend fun clearLegacyCrmDemoData(triggerType: String = "USER_EXIT_DEMO", nowEpochMs: Long = System.currentTimeMillis()): CrmDemoCleanupSummary =
        database.withTransaction {
            val crm = database.crmDao()
            val contacts = database.contactDao()
            val planned = linkedMapOf(
                "contacts" to contacts.countLegacyCrmDemo(),
                "schedules" to schedules.countLegacyCrmDemo(),
                "leads" to crm.countDemoLeads(),
                "opportunities" to crm.countDemoOpportunities(),
                "activities" to crm.countDemoActivities(),
                "actions" to crm.countDemoActions(),
                "suggestions" to crm.countDemoSuggestions(),
                "stakeholders" to crm.countDemoStakeholders(),
                "stageHistory" to crm.countDemoStageHistory(),
            )
            crm.deleteDemoOpportunities()
            crm.deleteDemoLeads()
            schedules.deleteLegacyCrmDemo()
            contacts.deleteLegacyCrmDemo()
            schedules.migrateCrmLabel()
            val remaining = linkedMapOf(
                "contacts" to contacts.countLegacyCrmDemo(),
                "schedules" to schedules.countLegacyCrmDemo(),
                "leads" to crm.countDemoLeads(),
                "opportunities" to crm.countDemoOpportunities(),
                "activities" to crm.countDemoActivities(),
                "actions" to crm.countDemoActions(),
                "suggestions" to crm.countDemoSuggestions(),
                "stakeholders" to crm.countDemoStakeholders(),
                "stageHistory" to crm.countDemoStageHistory(),
            )
            val deleted = planned.mapValues { (key, count) -> count - (remaining[key] ?: 0) }
            fun Map<String, Int>.toJson(): String = JSONObject().also { json ->
                forEach { (key, value) -> json.put(key, value) }
            }.toString()
            val cleanupKey = "user:crm-demo-cleanup:${UUID.randomUUID()}"
            crm.insertDemoCleanupAudit(
                CrmDemoCleanupAuditEntity(
                    auditId = "crm-demo-cleanup-${UUID.randomUUID()}",
                    cleanupKey = cleanupKey,
                    triggerType = triggerType,
                    plannedCountsJson = planned.toJson(),
                    deletedCountsJson = deleted.toJson(),
                    status = "COMPLETED",
                    startedAtEpochMs = nowEpochMs,
                    completedAtEpochMs = System.currentTimeMillis(),
                ),
            )
            CrmDemoCleanupSummary(planned, deleted)
        }

    companion object {
        /** A resolved (exact-match) call match is treated as fully confident for follow-up suggestions. */
        private const val CALL_FOLLOW_UP_CONFIDENCE = 0.7

        /** Below this confidence a suggestion is neither created nor shown. */
        internal const val SUGGESTION_MIN_CONFIDENCE = 0.7

        /** PENDING suggestions older than this are marked EXPIRED by maintenance. */
        internal const val SUGGESTION_TTL_MS = 7L * 24 * 60 * 60 * 1_000

        private fun formatCallMinutes(durationSeconds: Long): String {
            val minutes = (durationSeconds + 30) / 60
            return if (minutes <= 1) "1 分钟" else "$minutes 分钟"
        }
    }
}
