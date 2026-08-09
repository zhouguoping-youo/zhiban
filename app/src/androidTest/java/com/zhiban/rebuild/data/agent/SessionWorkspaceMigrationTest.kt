package com.zhiban.rebuild.data.agent

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionWorkspaceMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgentDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migration21To22AddsWorkspaceAndArtifactLedger() {
        val name = "session-workspace-migration.db"
        helper.createDatabase(name, 21).use { db ->
            db.execSQL(
                """INSERT INTO runtime_sessions
                   (sessionId,nextSequence,leaseOwnerId,leaseEpoch,leaseExpiresAtEpochMs,updatedAtEpochMs)
                   VALUES ('s1',1,NULL,0,NULL,1000)""",
            )
        }
        helper.runMigrationsAndValidate(name, 22, true, AgentDatabase.MIGRATION_21_22).use { db ->
            db.execSQL(
                """INSERT INTO runtime_session_workspaces
                   (sessionId,directoryName,state,summaryText,summaryThroughTurnAtEpochMs,totalArtifactBytes,createdAtEpochMs,updatedAtEpochMs)
                   VALUES ('s1','session-00000000000000000000000000000000','ACTIVE',NULL,NULL,0,1000,1000)""",
            )
            db.execSQL(
                """INSERT INTO runtime_artifacts
                   (artifactId,sessionId,runId,kind,displayName,mimeType,relativePath,byteLength,sha256Digest,status,provenance,createdAtEpochMs,updatedAtEpochMs)
                   VALUES ('a1','s1',NULL,'GENERATED_FILE','result.txt','text/plain','session-00000000000000000000000000000000/a1.txt',3,'digest','READY','agent_generated',1000,1000)""",
            )
            db.query("SELECT COUNT(*) FROM runtime_artifacts WHERE sessionId='s1' AND status='READY'").use {
                it.moveToFirst()
                assertEquals(1, it.getInt(0))
            }
        }
    }
}
