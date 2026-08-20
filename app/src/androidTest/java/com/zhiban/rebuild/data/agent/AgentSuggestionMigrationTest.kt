package com.zhiban.rebuild.data.agent

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentSuggestionMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgentDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migration47To51CreatesTheCompleteSuggestionSchema() {
        val name = "agent-suggestion-47-51.db"
        helper.createDatabase(name, 47).close()

        helper.runMigrationsAndValidate(
            name,
            51,
            true,
            AgentDatabase.MIGRATION_47_48,
            AgentDatabase.MIGRATION_48_49,
            AgentDatabase.MIGRATION_49_50,
            AgentDatabase.MIGRATION_50_51,
        ).use { db ->
            val columns = buildSet {
                db.query("PRAGMA table_info(`agent_suggestions`)").use { cursor ->
                    val nameIndex = cursor.getColumnIndex("name")
                    while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                }
            }
            assertTrue(
                columns.containsAll(
                    setOf(
                        "suggestionId",
                        "execActionType",
                        "contactCandidatesJson",
                        "completionRequestId",
                        "forwardMessage",
                        "missingFieldsJson",
                    ),
                ),
            )
        }
    }

    @Test
    fun migration48To51PreservesExistingSuggestions() {
        val name = "agent-suggestion-48-51.db"
        helper.createDatabase(name, 48).use { db ->
            db.execSQL(
                """INSERT INTO agent_suggestions
                    (suggestionId,type,title,body,contactId,candidateId,sourceEvent,dedupeKey,status,
                    createdAtEpochMs,updatedAtEpochMs)
                    VALUES ('suggestion-1','WAKEUP_GENERAL','旧建议','旧正文',NULL,'candidate-1',
                    'NOTIFICATION','wakeup-candidate-1','PENDING',1000,1000)""",
            )
        }

        helper.runMigrationsAndValidate(
            name,
            51,
            true,
            AgentDatabase.MIGRATION_48_49,
            AgentDatabase.MIGRATION_49_50,
            AgentDatabase.MIGRATION_50_51,
        ).use { db ->
            db.query(
                "SELECT title, status, execActionType, contactCandidatesJson, completionRequestId " +
                    "FROM agent_suggestions WHERE suggestionId='suggestion-1'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("旧建议", cursor.getString(0))
                assertEquals("PENDING", cursor.getString(1))
                assertTrue(cursor.isNull(2))
                assertTrue(cursor.isNull(3))
                assertTrue(cursor.isNull(4))
            }
        }
    }
}
