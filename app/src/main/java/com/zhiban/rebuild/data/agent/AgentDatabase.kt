package com.zhiban.rebuild.data.agent

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.zhiban.rebuild.data.calllog.CallLogDao
import com.zhiban.rebuild.data.calllog.CallNoteEntity
import com.zhiban.rebuild.data.calllog.CallRecordEntity
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
import com.zhiban.rebuild.data.contact.normalizeContactPhone
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
import com.zhiban.rebuild.data.notification.NotificationCandidateDao
import com.zhiban.rebuild.data.notification.NotificationCandidateEntity
import com.zhiban.rebuild.runtime.context.EmbeddingVectorDao
import com.zhiban.rebuild.runtime.context.EmbeddingVectorEntity
import com.zhiban.rebuild.runtime.context.FactDao
import com.zhiban.rebuild.runtime.context.FactEntity
import com.zhiban.rebuild.runtime.context.StagedMemoryCandidateDao
import com.zhiban.rebuild.runtime.context.StagedMemoryCandidateEntity
import com.zhiban.rebuild.runtime.governance.AutoWriteReceiptEntity
import com.zhiban.rebuild.runtime.governance.ChangeLogDao
import com.zhiban.rebuild.runtime.governance.ChangeLogEntity
import com.zhiban.rebuild.runtime.memory.*
import com.zhiban.rebuild.runtime.store.*

/** 知伴本机数据库文件名，供数据层以外的只读场景（如存储页显示大小）使用，避免引用 internal 的 AgentDatabase。 */
const val AGENT_DATABASE_FILE_NAME = "zhiban-agent.db"

@Database(
    entities = [
        ScheduleEntity::class, AgentRunEntity::class, MemoryEntity::class, ToolAuditEntity::class,
        RuntimeSessionEntity::class, RuntimeRunEntity::class, RuntimeAttemptEntity::class,
        RuntimeCommandInboxEntity::class, RuntimeEventEntity::class, RuntimeConversationTurnEntity::class,
        RuntimeToolExecutionEntity::class, RuntimeProjectionEntity::class, RuntimeInputStagingEntity::class,
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
        AndroidRawContactLinkEntity::class, ContactSyncSnapshotEntity::class,
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
    ],
    version = 36,
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
    abstract fun crmDao(): CrmDao
    abstract fun callLogDao(): CallLogDao
    abstract fun eventPlanningDao(): EventPlanningDao

    companion object {
        const val NAME = AGENT_DATABASE_FILE_NAME
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `runtime_sessions` (`sessionId` TEXT NOT NULL, `nextSequence` INTEGER NOT NULL, `leaseOwnerId` TEXT, `leaseEpoch` INTEGER NOT NULL, `leaseExpiresAtEpochMs` INTEGER, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`sessionId`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `runtime_runs` (`runId` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `schemaVersion` INTEGER NOT NULL, `status` TEXT NOT NULL, `activeAttemptId` TEXT, `budgetJson` TEXT NOT NULL, `recoveryCursor` INTEGER NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`runId`), FOREIGN KEY(`sessionId`) REFERENCES `runtime_sessions`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_runtime_runs_sessionId` ON `runtime_runs` (`sessionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_runtime_runs_status` ON `runtime_runs` (`status`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `runtime_attempts` (`attemptId` TEXT NOT NULL, `runId` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, `status` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`attemptId`), FOREIGN KEY(`runId`) REFERENCES `runtime_runs`(`runId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_runtime_attempts_runId` ON `runtime_attempts` (`runId`)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_runtime_attempts_runId_ordinal` ON `runtime_attempts` (`runId`, `ordinal`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `runtime_command_inbox` (`commandId` TEXT NOT NULL, `schemaVersion` INTEGER NOT NULL, `commandType` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `runId` TEXT, `correlationId` TEXT NOT NULL, `payloadJson` TEXT NOT NULL, `status` TEXT NOT NULL, `receiptJson` TEXT, `resultJson` TEXT, `claimedBy` TEXT, `claimedLeaseEpoch` INTEGER, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`commandId`), FOREIGN KEY(`sessionId`) REFERENCES `runtime_sessions`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`runId`) REFERENCES `runtime_runs`(`runId`) ON UPDATE NO ACTION ON DELETE SET NULL )",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_runtime_command_inbox_sessionId` ON `runtime_command_inbox` (`sessionId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_runtime_command_inbox_runId` ON `runtime_command_inbox` (`runId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_runtime_command_inbox_status` ON `runtime_command_inbox` (`status`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `runtime_events` (`eventId` TEXT NOT NULL, `schemaVersion` INTEGER NOT NULL, `eventType` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `runId` TEXT NOT NULL, `attemptId` TEXT, `sequence` INTEGER NOT NULL, `causationId` TEXT, `correlationId` TEXT NOT NULL, `producerVersion` TEXT NOT NULL, `payloadJson` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `fencingEpoch` INTEGER NOT NULL, PRIMARY KEY(`eventId`), FOREIGN KEY(`sessionId`) REFERENCES `runtime_sessions`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`runId`) REFERENCES `runtime_runs`(`runId`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`attemptId`) REFERENCES `runtime_attempts`(`attemptId`) ON UPDATE NO ACTION ON DELETE SET NULL )",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_runtime_events_sessionId_sequence` ON `runtime_events` (`sessionId`, `sequence`)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_runtime_events_runId` ON `runtime_events` (`runId`)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_runtime_events_attemptId` ON `runtime_events` (`attemptId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_runtime_events_correlationId` ON `runtime_events` (`correlationId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_runtime_events_causationId` ON `runtime_events` (`causationId`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `runtime_tool_executions` (`executionId` TEXT NOT NULL, `runId` TEXT NOT NULL, `logicalStepId` TEXT NOT NULL, `toolName` TEXT NOT NULL, `toolSpecVersion` INTEGER NOT NULL, `canonicalInputDigest` TEXT NOT NULL, `idempotencyKey` TEXT NOT NULL, `status` TEXT NOT NULL, `resultRef` TEXT, `safeResultJson` TEXT, `fencingEpoch` INTEGER NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`executionId`), FOREIGN KEY(`runId`) REFERENCES `runtime_runs`(`runId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_runtime_tool_executions_runId` ON `runtime_tool_executions` (`runId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_runtime_tool_executions_logicalStepId` ON `runtime_tool_executions` (`logicalStepId`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_runtime_tool_executions_idempotencyKey` ON `runtime_tool_executions` (`idempotencyKey`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `runtime_projections` (`projectionName` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `consumedSequence` INTEGER NOT NULL, `snapshotJson` TEXT NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`projectionName`, `sessionId`), FOREIGN KEY(`sessionId`) REFERENCES `runtime_sessions`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_runtime_projections_sessionId` ON `runtime_projections` (`sessionId`)",
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `runtime_input_staging` (`inputRef` TEXT NOT NULL, `rawText` TEXT NOT NULL, `utf8Length` INTEGER NOT NULL, `sha256Digest` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `expiresAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`inputRef`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_runtime_input_staging_expiresAtEpochMs` ON `runtime_input_staging` (`expiresAtEpochMs`)",
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `runtime_run_inputs` (`inputRef` TEXT NOT NULL, `runId` TEXT NOT NULL, `rawText` TEXT NOT NULL, `utf8Length` INTEGER NOT NULL, `sha256Digest` TEXT NOT NULL, `expiresAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`inputRef`), FOREIGN KEY(`runId`) REFERENCES `runtime_runs`(`runId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_runtime_run_inputs_runId` ON `runtime_run_inputs` (`runId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_runtime_run_inputs_expiresAtEpochMs` ON `runtime_run_inputs` (`expiresAtEpochMs`)",
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `schedules` ADD COLUMN `createdByRuntimeRunId` TEXT")
                db.execSQL("ALTER TABLE `schedules` ADD COLUMN `createdByRuntimeAttemptId` TEXT")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_schedules_createdByRuntimeRunId` ON `schedules` (`createdByRuntimeRunId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_schedules_createdByRuntimeAttemptId` ON `schedules` (`createdByRuntimeAttemptId`)",
                )
                db.execSQL("ALTER TABLE `tool_audits` ADD COLUMN `runtimeRunId` TEXT")
                db.execSQL("ALTER TABLE `tool_audits` ADD COLUMN `runtimeAttemptId` TEXT")
                db.execSQL("ALTER TABLE `tool_audits` ADD COLUMN `proposalId` TEXT")
                db.execSQL("ALTER TABLE `tool_audits` ADD COLUMN `payloadRefDigest` TEXT")
                db.execSQL("ALTER TABLE `tool_audits` ADD COLUMN `approvalRevision` INTEGER")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_tool_audits_runtimeRunId` ON `tool_audits` (`runtimeRunId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_tool_audits_runtimeAttemptId` ON `tool_audits` (`runtimeAttemptId`)",
                )
                db.execSQL("ALTER TABLE `runtime_tool_executions` ADD COLUMN `providerCallId` TEXT")
                db.execSQL("ALTER TABLE `runtime_tool_executions` ADD COLUMN `proposalId` TEXT")
                db.execSQL("ALTER TABLE `runtime_tool_executions` ADD COLUMN `payloadRefDigest` TEXT")
                db.execSQL("ALTER TABLE `runtime_tool_executions` ADD COLUMN `approvalRevision` INTEGER")
                db.execSQL("ALTER TABLE `runtime_tool_executions` ADD COLUMN `attemptId` TEXT")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_runtime_tool_executions_attemptId` ON `runtime_tool_executions` (`attemptId`)",
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `staged_memory_candidates` (`id` TEXT NOT NULL, `scope` TEXT NOT NULL, `scopeId` TEXT, `content` TEXT, `contentDigest` TEXT NOT NULL, `utf8Length` INTEGER NOT NULL, `sourceIdsJson` TEXT NOT NULL, `sensitivity` TEXT NOT NULL, `state` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `expiresAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_staged_memory_candidates_state` ON `staged_memory_candidates` (`state`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_staged_memory_candidates_expiresAtEpochMs` ON `staged_memory_candidates` (`expiresAtEpochMs`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_staged_memory_candidates_scope_scopeId` ON `staged_memory_candidates` (`scope`, `scopeId`)",
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `staged_memory_candidates` ADD COLUMN `approvalRef` TEXT")
                db.execSQL("ALTER TABLE `staged_memory_candidates` ADD COLUMN `revision` INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `memory_namespaces` (`namespaceId` TEXT NOT NULL, `ownerUserId` TEXT NOT NULL, `profileId` TEXT NOT NULL, `scopeType` TEXT NOT NULL, `scopeId` TEXT NOT NULL, `state` TEXT NOT NULL, `revision` INTEGER NOT NULL, `invalidationGeneration` INTEGER NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`namespaceId`))",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_memory_namespaces_ownerUserId_profileId_scopeType_scopeId` ON `memory_namespaces` (`ownerUserId`,`profileId`,`scopeType`,`scopeId`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `memory_records` (`namespaceId` TEXT NOT NULL, `memoryId` TEXT NOT NULL, `recordVersion` INTEGER NOT NULL, `logicalMemoryId` TEXT NOT NULL, `memoryType` TEXT NOT NULL, `subjectKey` TEXT NOT NULL, `predicateKey` TEXT NOT NULL, `objectText` TEXT NOT NULL, `canonicalText` TEXT NOT NULL, `canonicalDigest` TEXT NOT NULL, `sensitivity` TEXT NOT NULL, `confidence` REAL NOT NULL, `importance` REAL NOT NULL, `status` TEXT NOT NULL, `validFromEpochMs` INTEGER, `validToEpochMs` INTEGER, `observedAtEpochMs` INTEGER NOT NULL, `txFromEpochMs` INTEGER NOT NULL, `txToEpochMs` INTEGER, `createdAtEpochMs` INTEGER NOT NULL, `expiresAtEpochMs` INTEGER, `schemaVersion` INTEGER NOT NULL, `sourceSetDigest` TEXT NOT NULL, PRIMARY KEY(`namespaceId`,`memoryId`,`recordVersion`), FOREIGN KEY(`namespaceId`) REFERENCES `memory_namespaces`(`namespaceId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memory_records_namespaceId` ON `memory_records` (`namespaceId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memory_records_namespaceId_logicalMemoryId` ON `memory_records` (`namespaceId`,`logicalMemoryId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memory_records_namespaceId_subjectKey_predicateKey` ON `memory_records` (`namespaceId`,`subjectKey`,`predicateKey`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memory_records_namespaceId_expiresAtEpochMs` ON `memory_records` (`namespaceId`,`expiresAtEpochMs`)",
                )
                createSingleCurrentMemoryTrigger(db)
                createSingleCurrentMemoryUpdateTrigger(db)
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `memory_current_versions` (`namespaceId` TEXT NOT NULL, `logicalMemoryId` TEXT NOT NULL, `recordVersion` INTEGER NOT NULL, `memoryId` TEXT NOT NULL, `revision` INTEGER NOT NULL, PRIMARY KEY(`namespaceId`,`logicalMemoryId`), FOREIGN KEY(`namespaceId`,`memoryId`,`recordVersion`) REFERENCES `memory_records`(`namespaceId`,`memoryId`,`recordVersion`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_memory_current_versions_namespaceId_memoryId_recordVersion` ON `memory_current_versions` (`namespaceId`,`memoryId`,`recordVersion`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `memory_evidence` (`namespaceId` TEXT NOT NULL, `memoryId` TEXT NOT NULL, `recordVersion` INTEGER NOT NULL, `evidenceId` TEXT NOT NULL, `sourceType` TEXT NOT NULL, `sourceRef` TEXT NOT NULL, `sourceDigest` TEXT NOT NULL, `observedAtEpochMs` INTEGER NOT NULL, `excerptDigest` TEXT NOT NULL, `trust` TEXT NOT NULL, `sensitivity` TEXT NOT NULL, PRIMARY KEY(`namespaceId`,`memoryId`,`recordVersion`,`evidenceId`), FOREIGN KEY(`namespaceId`,`memoryId`,`recordVersion`) REFERENCES `memory_records`(`namespaceId`,`memoryId`,`recordVersion`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memory_evidence_namespaceId_memoryId_recordVersion` ON `memory_evidence` (`namespaceId`,`memoryId`,`recordVersion`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `memory_relations` (`namespaceId` TEXT NOT NULL, `fromMemoryId` TEXT NOT NULL, `fromRecordVersion` INTEGER NOT NULL, `toMemoryId` TEXT NOT NULL, `toRecordVersion` INTEGER NOT NULL, `relationType` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`namespaceId`,`fromMemoryId`,`fromRecordVersion`,`toMemoryId`,`toRecordVersion`,`relationType`), FOREIGN KEY(`namespaceId`,`fromMemoryId`,`fromRecordVersion`) REFERENCES `memory_records`(`namespaceId`,`memoryId`,`recordVersion`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`namespaceId`,`toMemoryId`,`toRecordVersion`) REFERENCES `memory_records`(`namespaceId`,`memoryId`,`recordVersion`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memory_relations_namespaceId_fromMemoryId_fromRecordVersion` ON `memory_relations` (`namespaceId`,`fromMemoryId`,`fromRecordVersion`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memory_relations_namespaceId_toMemoryId_toRecordVersion` ON `memory_relations` (`namespaceId`,`toMemoryId`,`toRecordVersion`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `memory_index_outbox` (`jobId` TEXT NOT NULL, `namespaceId` TEXT NOT NULL, `memoryId` TEXT NOT NULL, `recordVersion` INTEGER NOT NULL, `indexType` TEXT NOT NULL, `contentDigest` TEXT NOT NULL, `status` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`jobId`), FOREIGN KEY(`namespaceId`,`memoryId`,`recordVersion`) REFERENCES `memory_records`(`namespaceId`,`memoryId`,`recordVersion`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memory_index_outbox_namespaceId_memoryId_recordVersion` ON `memory_index_outbox` (`namespaceId`,`memoryId`,`recordVersion`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_memory_index_outbox_namespaceId_memoryId_recordVersion_indexType` ON `memory_index_outbox` (`namespaceId`,`memoryId`,`recordVersion`,`indexType`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memory_index_outbox_status` ON `memory_index_outbox` (`status`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `memory_commit_receipts` (`namespaceId` TEXT NOT NULL, `candidateId` TEXT NOT NULL, `approvalRef` TEXT NOT NULL, `canonicalDigest` TEXT NOT NULL, `memoryId` TEXT NOT NULL, `recordVersion` INTEGER NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`namespaceId`,`candidateId`), FOREIGN KEY(`namespaceId`,`memoryId`,`recordVersion`) REFERENCES `memory_records`(`namespaceId`,`memoryId`,`recordVersion`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memory_commit_receipts_namespaceId_memoryId_recordVersion` ON `memory_commit_receipts` (`namespaceId`,`memoryId`,`recordVersion`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_memory_commit_receipts_approvalRef` ON `memory_commit_receipts` (`approvalRef`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `memory_events` (`eventId` TEXT NOT NULL, `namespaceId` TEXT NOT NULL, `candidateId` TEXT NOT NULL, `memoryId` TEXT NOT NULL, `recordVersion` INTEGER NOT NULL, `eventType` TEXT NOT NULL, `payloadDigest` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`eventId`), FOREIGN KEY(`namespaceId`,`memoryId`,`recordVersion`) REFERENCES `memory_records`(`namespaceId`,`memoryId`,`recordVersion`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memory_events_namespaceId_memoryId_recordVersion` ON `memory_events` (`namespaceId`,`memoryId`,`recordVersion`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_memory_events_namespaceId_candidateId_eventType` ON `memory_events` (`namespaceId`,`candidateId`,`eventType`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `memory_tombstones` (`namespaceId` TEXT NOT NULL, `logicalMemoryId` TEXT NOT NULL, `memoryId` TEXT NOT NULL, `highestRecordVersion` INTEGER NOT NULL, `deleteCommandDigest` TEXT NOT NULL, `deletionRevision` INTEGER NOT NULL, `barrierState` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`namespaceId`,`logicalMemoryId`), FOREIGN KEY(`namespaceId`) REFERENCES `memory_namespaces`(`namespaceId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memory_tombstones_namespaceId` ON `memory_tombstones` (`namespaceId`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `memory_deletion_outbox` (`jobId` TEXT NOT NULL, `namespaceId` TEXT NOT NULL, `logicalMemoryId` TEXT NOT NULL, `deletionRevision` INTEGER NOT NULL, `targetIndex` TEXT NOT NULL, `status` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`jobId`), FOREIGN KEY(`namespaceId`) REFERENCES `memory_namespaces`(`namespaceId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memory_deletion_outbox_namespaceId` ON `memory_deletion_outbox` (`namespaceId`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_memory_deletion_outbox_namespaceId_logicalMemoryId_deletionRevision` ON `memory_deletion_outbox` (`namespaceId`,`logicalMemoryId`,`deletionRevision`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_memory_deletion_outbox_status` ON `memory_deletion_outbox` (`status`)",
                )
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `memory_fts` USING FTS4(`namespaceId` TEXT NOT NULL, `memoryId` TEXT NOT NULL, `recordVersion` INTEGER NOT NULL, `canonicalText` TEXT NOT NULL, tokenize=unicode61)",
                )
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `plan_versions` (`versionId` TEXT NOT NULL, `schemaVersion` INTEGER NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `note` TEXT, PRIMARY KEY(`versionId`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_plan_versions_schemaVersion` ON `plan_versions` (`schemaVersion`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_plan_versions_createdAtEpochMs` ON `plan_versions` (`createdAtEpochMs`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `plan_definitions` (`definitionId` TEXT NOT NULL, `versionId` TEXT NOT NULL, `ownerNamespace` TEXT NOT NULL, `fingerprint` TEXT NOT NULL, `payloadJson` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`definitionId`), FOREIGN KEY(`versionId`) REFERENCES `plan_versions`(`versionId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_plan_definitions_versionId` ON `plan_definitions` (`versionId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_plan_definitions_ownerNamespace` ON `plan_definitions` (`ownerNamespace`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_plan_definitions_fingerprint` ON `plan_definitions` (`fingerprint`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `plan_nodes` (`nodeId` TEXT NOT NULL, `definitionId` TEXT NOT NULL, `nodeKey` TEXT NOT NULL, `nodeType` TEXT NOT NULL, `payloadJson` TEXT NOT NULL, `requiresApproval` INTEGER NOT NULL, `sensitivity` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`nodeId`), FOREIGN KEY(`definitionId`) REFERENCES `plan_definitions`(`definitionId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_plan_nodes_definitionId` ON `plan_nodes` (`definitionId`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_plan_nodes_definitionId_nodeKey` ON `plan_nodes` (`definitionId`, `nodeKey`)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_plan_nodes_nodeType` ON `plan_nodes` (`nodeType`)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_plan_nodes_requiresApproval` ON `plan_nodes` (`requiresApproval`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `plan_edges` (`edgeId` TEXT NOT NULL, `definitionId` TEXT NOT NULL, `fromNodeId` TEXT NOT NULL, `toNodeId` TEXT NOT NULL, `condition` TEXT, `ordinal` INTEGER NOT NULL, PRIMARY KEY(`edgeId`), FOREIGN KEY(`definitionId`) REFERENCES `plan_definitions`(`definitionId`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`fromNodeId`) REFERENCES `plan_nodes`(`nodeId`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`toNodeId`) REFERENCES `plan_nodes`(`nodeId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_plan_edges_definitionId` ON `plan_edges` (`definitionId`)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_plan_edges_fromNodeId` ON `plan_edges` (`fromNodeId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_plan_edges_toNodeId` ON `plan_edges` (`toNodeId`)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_plan_edges_definitionId_fromNodeId_toNodeId` ON `plan_edges` (`definitionId`, `fromNodeId`, `toNodeId`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `plan_runs` (`runId` TEXT NOT NULL, `definitionId` TEXT NOT NULL, `runStatus` TEXT NOT NULL, `activeAttemptId` TEXT, `startedAtEpochMs` INTEGER NOT NULL, `completedAtEpochMs` INTEGER, PRIMARY KEY(`runId`), FOREIGN KEY(`definitionId`) REFERENCES `plan_definitions`(`definitionId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_plan_runs_definitionId` ON `plan_runs` (`definitionId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_plan_runs_runStatus` ON `plan_runs` (`runStatus`)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_plan_runs_activeAttemptId` ON `plan_runs` (`activeAttemptId`)",
                )
                // ADR-006 §3.1: per-(definition) single ACTIVE partial UNIQUE is added
                // by the onCreate + onOpen callback (Room cannot model partial UNIQUE
                // in @Index; MIGRATION_8_9 only creates the table here).
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `node_attempts` (`attemptId` TEXT NOT NULL, `runId` TEXT NOT NULL, `nodeId` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, `status` TEXT NOT NULL, `startedAtEpochMs` INTEGER NOT NULL, `finishedAtEpochMs` INTEGER, `idempotencyKey` TEXT NOT NULL, `errorCode` TEXT, PRIMARY KEY(`attemptId`), FOREIGN KEY(`runId`) REFERENCES `plan_runs`(`runId`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`nodeId`) REFERENCES `plan_nodes`(`nodeId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_node_attempts_runId` ON `node_attempts` (`runId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_node_attempts_nodeId` ON `node_attempts` (`nodeId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_node_attempts_status` ON `node_attempts` (`status`)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_node_attempts_idempotencyKey` ON `node_attempts` (`idempotencyKey`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_node_attempts_runId_nodeId_ordinal` ON `node_attempts` (`runId`, `nodeId`, `ordinal`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `approval_grants` (`grantId` TEXT NOT NULL, `attemptId` TEXT NOT NULL, `decision` TEXT NOT NULL, `decidedByUser` TEXT NOT NULL, `title` TEXT NOT NULL, `impact` TEXT NOT NULL, `actions` TEXT NOT NULL, `verificationComponentKey` TEXT NOT NULL, `decidedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`grantId`), FOREIGN KEY(`attemptId`) REFERENCES `node_attempts`(`attemptId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_approval_grants_attemptId` ON `approval_grants` (`attemptId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_approval_grants_decision` ON `approval_grants` (`decision`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_approval_grants_decidedByUser` ON `approval_grants` (`decidedByUser`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `dispatch_outbox` (`jobId` TEXT NOT NULL, `attemptId` TEXT NOT NULL, `toolName` TEXT NOT NULL, `argumentsDigest` TEXT NOT NULL, `idempotencyKey` TEXT NOT NULL, `status` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`jobId`), FOREIGN KEY(`attemptId`) REFERENCES `node_attempts`(`attemptId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_dispatch_outbox_attemptId` ON `dispatch_outbox` (`attemptId`)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_dispatch_outbox_status` ON `dispatch_outbox` (`status`)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_dispatch_outbox_idempotencyKey` ON `dispatch_outbox` (`idempotencyKey`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_dispatch_outbox_attemptId_ordinal` ON `dispatch_outbox` (`attemptId`, `ordinal`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `result_ledger` (`resultId` TEXT NOT NULL, `attemptId` TEXT NOT NULL, `resultJson` TEXT NOT NULL, `durabilityClass` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, `recordedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`resultId`), FOREIGN KEY(`attemptId`) REFERENCES `node_attempts`(`attemptId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_result_ledger_attemptId` ON `result_ledger` (`attemptId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_result_ledger_durabilityClass` ON `result_ledger` (`durabilityClass`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_result_ledger_attemptId_ordinal` ON `result_ledger` (`attemptId`, `ordinal`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `resource_leases` (`leaseId` TEXT NOT NULL, `attemptId` TEXT NOT NULL, `resourceKey` TEXT NOT NULL, `epoch` INTEGER NOT NULL, `ownerId` TEXT NOT NULL, `expiresAtEpochMs` INTEGER, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`leaseId`), FOREIGN KEY(`attemptId`) REFERENCES `node_attempts`(`attemptId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_resource_leases_attemptId` ON `resource_leases` (`attemptId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_resource_leases_resourceKey` ON `resource_leases` (`resourceKey`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_resource_leases_ownerId` ON `resource_leases` (`ownerId`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_resource_leases_attemptId_resourceKey` ON `resource_leases` (`attemptId`, `resourceKey`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `session_leases` (`leaseId` TEXT NOT NULL, `runId` TEXT NOT NULL, `sessionKey` TEXT NOT NULL, `epoch` INTEGER NOT NULL, `ownerId` TEXT NOT NULL, `expiresAtEpochMs` INTEGER, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`leaseId`), FOREIGN KEY(`runId`) REFERENCES `plan_runs`(`runId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_session_leases_runId` ON `session_leases` (`runId`)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_session_leases_sessionKey` ON `session_leases` (`sessionKey`)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_session_leases_ownerId` ON `session_leases` (`ownerId`)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_session_leases_runId_sessionKey` ON `session_leases` (`runId`, `sessionKey`)",
                )
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `contacts` (`contactId` TEXT NOT NULL, `displayName` TEXT NOT NULL, `normalizedName` TEXT NOT NULL, `phone` TEXT, `email` TEXT, `wechatId` TEXT, `company` TEXT, `title` TEXT, `aliasesJson` TEXT NOT NULL, `tagsJson` TEXT NOT NULL, `note` TEXT, `avatarUri` TEXT, `source` TEXT NOT NULL, `deletedAtEpochMs` INTEGER, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`contactId`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_contacts_normalizedName` ON `contacts` (`normalizedName`)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_contacts_phone` ON `contacts` (`phone`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_contacts_email` ON `contacts` (`email`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_contacts_company` ON `contacts` (`company`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_contacts_source` ON `contacts` (`source`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `contact_roles` (`contactId` TEXT NOT NULL, `skillId` TEXT NOT NULL, `roleType` TEXT NOT NULL, `confidence` REAL NOT NULL, `userConfirmed` INTEGER NOT NULL, `profileJson` TEXT, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`contactId`, `skillId`, `roleType`), FOREIGN KEY(`contactId`) REFERENCES `contacts`(`contactId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_contact_roles_contactId` ON `contact_roles` (`contactId`)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_contact_roles_skillId` ON `contact_roles` (`skillId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_contact_roles_roleType` ON `contact_roles` (`roleType`)")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `change_log` (`changeId` TEXT NOT NULL, `runtimeRunId` TEXT NOT NULL, `toolName` TEXT NOT NULL, `idempotencyKey` TEXT NOT NULL, `targetDomain` TEXT NOT NULL, `targetId` TEXT NOT NULL, `operation` TEXT NOT NULL, `beforeDigest` TEXT, `afterDigest` TEXT, `inversePayloadJson` TEXT NOT NULL, `undoState` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `undoneAtEpochMs` INTEGER, PRIMARY KEY(`changeId`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_change_log_runtimeRunId` ON `change_log` (`runtimeRunId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_change_log_targetDomain_targetId` ON `change_log` (`targetDomain`, `targetId`)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_change_log_undoState` ON `change_log` (`undoState`)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_change_log_idempotencyKey` ON `change_log` (`idempotencyKey`)",
                )
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `staged_contact_candidates` (`candidateId` TEXT NOT NULL, `payloadJson` TEXT NOT NULL, `payloadDigest` TEXT NOT NULL, `state` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `expiresAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`candidateId`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_staged_contact_candidates_state` ON `staged_contact_candidates` (`state`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_staged_contact_candidates_expiresAtEpochMs` ON `staged_contact_candidates` (`expiresAtEpochMs`)",
                )
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Room cannot model this partial UNIQUE index. Remove it before Room's strict
                // schema validation; CALLBACK.onOpen recreates it immediately after validation.
                db.execSQL("DROP INDEX IF EXISTS `index_plan_runs_single_active_per_definition`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `runtime_conversation_turns` (`turnId` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `runId` TEXT NOT NULL, `role` TEXT NOT NULL, `content` TEXT NOT NULL, `contentDigest` TEXT NOT NULL, `tokenEstimate` INTEGER NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`turnId`), FOREIGN KEY(`sessionId`) REFERENCES `runtime_sessions`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`runId`) REFERENCES `runtime_runs`(`runId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_runtime_conversation_turns_sessionId` ON `runtime_conversation_turns` (`sessionId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_runtime_conversation_turns_runId` ON `runtime_conversation_turns` (`runId`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_runtime_conversation_turns_runId_role` ON `runtime_conversation_turns` (`runId`,`role`)",
                )
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                createFactSchema(db)
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `relationship_edges` (`edgeId` TEXT NOT NULL, `fromContactId` TEXT NOT NULL, `toContactId` TEXT NOT NULL, `relationType` TEXT NOT NULL, `evidenceDigest` TEXT NOT NULL, `evidenceRefsJson` TEXT NOT NULL, `confidence` REAL NOT NULL, `userConfirmed` INTEGER NOT NULL, `skillId` TEXT, `status` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`edgeId`))",
                )
                listOf("fromContactId", "toContactId", "skillId", "status").forEach {
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_relationship_edges_$it` ON `relationship_edges` (`$it`)",
                    )
                }
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `embedding_vectors` (`embeddingId` TEXT NOT NULL, `factId` TEXT NOT NULL, `providerId` TEXT NOT NULL, `modelId` TEXT NOT NULL, `dimensions` INTEGER NOT NULL, `vectorBlob` BLOB NOT NULL, `generatedAtEpochMs` INTEGER NOT NULL, `modelVersion` TEXT, PRIMARY KEY(`embeddingId`), FOREIGN KEY(`factId`) REFERENCES `facts`(`factId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_embedding_vectors_factId` ON `embedding_vectors` (`factId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_embedding_vectors_providerId_modelId` ON `embedding_vectors` (`providerId`,`modelId`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_embedding_vectors_factId_providerId_modelId` ON `embedding_vectors` (`factId`,`providerId`,`modelId`)",
                )
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `relationship_events` (`eventId` TEXT NOT NULL, `eventType` TEXT NOT NULL, `title` TEXT NOT NULL, `note` TEXT, `occurredAtEpochMs` INTEGER, `evidenceDigest` TEXT NOT NULL, `evidenceRefsJson` TEXT NOT NULL, `userConfirmed` INTEGER NOT NULL, `status` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`eventId`))",
                )
                listOf("eventType", "status", "occurredAtEpochMs").forEach {
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_relationship_events_$it` ON `relationship_events` (`$it`)",
                    )
                }
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `relationship_event_participants` (`participantId` TEXT NOT NULL, `eventId` TEXT NOT NULL, `participantKind` TEXT NOT NULL, `contactId` TEXT, `participantRole` TEXT NOT NULL, `displayNameSnapshot` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`participantId`), FOREIGN KEY(`eventId`) REFERENCES `relationship_events`(`eventId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                listOf("eventId", "contactId", "participantRole").forEach {
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_relationship_event_participants_$it` ON `relationship_event_participants` (`$it`)",
                    )
                }
            }
        }

        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `contact_aliases` (`aliasId` TEXT NOT NULL, `contactId` TEXT NOT NULL, `alias` TEXT NOT NULL, `normalizedAlias` TEXT NOT NULL, `aliasType` TEXT NOT NULL, `source` TEXT NOT NULL, `userConfirmed` INTEGER NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`aliasId`), FOREIGN KEY(`contactId`) REFERENCES `contacts`(`contactId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_contact_aliases_contactId` ON `contact_aliases` (`contactId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_contact_aliases_normalizedAlias` ON `contact_aliases` (`normalizedAlias`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_contact_aliases_contactId_normalizedAlias` ON `contact_aliases` (`contactId`,`normalizedAlias`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `contact_platform_identities` (`identityId` TEXT NOT NULL, `contactId` TEXT NOT NULL, `platform` TEXT NOT NULL, `handle` TEXT NOT NULL, `normalizedHandle` TEXT NOT NULL, `platformUserId` TEXT, `source` TEXT NOT NULL, `userConfirmed` INTEGER NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`identityId`), FOREIGN KEY(`contactId`) REFERENCES `contacts`(`contactId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_contact_platform_identities_contactId` ON `contact_platform_identities` (`contactId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_contact_platform_identities_platform_normalizedHandle` ON `contact_platform_identities` (`platform`,`normalizedHandle`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_contact_platform_identities_contactId_platform_normalizedHandle` ON `contact_platform_identities` (`contactId`,`platform`,`normalizedHandle`)",
                )
            }
        }

        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `contact_merge_links` (`sourceContactId` TEXT NOT NULL, `canonicalContactId` TEXT NOT NULL, `reason` TEXT NOT NULL, `userConfirmed` INTEGER NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `undoneAtEpochMs` INTEGER, PRIMARY KEY(`sourceContactId`), FOREIGN KEY(`sourceContactId`) REFERENCES `contacts`(`contactId`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`canonicalContactId`) REFERENCES `contacts`(`contactId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_contact_merge_links_canonicalContactId` ON `contact_merge_links` (`canonicalContactId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_contact_merge_links_undoneAtEpochMs` ON `contact_merge_links` (`undoneAtEpochMs`)",
                )
            }
        }

        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // This partial unique index is maintained by CALLBACK.onOpen because Room cannot
                // represent its WHERE clause. Drop it before strict migration validation.
                db.execSQL("DROP INDEX IF EXISTS `index_plan_runs_single_active_per_definition`")
                db.execSQL("ALTER TABLE `schedules` ADD COLUMN `reminderMinutesBefore` INTEGER")
            }
        }

        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS `index_plan_runs_single_active_per_definition`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `notification_candidates` (`candidateId` TEXT NOT NULL, `sourceKey` TEXT NOT NULL, `packageName` TEXT NOT NULL, `appLabel` TEXT NOT NULL, `title` TEXT, `body` TEXT, `postedAtEpochMs` INTEGER NOT NULL, `status` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`candidateId`))",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_notification_candidates_sourceKey` ON `notification_candidates` (`sourceKey`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_notification_candidates_status` ON `notification_candidates` (`status`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_notification_candidates_postedAtEpochMs` ON `notification_candidates` (`postedAtEpochMs`)",
                )
            }
        }

        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS `index_plan_runs_single_active_per_definition`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `runtime_session_workspaces` (`sessionId` TEXT NOT NULL, `directoryName` TEXT NOT NULL, `state` TEXT NOT NULL, `summaryText` TEXT, `summaryThroughTurnAtEpochMs` INTEGER, `totalArtifactBytes` INTEGER NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`sessionId`), FOREIGN KEY(`sessionId`) REFERENCES `runtime_sessions`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_runtime_session_workspaces_updatedAtEpochMs` ON `runtime_session_workspaces` (`updatedAtEpochMs`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `runtime_artifacts` (`artifactId` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `runId` TEXT, `kind` TEXT NOT NULL, `displayName` TEXT NOT NULL, `mimeType` TEXT NOT NULL, `relativePath` TEXT NOT NULL, `byteLength` INTEGER NOT NULL, `sha256Digest` TEXT NOT NULL, `status` TEXT NOT NULL, `provenance` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`artifactId`), FOREIGN KEY(`sessionId`) REFERENCES `runtime_sessions`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`runId`) REFERENCES `runtime_runs`(`runId`) ON UPDATE NO ACTION ON DELETE SET NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_runtime_artifacts_sessionId` ON `runtime_artifacts` (`sessionId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_runtime_artifacts_runId` ON `runtime_artifacts` (`runId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_runtime_artifacts_status` ON `runtime_artifacts` (`status`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_runtime_artifacts_sessionId_sha256Digest` ON `runtime_artifacts` (`sessionId`,`sha256Digest`)",
                )
            }
        }

        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS `index_plan_runs_single_active_per_definition`")
                db.execSQL(
                    "ALTER TABLE `notification_candidates` ADD COLUMN `sourceType` TEXT NOT NULL DEFAULT 'NOTIFICATION'",
                )
                db.execSQL("ALTER TABLE `notification_candidates` ADD COLUMN `platform` TEXT NOT NULL DEFAULT 'OTHER'")
                db.execSQL("ALTER TABLE `notification_candidates` ADD COLUMN `conversationTitle` TEXT")
                db.execSQL("ALTER TABLE `notification_candidates` ADD COLUMN `senderName` TEXT")
                db.execSQL(
                    "ALTER TABLE `notification_candidates` ADD COLUMN `direction` TEXT NOT NULL DEFAULT 'INCOMING'",
                )
                db.execSQL("ALTER TABLE `notification_candidates` ADD COLUMN `isGroupChat` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "ALTER TABLE `notification_candidates` ADD COLUMN `messageKind` TEXT NOT NULL DEFAULT 'MESSAGE'",
                )
                db.execSQL("ALTER TABLE `notification_candidates` ADD COLUMN `insightJson` TEXT")
                db.execSQL("ALTER TABLE `notification_candidates` ADD COLUMN `suggestedContactId` TEXT")
                db.execSQL(
                    "ALTER TABLE `notification_candidates` ADD COLUMN `suggestedContactConfidence` REAL NOT NULL DEFAULT 0.0",
                )
                db.execSQL("ALTER TABLE `notification_candidates` ADD COLUMN `linkedContactId` TEXT")
                db.execSQL("ALTER TABLE `notification_candidates` ADD COLUMN `createdScheduleId` TEXT")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_notification_candidates_platform` ON `notification_candidates` (`platform`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_notification_candidates_suggestedContactId` ON `notification_candidates` (`suggestedContactId`)",
                )
            }
        }

        /**
         * Repairs prerelease v23 databases created while structured message
         * perception was still being expanded. Released installs normally
         * arrive here with every column already present, so this is a no-op
         * except for advancing Room's schema identity.
         */
        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS `index_plan_runs_single_active_per_definition`")
                val columns = buildSet {
                    db.query("PRAGMA table_info(`notification_candidates`)").use { cursor ->
                        val nameIndex = cursor.getColumnIndex("name")
                        while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                    }
                }
                fun addColumn(name: String, definition: String) {
                    if (name !in columns) {
                        db.execSQL("ALTER TABLE `notification_candidates` ADD COLUMN `$name` $definition")
                    }
                }
                addColumn("sourceType", "TEXT NOT NULL DEFAULT 'NOTIFICATION'")
                addColumn("platform", "TEXT NOT NULL DEFAULT 'OTHER'")
                addColumn("conversationTitle", "TEXT")
                addColumn("senderName", "TEXT")
                addColumn("direction", "TEXT NOT NULL DEFAULT 'INCOMING'")
                addColumn("isGroupChat", "INTEGER NOT NULL DEFAULT 0")
                addColumn("messageKind", "TEXT NOT NULL DEFAULT 'MESSAGE'")
                addColumn("insightJson", "TEXT")
                addColumn("suggestedContactId", "TEXT")
                addColumn("suggestedContactConfidence", "REAL NOT NULL DEFAULT 0.0")
                addColumn("linkedContactId", "TEXT")
                addColumn("createdScheduleId", "TEXT")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_notification_candidates_platform` ON `notification_candidates` (`platform`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_notification_candidates_suggestedContactId` ON `notification_candidates` (`suggestedContactId`)",
                )
            }
        }

        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS `index_plan_runs_single_active_per_definition`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `contact_methods` (`methodId` TEXT NOT NULL, `contactId` TEXT NOT NULL, `kind` TEXT NOT NULL, `value` TEXT NOT NULL, `normalizedValue` TEXT NOT NULL, `label` TEXT, `isPrimary` INTEGER NOT NULL, `source` TEXT NOT NULL, `evidenceRef` TEXT, `confidence` REAL NOT NULL, `userConfirmed` INTEGER NOT NULL, `verifiedAtEpochMs` INTEGER, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`methodId`), FOREIGN KEY(`contactId`) REFERENCES `contacts`(`contactId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_contact_methods_contactId` ON `contact_methods` (`contactId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_contact_methods_kind_normalizedValue` ON `contact_methods` (`kind`, `normalizedValue`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_contact_methods_contactId_kind_normalizedValue` ON `contact_methods` (`contactId`, `kind`, `normalizedValue`)",
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `organizations` (`organizationId` TEXT NOT NULL, `canonicalName` TEXT NOT NULL, `normalizedName` TEXT NOT NULL, `creditCode` TEXT, `status` TEXT, `registeredAddress` TEXT, `longitude` REAL, `latitude` REAL, `source` TEXT NOT NULL, `sourceRef` TEXT, `userConfirmed` INTEGER NOT NULL, `verifiedAtEpochMs` INTEGER, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`organizationId`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_organizations_normalizedName` ON `organizations` (`normalizedName`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_organizations_creditCode` ON `organizations` (`creditCode`)",
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `contact_employments` (`employmentId` TEXT NOT NULL, `contactId` TEXT NOT NULL, `organizationId` TEXT, `companyNameSnapshot` TEXT NOT NULL, `department` TEXT, `title` TEXT, `jobDescription` TEXT, `officeLocation` TEXT, `isCurrent` INTEGER NOT NULL, `source` TEXT NOT NULL, `evidenceRef` TEXT, `confidence` REAL NOT NULL, `userConfirmed` INTEGER NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`employmentId`), FOREIGN KEY(`contactId`) REFERENCES `contacts`(`contactId`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`organizationId`) REFERENCES `organizations`(`organizationId`) ON UPDATE NO ACTION ON DELETE SET NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_contact_employments_contactId` ON `contact_employments` (`contactId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_contact_employments_organizationId` ON `contact_employments` (`organizationId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_contact_employments_isCurrent` ON `contact_employments` (`isCurrent`)",
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `contact_addresses` (`addressId` TEXT NOT NULL, `contactId` TEXT NOT NULL, `kind` TEXT NOT NULL, `formattedAddress` TEXT NOT NULL, `longitude` REAL, `latitude` REAL, `precision` TEXT, `source` TEXT NOT NULL, `evidenceRef` TEXT, `userConfirmed` INTEGER NOT NULL, `verifiedAtEpochMs` INTEGER, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`addressId`), FOREIGN KEY(`contactId`) REFERENCES `contacts`(`contactId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_contact_addresses_contactId` ON `contact_addresses` (`contactId`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_contact_addresses_contactId_kind_formattedAddress` ON `contact_addresses` (`contactId`, `kind`, `formattedAddress`)",
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `contact_important_dates` (`dateId` TEXT NOT NULL, `contactId` TEXT NOT NULL, `kind` TEXT NOT NULL, `year` INTEGER, `month` INTEGER NOT NULL, `day` INTEGER NOT NULL, `source` TEXT NOT NULL, `evidenceRef` TEXT, `userConfirmed` INTEGER NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`dateId`), FOREIGN KEY(`contactId`) REFERENCES `contacts`(`contactId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_contact_important_dates_contactId` ON `contact_important_dates` (`contactId`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_contact_important_dates_contactId_kind` ON `contact_important_dates` (`contactId`, `kind`)",
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `contact_facets` (`facetId` TEXT NOT NULL, `contactId` TEXT NOT NULL, `dimension` TEXT NOT NULL, `value` TEXT NOT NULL, `source` TEXT NOT NULL, `evidenceRef` TEXT, `confidence` REAL NOT NULL, `userConfirmed` INTEGER NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`facetId`), FOREIGN KEY(`contactId`) REFERENCES `contacts`(`contactId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_contact_facets_contactId` ON `contact_facets` (`contactId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_contact_facets_dimension` ON `contact_facets` (`dimension`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_contact_facets_contactId_dimension_value` ON `contact_facets` (`contactId`, `dimension`, `value`)",
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `contact_enrichment_candidates` (`candidateId` TEXT NOT NULL, `contactId` TEXT, `providerId` TEXT NOT NULL, `fieldKind` TEXT NOT NULL, `proposedValueJson` TEXT NOT NULL, `sourceRef` TEXT, `confidence` REAL NOT NULL, `status` TEXT NOT NULL, `observedAtEpochMs` INTEGER NOT NULL, `expiresAtEpochMs` INTEGER, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`candidateId`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_contact_enrichment_candidates_contactId` ON `contact_enrichment_candidates` (`contactId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_contact_enrichment_candidates_providerId` ON `contact_enrichment_candidates` (`providerId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_contact_enrichment_candidates_status` ON `contact_enrichment_candidates` (`status`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_contact_enrichment_candidates_expiresAtEpochMs` ON `contact_enrichment_candidates` (`expiresAtEpochMs`)",
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `owner_contact_links` (`contactId` TEXT NOT NULL, `reason` TEXT NOT NULL, `userConfirmed` INTEGER NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `undoneAtEpochMs` INTEGER, PRIMARY KEY(`contactId`), FOREIGN KEY(`contactId`) REFERENCES `contacts`(`contactId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_owner_contact_links_undoneAtEpochMs` ON `owner_contact_links` (`undoneAtEpochMs`)",
                )

                db.execSQL(
                    "INSERT OR IGNORE INTO `contact_methods` SELECT 'legacy-phone-' || contactId, contactId, 'PHONE', phone, phone, NULL, 1, source, NULL, 0.7, 0, NULL, createdAtEpochMs, updatedAtEpochMs FROM contacts WHERE phone IS NOT NULL AND trim(phone) != ''",
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `contact_methods` SELECT 'legacy-email-' || contactId, contactId, 'EMAIL', email, lower(email), NULL, 1, source, NULL, 0.7, 0, NULL, createdAtEpochMs, updatedAtEpochMs FROM contacts WHERE email IS NOT NULL AND trim(email) != ''",
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `contact_methods` SELECT 'legacy-wechat-' || contactId, contactId, 'WECHAT', wechatId, lower(wechatId), NULL, 1, source, NULL, 0.7, 0, NULL, createdAtEpochMs, updatedAtEpochMs FROM contacts WHERE wechatId IS NOT NULL AND trim(wechatId) != ''",
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `organizations` SELECT 'legacy-org-' || lower(trim(company)), company, lower(trim(company)), NULL, NULL, NULL, NULL, NULL, source, NULL, 0, NULL, min(createdAtEpochMs), max(updatedAtEpochMs) FROM contacts WHERE company IS NOT NULL AND trim(company) != '' GROUP BY lower(trim(company))",
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `contact_employments` SELECT 'legacy-employment-' || contactId, contactId, 'legacy-org-' || lower(trim(company)), company, NULL, title, NULL, NULL, 1, source, NULL, 0.7, 0, createdAtEpochMs, updatedAtEpochMs FROM contacts WHERE company IS NOT NULL AND trim(company) != ''",
                )
            }
        }

        /** Repairs prerelease v25 installs that predate explicit owner-contact mapping. */
        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS `index_plan_runs_single_active_per_definition`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `owner_contact_links` (`contactId` TEXT NOT NULL, `reason` TEXT NOT NULL, `userConfirmed` INTEGER NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `undoneAtEpochMs` INTEGER, PRIMARY KEY(`contactId`), FOREIGN KEY(`contactId`) REFERENCES `contacts`(`contactId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_owner_contact_links_undoneAtEpochMs` ON `owner_contact_links` (`undoneAtEpochMs`)",
                )
            }
        }

        val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // CALLBACK recreates this unmanaged partial index after Room opens the database.
                // Remove it before migration validation so Room compares only its managed schema;
                // onOpen restores the runtime invariant immediately after validation.
                db.execSQL("DROP INDEX IF EXISTS `index_plan_runs_single_active_per_definition`")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `crm_leads` (`leadId` TEXT NOT NULL, `contactId` TEXT, `displayNameSnapshot` TEXT NOT NULL, `companyNameSnapshot` TEXT, `status` TEXT NOT NULL, `sourceType` TEXT NOT NULL, `sourceRef` TEXT, `fitSummary` TEXT, `confidence` REAL NOT NULL, `userConfirmed` INTEGER NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`leadId`), FOREIGN KEY(`contactId`) REFERENCES `contacts`(`contactId`) ON UPDATE NO ACTION ON DELETE SET NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_crm_leads_contactId` ON `crm_leads` (`contactId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_crm_leads_status` ON `crm_leads` (`status`)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_crm_leads_updatedAtEpochMs` ON `crm_leads` (`updatedAtEpochMs`)",
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `crm_opportunities` (`opportunityId` TEXT NOT NULL, `title` TEXT NOT NULL, `accountNameSnapshot` TEXT NOT NULL, `primaryContactId` TEXT, `sourceLeadId` TEXT, `stage` TEXT NOT NULL, `status` TEXT NOT NULL, `valueMinor` INTEGER, `currencyCode` TEXT NOT NULL, `probabilityPercent` INTEGER NOT NULL, `expectedCloseAtEpochMs` INTEGER, `productSummary` TEXT, `needSummary` TEXT, `lossReason` TEXT, `sourceType` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`opportunityId`), FOREIGN KEY(`primaryContactId`) REFERENCES `contacts`(`contactId`) ON UPDATE NO ACTION ON DELETE SET NULL, FOREIGN KEY(`sourceLeadId`) REFERENCES `crm_leads`(`leadId`) ON UPDATE NO ACTION ON DELETE SET NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_crm_opportunities_primaryContactId` ON `crm_opportunities` (`primaryContactId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_crm_opportunities_sourceLeadId` ON `crm_opportunities` (`sourceLeadId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_crm_opportunities_stage` ON `crm_opportunities` (`stage`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_crm_opportunities_status` ON `crm_opportunities` (`status`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_crm_opportunities_expectedCloseAtEpochMs` ON `crm_opportunities` (`expectedCloseAtEpochMs`)",
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `crm_opportunity_stakeholders` (`opportunityId` TEXT NOT NULL, `contactId` TEXT NOT NULL, `roleType` TEXT NOT NULL, `influenceLevel` TEXT NOT NULL, `userConfirmed` INTEGER NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`opportunityId`, `contactId`, `roleType`), FOREIGN KEY(`opportunityId`) REFERENCES `crm_opportunities`(`opportunityId`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`contactId`) REFERENCES `contacts`(`contactId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_crm_opportunity_stakeholders_opportunityId` ON `crm_opportunity_stakeholders` (`opportunityId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_crm_opportunity_stakeholders_contactId` ON `crm_opportunity_stakeholders` (`contactId`)",
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `crm_activities` (`activityId` TEXT NOT NULL, `opportunityId` TEXT NOT NULL, `contactId` TEXT, `activityType` TEXT NOT NULL, `title` TEXT NOT NULL, `summary` TEXT NOT NULL, `occurredAtEpochMs` INTEGER NOT NULL, `sourceType` TEXT NOT NULL, `sourceRef` TEXT, `evidenceSummary` TEXT, `userConfirmed` INTEGER NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`activityId`), FOREIGN KEY(`opportunityId`) REFERENCES `crm_opportunities`(`opportunityId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_crm_activities_opportunityId` ON `crm_activities` (`opportunityId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_crm_activities_contactId` ON `crm_activities` (`contactId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_crm_activities_occurredAtEpochMs` ON `crm_activities` (`occurredAtEpochMs`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_crm_activities_sourceType` ON `crm_activities` (`sourceType`)",
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `crm_next_actions` (`actionId` TEXT NOT NULL, `opportunityId` TEXT NOT NULL, `contactId` TEXT, `actionType` TEXT NOT NULL, `title` TEXT NOT NULL, `dueAtEpochMs` INTEGER, `status` TEXT NOT NULL, `priority` INTEGER NOT NULL, `rationale` TEXT, `sourceType` TEXT NOT NULL, `scheduleId` TEXT, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`actionId`), FOREIGN KEY(`opportunityId`) REFERENCES `crm_opportunities`(`opportunityId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_crm_next_actions_opportunityId` ON `crm_next_actions` (`opportunityId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_crm_next_actions_contactId` ON `crm_next_actions` (`contactId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_crm_next_actions_scheduleId` ON `crm_next_actions` (`scheduleId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_crm_next_actions_status` ON `crm_next_actions` (`status`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_crm_next_actions_dueAtEpochMs` ON `crm_next_actions` (`dueAtEpochMs`)",
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `crm_agent_suggestions` (`suggestionId` TEXT NOT NULL, `opportunityId` TEXT NOT NULL, `suggestionType` TEXT NOT NULL, `title` TEXT NOT NULL, `summary` TEXT NOT NULL, `rationale` TEXT NOT NULL, `evidenceRefsJson` TEXT NOT NULL, `confidence` REAL NOT NULL, `proposedActionJson` TEXT, `status` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`suggestionId`), FOREIGN KEY(`opportunityId`) REFERENCES `crm_opportunities`(`opportunityId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_crm_agent_suggestions_opportunityId` ON `crm_agent_suggestions` (`opportunityId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_crm_agent_suggestions_status` ON `crm_agent_suggestions` (`status`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_crm_agent_suggestions_createdAtEpochMs` ON `crm_agent_suggestions` (`createdAtEpochMs`)",
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `crm_stage_history` (`historyId` TEXT NOT NULL, `opportunityId` TEXT NOT NULL, `fromStage` TEXT, `toStage` TEXT NOT NULL, `reason` TEXT, `sourceType` TEXT NOT NULL, `userConfirmed` INTEGER NOT NULL, `changedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`historyId`), FOREIGN KEY(`opportunityId`) REFERENCES `crm_opportunities`(`opportunityId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_crm_stage_history_opportunityId` ON `crm_stage_history` (`opportunityId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_crm_stage_history_changedAtEpochMs` ON `crm_stage_history` (`changedAtEpochMs`)",
                )
            }
        }

        /**
         * Removes the prerelease CRM seed only through explicit durable markers and records a
         * non-PII audit receipt containing planned and actual deletion counts.
         */
        val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS `index_plan_runs_single_active_per_definition`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `crm_demo_cleanup_audits` (`auditId` TEXT NOT NULL, `cleanupKey` TEXT NOT NULL, `triggerType` TEXT NOT NULL, `plannedCountsJson` TEXT NOT NULL, `deletedCountsJson` TEXT NOT NULL, `status` TEXT NOT NULL, `startedAtEpochMs` INTEGER NOT NULL, `completedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`auditId`))",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_crm_demo_cleanup_audits_cleanupKey` ON `crm_demo_cleanup_audits` (`cleanupKey`)",
                )

                fun count(sql: String): Int = db.query(sql).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }
                val planned = linkedMapOf(
                    "contacts" to count("SELECT COUNT(*) FROM contacts WHERE source = 'CRM_DEMO'"),
                    "schedules" to count("SELECT COUNT(*) FROM schedules WHERE id LIKE 'crm-demo-%'"),
                    "leads" to count("SELECT COUNT(*) FROM crm_leads WHERE sourceType = 'DEMO'"),
                    "opportunities" to count("SELECT COUNT(*) FROM crm_opportunities WHERE sourceType = 'DEMO'"),
                    "activities" to count("SELECT COUNT(*) FROM crm_activities WHERE sourceType = 'DEMO'"),
                    "actions" to count("SELECT COUNT(*) FROM crm_next_actions WHERE sourceType = 'DEMO'"),
                    "suggestions" to
                        count(
                            "SELECT COUNT(*) FROM crm_agent_suggestions WHERE opportunityId IN (SELECT opportunityId FROM crm_opportunities WHERE sourceType = 'DEMO')",
                        ),
                    "stakeholders" to
                        count(
                            "SELECT COUNT(*) FROM crm_opportunity_stakeholders WHERE opportunityId IN (SELECT opportunityId FROM crm_opportunities WHERE sourceType = 'DEMO')",
                        ),
                    "stageHistory" to count("SELECT COUNT(*) FROM crm_stage_history WHERE sourceType = 'DEMO'"),
                )
                val startedAt = System.currentTimeMillis()
                db.execSQL("DELETE FROM crm_opportunities WHERE sourceType = 'DEMO'")
                db.execSQL("DELETE FROM crm_leads WHERE sourceType = 'DEMO'")
                db.execSQL("DELETE FROM schedules WHERE id LIKE 'crm-demo-%'")
                db.execSQL("DELETE FROM contacts WHERE source = 'CRM_DEMO'")

                listOf(
                    "UPDATE schedules SET title = REPLACE(title, '销售 CRM', '个人 CRM'), note = REPLACE(note, '销售 CRM', '个人 CRM') WHERE title LIKE '%销售 CRM%' OR note LIKE '%销售 CRM%'",
                    "UPDATE crm_leads SET displayNameSnapshot = REPLACE(displayNameSnapshot, '销售 CRM', '个人 CRM'), companyNameSnapshot = REPLACE(companyNameSnapshot, '销售 CRM', '个人 CRM'), fitSummary = REPLACE(fitSummary, '销售 CRM', '个人 CRM') WHERE displayNameSnapshot LIKE '%销售 CRM%' OR companyNameSnapshot LIKE '%销售 CRM%' OR fitSummary LIKE '%销售 CRM%'",
                    "UPDATE crm_opportunities SET title = REPLACE(title, '销售 CRM', '个人 CRM'), accountNameSnapshot = REPLACE(accountNameSnapshot, '销售 CRM', '个人 CRM'), productSummary = REPLACE(productSummary, '销售 CRM', '个人 CRM'), needSummary = REPLACE(needSummary, '销售 CRM', '个人 CRM'), lossReason = REPLACE(lossReason, '销售 CRM', '个人 CRM') WHERE title LIKE '%销售 CRM%' OR accountNameSnapshot LIKE '%销售 CRM%' OR productSummary LIKE '%销售 CRM%' OR needSummary LIKE '%销售 CRM%' OR lossReason LIKE '%销售 CRM%'",
                    "UPDATE crm_activities SET title = REPLACE(title, '销售 CRM', '个人 CRM'), summary = REPLACE(summary, '销售 CRM', '个人 CRM'), evidenceSummary = REPLACE(evidenceSummary, '销售 CRM', '个人 CRM') WHERE title LIKE '%销售 CRM%' OR summary LIKE '%销售 CRM%' OR evidenceSummary LIKE '%销售 CRM%'",
                    "UPDATE crm_next_actions SET title = REPLACE(title, '销售 CRM', '个人 CRM'), rationale = REPLACE(rationale, '销售 CRM', '个人 CRM') WHERE title LIKE '%销售 CRM%' OR rationale LIKE '%销售 CRM%'",
                    "UPDATE crm_agent_suggestions SET title = REPLACE(title, '销售 CRM', '个人 CRM'), summary = REPLACE(summary, '销售 CRM', '个人 CRM'), rationale = REPLACE(rationale, '销售 CRM', '个人 CRM') WHERE title LIKE '%销售 CRM%' OR summary LIKE '%销售 CRM%' OR rationale LIKE '%销售 CRM%'",
                    "UPDATE crm_stage_history SET reason = REPLACE(reason, '销售 CRM', '个人 CRM') WHERE reason LIKE '%销售 CRM%'",
                ).forEach(db::execSQL)

                val remaining = linkedMapOf(
                    "contacts" to count("SELECT COUNT(*) FROM contacts WHERE source = 'CRM_DEMO'"),
                    "schedules" to count("SELECT COUNT(*) FROM schedules WHERE id LIKE 'crm-demo-%'"),
                    "leads" to count("SELECT COUNT(*) FROM crm_leads WHERE sourceType = 'DEMO'"),
                    "opportunities" to count("SELECT COUNT(*) FROM crm_opportunities WHERE sourceType = 'DEMO'"),
                    "activities" to count("SELECT COUNT(*) FROM crm_activities WHERE sourceType = 'DEMO'"),
                    "actions" to count("SELECT COUNT(*) FROM crm_next_actions WHERE sourceType = 'DEMO'"),
                    "suggestions" to 0,
                    "stakeholders" to 0,
                    "stageHistory" to count("SELECT COUNT(*) FROM crm_stage_history WHERE sourceType = 'DEMO'"),
                )
                val deleted = planned.mapValues { (key, value) -> value - (remaining[key] ?: 0) }
                fun Map<String, Int>.json(): String = entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
                    "\"$key\":$value"
                }
                val completedAt = System.currentTimeMillis()
                db.execSQL(
                    "INSERT OR IGNORE INTO crm_demo_cleanup_audits (auditId, cleanupKey, triggerType, plannedCountsJson, deletedCountsJson, status, startedAtEpochMs, completedAtEpochMs) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(
                        "crm-demo-cleanup-migration-v1",
                        "migration:crm-demo-cleanup:v1",
                        "DATABASE_MIGRATION",
                        planned.json(),
                        deleted.json(),
                        "COMPLETED",
                        startedAt,
                        completedAt,
                    ),
                )
            }
        }

        /**
         * Makes optional CRM references durable: deleting a contact or linked schedule preserves
         * the CRM history while clearing the reference. Existing orphan ids are cleared on copy.
         */
        val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS `index_plan_runs_single_active_per_definition`")

                db.execSQL("ALTER TABLE `crm_activities` RENAME TO `crm_activities_v28`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `crm_activities` (`activityId` TEXT NOT NULL, `opportunityId` TEXT NOT NULL, `contactId` TEXT, `activityType` TEXT NOT NULL, `title` TEXT NOT NULL, `summary` TEXT NOT NULL, `occurredAtEpochMs` INTEGER NOT NULL, `sourceType` TEXT NOT NULL, `sourceRef` TEXT, `evidenceSummary` TEXT, `userConfirmed` INTEGER NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`activityId`), FOREIGN KEY(`opportunityId`) REFERENCES `crm_opportunities`(`opportunityId`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`contactId`) REFERENCES `contacts`(`contactId`) ON UPDATE NO ACTION ON DELETE SET NULL)",
                )
                db.execSQL(
                    "INSERT INTO `crm_activities` " +
                        "(`activityId`, `opportunityId`, `contactId`, `activityType`, `title`, `summary`, " +
                        "`occurredAtEpochMs`, `sourceType`, `sourceRef`, `evidenceSummary`, `userConfirmed`, `createdAtEpochMs`) " +
                        "SELECT `activityId`, `opportunityId`, CASE WHEN `contactId` IS NULL OR EXISTS (SELECT 1 FROM `contacts` WHERE `contacts`.`contactId` = `crm_activities_v28`.`contactId`) THEN `contactId` ELSE NULL END, `activityType`, `title`, `summary`, `occurredAtEpochMs`, `sourceType`, `sourceRef`, `evidenceSummary`, `userConfirmed`, `createdAtEpochMs` FROM `crm_activities_v28`",
                )
                db.execSQL("DROP TABLE `crm_activities_v28`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_crm_activities_opportunityId` ON `crm_activities` (`opportunityId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_crm_activities_contactId` ON `crm_activities` (`contactId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_crm_activities_occurredAtEpochMs` ON `crm_activities` (`occurredAtEpochMs`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_crm_activities_sourceType` ON `crm_activities` (`sourceType`)",
                )

                db.execSQL("ALTER TABLE `crm_next_actions` RENAME TO `crm_next_actions_v28`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `crm_next_actions` (`actionId` TEXT NOT NULL, `opportunityId` TEXT NOT NULL, `contactId` TEXT, `actionType` TEXT NOT NULL, `title` TEXT NOT NULL, `dueAtEpochMs` INTEGER, `status` TEXT NOT NULL, `priority` INTEGER NOT NULL, `rationale` TEXT, `sourceType` TEXT NOT NULL, `scheduleId` TEXT, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`actionId`), FOREIGN KEY(`opportunityId`) REFERENCES `crm_opportunities`(`opportunityId`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`contactId`) REFERENCES `contacts`(`contactId`) ON UPDATE NO ACTION ON DELETE SET NULL, FOREIGN KEY(`scheduleId`) REFERENCES `schedules`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)",
                )
                db.execSQL(
                    "INSERT INTO `crm_next_actions` " +
                        "(`actionId`, `opportunityId`, `contactId`, `actionType`, `title`, `dueAtEpochMs`, `status`, " +
                        "`priority`, `rationale`, `sourceType`, `scheduleId`, `createdAtEpochMs`, `updatedAtEpochMs`) " +
                        "SELECT `actionId`, `opportunityId`, CASE WHEN `contactId` IS NULL OR EXISTS (SELECT 1 FROM `contacts` WHERE `contacts`.`contactId` = `crm_next_actions_v28`.`contactId`) THEN `contactId` ELSE NULL END, `actionType`, `title`, `dueAtEpochMs`, `status`, `priority`, `rationale`, `sourceType`, CASE WHEN `scheduleId` IS NULL OR EXISTS (SELECT 1 FROM `schedules` WHERE `schedules`.`id` = `crm_next_actions_v28`.`scheduleId`) THEN `scheduleId` ELSE NULL END, `createdAtEpochMs`, `updatedAtEpochMs` FROM `crm_next_actions_v28`",
                )
                db.execSQL("DROP TABLE `crm_next_actions_v28`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_crm_next_actions_opportunityId` ON `crm_next_actions` (`opportunityId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_crm_next_actions_contactId` ON `crm_next_actions` (`contactId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_crm_next_actions_scheduleId` ON `crm_next_actions` (`scheduleId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_crm_next_actions_status` ON `crm_next_actions` (`status`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_crm_next_actions_dueAtEpochMs` ON `crm_next_actions` (`dueAtEpochMs`)",
                )
            }
        }

        val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `call_records` (`callRecordId` TEXT NOT NULL, `source` TEXT NOT NULL, `providerRowId` INTEGER NOT NULL, `rawNumber` TEXT, `normalizedNumber` TEXT, `numberPresentation` INTEGER NOT NULL, `systemType` INTEGER NOT NULL, `direction` TEXT NOT NULL, `startedAtEpochMs` INTEGER NOT NULL, `durationSeconds` INTEGER NOT NULL, `lastModifiedEpochMs` INTEGER NOT NULL, `phoneAccountId` TEXT, `phoneAccountComponentName` TEXT, `linkedContactId` TEXT, `linkState` TEXT NOT NULL, `linkSource` TEXT, `sourceStatus` TEXT NOT NULL, `notePromptState` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`callRecordId`), FOREIGN KEY(`linkedContactId`) REFERENCES `contacts`(`contactId`) ON UPDATE NO ACTION ON DELETE SET NULL)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_call_records_source_providerRowId` ON `call_records` (`source`, `providerRowId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_call_records_normalizedNumber` ON `call_records` (`normalizedNumber`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_call_records_startedAtEpochMs` ON `call_records` (`startedAtEpochMs`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_call_records_linkedContactId` ON `call_records` (`linkedContactId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_call_records_notePromptState` ON `call_records` (`notePromptState`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `call_notes` (`callNoteId` TEXT NOT NULL, `callRecordId` TEXT NOT NULL, `noteText` TEXT NOT NULL, `source` TEXT NOT NULL, `asrProvider` TEXT, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`callNoteId`), FOREIGN KEY(`callRecordId`) REFERENCES `call_records`(`callRecordId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_call_notes_callRecordId` ON `call_notes` (`callRecordId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_call_notes_createdAtEpochMs` ON `call_notes` (`createdAtEpochMs`)",
                )
            }
        }

        val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // This partial unique index is maintained by CALLBACK.onOpen rather than the
                // Room entity schema. Existing installs already contain it, so remove it before
                // Room validates the migrated schema; onOpen recreates it immediately afterward.
                db.execSQL("DROP INDEX IF EXISTS `index_plan_runs_single_active_per_definition`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `change_log_v31` (`changeId` TEXT NOT NULL, `runtimeRunId` TEXT, `toolName` TEXT NOT NULL, `idempotencyKey` TEXT NOT NULL, `targetDomain` TEXT NOT NULL, `targetId` TEXT NOT NULL, `operation` TEXT NOT NULL, `beforeDigest` TEXT, `afterDigest` TEXT, `inversePayloadJson` TEXT NOT NULL, `undoState` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, `undoneAtEpochMs` INTEGER, `originType` TEXT NOT NULL, PRIMARY KEY(`changeId`))",
                )
                db.execSQL(
                    "INSERT INTO `change_log_v31` (`changeId`, `runtimeRunId`, `toolName`, `idempotencyKey`, `targetDomain`, `targetId`, `operation`, `beforeDigest`, `afterDigest`, `inversePayloadJson`, `undoState`, `createdAtEpochMs`, `undoneAtEpochMs`, `originType`) SELECT `changeId`, `runtimeRunId`, `toolName`, `idempotencyKey`, `targetDomain`, `targetId`, `operation`, `beforeDigest`, `afterDigest`, `inversePayloadJson`, `undoState`, `createdAtEpochMs`, `undoneAtEpochMs`, 'RUNTIME_TOOL' FROM `change_log`",
                )
                db.execSQL("DROP TABLE `change_log`")
                db.execSQL("ALTER TABLE `change_log_v31` RENAME TO `change_log`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_change_log_runtimeRunId` ON `change_log` (`runtimeRunId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_change_log_targetDomain_targetId` ON `change_log` (`targetDomain`, `targetId`)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_change_log_undoState` ON `change_log` (`undoState`)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_change_log_idempotencyKey` ON `change_log` (`idempotencyKey`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `auto_write_receipts` (`changeId` TEXT NOT NULL, `subjectContactId` TEXT, `sourceType` TEXT NOT NULL, `sourceRefDigest` TEXT NOT NULL, `confidence` REAL, `presentationType` TEXT NOT NULL, `correctionRoute` TEXT NOT NULL, `reviewState` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`changeId`), FOREIGN KEY(`changeId`) REFERENCES `change_log`(`changeId`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`subjectContactId`) REFERENCES `contacts`(`contactId`) ON UPDATE NO ACTION ON DELETE SET NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_auto_write_receipts_subjectContactId` ON `auto_write_receipts` (`subjectContactId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_auto_write_receipts_reviewState` ON `auto_write_receipts` (`reviewState`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_auto_write_receipts_createdAtEpochMs` ON `auto_write_receipts` (`createdAtEpochMs`)",
                )
            }
        }

        val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS contact_search_fts USING fts4(contactId, content)")
                val concat =
                    "new.displayName || ' ' || new.normalizedName || ' ' || COALESCE(new.phone,'') || ' ' || COALESCE(new.email,'') || ' ' || COALESCE(new.company,'') || ' ' || new.aliasesJson || ' ' || new.tagsJson || ' ' || COALESCE(new.note,'')"
                db.execSQL(
                    """
                    CREATE TRIGGER IF NOT EXISTS contact_fts_ai AFTER INSERT ON contacts BEGIN
                        INSERT INTO contact_search_fts(contactId, content) VALUES (new.contactId, $concat);
                    END
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TRIGGER IF NOT EXISTS contact_fts_ad AFTER DELETE ON contacts BEGIN
                        DELETE FROM contact_search_fts WHERE contactId = old.contactId;
                    END
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TRIGGER IF NOT EXISTS contact_fts_au AFTER UPDATE ON contacts BEGIN
                        DELETE FROM contact_search_fts WHERE contactId = old.contactId;
                        INSERT INTO contact_search_fts(contactId, content) VALUES (new.contactId, $concat);
                    END
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO contact_search_fts(contactId, content)
                    SELECT contactId, displayName || ' ' || normalizedName || ' ' || COALESCE(phone, '')
                        || ' ' || COALESCE(email, '') || ' ' || COALESCE(company, '')
                        || ' ' || aliasesJson || ' ' || tagsJson || ' ' || COALESCE(note, '')
                    FROM contacts WHERE deletedAtEpochMs IS NULL
                    """.trimIndent(),
                )
            }
        }

        /**
         * Makes `crm_agent_suggestions.opportunityId` nullable and adds a nullable `contactId` so
         * contact-scoped suggestions (NEW_LEAD) can live beside opportunity-scoped ones
         * (CALL_FOLLOW_UP). SQLite cannot alter nullability or add a foreign-key column in place,
         * so the table is rebuilt and its rows copied verbatim.
         */
        val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `crm_agent_suggestions_new` (" +
                        "`suggestionId` TEXT NOT NULL, " +
                        "`opportunityId` TEXT, " +
                        "`contactId` TEXT, " +
                        "`suggestionType` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`summary` TEXT NOT NULL, " +
                        "`rationale` TEXT NOT NULL, " +
                        "`evidenceRefsJson` TEXT NOT NULL, " +
                        "`confidence` REAL NOT NULL, " +
                        "`proposedActionJson` TEXT, " +
                        "`status` TEXT NOT NULL, " +
                        "`createdAtEpochMs` INTEGER NOT NULL, " +
                        "`updatedAtEpochMs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`suggestionId`), " +
                        "FOREIGN KEY(`opportunityId`) REFERENCES `crm_opportunities`(`opportunityId`) ON UPDATE NO ACTION ON DELETE SET NULL, " +
                        "FOREIGN KEY(`contactId`) REFERENCES `contacts`(`contactId`) ON UPDATE NO ACTION ON DELETE SET NULL" +
                        ")",
                )
                db.execSQL(
                    "INSERT INTO `crm_agent_suggestions_new` (" +
                        "`suggestionId`, `opportunityId`, `contactId`, `suggestionType`, `title`, `summary`, " +
                        "`rationale`, `evidenceRefsJson`, `confidence`, `proposedActionJson`, `status`, " +
                        "`createdAtEpochMs`, `updatedAtEpochMs`" +
                        ") SELECT " +
                        "`suggestionId`, `opportunityId`, NULL, `suggestionType`, `title`, `summary`, " +
                        "`rationale`, `evidenceRefsJson`, `confidence`, `proposedActionJson`, `status`, " +
                        "`createdAtEpochMs`, `updatedAtEpochMs` FROM `crm_agent_suggestions`",
                )
                db.execSQL("DROP TABLE `crm_agent_suggestions`")
                db.execSQL("ALTER TABLE `crm_agent_suggestions_new` RENAME TO `crm_agent_suggestions`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_crm_agent_suggestions_opportunityId` ON `crm_agent_suggestions` (`opportunityId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_crm_agent_suggestions_contactId` ON `crm_agent_suggestions` (`contactId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_crm_agent_suggestions_status` ON `crm_agent_suggestions` (`status`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_crm_agent_suggestions_createdAtEpochMs` ON `crm_agent_suggestions` (`createdAtEpochMs`)",
                )
            }
        }

        /** Repairs legacy phone methods whose normalized value was copied from formatted input. */
        val MIGRATION_33_34 = object : Migration(33, 34) {
            override fun migrate(db: SupportSQLiteDatabase) {
                data class PhoneMethod(val methodId: String, val contactId: String, val normalizedValue: String)

                val methods = buildList {
                    db.query(
                        "SELECT methodId, contactId, value FROM contact_methods WHERE kind = 'PHONE' " +
                            "ORDER BY contactId, isPrimary DESC, userConfirmed DESC, " +
                            "verifiedAtEpochMs DESC, updatedAtEpochMs DESC, methodId",
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            val rawValue = cursor.getString(2)
                            add(
                                PhoneMethod(
                                    methodId = cursor.getString(0),
                                    contactId = cursor.getString(1),
                                    normalizedValue = normalizeContactPhone(rawValue) ?: rawValue,
                                ),
                            )
                        }
                    }
                }
                val winners = linkedMapOf<Pair<String, String>, PhoneMethod>()
                val duplicates = mutableListOf<String>()
                methods.forEach { method ->
                    val key = method.contactId to method.normalizedValue
                    if (winners.putIfAbsent(key, method) != null) duplicates += method.methodId
                }
                duplicates.forEach { methodId ->
                    db.execSQL("DELETE FROM contact_methods WHERE methodId = ?", arrayOf(methodId))
                }
                winners.values.forEach { method ->
                    db.execSQL(
                        "UPDATE contact_methods SET normalizedValue = ? WHERE methodId = ?",
                        arrayOf(method.normalizedValue, method.methodId),
                    )
                }
            }
        }

        val MIGRATION_34_35 = object : Migration(34, 35) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `event_plans` (
                        `planId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `proposedStartAtEpochMs` INTEGER NOT NULL,
                        `durationMinutes` INTEGER NOT NULL,
                        `location` TEXT,
                        `note` TEXT,
                        `status` TEXT NOT NULL,
                        `scheduleId` TEXT,
                        `sourceType` TEXT NOT NULL,
                        `createdAtEpochMs` INTEGER NOT NULL,
                        `updatedAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`planId`),
                        FOREIGN KEY(`scheduleId`) REFERENCES `schedules`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                    )""",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_event_plans_status` ON `event_plans` (`status`)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_event_plans_proposedStartAtEpochMs` " +
                        "ON `event_plans` (`proposedStartAtEpochMs`)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_event_plans_scheduleId` ON `event_plans` (`scheduleId`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `event_plan_participants` (
                        `planId` TEXT NOT NULL,
                        `contactId` TEXT NOT NULL,
                        `responseStatus` TEXT NOT NULL,
                        `responseSource` TEXT NOT NULL,
                        `updatedAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`planId`, `contactId`),
                        FOREIGN KEY(`planId`) REFERENCES `event_plans`(`planId`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`contactId`) REFERENCES `contacts`(`contactId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_event_plan_participants_planId` " +
                        "ON `event_plan_participants` (`planId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_event_plan_participants_contactId` " +
                        "ON `event_plan_participants` (`contactId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_event_plan_participants_responseStatus` " +
                        "ON `event_plan_participants` (`responseStatus`)",
                )
            }
        }

        val MIGRATION_35_36 = object : Migration(35, 36) {
            override fun migrate(db: SupportSQLiteDatabase) {
                createContactIntelligenceTables(db)
                downgradeUnverifiedSystemContactFacts(db)
                backfillPeopleAndSourceIdentities(db)
                backfillIdentityClaims(db)
                backfillTemporalEpisodes(db)
            }
        }

        private fun createContactIntelligenceTables(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `persons` (
                    `personId` TEXT NOT NULL,
                    `canonicalContactId` TEXT,
                    `displayName` TEXT NOT NULL,
                    `normalizedName` TEXT NOT NULL,
                    `kind` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `createdAtEpochMs` INTEGER NOT NULL,
                    `updatedAtEpochMs` INTEGER NOT NULL,
                    PRIMARY KEY(`personId`),
                    FOREIGN KEY(`canonicalContactId`) REFERENCES `contacts`(`contactId`)
                        ON UPDATE NO ACTION ON DELETE SET NULL
                )""",
            )
            createIndices(
                db,
                "persons",
                "canonicalContactId" to true,
                "kind" to false,
                "status" to false,
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `source_identities` (
                    `sourceIdentityId` TEXT NOT NULL,
                    `personId` TEXT,
                    `sourceType` TEXT NOT NULL,
                    `accountScope` TEXT NOT NULL,
                    `tenantId` TEXT,
                    `stableExternalId` TEXT,
                    `visibleHandle` TEXT NOT NULL,
                    `normalizedHandle` TEXT NOT NULL,
                    `conversationScopeId` TEXT,
                    `resolutionStatus` TEXT NOT NULL,
                    `confidence` REAL NOT NULL,
                    `sourceRef` TEXT,
                    `firstObservedAtEpochMs` INTEGER NOT NULL,
                    `lastObservedAtEpochMs` INTEGER NOT NULL,
                    PRIMARY KEY(`sourceIdentityId`),
                    FOREIGN KEY(`personId`) REFERENCES `persons`(`personId`)
                        ON UPDATE NO ACTION ON DELETE SET NULL
                )""",
            )
            createIndices(
                db,
                "source_identities",
                "personId" to false,
                "sourceType,accountScope,stableExternalId" to false,
                "sourceType,conversationScopeId,normalizedHandle" to false,
                "resolutionStatus" to false,
                "lastObservedAtEpochMs" to false,
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `identity_claims` (
                    `claimId` TEXT NOT NULL,
                    `personId` TEXT NOT NULL,
                    `fieldType` TEXT NOT NULL,
                    `displayValue` TEXT NOT NULL,
                    `normalizedValue` TEXT NOT NULL,
                    `validFromEpochMs` INTEGER,
                    `validToEpochMs` INTEGER,
                    `temporalPrecision` TEXT NOT NULL,
                    `recordedAtEpochMs` INTEGER NOT NULL,
                    `sourceIdentityId` TEXT,
                    `sourceRef` TEXT,
                    `confidence` REAL NOT NULL,
                    `verificationState` TEXT NOT NULL,
                    `supersedesClaimId` TEXT,
                    `status` TEXT NOT NULL,
                    PRIMARY KEY(`claimId`),
                    FOREIGN KEY(`personId`) REFERENCES `persons`(`personId`)
                        ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`sourceIdentityId`) REFERENCES `source_identities`(`sourceIdentityId`)
                        ON UPDATE NO ACTION ON DELETE SET NULL
                )""",
            )
            createIndices(
                db,
                "identity_claims",
                "personId" to false,
                "sourceIdentityId" to false,
                "personId,fieldType,status" to false,
                "fieldType,normalizedValue" to false,
                "verificationState" to false,
                "validToEpochMs" to false,
            )
            createEmploymentEpisodeTable(db)
            createRelationshipEpisodeTable(db)
            createGroupTables(db)
            createAndroidSyncTables(db)
        }

        private fun createEmploymentEpisodeTable(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `person_employment_episodes` (
                    `episodeId` TEXT NOT NULL,
                    `personId` TEXT NOT NULL,
                    `organizationId` TEXT,
                    `companyNameSnapshot` TEXT NOT NULL,
                    `department` TEXT,
                    `title` TEXT,
                    `validFromEpochMs` INTEGER,
                    `validToEpochMs` INTEGER,
                    `temporalPrecision` TEXT NOT NULL,
                    `currentState` TEXT NOT NULL,
                    `sourceRef` TEXT,
                    `confidence` REAL NOT NULL,
                    `verificationState` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `recordedAtEpochMs` INTEGER NOT NULL,
                    `updatedAtEpochMs` INTEGER NOT NULL,
                    PRIMARY KEY(`episodeId`),
                    FOREIGN KEY(`personId`) REFERENCES `persons`(`personId`)
                        ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`organizationId`) REFERENCES `organizations`(`organizationId`)
                        ON UPDATE NO ACTION ON DELETE SET NULL
                )""",
            )
            createIndices(
                db,
                "person_employment_episodes",
                "personId" to false,
                "organizationId" to false,
                "validFromEpochMs" to false,
                "validToEpochMs" to false,
                "status" to false,
            )
        }

        private fun createRelationshipEpisodeTable(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `relationship_episodes` (
                    `episodeId` TEXT NOT NULL,
                    `fromPersonId` TEXT NOT NULL,
                    `toPersonId` TEXT NOT NULL,
                    `relationshipType` TEXT NOT NULL,
                    `direction` TEXT NOT NULL,
                    `validFromEpochMs` INTEGER,
                    `validToEpochMs` INTEGER,
                    `temporalPrecision` TEXT NOT NULL,
                    `evidenceRefsJson` TEXT NOT NULL,
                    `confidence` REAL NOT NULL,
                    `verificationState` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `recordedAtEpochMs` INTEGER NOT NULL,
                    `updatedAtEpochMs` INTEGER NOT NULL,
                    PRIMARY KEY(`episodeId`),
                    FOREIGN KEY(`fromPersonId`) REFERENCES `persons`(`personId`)
                        ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`toPersonId`) REFERENCES `persons`(`personId`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )""",
            )
            createIndices(
                db,
                "relationship_episodes",
                "fromPersonId" to false,
                "toPersonId" to false,
                "relationshipType" to false,
                "validFromEpochMs" to false,
                "validToEpochMs" to false,
                "status" to false,
            )
        }

        private fun createGroupTables(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `group_conversations` (
                    `groupId` TEXT NOT NULL,
                    `platform` TEXT NOT NULL,
                    `accountScope` TEXT NOT NULL,
                    `stableGroupId` TEXT,
                    `displayName` TEXT NOT NULL,
                    `sourceRef` TEXT,
                    `firstObservedAtEpochMs` INTEGER NOT NULL,
                    `lastObservedAtEpochMs` INTEGER NOT NULL,
                    PRIMARY KEY(`groupId`)
                )""",
            )
            createIndices(
                db,
                "group_conversations",
                "platform,accountScope,stableGroupId" to false,
                "lastObservedAtEpochMs" to false,
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `group_membership_episodes` (
                    `membershipId` TEXT NOT NULL,
                    `groupId` TEXT NOT NULL,
                    `sourceIdentityId` TEXT NOT NULL,
                    `groupAlias` TEXT,
                    `validFromEpochMs` INTEGER,
                    `validToEpochMs` INTEGER,
                    `status` TEXT NOT NULL,
                    `confidence` REAL NOT NULL,
                    `sourceRef` TEXT,
                    `recordedAtEpochMs` INTEGER NOT NULL,
                    PRIMARY KEY(`membershipId`),
                    FOREIGN KEY(`groupId`) REFERENCES `group_conversations`(`groupId`)
                        ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`sourceIdentityId`) REFERENCES `source_identities`(`sourceIdentityId`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )""",
            )
            createIndices(
                db,
                "group_membership_episodes",
                "groupId" to false,
                "sourceIdentityId" to false,
                "validToEpochMs" to false,
                "status" to false,
            )
        }

        private fun createAndroidSyncTables(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `android_raw_contact_links` (
                    `linkId` TEXT NOT NULL,
                    `personId` TEXT NOT NULL,
                    `aggregateContactId` INTEGER NOT NULL,
                    `lookupKey` TEXT NOT NULL,
                    `rawContactId` INTEGER NOT NULL,
                    `accountName` TEXT,
                    `accountType` TEXT,
                    `sourceId` TEXT,
                    `version` INTEGER NOT NULL,
                    `isReadOnly` INTEGER NOT NULL,
                    `lastObservedAtEpochMs` INTEGER NOT NULL,
                    PRIMARY KEY(`linkId`),
                    FOREIGN KEY(`personId`) REFERENCES `persons`(`personId`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )""",
            )
            createIndices(
                db,
                "android_raw_contact_links",
                "personId" to false,
                "aggregateContactId" to false,
                "lookupKey" to false,
                "rawContactId" to true,
                "accountType,accountName" to false,
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `contact_sync_snapshots` (
                    `snapshotId` TEXT NOT NULL,
                    `linkId` TEXT NOT NULL,
                    `baseProjectionJson` TEXT NOT NULL,
                    `baseDigest` TEXT NOT NULL,
                    `desiredProjectionJson` TEXT,
                    `desiredDigest` TEXT,
                    `syncState` TEXT NOT NULL,
                    `lastVerifiedAtEpochMs` INTEGER,
                    `updatedAtEpochMs` INTEGER NOT NULL,
                    PRIMARY KEY(`snapshotId`),
                    FOREIGN KEY(`linkId`) REFERENCES `android_raw_contact_links`(`linkId`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )""",
            )
            createIndices(
                db,
                "contact_sync_snapshots",
                "linkId" to true,
                "syncState" to false,
                "updatedAtEpochMs" to false,
            )
        }

        private fun createIndices(db: SupportSQLiteDatabase, table: String, vararg columns: Pair<String, Boolean>) {
            columns.forEach { (columnList, unique) ->
                val indexColumns = columnList.split(',')
                val indexName = "index_${table}_${indexColumns.joinToString("_")}"
                val sqlColumns = indexColumns.joinToString(", ") { "`$it`" }
                db.execSQL("CREATE ${if (unique) "UNIQUE " else ""}INDEX IF NOT EXISTS `$indexName` ON `$table` ($sqlColumns)")
            }
        }

        private fun downgradeUnverifiedSystemContactFacts(db: SupportSQLiteDatabase) {
            db.execSQL(
                "UPDATE contact_employments SET isCurrent = 0, userConfirmed = 0, confidence = MIN(confidence, 0.6) " +
                    "WHERE source = 'SYSTEM_CONTACT'",
            )
            db.execSQL("UPDATE organizations SET userConfirmed = 0 WHERE source = 'SYSTEM_CONTACT'")
            db.execSQL("UPDATE contact_methods SET userConfirmed = 0 WHERE source = 'SYSTEM_CONTACT'")
            db.execSQL("UPDATE contact_addresses SET userConfirmed = 0 WHERE source = 'SYSTEM_CONTACT'")
            db.execSQL("UPDATE contact_important_dates SET userConfirmed = 0 WHERE source = 'SYSTEM_CONTACT'")
            db.execSQL("UPDATE contact_platform_identities SET userConfirmed = 0 WHERE source = 'SYSTEM_CONTACT'")
        }

        private fun backfillPeopleAndSourceIdentities(db: SupportSQLiteDatabase) {
            db.execSQL(
                """INSERT OR IGNORE INTO persons (
                    personId, canonicalContactId, displayName, normalizedName, kind, status,
                    createdAtEpochMs, updatedAtEpochMs
                ) SELECT contactId, contactId, displayName, normalizedName, 'CONTACT',
                    CASE WHEN deletedAtEpochMs IS NULL THEN 'ACTIVE' ELSE 'DELETED' END,
                    createdAtEpochMs, updatedAtEpochMs FROM contacts""",
            )
            db.execSQL(
                """INSERT OR IGNORE INTO persons VALUES (
                    'user:self', NULL, '我', '我', 'OWNER', 'ACTIVE', 0, 0
                )""",
            )
            db.execSQL(
                """INSERT OR IGNORE INTO source_identities (
                    sourceIdentityId, personId, sourceType, accountScope, tenantId,
                    stableExternalId, visibleHandle, normalizedHandle, conversationScopeId,
                    resolutionStatus, confidence, sourceRef, firstObservedAtEpochMs, lastObservedAtEpochMs
                ) SELECT 'contact:' || contactId, contactId, source, 'LOCAL', NULL,
                    NULL, displayName, normalizedName, NULL, 'RESOLVED',
                    CASE WHEN source = 'USER' THEN 1.0 ELSE 0.7 END,
                    source, createdAtEpochMs, updatedAtEpochMs FROM contacts""",
            )
            db.execSQL(
                """INSERT OR IGNORE INTO source_identities (
                    sourceIdentityId, personId, sourceType, accountScope, tenantId,
                    stableExternalId, visibleHandle, normalizedHandle, conversationScopeId,
                    resolutionStatus, confidence, sourceRef, firstObservedAtEpochMs, lastObservedAtEpochMs
                ) SELECT 'platform:' || identityId, contactId, platform, 'LOCAL', NULL,
                    platformUserId, handle, normalizedHandle, NULL, 'RESOLVED',
                    CASE WHEN userConfirmed = 1 THEN 1.0 ELSE 0.7 END,
                    source, createdAtEpochMs, updatedAtEpochMs FROM contact_platform_identities""",
            )
        }

        private fun backfillIdentityClaims(db: SupportSQLiteDatabase) {
            insertContactClaim(db, "NAME", "displayName", "normalizedName")
            insertContactClaim(db, "PHONE", "phone", "phone")
            insertContactClaim(db, "EMAIL", "email", "email")
            insertContactClaim(db, "COMPANY", "company", "company")
            insertContactClaim(db, "TITLE", "title", "title")
        }

        private fun insertContactClaim(db: SupportSQLiteDatabase, fieldType: String, displayColumn: String, normalizedColumn: String) {
            db.execSQL(
                """INSERT OR IGNORE INTO identity_claims (
                    claimId, personId, fieldType, displayValue, normalizedValue,
                    validFromEpochMs, validToEpochMs, temporalPrecision, recordedAtEpochMs,
                    sourceIdentityId, sourceRef, confidence, verificationState, supersedesClaimId, status
                ) SELECT 'migration:${fieldType.lowercase()}:' || contactId, contactId, '$fieldType',
                    $displayColumn, lower($normalizedColumn), NULL, NULL, 'UNKNOWN', updatedAtEpochMs,
                    'contact:' || contactId, source,
                    CASE WHEN source = 'USER' THEN 1.0 ELSE 0.6 END,
                    CASE WHEN source = 'USER' THEN 'USER_CONFIRMED' ELSE 'OBSERVED' END,
                    NULL, 'ACTIVE' FROM contacts WHERE $displayColumn IS NOT NULL AND trim($displayColumn) != ''""",
            )
        }

        private fun backfillTemporalEpisodes(db: SupportSQLiteDatabase) {
            db.execSQL(
                """INSERT OR IGNORE INTO person_employment_episodes (
                    episodeId, personId, organizationId, companyNameSnapshot, department, title,
                    validFromEpochMs, validToEpochMs, temporalPrecision, currentState, sourceRef,
                    confidence, verificationState, status, recordedAtEpochMs, updatedAtEpochMs
                ) SELECT employmentId, contactId, organizationId, companyNameSnapshot, department, title,
                    NULL, NULL, 'UNKNOWN',
                    CASE WHEN isCurrent = 1 AND userConfirmed = 1 THEN 'CURRENT_CONFIRMED' ELSE 'UNKNOWN' END,
                    evidenceRef, confidence,
                    CASE WHEN userConfirmed = 1 THEN 'USER_CONFIRMED' ELSE 'OBSERVED' END,
                    'ACTIVE', createdAtEpochMs, updatedAtEpochMs FROM contact_employments""",
            )
            db.execSQL(
                """INSERT OR IGNORE INTO relationship_episodes (
                    episodeId, fromPersonId, toPersonId, relationshipType, direction,
                    validFromEpochMs, validToEpochMs, temporalPrecision, evidenceRefsJson,
                    confidence, verificationState, status, recordedAtEpochMs, updatedAtEpochMs
                ) SELECT edgeId, fromContactId, toContactId, relationType, 'BIDIRECTIONAL',
                    NULL, NULL, 'UNKNOWN', evidenceRefsJson, confidence,
                    CASE WHEN userConfirmed = 1 THEN 'USER_CONFIRMED' ELSE 'INFERRED' END,
                    CASE WHEN status = 'DELETED' THEN 'DELETED' ELSE 'ACTIVE' END,
                    createdAtEpochMs, updatedAtEpochMs FROM relationship_edges
                    WHERE EXISTS (SELECT 1 FROM persons WHERE personId = fromContactId)
                      AND EXISTS (SELECT 1 FROM persons WHERE personId = toContactId)""",
            )
        }

        val CALLBACK = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                createPreferenceDetachTrigger(db)
                createSingleCurrentMemoryTrigger(db)
                createSingleCurrentMemoryUpdateTrigger(db)
                createPlanRunsSingleActiveIndex(db)
                createFactFts(db)
                createContactSearchFts(db)
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                createPreferenceDetachTrigger(db)
                createSingleCurrentMemoryTrigger(db)
                createSingleCurrentMemoryUpdateTrigger(db)
                createPlanRunsSingleActiveIndex(db)
                createFactFts(db)
                createContactSearchFts(db)
            }

            private fun createPreferenceDetachTrigger(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TRIGGER IF NOT EXISTS detach_user_preferences_before_run_delete
                    BEFORE DELETE ON agent_runs
                    BEGIN
                        UPDATE memories
                        SET sourceRunId = NULL
                        WHERE sourceRunId = OLD.id AND kind = 'USER_PREFERENCE';
                    END
                    """.trimIndent(),
                )
            }
        }

        private fun createSingleCurrentMemoryTrigger(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS reject_second_current_memory_version
                BEFORE INSERT ON memory_records
                WHEN NEW.txToEpochMs IS NULL AND EXISTS (
                    SELECT 1 FROM memory_records
                    WHERE namespaceId = NEW.namespaceId
                      AND logicalMemoryId = NEW.logicalMemoryId
                      AND txToEpochMs IS NULL
                )
                BEGIN
                    SELECT RAISE(ABORT, 'duplicate current memory version');
                END
                """.trimIndent(),
            )
        }

        private fun createSingleCurrentMemoryUpdateTrigger(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS reject_reopened_second_current_memory_version
                BEFORE UPDATE OF txToEpochMs, namespaceId, logicalMemoryId ON memory_records
                WHEN NEW.txToEpochMs IS NULL AND EXISTS (
                    SELECT 1 FROM memory_records
                    WHERE namespaceId = NEW.namespaceId
                      AND logicalMemoryId = NEW.logicalMemoryId
                      AND txToEpochMs IS NULL
                      AND NOT (
                        namespaceId = OLD.namespaceId
                        AND memoryId = OLD.memoryId
                        AND recordVersion = OLD.recordVersion
                      )
                )
                BEGIN
                    SELECT RAISE(ABORT, 'duplicate current memory version');
                END
                """.trimIndent(),
            )
        }

        private fun createPlanRunsSingleActiveIndex(db: SupportSQLiteDatabase) {
            // ADR-006 §3.1: per-(definition) single ACTIVE partial UNIQUE.
            // Fresh installs get this from MIGRATION_8_9; onOpen is a defensive
            // safety net in case the index was dropped or migrated from a
            // schema path that bypassed MIGRATION_8_9.
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_plan_runs_single_active_per_definition` " +
                    "ON `plan_runs` (`definitionId`) WHERE `runStatus` = 'ACTIVE'",
            )
        }

        private fun createFactSchema(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `facts` (`factId` TEXT NOT NULL, `factType` TEXT NOT NULL, `textContent` TEXT NOT NULL, `structuredDataJson` TEXT, `sourceType` TEXT NOT NULL, `sourceRef` TEXT, `contactId` TEXT, `skillId` TEXT, `confidence` REAL NOT NULL, `sensitivity` TEXT NOT NULL, `status` TEXT NOT NULL, `ttlDays` INTEGER NOT NULL, `expiresAtEpochMs` INTEGER, `createdAtEpochMs` INTEGER NOT NULL, `updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`factId`))",
            )
            listOf(
                "factType",
                "sourceType",
                "sourceRef",
                "contactId",
                "skillId",
                "expiresAtEpochMs",
                "status",
            ).forEach {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_facts_$it` ON `facts` (`$it`)")
            }
            // The virtual table is intentionally created by CALLBACK after Room's strict
            // managed-schema validation; Room 2.6 treats unmanaged virtual tables as unexpected.
        }

        private fun createFactFts(db: SupportSQLiteDatabase) {
            // Android's framework SQLite build varies by OS/vendor. Probe the actual module
            // instead of relying on compile-option functions, which are themselves optional.
            runCatching {
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS fact_fts USING fts5(factId UNINDEXED, textContent, factType, sourceType)",
                )
            }.getOrElse {
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS fact_fts USING fts4(factId, textContent, factType, sourceType)",
                )
            }
        }

        private fun createContactSearchFts(db: SupportSQLiteDatabase) {
            val concat =
                "new.displayName || ' ' || new.normalizedName || ' ' || COALESCE(new.phone,'') || ' ' || COALESCE(new.email,'') || ' ' || COALESCE(new.company,'') || ' ' || new.aliasesJson || ' ' || new.tagsJson || ' ' || COALESCE(new.note,'')"
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS contact_fts_ai AFTER INSERT ON contacts BEGIN
                    INSERT INTO contact_search_fts(contactId, content) VALUES (new.contactId, $concat);
                END
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS contact_fts_ad AFTER DELETE ON contacts BEGIN
                    DELETE FROM contact_search_fts WHERE contactId = old.contactId;
                END
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS contact_fts_au AFTER UPDATE ON contacts BEGIN
                    DELETE FROM contact_search_fts WHERE contactId = old.contactId;
                    INSERT INTO contact_search_fts(contactId, content) VALUES (new.contactId, $concat);
                END
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO contact_search_fts(contactId, content)
                SELECT contactId, displayName || ' ' || normalizedName || ' ' || COALESCE(phone, '')
                    || ' ' || COALESCE(email, '') || ' ' || COALESCE(company, '')
                    || ' ' || aliasesJson || ' ' || tagsJson || ' ' || COALESCE(note, '')
                FROM contacts WHERE deletedAtEpochMs IS NULL
                    AND contactId NOT IN (SELECT contactId FROM contact_search_fts)
                """.trimIndent(),
            )
        }
    }
}
