package com.zhiban.rebuild.data.agent

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.zhiban.rebuild.data.contact.normalizeContactPhone

/** Current schema steps 25 through 37. */
internal object AgentDatabaseMigrations25To37 {
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
            createContactSearchFts(db)
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

    val MIGRATION_36_37 = object : Migration(36, 37) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `contact_sync_operations` (
                    `operationId` TEXT NOT NULL,
                    `linkId` TEXT,
                    `contactId` TEXT NOT NULL,
                    `beforeProjectionJson` TEXT NOT NULL,
                    `afterProjectionJson` TEXT NOT NULL,
                    `insertedDataRowIdsJson` TEXT NOT NULL,
                    `rawContactVersionBefore` INTEGER,
                    `rawContactVersionAfter` INTEGER,
                    `state` TEXT NOT NULL,
                    `createdAtEpochMs` INTEGER NOT NULL,
                    `undoneAtEpochMs` INTEGER,
                    PRIMARY KEY(`operationId`),
                    FOREIGN KEY(`linkId`) REFERENCES `android_raw_contact_links`(`linkId`)
                        ON UPDATE NO ACTION ON DELETE SET NULL
                )""",
            )
            createIndices(
                db,
                "contact_sync_operations",
                "linkId" to false,
                "contactId" to false,
                "state" to false,
                "createdAtEpochMs" to false,
            )
        }
    }
}
