package com.zhiban.rebuild.data.agent

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationMemoryMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AgentDatabase::class.java)

    @Test fun migration12To13AddsSessionTurnsAndPreservesRuntimeRows() {
        val name = "conversation-memory-v12-v13"
        helper.createDatabase(name, 12).apply {
            execSQL(
                "INSERT INTO runtime_sessions(sessionId,nextSequence,leaseOwnerId,leaseEpoch,leaseExpiresAtEpochMs,updatedAtEpochMs) VALUES('s',1,NULL,0,NULL,1)",
            )
            execSQL(
                "INSERT INTO runtime_runs(runId,sessionId,schemaVersion,status,activeAttemptId,budgetJson,recoveryCursor,createdAtEpochMs,updatedAtEpochMs) VALUES('r','s',1,'SUCCEEDED',NULL,'{}',0,1,1)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 13, true, AgentDatabase.MIGRATION_12_13)
        assertEquals(1, scalar(db, "SELECT COUNT(*) FROM runtime_runs WHERE runId='r'"))
        db.execSQL(
            "INSERT INTO runtime_conversation_turns(turnId,sessionId,runId,role,content,contentDigest,tokenEstimate,createdAtEpochMs) VALUES('t','s','r','user','hello','digest',2,2)",
        )
        assertEquals(1, scalar(db, "SELECT COUNT(*) FROM runtime_conversation_turns WHERE turnId='t'"))
    }

    private fun scalar(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): Int = db.query(sql).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getInt(0)
    }
}
