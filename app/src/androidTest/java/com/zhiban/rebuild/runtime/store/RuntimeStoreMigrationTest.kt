package com.zhiban.rebuild.runtime.store

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiban.rebuild.data.agent.AgentDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeStoreMigrationTest {
    private val dbName = "runtime-v2-migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgentDatabase::class.java,
    )

    @Test
    fun migrate1To3PreservesLegacyDataAndCreatesRuntimeTables() {
        helper.createDatabase(dbName, 1).apply {
            execSQL(
                "INSERT INTO agent_runs(id, userInput, status, pendingToolCallJson, schemaVersion, expiresAtEpochMs, errorCode, createdAtEpochMs, updatedAtEpochMs) VALUES ('legacy', 'hello', 'SUCCEEDED', NULL, 1, NULL, NULL, 1, 1)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            dbName,
            5,
            true,
            AgentDatabase.MIGRATION_1_2,
            AgentDatabase.MIGRATION_2_3,
            AgentDatabase.MIGRATION_3_4,
            AgentDatabase.MIGRATION_4_5,
        )

        assertEquals(1, scalarLong(db, "SELECT COUNT(*) FROM agent_runs WHERE id = 'legacy'"))
        listOf(
            "runtime_sessions",
            "runtime_runs",
            "runtime_attempts",
            "runtime_command_inbox",
            "runtime_events",
            "runtime_tool_executions",
            "runtime_projections",
            "runtime_input_staging",
            "runtime_run_inputs",
        ).forEach { table ->
            assertEquals(
                "missing $table",
                1,
                scalarLong(db, "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='$table'"),
            )
        }

        db.execSQL(
            "INSERT INTO runtime_sessions(sessionId, nextSequence, leaseEpoch, updatedAtEpochMs) VALUES ('s1', 2, 0, 1)",
        )
        db.execSQL(
            "INSERT INTO runtime_runs(runId, sessionId, schemaVersion, status, budgetJson, recoveryCursor, createdAtEpochMs, updatedAtEpochMs) VALUES ('r1', 's1', 1, 'RECEIVED', '{}', 0, 1, 1)",
        )
        db.execSQL(
            "INSERT INTO runtime_events(eventId, schemaVersion, eventType, sessionId, runId, attemptId, sequence, correlationId, producerVersion, payloadJson, createdAtEpochMs, fencingEpoch) VALUES ('e1', 1, 'RunReceived', 's1', 'r1', NULL, 1, 'c1', 'test', '{}', 1, 0)",
        )
        val duplicateRejected = runCatching {
            db.execSQL(
                "INSERT INTO runtime_events(eventId, schemaVersion, eventType, sessionId, runId, attemptId, sequence, correlationId, producerVersion, payloadJson, createdAtEpochMs, fencingEpoch) VALUES ('e2', 1, 'RunStarted', 's1', 'r1', NULL, 1, 'c1', 'test', '{}', 2, 0)",
            )
        }.isFailure
        assertTrue("(sessionId, sequence) must be unique", duplicateRejected)

        db.execSQL("PRAGMA foreign_keys=ON")
        val orphanRejected = runCatching {
            db.execSQL(
                "INSERT INTO runtime_runs(runId, sessionId, schemaVersion, status, budgetJson, recoveryCursor, createdAtEpochMs, updatedAtEpochMs) VALUES ('orphan', 'missing-session', 1, 'RECEIVED', '{}', 0, 1, 1)",
            )
        }.isFailure
        assertTrue("runtime relation must reject orphan", orphanRejected)
        db.close()
    }

    @Test
    fun migrate38To39AddsRunBoundApprovalStaging() {
        val name = "runtime-approval-staging-38-39"
        helper.createDatabase(name, 38).close()

        helper.runMigrationsAndValidate(name, 39, true, AgentDatabase.MIGRATION_38_39).use { db ->
            assertEquals(
                1,
                scalarLong(
                    db,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='runtime_approval_staging'",
                ),
            )
            assertEquals(
                1,
                scalarLong(
                    db,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='index' " +
                        "AND name='index_runtime_approval_staging_runId'",
                ),
            )
        }
    }

    @Test
    fun migrate4To5PreservesExistingScheduleAuditAndToolExecutionRows() {
        val name = "runtime-v4-v5-data-preservation"
        helper.createDatabase(name, 4).apply {
            execSQL(
                "INSERT INTO agent_runs(id, userInput, status, pendingToolCallJson, schemaVersion, expiresAtEpochMs, errorCode, createdAtEpochMs, updatedAtEpochMs) VALUES ('legacy-run', NULL, 'SUCCEEDED', NULL, 1, NULL, NULL, 1, 1)",
            )
            execSQL(
                "INSERT INTO schedules(id, title, startAtEpochMs, durationMinutes, note, createdByRunId, createdAtEpochMs, updatedAtEpochMs) VALUES ('schedule-old', '旧日程', 1000, 30, 'note', 'legacy-run', 1, 2)",
            )
            execSQL(
                "INSERT INTO tool_audits(id, runId, subjectRunDigest, toolCallId, toolName, idempotencyKey, argumentsDigest, schemaVersion, status, resultJson, expiresAtEpochMs, createdAtEpochMs, updatedAtEpochMs) VALUES ('audit-old', 'legacy-run', 'subject', 'call', 'calendar.schedule.create', 'key-old', 'digest-old', 1, 'SUCCEEDED', '{\"scheduleId\":\"schedule-old\"}', NULL, 1, 2)",
            )
            execSQL(
                "INSERT INTO runtime_sessions(sessionId, nextSequence, leaseOwnerId, leaseEpoch, leaseExpiresAtEpochMs, updatedAtEpochMs) VALUES ('session-old', 1, NULL, 0, NULL, 1)",
            )
            execSQL(
                "INSERT INTO runtime_runs(runId, sessionId, schemaVersion, status, activeAttemptId, budgetJson, recoveryCursor, createdAtEpochMs, updatedAtEpochMs) VALUES ('runtime-run-old', 'session-old', 1, 'SUCCEEDED', NULL, '{}', 0, 1, 2)",
            )
            execSQL(
                "INSERT INTO runtime_tool_executions(executionId, runId, logicalStepId, toolName, toolSpecVersion, canonicalInputDigest, idempotencyKey, status, resultRef, safeResultJson, fencingEpoch, createdAtEpochMs, updatedAtEpochMs) VALUES ('exec-old', 'runtime-run-old', 'step', 'calendar.schedule.create', 1, 'digest-old', 'runtime-key-old', 'SUCCEEDED', 'schedule-old', '{\"scheduleId\":\"schedule-old\"}', 1, 1, 2)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(name, 5, true, AgentDatabase.MIGRATION_4_5)
        assertEquals("旧日程", scalarString(db, "SELECT title FROM schedules WHERE id='schedule-old'"))
        assertEquals("legacy-run", scalarString(db, "SELECT createdByRunId FROM schedules WHERE id='schedule-old'"))
        assertEquals("key-old", scalarString(db, "SELECT idempotencyKey FROM tool_audits WHERE id='audit-old'"))
        assertEquals(
            "schedule-old",
            scalarString(db, "SELECT resultRef FROM runtime_tool_executions WHERE executionId='exec-old'"),
        )
        assertEquals(
            1,
            scalarLong(
                db,
                "SELECT COUNT(*) FROM schedules WHERE createdByRuntimeRunId IS NULL AND createdByRuntimeAttemptId IS NULL",
            ),
        )
        assertEquals(
            1,
            scalarLong(
                db,
                "SELECT COUNT(*) FROM tool_audits WHERE runtimeRunId IS NULL AND runtimeAttemptId IS NULL AND proposalId IS NULL",
            ),
        )
        assertEquals(
            1,
            scalarLong(
                db,
                "SELECT COUNT(*) FROM runtime_tool_executions WHERE providerCallId IS NULL AND attemptId IS NULL",
            ),
        )
        db.close()
    }

    @Test
    fun migrate5To6CreatesEmptyStagingTableWithoutTouchingExistingData() {
        val name = "runtime-v5-v6-staged-memory"
        helper.createDatabase(name, 5).apply {
            execSQL(
                "INSERT INTO agent_runs(id,userInput,status,pendingToolCallJson,schemaVersion,expiresAtEpochMs,errorCode,createdAtEpochMs,updatedAtEpochMs) VALUES ('keep',NULL,'SUCCEEDED',NULL,1,NULL,NULL,1,1)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 6, true, AgentDatabase.MIGRATION_5_6)
        assertEquals(1, scalarLong(db, "SELECT COUNT(*) FROM agent_runs WHERE id='keep'"))
        assertEquals(0, scalarLong(db, "SELECT COUNT(*) FROM staged_memory_candidates"))
        db.close()
    }

    @Test fun migrate6To7PreservesPendingCandidate() {
        val name = "runtime-v6-v7-candidate"
        helper.createDatabase(name, 6).apply {
            execSQL(
                "INSERT INTO staged_memory_candidates(id,scope,scopeId,content,contentDigest,utf8Length,sourceIdsJson,sensitivity,state,createdAtEpochMs,expiresAtEpochMs,updatedAtEpochMs) VALUES ('c','SESSION','s','body','d',4,'[]','PERSONAL','PENDING',1,100,1)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 7, true, AgentDatabase.MIGRATION_6_7)
        assertEquals("body", scalarString(db, "SELECT content FROM staged_memory_candidates WHERE id='c'"))
        assertEquals(0, scalarLong(db, "SELECT revision FROM staged_memory_candidates WHERE id='c'"))
        db.close()
    }

    private fun scalarString(db: SupportSQLiteDatabase, sql: String): String = db.query(sql).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getString(0)
    }

    private fun scalarLong(db: SupportSQLiteDatabase, sql: String): Long = db.query(sql).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }
}
