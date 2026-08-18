package com.zhiban.rebuild.data.agent

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Historical schema steps 1 through 24. */
internal object AgentDatabaseMigrations1To24 {
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

    private fun migrate7To8Part1(db: SupportSQLiteDatabase) {
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
    }

    private fun migrate7To8Part2(db: SupportSQLiteDatabase) {
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
    }

    private fun migrate7To8Part3(db: SupportSQLiteDatabase) {
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

    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            migrate7To8Part1(db)
            migrate7To8Part2(db)
            migrate7To8Part3(db)
        }
    }

    private fun migrate8To9Part1(db: SupportSQLiteDatabase) {
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
    }

    private fun migrate8To9Part2(db: SupportSQLiteDatabase) {
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
    }

    private fun migrate8To9Part3(db: SupportSQLiteDatabase) {
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
    }

    private fun migrate8To9Part4(db: SupportSQLiteDatabase) {
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

    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            migrate8To9Part1(db)
            migrate8To9Part2(db)
            migrate8To9Part3(db)
            migrate8To9Part4(db)
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

    private fun migrate24To25Part1(db: SupportSQLiteDatabase) {
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
    }

    private fun migrate24To25Part2(db: SupportSQLiteDatabase) {
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
    }

    private fun migrate24To25Part3(db: SupportSQLiteDatabase) {
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

    val MIGRATION_24_25 = object : Migration(24, 25) {
        override fun migrate(db: SupportSQLiteDatabase) {
            migrate24To25Part1(db)
            migrate24To25Part2(db)
            migrate24To25Part3(db)
        }
    }
}
