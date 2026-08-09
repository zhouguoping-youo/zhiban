package com.zhiban.rebuild.data.crm

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.zhiban.rebuild.data.agent.ScheduleEntity
import com.zhiban.rebuild.data.contact.ContactEntity
import kotlinx.serialization.Serializable

object CrmLeadStatus {
    const val CANDIDATE = "CANDIDATE"
    const val NEW = "NEW"
    const val CONTACTED = "CONTACTED"
    const val QUALIFIED = "QUALIFIED"
    const val DISQUALIFIED = "DISQUALIFIED"
    const val CONVERTED = "CONVERTED"

    val convertibleStatuses = setOf(NEW, CONTACTED, QUALIFIED)
}

object CrmOpportunityStage {
    const val LEAD = "LEAD"
    const val CONTACTED = "CONTACTED"
    const val QUALIFIED = "QUALIFIED"
    const val PROPOSAL = "PROPOSAL"
    const val NEGOTIATION = "NEGOTIATION"
    const val WON = "WON"
    const val LOST = "LOST"

    val activeStages = listOf(LEAD, CONTACTED, QUALIFIED, PROPOSAL, NEGOTIATION)
    val allStages = activeStages + WON + LOST
    val terminalStages = setOf(WON, LOST)

    fun requireTransitionAllowed(currentStage: String, newStage: String) {
        require(newStage in allStages) { "不支持的推进阶段" }
        require(currentStage !in terminalStages || currentStage == newStage) {
            "机会已经结束，如需重新开启必须使用单独的重新开启操作"
        }
    }

    fun probabilityPercent(stage: String): Int = when (stage) {
        LEAD -> 10
        CONTACTED -> 25
        QUALIFIED -> 45
        PROPOSAL -> 65
        NEGOTIATION -> 80
        WON -> 100
        LOST -> 0
        else -> throw IllegalArgumentException("不支持的机会阶段")
    }
}

object CrmRecordStatus {
    const val OPEN = "OPEN"
    const val WON = "WON"
    const val LOST = "LOST"
}

object CrmActionStatus {
    const val PENDING = "PENDING"
    const val COMPLETED = "COMPLETED"
    const val CANCELLED = "CANCELLED"
}

object CrmSuggestionStatus {
    const val PENDING = "PENDING"
    const val ACCEPTED = "ACCEPTED"
    const val DISMISSED = "DISMISSED"
    const val EXPIRED = "EXPIRED"
}

object CrmSuggestionType {
    const val CALL_FOLLOW_UP = "CALL_FOLLOW_UP"
    const val NEW_LEAD = "NEW_LEAD"
}

@Entity(
    tableName = "crm_leads",
    foreignKeys = [
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["contactId"],
            childColumns = ["contactId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("contactId"), Index("status"), Index("updatedAtEpochMs")],
)
@Serializable
data class CrmLeadEntity(
    @PrimaryKey val leadId: String,
    val contactId: String?,
    val displayNameSnapshot: String,
    val companyNameSnapshot: String?,
    val status: String,
    val sourceType: String,
    val sourceRef: String?,
    val fitSummary: String?,
    val confidence: Double,
    val userConfirmed: Boolean,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "crm_opportunities",
    foreignKeys = [
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["contactId"],
            childColumns = ["primaryContactId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = CrmLeadEntity::class,
            parentColumns = ["leadId"],
            childColumns = ["sourceLeadId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(
            "primaryContactId",
        ), Index("sourceLeadId"), Index("stage"), Index("status"), Index("expectedCloseAtEpochMs"),
    ],
)
@Serializable
data class CrmOpportunityEntity(
    @PrimaryKey val opportunityId: String,
    val title: String,
    val accountNameSnapshot: String,
    val primaryContactId: String?,
    val sourceLeadId: String?,
    val stage: String,
    val status: String,
    val valueMinor: Long?,
    val currencyCode: String,
    val probabilityPercent: Int,
    val expectedCloseAtEpochMs: Long?,
    val productSummary: String?,
    val needSummary: String?,
    val lossReason: String?,
    val sourceType: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "crm_opportunity_stakeholders",
    primaryKeys = ["opportunityId", "contactId", "roleType"],
    foreignKeys = [
        ForeignKey(
            entity = CrmOpportunityEntity::class,
            parentColumns = ["opportunityId"],
            childColumns = ["opportunityId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["contactId"],
            childColumns = ["contactId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("opportunityId"), Index("contactId")],
)
data class CrmOpportunityStakeholderEntity(
    val opportunityId: String,
    val contactId: String,
    val roleType: String,
    val influenceLevel: String,
    val userConfirmed: Boolean,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "crm_activities",
    foreignKeys = [
        ForeignKey(
            entity = CrmOpportunityEntity::class,
            parentColumns = ["opportunityId"],
            childColumns = ["opportunityId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["contactId"],
            childColumns = ["contactId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("opportunityId"), Index("contactId"), Index("occurredAtEpochMs"), Index("sourceType")],
)
@Serializable
data class CrmActivityEntity(
    @PrimaryKey val activityId: String,
    val opportunityId: String,
    val contactId: String?,
    val activityType: String,
    val title: String,
    val summary: String,
    val occurredAtEpochMs: Long,
    val sourceType: String,
    val sourceRef: String?,
    val evidenceSummary: String?,
    val userConfirmed: Boolean,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "crm_next_actions",
    foreignKeys = [
        ForeignKey(
            entity = CrmOpportunityEntity::class,
            parentColumns = ["opportunityId"],
            childColumns = ["opportunityId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["contactId"],
            childColumns = ["contactId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = ScheduleEntity::class,
            parentColumns = ["id"],
            childColumns = ["scheduleId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("opportunityId"), Index("contactId"), Index("scheduleId"), Index("status"), Index("dueAtEpochMs")],
)
@Serializable
data class CrmNextActionEntity(
    @PrimaryKey val actionId: String,
    val opportunityId: String,
    val contactId: String?,
    val actionType: String,
    val title: String,
    val dueAtEpochMs: Long?,
    val status: String,
    val priority: Int,
    val rationale: String?,
    val sourceType: String,
    val scheduleId: String?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "crm_agent_suggestions",
    foreignKeys = [
        ForeignKey(
            entity = CrmOpportunityEntity::class,
            parentColumns = ["opportunityId"],
            childColumns = ["opportunityId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["contactId"],
            childColumns = ["contactId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("opportunityId"), Index("contactId"), Index("status"), Index("createdAtEpochMs")],
)
data class CrmAgentSuggestionEntity(
    @PrimaryKey val suggestionId: String,
    /** Null for contact-scoped suggestions (e.g. NEW_LEAD) that have no opportunity yet. */
    val opportunityId: String?,
    /** The subject contact; set for both CALL_FOLLOW_UP and NEW_LEAD. Null only for legacy rows. */
    val contactId: String?,
    val suggestionType: String,
    val title: String,
    val summary: String,
    val rationale: String,
    val evidenceRefsJson: String,
    val confidence: Double,
    val proposedActionJson: String?,
    val status: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "crm_stage_history",
    foreignKeys = [
        ForeignKey(
            entity = CrmOpportunityEntity::class,
            parentColumns = ["opportunityId"],
            childColumns = ["opportunityId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("opportunityId"), Index("changedAtEpochMs")],
)
data class CrmStageHistoryEntity(
    @PrimaryKey val historyId: String,
    val opportunityId: String,
    val fromStage: String?,
    val toStage: String,
    val reason: String?,
    val sourceType: String,
    val userConfirmed: Boolean,
    val changedAtEpochMs: Long,
)

/** Audit receipt for a marker-scoped legacy demo cleanup. Counts never contain contact data. */
@Entity(tableName = "crm_demo_cleanup_audits", indices = [Index(value = ["cleanupKey"], unique = true)])
data class CrmDemoCleanupAuditEntity(
    @PrimaryKey val auditId: String,
    val cleanupKey: String,
    val triggerType: String,
    val plannedCountsJson: String,
    val deletedCountsJson: String,
    val status: String,
    val startedAtEpochMs: Long,
    val completedAtEpochMs: Long,
)
