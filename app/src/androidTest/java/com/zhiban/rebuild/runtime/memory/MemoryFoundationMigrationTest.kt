package com.zhiban.rebuild.runtime.memory

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
class MemoryFoundationMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgentDatabase::class.java,
    )

    @Test
    fun migrate7To8PreservesLegacyRowsAndCreatesConstrainedMemoryFoundation() {
        val name = "memory-foundation-v7-v8"
        helper.createDatabase(name, 7).apply {
            execSQL(
                "INSERT INTO memories(id,kind,content,sourceRunId,schemaVersion,createdAtEpochMs) VALUES ('legacy-memory','USER_PREFERENCE','旧偏好',NULL,1,10)",
            )
            execSQL(
                "INSERT INTO staged_memory_candidates(id,scope,scopeId,content,contentDigest,utf8Length,sourceIdsJson,sensitivity,state,approvalRef,revision,createdAtEpochMs,expiresAtEpochMs,updatedAtEpochMs) VALUES ('legacy-candidate','SESSION','session-1','候选','digest',6,'[\"source\"]','PERSONAL','PENDING',NULL,0,10,1000,10)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(name, 8, true, AgentDatabase.MIGRATION_7_8)

        assertEquals(1, scalarLong(db, "SELECT COUNT(*) FROM memories WHERE id='legacy-memory' AND content='旧偏好'"))
        assertEquals(
            1,
            scalarLong(
                db,
                "SELECT COUNT(*) FROM staged_memory_candidates WHERE id='legacy-candidate' AND content='候选'",
            ),
        )
        listOf(
            "memory_namespaces", "memory_records", "memory_current_versions", "memory_evidence",
            "memory_relations", "memory_index_outbox", "memory_commit_receipts", "memory_events",
            "memory_tombstones", "memory_deletion_outbox", "memory_fts",
        ).forEach { table ->
            assertEquals("missing $table", 1, scalarLong(db, "SELECT COUNT(*) FROM sqlite_master WHERE name='$table'"))
        }

        db.execSQL("PRAGMA foreign_keys=ON")
        insertNamespace(db)
        insertRecord(db, memoryId = "m1", logicalId = "logical", version = 1, txTo = null)

        val duplicateCurrentRejected = runCatching {
            insertRecord(db, memoryId = "m2", logicalId = "logical", version = 2, txTo = null)
        }.isFailure
        assertTrue("partial unique must reject two current versions", duplicateCurrentRejected)

        insertRecord(db, memoryId = "m2", logicalId = "logical", version = 2, txTo = 99)
        val reopenedCurrentRejected = runCatching {
            db.execSQL(
                "UPDATE memory_records SET txToEpochMs=NULL WHERE namespaceId='ns' AND memoryId='m2' AND recordVersion=2",
            )
        }.isFailure
        assertTrue("direct update must not reopen a second current version", reopenedCurrentRejected)

        val orphanEvidenceRejected = runCatching {
            db.execSQL(
                "INSERT INTO memory_evidence(namespaceId,memoryId,recordVersion,evidenceId,sourceType,sourceRef,sourceDigest,observedAtEpochMs,excerptDigest,trust,sensitivity) VALUES ('ns','missing',9,'e1','USER_MESSAGE','ref','digest',1,'excerpt','USER','PERSONAL')",
            )
        }.isFailure
        assertTrue("evidence must reference an exact record version", orphanEvidenceRejected)

        val orphanReceiptRejected = runCatching {
            db.execSQL(
                "INSERT INTO memory_commit_receipts(namespaceId,candidateId,approvalRef,canonicalDigest,memoryId,recordVersion,createdAtEpochMs) VALUES ('ns','candidate','approval','digest','missing',9,1)",
            )
        }.isFailure
        assertTrue("receipt must reference an exact record version", orphanReceiptRejected)
        val orphanEventRejected = runCatching {
            db.execSQL(
                "INSERT INTO memory_events(eventId,namespaceId,candidateId,memoryId,recordVersion,eventType,payloadDigest,createdAtEpochMs) VALUES ('event','ns','candidate','missing',9,'MemoryCommitted','digest',1)",
            )
        }.isFailure
        assertTrue("event must reference an exact record version", orphanEventRejected)
        db.close()
    }

    private fun insertNamespace(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO memory_namespaces(namespaceId,ownerUserId,profileId,scopeType,scopeId,state,revision,invalidationGeneration,createdAtEpochMs) VALUES ('ns','owner','profile','USER','user','ACTIVE',0,0,1)",
        )
    }

    private fun insertRecord(db: SupportSQLiteDatabase, memoryId: String, logicalId: String, version: Long, txTo: Long?) {
        val txToSql = txTo?.toString() ?: "NULL"
        db.execSQL(
            "INSERT INTO memory_records(namespaceId,memoryId,recordVersion,logicalMemoryId,memoryType,subjectKey," +
                "predicateKey,objectText,canonicalText,canonicalDigest,sensitivity,confidence,importance,status," +
                "validFromEpochMs,validToEpochMs,observedAtEpochMs,txFromEpochMs,txToEpochMs,createdAtEpochMs," +
                "expiresAtEpochMs,schemaVersion,sourceSetDigest) " +
                "VALUES ('ns','$memoryId',$version,'$logicalId','SEMANTIC','subject','predicate','value','value','digest','PERSONAL',1.0,1.0,'ACTIVE',1,NULL,1,1,$txToSql,1,NULL,1,'sources')",
        )
    }

    private fun scalarLong(db: SupportSQLiteDatabase, sql: String): Long = db.query(sql).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }
}
