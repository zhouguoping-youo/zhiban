package com.zhiban.rebuild.data.agent

import androidx.room.Database
import androidx.room.RoomDatabase
import com.zhiban.rebuild.data.autowrite.AutoWriteReceiptEntity
import com.zhiban.rebuild.data.autowrite.ChangeLogDao
import com.zhiban.rebuild.data.autowrite.ChangeLogEntity
import com.zhiban.rebuild.data.calllog.CallLogDao
import com.zhiban.rebuild.data.calllog.CallNoteEntity
import com.zhiban.rebuild.data.calllog.CallRecordEntity
import com.zhiban.rebuild.data.completion.ContactCompletionRequestDao
import com.zhiban.rebuild.data.completion.ContactCompletionRequestEntity
import com.zhiban.rebuild.data.contact.AndroidRawContactLinkEntity
import com.zhiban.rebuild.data.contact.ContactAddressEntity
import com.zhiban.rebuild.data.contact.ContactAliasEntity
import com.zhiban.rebuild.data.contact.ContactDao
import com.zhiban.rebuild.data.contact.ContactEmploymentEntity
import com.zhiban.rebuild.data.contact.ContactEnrichmentCandidateEntity
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.ContactFacetEntity
import com.zhiban.rebuild.data.contact.ContactIdentityDao
import com.zhiban.rebuild.data.contact.ContactImportantDateEntity
import com.zhiban.rebuild.data.contact.ContactIntelligenceDao
import com.zhiban.rebuild.data.contact.ContactKnowledgeDao
import com.zhiban.rebuild.data.contact.ContactMergeLinkEntity
import com.zhiban.rebuild.data.contact.ContactMethodEntity
import com.zhiban.rebuild.data.contact.ContactPlatformIdentityEntity
import com.zhiban.rebuild.data.contact.ContactRoleEntity
import com.zhiban.rebuild.data.contact.ContactSyncOperationEntity
import com.zhiban.rebuild.data.contact.ContactSyncSnapshotEntity
import com.zhiban.rebuild.data.contact.GroupConversationEntity
import com.zhiban.rebuild.data.contact.GroupMembershipEpisodeEntity
import com.zhiban.rebuild.data.contact.IdentityClaimEntity
import com.zhiban.rebuild.data.contact.OrganizationEntity
import com.zhiban.rebuild.data.contact.OwnerContactLinkEntity
import com.zhiban.rebuild.data.contact.PersonEmploymentEpisodeEntity
import com.zhiban.rebuild.data.contact.PersonEntity
import com.zhiban.rebuild.data.contact.RelationshipEdgeDao
import com.zhiban.rebuild.data.contact.RelationshipEdgeEntity
import com.zhiban.rebuild.data.contact.RelationshipEpisodeEntity
import com.zhiban.rebuild.data.contact.RelationshipEventDao
import com.zhiban.rebuild.data.contact.RelationshipEventEntity
import com.zhiban.rebuild.data.contact.RelationshipEventParticipantEntity
import com.zhiban.rebuild.data.contact.SourceIdentityEntity
import com.zhiban.rebuild.data.contact.StagedContactCandidateDao
import com.zhiban.rebuild.data.contact.StagedContactCandidateEntity
import com.zhiban.rebuild.data.crm.CrmActivityEntity
import com.zhiban.rebuild.data.crm.CrmAgentSuggestionEntity
import com.zhiban.rebuild.data.crm.CrmDao
import com.zhiban.rebuild.data.crm.CrmDemoCleanupAuditEntity
import com.zhiban.rebuild.data.crm.CrmLeadEntity
import com.zhiban.rebuild.data.crm.CrmNextActionEntity
import com.zhiban.rebuild.data.crm.CrmOpportunityEntity
import com.zhiban.rebuild.data.crm.CrmOpportunityStakeholderEntity
import com.zhiban.rebuild.data.crm.CrmStageHistoryEntity
import com.zhiban.rebuild.data.event.EventPlanEntity
import com.zhiban.rebuild.data.event.EventPlanParticipantEntity
import com.zhiban.rebuild.data.event.EventPlanningDao
import com.zhiban.rebuild.data.facts.EmbeddingVectorDao
import com.zhiban.rebuild.data.facts.EmbeddingVectorEntity
import com.zhiban.rebuild.data.facts.FactDao
import com.zhiban.rebuild.data.facts.FactEntity
import com.zhiban.rebuild.data.memory.*
import com.zhiban.rebuild.data.memory.MemoryCommitReceiptEntity
import com.zhiban.rebuild.data.memory.MemoryCurrentVersionEntity
import com.zhiban.rebuild.data.memory.MemoryDeletionOutboxEntity
import com.zhiban.rebuild.data.memory.MemoryEventEntity
import com.zhiban.rebuild.data.memory.MemoryEvidenceEntity
import com.zhiban.rebuild.data.memory.MemoryFtsEntity
import com.zhiban.rebuild.data.memory.MemoryIndexOutboxEntity
import com.zhiban.rebuild.data.memory.MemoryNamespaceEntity
import com.zhiban.rebuild.data.memory.MemoryPersistenceDao
import com.zhiban.rebuild.data.memory.MemoryRecordEntity
import com.zhiban.rebuild.data.memory.MemoryRelationEntity
import com.zhiban.rebuild.data.memory.MemoryTombstoneEntity
import com.zhiban.rebuild.data.memory.StagedMemoryCandidateDao
import com.zhiban.rebuild.data.memory.StagedMemoryCandidateEntity
import com.zhiban.rebuild.data.notification.NotificationCandidateDao
import com.zhiban.rebuild.data.notification.NotificationCandidateEntity
import com.zhiban.rebuild.data.store.*
import com.zhiban.rebuild.data.store.RuntimeApprovalStagingDao
import com.zhiban.rebuild.data.store.RuntimeApprovalStagingEntity
import com.zhiban.rebuild.data.store.RuntimeArtifactDao
import com.zhiban.rebuild.data.store.RuntimeArtifactEntity
import com.zhiban.rebuild.data.store.RuntimeAttemptDao
import com.zhiban.rebuild.data.store.RuntimeAttemptEntity
import com.zhiban.rebuild.data.store.RuntimeCommandInboxDao
import com.zhiban.rebuild.data.store.RuntimeCommandInboxEntity
import com.zhiban.rebuild.data.store.RuntimeConversationTurnDao
import com.zhiban.rebuild.data.store.RuntimeConversationTurnEntity
import com.zhiban.rebuild.data.store.RuntimeEventDao
import com.zhiban.rebuild.data.store.RuntimeEventEntity
import com.zhiban.rebuild.data.store.RuntimeInputStagingDao
import com.zhiban.rebuild.data.store.RuntimeInputStagingEntity
import com.zhiban.rebuild.data.store.RuntimeProjectionDao
import com.zhiban.rebuild.data.store.RuntimeProjectionEntity
import com.zhiban.rebuild.data.store.RuntimeRunDao
import com.zhiban.rebuild.data.store.RuntimeRunEntity
import com.zhiban.rebuild.data.store.RuntimeRunInputDao
import com.zhiban.rebuild.data.store.RuntimeRunInputEntity
import com.zhiban.rebuild.data.store.RuntimeSessionDao
import com.zhiban.rebuild.data.store.RuntimeSessionEntity
import com.zhiban.rebuild.data.store.RuntimeSessionWorkspaceDao
import com.zhiban.rebuild.data.store.RuntimeSessionWorkspaceEntity
import com.zhiban.rebuild.data.store.RuntimeToolExecutionDao
import com.zhiban.rebuild.data.store.RuntimeToolExecutionEntity

/** 知伴本机数据库文件名，供数据层以外的只读场景（如存储页显示大小）使用，避免引用 internal 的 AgentDatabase。 */
const val AGENT_DATABASE_FILE_NAME = "zhiban-agent.db"
const val AGENT_DATABASE_SCHEMA_VERSION = 42

@Database(
    entities = [
        ScheduleEntity::class, AgentRunEntity::class, MemoryEntity::class, ToolAuditEntity::class,
        RuntimeSessionEntity::class, RuntimeRunEntity::class, RuntimeAttemptEntity::class,
        RuntimeCommandInboxEntity::class, RuntimeEventEntity::class, RuntimeConversationTurnEntity::class,
        RuntimeToolExecutionEntity::class, RuntimeProjectionEntity::class, RuntimeInputStagingEntity::class,
        RuntimeApprovalStagingEntity::class,
        RuntimeRunInputEntity::class, RuntimeSessionWorkspaceEntity::class, RuntimeArtifactEntity::class,
        StagedMemoryCandidateEntity::class,
        MemoryNamespaceEntity::class, MemoryRecordEntity::class, MemoryCurrentVersionEntity::class,
        MemoryEvidenceEntity::class, MemoryRelationEntity::class, MemoryIndexOutboxEntity::class,
        MemoryCommitReceiptEntity::class, MemoryEventEntity::class, MemoryTombstoneEntity::class,
        MemoryDeletionOutboxEntity::class, MemoryFtsEntity::class,
        PlanVersionEntity::class, PlanDefinitionEntity::class, PlanNodeEntity::class,
        PlanEdgeEntity::class, PlanRunEntity::class, NodeAttemptEntity::class,
        ApprovalGrantEntity::class, DispatchOutboxEntity::class, ResultLedgerEntity::class,
        ResourceLeaseEntity::class, SessionLeaseEntity::class,
        ContactEntity::class, ContactRoleEntity::class,
        ContactAliasEntity::class, ContactPlatformIdentityEntity::class, ContactMergeLinkEntity::class,
        PersonEntity::class, SourceIdentityEntity::class, IdentityClaimEntity::class,
        PersonEmploymentEpisodeEntity::class, RelationshipEpisodeEntity::class,
        GroupConversationEntity::class, GroupMembershipEpisodeEntity::class,
        AndroidRawContactLinkEntity::class, ContactSyncSnapshotEntity::class, ContactSyncOperationEntity::class,
        ChangeLogEntity::class, AutoWriteReceiptEntity::class,
        StagedContactCandidateEntity::class,
        FactEntity::class,
        EmbeddingVectorEntity::class,
        RelationshipEdgeEntity::class,
        RelationshipEventEntity::class,
        RelationshipEventParticipantEntity::class,
        ContactMethodEntity::class,
        OrganizationEntity::class,
        ContactEmploymentEntity::class,
        ContactAddressEntity::class,
        ContactImportantDateEntity::class,
        ContactFacetEntity::class,
        ContactEnrichmentCandidateEntity::class,
        com.zhiban.rebuild.data.contact.ContactFtsEntity::class,
        OwnerContactLinkEntity::class,
        NotificationCandidateEntity::class,
        CrmLeadEntity::class,
        CrmOpportunityEntity::class,
        CrmOpportunityStakeholderEntity::class,
        CrmActivityEntity::class,
        CrmNextActionEntity::class,
        CrmAgentSuggestionEntity::class,
        CrmStageHistoryEntity::class,
        CrmDemoCleanupAuditEntity::class,
        CallRecordEntity::class,
        CallNoteEntity::class,
        EventPlanEntity::class,
        EventPlanParticipantEntity::class,
        com.zhiban.rebuild.data.reply.ReplySuggestionEntity::class,
        ContactCompletionRequestEntity::class,
    ],
    version = AGENT_DATABASE_SCHEMA_VERSION,
    exportSchema = true,
)
internal abstract class AgentDatabase : RoomDatabase() {
    abstract fun scheduleDao(): ScheduleDao
    abstract fun agentRunDao(): AgentRunDao
    abstract fun memoryDao(): MemoryDao
    abstract fun toolAuditDao(): ToolAuditDao
    internal abstract fun runtimeSessionDao(): RuntimeSessionDao
    internal abstract fun runtimeRunDao(): RuntimeRunDao
    internal abstract fun runtimeAttemptDao(): RuntimeAttemptDao
    internal abstract fun runtimeCommandInboxDao(): RuntimeCommandInboxDao
    internal abstract fun runtimeEventDao(): RuntimeEventDao
    internal abstract fun runtimeConversationTurnDao(): RuntimeConversationTurnDao
    internal abstract fun runtimeToolExecutionDao(): RuntimeToolExecutionDao
    internal abstract fun runtimeProjectionDao(): RuntimeProjectionDao
    internal abstract fun runtimeInputStagingDao(): RuntimeInputStagingDao
    internal abstract fun runtimeApprovalStagingDao(): RuntimeApprovalStagingDao
    internal abstract fun runtimeRunInputDao(): RuntimeRunInputDao
    internal abstract fun runtimeSessionWorkspaceDao(): RuntimeSessionWorkspaceDao
    internal abstract fun runtimeArtifactDao(): RuntimeArtifactDao
    internal abstract fun stagedMemoryCandidateDao(): StagedMemoryCandidateDao
    internal abstract fun memoryPersistenceDao(): MemoryPersistenceDao
    internal abstract fun planDao(): PlanDao
    abstract fun contactDao(): ContactDao
    abstract fun contactIdentityDao(): ContactIdentityDao
    abstract fun contactIntelligenceDao(): ContactIntelligenceDao
    abstract fun changeLogDao(): ChangeLogDao
    abstract fun stagedContactCandidateDao(): StagedContactCandidateDao
    internal abstract fun factDao(): FactDao
    internal abstract fun embeddingVectorDao(): EmbeddingVectorDao
    internal abstract fun relationshipEdgeDao(): RelationshipEdgeDao
    internal abstract fun relationshipEventDao(): RelationshipEventDao
    internal abstract fun contactKnowledgeDao(): ContactKnowledgeDao
    abstract fun notificationCandidateDao(): NotificationCandidateDao
    abstract fun replySuggestionDao(): com.zhiban.rebuild.data.reply.ReplySuggestionDao
    abstract fun contactCompletionRequestDao(): ContactCompletionRequestDao
    abstract fun crmDao(): CrmDao
    abstract fun callLogDao(): CallLogDao
    abstract fun eventPlanningDao(): EventPlanningDao

    companion object {
        const val NAME = AGENT_DATABASE_FILE_NAME
        val MIGRATION_1_2 = AgentDatabaseSchema.MIGRATION_1_2
        val MIGRATION_2_3 = AgentDatabaseSchema.MIGRATION_2_3
        val MIGRATION_3_4 = AgentDatabaseSchema.MIGRATION_3_4
        val MIGRATION_4_5 = AgentDatabaseSchema.MIGRATION_4_5
        val MIGRATION_5_6 = AgentDatabaseSchema.MIGRATION_5_6
        val MIGRATION_6_7 = AgentDatabaseSchema.MIGRATION_6_7
        val MIGRATION_7_8 = AgentDatabaseSchema.MIGRATION_7_8
        val MIGRATION_8_9 = AgentDatabaseSchema.MIGRATION_8_9
        val MIGRATION_9_10 = AgentDatabaseSchema.MIGRATION_9_10
        val MIGRATION_10_11 = AgentDatabaseSchema.MIGRATION_10_11
        val MIGRATION_11_12 = AgentDatabaseSchema.MIGRATION_11_12
        val MIGRATION_12_13 = AgentDatabaseSchema.MIGRATION_12_13
        val MIGRATION_13_14 = AgentDatabaseSchema.MIGRATION_13_14
        val MIGRATION_14_15 = AgentDatabaseSchema.MIGRATION_14_15
        val MIGRATION_15_16 = AgentDatabaseSchema.MIGRATION_15_16
        val MIGRATION_16_17 = AgentDatabaseSchema.MIGRATION_16_17
        val MIGRATION_17_18 = AgentDatabaseSchema.MIGRATION_17_18
        val MIGRATION_18_19 = AgentDatabaseSchema.MIGRATION_18_19
        val MIGRATION_19_20 = AgentDatabaseSchema.MIGRATION_19_20
        val MIGRATION_20_21 = AgentDatabaseSchema.MIGRATION_20_21
        val MIGRATION_21_22 = AgentDatabaseSchema.MIGRATION_21_22
        val MIGRATION_22_23 = AgentDatabaseSchema.MIGRATION_22_23
        val MIGRATION_23_24 = AgentDatabaseSchema.MIGRATION_23_24
        val MIGRATION_24_25 = AgentDatabaseSchema.MIGRATION_24_25
        val MIGRATION_25_26 = AgentDatabaseSchema.MIGRATION_25_26
        val MIGRATION_26_27 = AgentDatabaseSchema.MIGRATION_26_27
        val MIGRATION_27_28 = AgentDatabaseSchema.MIGRATION_27_28
        val MIGRATION_28_29 = AgentDatabaseSchema.MIGRATION_28_29
        val MIGRATION_29_30 = AgentDatabaseSchema.MIGRATION_29_30
        val MIGRATION_30_31 = AgentDatabaseSchema.MIGRATION_30_31
        val MIGRATION_31_32 = AgentDatabaseSchema.MIGRATION_31_32
        val MIGRATION_32_33 = AgentDatabaseSchema.MIGRATION_32_33
        val MIGRATION_33_34 = AgentDatabaseSchema.MIGRATION_33_34
        val MIGRATION_34_35 = AgentDatabaseSchema.MIGRATION_34_35
        val MIGRATION_35_36 = AgentDatabaseSchema.MIGRATION_35_36
        val MIGRATION_36_37 = AgentDatabaseSchema.MIGRATION_36_37
        val MIGRATION_37_38 = AgentDatabaseSchema.MIGRATION_37_38
        val MIGRATION_38_39 = AgentDatabaseSchema.MIGRATION_38_39
        val MIGRATION_39_40 = AgentDatabaseSchema.MIGRATION_39_40
        val MIGRATION_40_41 = AgentDatabaseSchema.MIGRATION_40_41
        val MIGRATION_41_42 = AgentDatabaseSchema.MIGRATION_41_42
        val CALLBACK = AgentDatabaseSchema.CALLBACK
    }
}
