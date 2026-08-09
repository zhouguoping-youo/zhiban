package com.zhiban.rebuild.data.agent

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CrmReferenceIntegrityMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgentDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migration28To29RepairsAndMaintainsOptionalReferences() {
        val name = "crm-reference-integrity-migration.db"
        helper.createDatabase(name, 28).use { db ->
            db.execSQL(
                "INSERT INTO contacts (contactId, displayName, normalizedName, aliasesJson, tagsJson, source, createdAtEpochMs, updatedAtEpochMs) VALUES ('contact-1', '联系人', '联系人', '[]', '[]', 'MANUAL', 1, 1)",
            )
            db.execSQL(
                "INSERT INTO schedules (id, title, startAtEpochMs, durationMinutes, createdAtEpochMs, updatedAtEpochMs) VALUES ('schedule-1', '跟进', 1000, 30, 1, 1)",
            )
            db.execSQL(
                "INSERT INTO crm_opportunities (opportunityId, title, accountNameSnapshot, primaryContactId, stage, status, currencyCode, probabilityPercent, sourceType, createdAtEpochMs, updatedAtEpochMs) VALUES ('opportunity-1', '机会', '客户', 'contact-1', 'QUALIFIED', 'OPEN', 'CNY', 50, 'MANUAL', 1, 1)",
            )
            db.execSQL(
                "INSERT INTO crm_activities VALUES ('activity-valid', 'opportunity-1', 'contact-1', 'NOTE', '记录', '摘要', 1, 'MANUAL', NULL, NULL, 1, 1)",
            )
            db.execSQL(
                "INSERT INTO crm_activities VALUES ('activity-orphan', 'opportunity-1', 'missing-contact', 'NOTE', '记录', '摘要', 1, 'MANUAL', NULL, NULL, 1, 1)",
            )
            db.execSQL(
                "INSERT INTO crm_next_actions VALUES ('action-valid', 'opportunity-1', 'contact-1', 'CALL', '联系', 1000, 'PENDING', 10, NULL, 'MANUAL', 'schedule-1', 1, 1)",
            )
            db.execSQL(
                "INSERT INTO crm_next_actions VALUES ('action-orphan', 'opportunity-1', 'missing-contact', 'CALL', '联系', 1000, 'PENDING', 10, NULL, 'MANUAL', 'missing-schedule', 1, 1)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_plan_runs_single_active_per_definition` ON `plan_runs` (`definitionId`) WHERE `runStatus` = 'ACTIVE'",
            )
        }

        helper.runMigrationsAndValidate(name, 29, true, AgentDatabase.MIGRATION_28_29).use { db ->
            db.query("SELECT contactId FROM crm_activities WHERE activityId = 'activity-orphan'").use {
                check(it.moveToFirst() && it.isNull(0))
            }
            db.query("SELECT contactId, scheduleId FROM crm_next_actions WHERE actionId = 'action-orphan'").use {
                check(it.moveToFirst() && it.isNull(0) && it.isNull(1))
            }

            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("DELETE FROM contacts WHERE contactId = 'contact-1'")
            db.query("SELECT contactId FROM crm_activities WHERE activityId = 'activity-valid'").use {
                check(it.moveToFirst() && it.isNull(0))
            }
            db.query("SELECT contactId FROM crm_next_actions WHERE actionId = 'action-valid'").use {
                check(it.moveToFirst() && it.isNull(0))
            }

            db.execSQL("DELETE FROM schedules WHERE id = 'schedule-1'")
            db.query("SELECT scheduleId FROM crm_next_actions WHERE actionId = 'action-valid'").use {
                check(it.moveToFirst() && it.isNull(0))
            }

            db.execSQL("DELETE FROM crm_opportunities WHERE opportunityId = 'opportunity-1'")
            db.query("SELECT COUNT(*) FROM crm_activities").use {
                check(it.moveToFirst() && it.getInt(0) == 0)
            }
            db.query("SELECT COUNT(*) FROM crm_next_actions").use {
                check(it.moveToFirst() && it.getInt(0) == 0)
            }
        }
    }
}
