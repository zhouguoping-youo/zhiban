package com.zhiban.rebuild.data.crm

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CrmDao {
    @Query("SELECT * FROM crm_leads ORDER BY leadId LIMIT :limit OFFSET :offset")
    suspend fun listLeadPageForExport(limit: Int, offset: Int): List<CrmLeadEntity>

    @Query("SELECT * FROM crm_opportunities ORDER BY opportunityId LIMIT :limit OFFSET :offset")
    suspend fun listOpportunityPageForExport(limit: Int, offset: Int): List<CrmOpportunityEntity>

    @Query("SELECT * FROM crm_activities ORDER BY activityId LIMIT :limit OFFSET :offset")
    suspend fun listActivityPageForExport(limit: Int, offset: Int): List<CrmActivityEntity>

    @Query("SELECT * FROM crm_next_actions ORDER BY actionId LIMIT :limit OFFSET :offset")
    suspend fun listNextActionPageForExport(limit: Int, offset: Int): List<CrmNextActionEntity>

    @Query("SELECT * FROM crm_leads WHERE status != 'CANDIDATE' ORDER BY updatedAtEpochMs DESC")
    fun observeLeads(): Flow<List<CrmLeadEntity>>

    @Query("SELECT * FROM crm_leads WHERE status = 'CANDIDATE' ORDER BY confidence DESC, updatedAtEpochMs DESC")
    fun observeCandidateLeads(): Flow<List<CrmLeadEntity>>

    @Query("SELECT * FROM crm_opportunities ORDER BY CASE status WHEN 'OPEN' THEN 0 ELSE 1 END, updatedAtEpochMs DESC")
    fun observeOpportunities(): Flow<List<CrmOpportunityEntity>>

    @Query("SELECT * FROM crm_opportunities WHERE opportunityId = :opportunityId")
    fun observeOpportunity(opportunityId: String): Flow<CrmOpportunityEntity?>

    @Query("SELECT * FROM crm_opportunities WHERE opportunityId = :opportunityId")
    suspend fun findOpportunity(opportunityId: String): CrmOpportunityEntity?

    @Query("SELECT * FROM crm_leads WHERE leadId = :leadId")
    suspend fun findLead(leadId: String): CrmLeadEntity?

    @Query("SELECT * FROM crm_opportunities WHERE sourceLeadId = :leadId ORDER BY createdAtEpochMs LIMIT 1")
    suspend fun findOpportunityBySourceLead(leadId: String): CrmOpportunityEntity?

    @Query(
        """SELECT * FROM crm_leads WHERE COALESCE(
            (SELECT canonicalContactId FROM contact_merge_links
             WHERE sourceContactId = crm_leads.contactId AND undoneAtEpochMs IS NULL),
            contactId
        ) = COALESCE(
            (SELECT canonicalContactId FROM contact_merge_links
             WHERE sourceContactId = :contactId AND undoneAtEpochMs IS NULL),
            :contactId
        ) ORDER BY updatedAtEpochMs DESC""",
    )
    fun observeLeadsByContact(contactId: String): Flow<List<CrmLeadEntity>>

    @Query(
        """SELECT * FROM crm_opportunities WHERE COALESCE(
            (SELECT canonicalContactId FROM contact_merge_links
             WHERE sourceContactId = crm_opportunities.primaryContactId AND undoneAtEpochMs IS NULL),
            primaryContactId
        ) = COALESCE(
            (SELECT canonicalContactId FROM contact_merge_links
             WHERE sourceContactId = :contactId AND undoneAtEpochMs IS NULL),
            :contactId
        ) ORDER BY updatedAtEpochMs DESC""",
    )
    fun observeOpportunitiesByContact(contactId: String): Flow<List<CrmOpportunityEntity>>

    @Query(
        """SELECT * FROM crm_opportunities WHERE COALESCE(
            (SELECT canonicalContactId FROM contact_merge_links
             WHERE sourceContactId = crm_opportunities.primaryContactId AND undoneAtEpochMs IS NULL),
            primaryContactId
        ) = COALESCE(
            (SELECT canonicalContactId FROM contact_merge_links
             WHERE sourceContactId = :contactId AND undoneAtEpochMs IS NULL),
            :contactId
        ) AND status = 'OPEN' ORDER BY updatedAtEpochMs DESC LIMIT 1""",
    )
    suspend fun findOpenOpportunityByContact(contactId: String): CrmOpportunityEntity?

    @Query(
        """SELECT * FROM crm_leads WHERE COALESCE(
            (SELECT canonicalContactId FROM contact_merge_links
             WHERE sourceContactId = crm_leads.contactId AND undoneAtEpochMs IS NULL),
            contactId
        ) = COALESCE(
            (SELECT canonicalContactId FROM contact_merge_links
             WHERE sourceContactId = :contactId AND undoneAtEpochMs IS NULL),
            :contactId
        ) ORDER BY updatedAtEpochMs DESC LIMIT 1""",
    )
    suspend fun findLeadByContact(contactId: String): CrmLeadEntity?

    @Query(
        """SELECT * FROM crm_activities WHERE COALESCE(
            (SELECT canonicalContactId FROM contact_merge_links
             WHERE sourceContactId = crm_activities.contactId AND undoneAtEpochMs IS NULL),
            contactId
        ) = COALESCE(
            (SELECT canonicalContactId FROM contact_merge_links
             WHERE sourceContactId = :contactId AND undoneAtEpochMs IS NULL),
            :contactId
        ) ORDER BY occurredAtEpochMs DESC""",
    )
    fun observeActivitiesByContact(contactId: String): Flow<List<CrmActivityEntity>>

    @Query(
        """SELECT * FROM crm_next_actions WHERE COALESCE(
            (SELECT canonicalContactId FROM contact_merge_links
             WHERE sourceContactId = crm_next_actions.contactId AND undoneAtEpochMs IS NULL),
            contactId
        ) = COALESCE(
            (SELECT canonicalContactId FROM contact_merge_links
             WHERE sourceContactId = :contactId AND undoneAtEpochMs IS NULL),
            :contactId
        ) AND status = 'PENDING' ORDER BY dueAtEpochMs""",
    )
    fun observePendingActionsByContact(contactId: String): Flow<List<CrmNextActionEntity>>

    @Query(
        "UPDATE crm_leads SET status = 'DISQUALIFIED', updatedAtEpochMs = :nowEpochMs " +
            "WHERE leadId = :leadId AND status IN ('NEW', 'CONTACTED', 'QUALIFIED')",
    )
    suspend fun disqualifyLead(leadId: String, nowEpochMs: Long): Int

    @Query(
        "UPDATE crm_leads SET status = 'CONVERTED', userConfirmed = 1, updatedAtEpochMs = :nowEpochMs " +
            "WHERE leadId = :leadId AND status IN ('NEW', 'CONTACTED', 'QUALIFIED')",
    )
    suspend fun markLeadConverted(leadId: String, nowEpochMs: Long): Int

    @Query("SELECT * FROM crm_next_actions WHERE actionId = :actionId")
    suspend fun findAction(actionId: String): CrmNextActionEntity?

    @Query("SELECT * FROM crm_activities WHERE activityId = :activityId")
    suspend fun findActivity(activityId: String): CrmActivityEntity?

    @Query("UPDATE crm_leads SET contactId = NULL, updatedAtEpochMs = :nowEpochMs WHERE contactId IN (:contactIds)")
    suspend fun detachLeadContacts(contactIds: List<String>, nowEpochMs: Long): Int

    @Query("UPDATE crm_opportunities SET primaryContactId = NULL, updatedAtEpochMs = :nowEpochMs WHERE primaryContactId IN (:contactIds)")
    suspend fun detachOpportunityContacts(contactIds: List<String>, nowEpochMs: Long): Int

    @Query("DELETE FROM crm_opportunity_stakeholders WHERE contactId IN (:contactIds)")
    suspend fun deleteStakeholdersForContacts(contactIds: List<String>): Int

    @Query("UPDATE crm_activities SET contactId = NULL WHERE contactId IN (:contactIds)")
    suspend fun detachActivityContacts(contactIds: List<String>): Int

    @Query("UPDATE crm_next_actions SET contactId = NULL, updatedAtEpochMs = :nowEpochMs WHERE contactId IN (:contactIds)")
    suspend fun detachActionContacts(contactIds: List<String>, nowEpochMs: Long): Int

    @Query("UPDATE crm_agent_suggestions SET contactId = NULL, updatedAtEpochMs = :nowEpochMs WHERE contactId IN (:contactIds)")
    suspend fun detachSuggestionContacts(contactIds: List<String>, nowEpochMs: Long): Int

    @Query("SELECT COUNT(*) FROM crm_opportunities")
    suspend fun countOpportunities(): Int

    @Query("SELECT COUNT(*) FROM crm_leads WHERE status IN ('NEW', 'CONTACTED', 'QUALIFIED')")
    suspend fun countForecastEligibleLeads(): Int

    // ---- 仪表盘聚合（纯 SQL，不涉及 AI）----

    @Query(
        "SELECT " +
            "(SELECT COUNT(*) FROM crm_leads WHERE createdAtEpochMs >= :sinceEpochMs AND status != 'CANDIDATE') AS newLeadCount, " +
            "(SELECT COUNT(*) FROM crm_activities WHERE occurredAtEpochMs >= :sinceEpochMs) AS activityCount",
    )
    fun observeDashboardActivityCounts(sinceEpochMs: Long): Flow<CrmDashboardCounts>

    @Query(
        "SELECT * FROM crm_opportunity_stakeholders WHERE opportunityId = :opportunityId ORDER BY roleType, influenceLevel",
    )
    fun observeStakeholders(opportunityId: String): Flow<List<CrmOpportunityStakeholderEntity>>

    @Query("SELECT * FROM crm_activities WHERE opportunityId = :opportunityId ORDER BY occurredAtEpochMs DESC")
    fun observeActivities(opportunityId: String): Flow<List<CrmActivityEntity>>

    @Query(
        "SELECT * FROM crm_next_actions WHERE status = 'PENDING' ORDER BY CASE WHEN dueAtEpochMs IS NULL THEN 1 ELSE 0 END, dueAtEpochMs, priority DESC",
    )
    fun observePendingActions(): Flow<List<CrmNextActionEntity>>

    @Query(
        "SELECT * FROM crm_next_actions WHERE opportunityId = :opportunityId ORDER BY CASE status WHEN 'PENDING' THEN 0 ELSE 1 END, dueAtEpochMs DESC",
    )
    fun observeActions(opportunityId: String): Flow<List<CrmNextActionEntity>>

    @Query(
        "SELECT * FROM crm_agent_suggestions WHERE status = 'PENDING' AND confidence >= :minConfidence ORDER BY confidence DESC, createdAtEpochMs DESC",
    )
    fun observePendingSuggestions(minConfidence: Double): Flow<List<CrmAgentSuggestionEntity>>

    @Query(
        "SELECT * FROM crm_agent_suggestions WHERE opportunityId = :opportunityId " +
            "ORDER BY CASE status WHEN 'PENDING' THEN 0 WHEN 'ACCEPTED' THEN 1 WHEN 'DISMISSED' THEN 2 ELSE 3 END, createdAtEpochMs DESC",
    )
    fun observeSuggestions(opportunityId: String): Flow<List<CrmAgentSuggestionEntity>>

    @Query("SELECT * FROM crm_stage_history WHERE opportunityId = :opportunityId ORDER BY changedAtEpochMs DESC")
    fun observeStageHistory(opportunityId: String): Flow<List<CrmStageHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLead(value: CrmLeadEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLeads(values: List<CrmLeadEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOpportunity(value: CrmOpportunityEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertActivity(value: CrmActivityEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAction(value: CrmNextActionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertStageHistory(value: CrmStageHistoryEntity)

    @Update
    suspend fun updateOpportunity(value: CrmOpportunityEntity): Int

    @Update
    suspend fun updateAction(value: CrmNextActionEntity): Int

    @Query(
        "UPDATE crm_leads SET status = 'QUALIFIED', userConfirmed = 1, sourceType = 'USER_CONFIRMED', updatedAtEpochMs = :nowEpochMs WHERE leadId = :leadId AND status = 'CANDIDATE'",
    )
    suspend fun promoteCandidateLead(leadId: String, nowEpochMs: Long): Int

    @Query("DELETE FROM crm_leads WHERE leadId = :leadId AND status = 'CANDIDATE' AND sourceType = 'AGENT_AUTO'")
    suspend fun deleteAutoCandidateLead(leadId: String): Int

    @Query("DELETE FROM crm_activities WHERE activityId = :activityId AND sourceType = 'AGENT_AUTO'")
    suspend fun deleteAutoActivity(activityId: String): Int

    @Query("DELETE FROM crm_next_actions WHERE actionId = :actionId AND sourceType = 'AGENT_AUTO'")
    suspend fun deleteAutoAction(actionId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOpportunity(value: CrmOpportunityEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStakeholders(values: List<CrmOpportunityStakeholderEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertActivities(values: List<CrmActivityEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertActions(values: List<CrmNextActionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSuggestions(values: List<CrmAgentSuggestionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStageHistory(values: List<CrmStageHistoryEntity>)

    @Query("UPDATE crm_next_actions SET status = :status, updatedAtEpochMs = :nowEpochMs WHERE actionId = :actionId")
    suspend fun updateActionStatus(actionId: String, status: String, nowEpochMs: Long): Int

    @Query(
        "UPDATE crm_agent_suggestions SET status = :status, updatedAtEpochMs = :nowEpochMs " +
            "WHERE suggestionId = :suggestionId AND status = :expectedStatus",
    )
    suspend fun transitionSuggestionStatus(suggestionId: String, expectedStatus: String, status: String, nowEpochMs: Long): Int

    @Query("SELECT * FROM crm_agent_suggestions WHERE suggestionId = :suggestionId")
    suspend fun findSuggestion(suggestionId: String): CrmAgentSuggestionEntity?

    @Query(
        "SELECT COUNT(*) FROM crm_agent_suggestions WHERE opportunityId = :opportunityId AND suggestionType = :suggestionType AND status = 'PENDING'",
    )
    suspend fun countPendingSuggestionsOfType(opportunityId: String, suggestionType: String): Int

    suspend fun hasPendingSuggestionOfType(opportunityId: String, suggestionType: String): Boolean =
        countPendingSuggestionsOfType(opportunityId, suggestionType) > 0

    @Query(
        "SELECT COUNT(*) FROM crm_agent_suggestions WHERE suggestionType = :suggestionType AND evidenceRefsJson = :evidenceRefsJson",
    )
    suspend fun countSuggestionsByEvidence(suggestionType: String, evidenceRefsJson: String): Int

    @Query(
        "SELECT COUNT(*) FROM crm_agent_suggestions WHERE contactId = :contactId AND suggestionType = :suggestionType AND status = 'PENDING'",
    )
    suspend fun countPendingSuggestionsOfTypeForContact(contactId: String, suggestionType: String): Int

    suspend fun hasPendingSuggestionOfTypeForContact(contactId: String, suggestionType: String): Boolean =
        countPendingSuggestionsOfTypeForContact(contactId, suggestionType) > 0

    @Query(
        "UPDATE crm_agent_suggestions SET status = 'EXPIRED', updatedAtEpochMs = :nowEpochMs " +
            "WHERE status = 'PENDING' AND createdAtEpochMs < :expireBeforeEpochMs",
    )
    suspend fun expirePendingSuggestionsBefore(expireBeforeEpochMs: Long, nowEpochMs: Long): Int

    @Query("DELETE FROM crm_activities WHERE activityId = :activityId")
    suspend fun deleteActivityById(activityId: String): Int

    @Query("DELETE FROM crm_leads WHERE leadId = :leadId")
    suspend fun deleteLeadById(leadId: String): Int

    @Query(
        "UPDATE crm_opportunities SET stage = :stage, status = :recordStatus, probabilityPercent = :probabilityPercent, updatedAtEpochMs = :nowEpochMs WHERE opportunityId = :opportunityId",
    )
    suspend fun updateOpportunityStage(opportunityId: String, stage: String, recordStatus: String, probabilityPercent: Int, nowEpochMs: Long): Int

    @Query("SELECT COUNT(*) FROM crm_leads WHERE sourceType = 'DEMO'")
    suspend fun countDemoLeads(): Int

    @Query("SELECT COUNT(*) FROM crm_opportunities WHERE sourceType = 'DEMO'")
    suspend fun countDemoOpportunities(): Int

    @Query("SELECT COUNT(*) FROM crm_activities WHERE sourceType = 'DEMO'")
    suspend fun countDemoActivities(): Int

    @Query("SELECT COUNT(*) FROM crm_next_actions WHERE sourceType = 'DEMO'")
    suspend fun countDemoActions(): Int

    @Query("SELECT COUNT(*) FROM crm_stage_history WHERE sourceType = 'DEMO'")
    suspend fun countDemoStageHistory(): Int

    @Query(
        "SELECT COUNT(*) FROM crm_agent_suggestions WHERE opportunityId IN (SELECT opportunityId FROM crm_opportunities WHERE sourceType = 'DEMO')",
    )
    suspend fun countDemoSuggestions(): Int

    @Query(
        "SELECT COUNT(*) FROM crm_opportunity_stakeholders WHERE opportunityId IN (SELECT opportunityId FROM crm_opportunities WHERE sourceType = 'DEMO')",
    )
    suspend fun countDemoStakeholders(): Int

    @Query("DELETE FROM crm_opportunities WHERE sourceType = 'DEMO'")
    suspend fun deleteDemoOpportunities(): Int

    @Query("DELETE FROM crm_leads WHERE sourceType = 'DEMO'")
    suspend fun deleteDemoLeads(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDemoCleanupAudit(value: CrmDemoCleanupAuditEntity): Long

    @Query("SELECT * FROM crm_demo_cleanup_audits WHERE cleanupKey = :cleanupKey")
    suspend fun findDemoCleanupAudit(cleanupKey: String): CrmDemoCleanupAuditEntity?
}

/** Scalar counters for the CRM dashboard "近 7 天" window; mapped from a two-column aggregate query. */
data class CrmDashboardCounts(val newLeadCount: Int, val activityCount: Int)
