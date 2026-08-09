package com.zhiban.rebuild.data.agent

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CrmSuggestionSchemaMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgentDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migration32To33MakesOpportunityNullableAddsContactAndPreservesRows() {
        val name = "crm-suggestion-schema-migration.db"
        helper.createDatabase(name, 32).use { db ->
            db.execSQL(
                "INSERT INTO contacts (contactId, displayName, normalizedName, aliasesJson, tagsJson, source, createdAtEpochMs, updatedAtEpochMs) " +
                    "VALUES ('contact-1', '联系人', '联系人', '[]', '[]', 'MANUAL', 1, 1)",
            )
            db.execSQL(
                "INSERT INTO crm_opportunities (opportunityId, title, accountNameSnapshot, primaryContactId, stage, status, currencyCode, probabilityPercent, sourceType, createdAtEpochMs, updatedAtEpochMs) " +
                    "VALUES ('opportunity-1', '机会', '客户', 'contact-1', 'QUALIFIED', 'OPEN', 'CNY', 50, 'MANUAL', 1, 1)",
            )
            db.execSQL(
                "INSERT INTO crm_agent_suggestions " +
                    "(suggestionId, opportunityId, suggestionType, title, summary, rationale, evidenceRefsJson, confidence, proposedActionJson, status, createdAtEpochMs, updatedAtEpochMs) " +
                    "VALUES ('suggestion-1', 'opportunity-1', 'CALL_FOLLOW_UP', '记录通话跟进', '摘要', '依据', '[\"call-1\"]', 0.7, NULL, 'PENDING', 1, 1)",
            )
        }

        helper.runMigrationsAndValidate(name, 33, true, AgentDatabase.MIGRATION_32_33).use { db ->
            // The pre-existing row survives, with the new contactId column defaulted to NULL.
            db.query("SELECT opportunityId, contactId, suggestionType, status FROM crm_agent_suggestions WHERE suggestionId = 'suggestion-1'").use {
                check(it.moveToFirst())
                check(it.getString(0) == "opportunity-1")
                check(it.isNull(1))
                check(it.getString(2) == "CALL_FOLLOW_UP")
                check(it.getString(3) == "PENDING")
            }

            // v33 accepts a NULL opportunityId (contact-scoped NEW_LEAD suggestions).
            db.execSQL(
                "INSERT INTO crm_agent_suggestions " +
                    "(suggestionId, opportunityId, contactId, suggestionType, title, summary, rationale, evidenceRefsJson, confidence, proposedActionJson, status, createdAtEpochMs, updatedAtEpochMs) " +
                    "VALUES ('suggestion-2', NULL, 'contact-1', 'NEW_LEAD', '新建线索', '摘要', '依据', '[\"cand-1\"]', 0.9, NULL, 'PENDING', 2, 2)",
            )
            db.query("SELECT COUNT(*) FROM crm_agent_suggestions").use {
                check(it.moveToFirst() && it.getInt(0) == 2)
            }

            // Both foreign keys are SET_NULL: deleting the parent nulls the reference, not the row.
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("DELETE FROM crm_opportunities WHERE opportunityId = 'opportunity-1'")
            db.query("SELECT opportunityId FROM crm_agent_suggestions WHERE suggestionId = 'suggestion-1'").use {
                check(it.moveToFirst() && it.isNull(0))
            }
            db.execSQL("DELETE FROM contacts WHERE contactId = 'contact-1'")
            db.query("SELECT contactId FROM crm_agent_suggestions WHERE suggestionId = 'suggestion-2'").use {
                check(it.moveToFirst() && it.isNull(0))
            }
            db.query("SELECT COUNT(*) FROM crm_agent_suggestions").use {
                check(it.moveToFirst() && it.getInt(0) == 2)
            }
        }
    }
}
