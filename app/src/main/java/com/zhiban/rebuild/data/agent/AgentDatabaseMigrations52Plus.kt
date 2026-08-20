package com.zhiban.rebuild.data.agent

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object AgentDatabaseMigrations52Plus {
    val MIGRATION_51_52 = object : Migration(51, 52) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP INDEX IF EXISTS `index_plan_runs_single_active_per_definition`")
            db.execSQL("DROP INDEX IF EXISTS `index_contacts_active_deleted`")
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `contact_interactions` (
                `interactionId` TEXT NOT NULL,
                `contactId` TEXT NOT NULL,
                `occurredAtEpochMs` INTEGER NOT NULL,
                `channel` TEXT NOT NULL,
                `direction` TEXT NOT NULL,
                `sourceType` TEXT NOT NULL,
                `sourceId` TEXT NOT NULL,
                `createdAtEpochMs` INTEGER NOT NULL,
                PRIMARY KEY(`interactionId`),
                FOREIGN KEY(`contactId`) REFERENCES `contacts`(`contactId`) ON UPDATE NO ACTION ON DELETE CASCADE
                )""",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_contact_interactions_contactId` ON `contact_interactions` (`contactId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_contact_interactions_occurredAtEpochMs` ON `contact_interactions` (`occurredAtEpochMs`)")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_contact_interactions_contactId_occurredAtEpochMs` " +
                    "ON `contact_interactions` (`contactId`, `occurredAtEpochMs`)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_contact_interactions_sourceType_sourceId` " +
                    "ON `contact_interactions` (`sourceType`, `sourceId`)",
            )
            backfillFacts(db)
            backfillCalls(db)
            backfillNotifications(db)
        }
    }

    val MIGRATION_52_53 = object : Migration(52, 53) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP INDEX IF EXISTS `index_plan_runs_single_active_per_definition`")
            db.execSQL("DROP INDEX IF EXISTS `index_contacts_active_deleted`")
            db.execSQL("ALTER TABLE `agent_suggestions` ADD COLUMN `priorityScore` INTEGER NOT NULL DEFAULT 50")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_agent_suggestions_status_priorityScore` " +
                    "ON `agent_suggestions` (`status`, `priorityScore`)",
            )
        }
    }

    private fun backfillFacts(db: SupportSQLiteDatabase) {
        db.execSQL(
            """INSERT OR IGNORE INTO contact_interactions
            (interactionId, contactId, occurredAtEpochMs, channel, direction, sourceType, sourceId, createdAtEpochMs)
            SELECT 'fact:' || factId, contactId, createdAtEpochMs, 'FACT', 'UNKNOWN', 'FACT', factId, createdAtEpochMs
            FROM facts
            WHERE factType = 'INTERACTION_SUMMARY' AND status = 'ACTIVE' AND contactId IS NOT NULL
              AND sourceType NOT IN ('OBSERVED_NOTIFICATION', 'INFERRED_NOTIFICATION')""",
        )
    }

    private fun backfillCalls(db: SupportSQLiteDatabase) {
        db.execSQL(
            """INSERT OR IGNORE INTO contact_interactions
            (interactionId, contactId, occurredAtEpochMs, channel, direction, sourceType, sourceId, createdAtEpochMs)
            SELECT 'call:' || callRecordId, linkedContactId, startedAtEpochMs, 'PHONE', direction,
                   'CALL', callRecordId, createdAtEpochMs
            FROM call_records
            WHERE sourceStatus = 'ACTIVE' AND linkedContactId IS NOT NULL AND durationSeconds > 0""",
        )
    }

    private fun backfillNotifications(db: SupportSQLiteDatabase) {
        db.execSQL(
            """INSERT OR IGNORE INTO contact_interactions
            (interactionId, contactId, occurredAtEpochMs, channel, direction, sourceType, sourceId, createdAtEpochMs)
            SELECT 'notification:' || candidateId, linkedContactId, postedAtEpochMs, platform, direction,
                   'NOTIFICATION', candidateId, createdAtEpochMs
            FROM notification_candidates
            WHERE linkedContactId IS NOT NULL""",
        )
    }
}
