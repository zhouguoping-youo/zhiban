package com.zhiban.rebuild.runtime.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.facts.FactIndex
import com.zhiban.rebuild.data.memory.ApprovalWriteResult
import com.zhiban.rebuild.data.memory.MemoryNamespaceEntity
import com.zhiban.rebuild.data.memory.RoomStagedMemoryCandidateStore
import com.zhiban.rebuild.foundation.MemoryScope
import com.zhiban.rebuild.foundation.Sensitivity
import com.zhiban.rebuild.runtime.context.EmbeddingGateway
import com.zhiban.rebuild.runtime.context.EmbeddingIndex
import com.zhiban.rebuild.runtime.context.EmbeddingInput
import com.zhiban.rebuild.runtime.context.EmbeddingSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryAtomicCommitStoreTest {
    private lateinit var database: AgentDatabase
    private lateinit var store: MemoryAtomicStore
    private var now = 1_000L

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AgentDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = MemoryAtomicStore(database) { now++ }
    }

    @After fun tearDown() = database.close()

    @Test fun concurrentCommitConsumesCandidateOnceAndDuplicateIsSideEffectFree() = runBlocking {
        store.ensureNamespace(namespace())
        val candidateId = stage()
        val request = commitRequest(candidateId)

        val results = listOf(
            async(Dispatchers.IO) { store.commit(request) },
            async(Dispatchers.IO) { store.commit(request) },
        ).awaitAll()

        assertEquals(1, results.count { it.created })
        assertEquals(1, results.count { !it.created })
        assertEquals("CONSUMED", database.stagedMemoryCandidateDao().find(candidateId)?.state)
        assertEquals(1, scalar("SELECT COUNT(*) FROM memory_records"))
        assertEquals(1, scalar("SELECT COUNT(*) FROM memory_commit_receipts"))
        assertEquals(1, scalar("SELECT COUNT(*) FROM memory_events"))
        assertEquals(2, scalar("SELECT COUNT(*) FROM memory_index_outbox"))
        assertEquals(listOf("value"), store.recall("namespace-1").records.map { it.objectText })
        val search = MemorySearch(database, clock = { now++ }).search(
            MemorySearchQuery(
                "namespace-1",
                "owner-1",
                "profile-1",
                "value",
                limit = 10,
                tokenBudget = 1_000,
            ),
        )
        assertEquals(listOf("value"), search.items.map { it.canonicalText })
        assertTrue(search.semanticSearchDegraded)
        assertTrue(
            runCatching {
                MemorySearch(database, clock = { now++ }).search(
                    MemorySearchQuery("namespace-1", "wrong-owner", "profile-1", "value", 10, 1_000),
                )
            }.isFailure,
        )
    }

    @Test fun ftsFailureKeepsExactResultsAndReportsFixedDegradationReason() = runBlocking {
        store.ensureNamespace(namespace())
        val candidateId = stage("fts-degradation")
        store.commit(commitRequest(candidateId))
        database.openHelper.writableDatabase.execSQL("DROP TABLE memory_fts")

        val search = MemorySearch(database, clock = { now++ }).search(
            MemorySearchQuery(
                "namespace-1",
                "owner-1",
                "profile-1",
                "value",
                limit = 10,
                tokenBudget = 1_000,
            ),
        )

        assertEquals(listOf("value"), search.items.map { it.canonicalText })
        assertEquals(listOf("memory_fts:failure"), search.degradationReasons)
    }

    @Test fun naturalChineseQuestionRecallsMemoryByMeaningfulSubstring() = runBlocking {
        store.ensureNamespace(namespace())
        val memoryText = "张三在知伴科技负责数据库项目"
        val candidateId = stage("chinese-recall", memoryText)
        store.commit(commitRequest(candidateId, memoryText))

        val search = MemorySearch(database, clock = { now++ }).search(
            MemorySearchQuery(
                "namespace-1",
                "owner-1",
                "profile-1",
                "做数据库的是谁",
                limit = 10,
                tokenBudget = 1_000,
            ),
        )

        assertEquals(listOf(memoryText), search.items.map { it.canonicalText })
    }

    @Test fun semanticQuestionRecallsMemoryWithoutLiteralOverlap() = runBlocking {
        store.ensureNamespace(namespace())
        val memoryText = "用户喜欢手冲咖啡"
        val candidateId = stage("semantic-recall", memoryText)
        store.commit(commitRequest(candidateId, memoryText))
        val gateway = SameMeaningEmbeddingGateway()
        assertEquals(1, EmbeddingIndex(database, gateway) { now++ }.backfillBatch())

        val search = MemorySearch(database, { now++ }, gateway).search(
            MemorySearchQuery(
                "namespace-1",
                "owner-1",
                "profile-1",
                "常喝什么饮料",
                limit = 10,
                tokenBudget = 1_000,
            ),
        )

        assertEquals(listOf(memoryText), search.items.map { it.canonicalText })
        assertEquals(0.9, search.items.single().score, 0.0)
        assertFalse(search.semanticSearchDegraded)
        assertTrue(search.degradationReasons.isEmpty())
    }

    @Test fun recallReturnsNewestHighConfidenceMemoriesFirst() = runBlocking {
        store.ensureNamespace(namespace())
        val olderId = stage("older", "较早记忆")
        store.commit(commitRequest(olderId, "较早记忆").copy(logicalMemoryId = "a-older"))
        now += 10_000
        val newerId = stage("newer", "最新记忆")
        store.commit(commitRequest(newerId, "最新记忆").copy(logicalMemoryId = "z-newer"))

        assertEquals(
            listOf("最新记忆", "较早记忆"),
            store.recall("namespace-1").records.map { it.canonicalText },
        )
    }

    @Test fun reversibleUpsertCreatesUpdatesAndRestoresThePreviousVersion() = runBlocking {
        store.ensureNamespace(namespace())

        val created = store.upsertReversible(upsertRequest("喜欢清淡口味"))
        val updated = store.upsertReversible(upsertRequest("现在喜欢微辣"))

        assertTrue(created.created)
        assertFalse(updated.created)
        assertEquals(2L, updated.recordVersion)
        assertEquals(listOf("现在喜欢微辣"), store.recall("namespace-1").records.map { it.canonicalText })
        assertTrue(
            store.undoReversible(
                "namespace-1",
                "auto-user-food",
                updated.memoryId,
                updated.recordVersion,
                updated.afterDigest,
                previousVersion = 1,
            ),
        )
        assertEquals(listOf("喜欢清淡口味"), store.recall("namespace-1").records.map { it.canonicalText })
        assertEquals(1, scalar("SELECT COUNT(*) FROM memory_records WHERE logicalMemoryId='auto-user-food'"))
    }

    @Test fun reversibleUpsertIsIdempotentAndNeverOverwritesANewerEditDuringUndo() = runBlocking {
        store.ensureNamespace(namespace())
        val first = store.upsertReversible(upsertRequest("偏好简洁回答"))
        val duplicate = store.upsertReversible(upsertRequest("偏好简洁回答"))
        val newer = store.upsertReversible(upsertRequest("偏好先给结论再解释"))

        assertFalse(duplicate.changed)
        assertFalse(
            store.undoReversible(
                "namespace-1",
                "auto-user-food",
                first.memoryId,
                first.recordVersion,
                first.afterDigest,
                previousVersion = null,
            ),
        )
        assertEquals(listOf("偏好先给结论再解释"), store.recall("namespace-1").records.map { it.canonicalText })
        assertEquals(2L, newer.recordVersion)
    }

    @Test fun reversibleUpsertFailureRollsBackEveryProjection() = runBlocking {
        store.ensureNamespace(namespace())
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER abort_auto_memory BEFORE INSERT ON memory_index_outbox BEGIN SELECT RAISE(ABORT, 'simulated crash'); END",
        )

        assertTrue(runCatching { store.upsertReversible(upsertRequest("必须完整回滚")) }.isFailure)
        assertEquals(0, scalar("SELECT COUNT(*) FROM memory_records WHERE logicalMemoryId='auto-user-food'"))
        assertEquals(0, scalar("SELECT COUNT(*) FROM memory_current_versions WHERE logicalMemoryId='auto-user-food'"))
        assertEquals(0, scalar("SELECT COUNT(*) FROM memory_fts"))
    }

    @Test fun commitFailureRollsBackRecordReceiptEventOutboxAndCandidateConsumption() = runBlocking {
        store.ensureNamespace(namespace())
        val candidateId = stage()
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER abort_memory_commit BEFORE INSERT ON memory_events BEGIN SELECT RAISE(ABORT, 'simulated crash'); END",
        )

        assertTrue(runCatching { store.commit(commitRequest(candidateId)) }.isFailure)

        assertEquals("APPROVED", database.stagedMemoryCandidateDao().find(candidateId)?.state)
        assertEquals(0, scalar("SELECT COUNT(*) FROM memory_records"))
        assertEquals(0, scalar("SELECT COUNT(*) FROM memory_commit_receipts"))
        assertEquals(0, scalar("SELECT COUNT(*) FROM memory_index_outbox"))
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER abort_memory_commit")
        assertTrue(store.commit(commitRequest(candidateId)).created)
        assertFalse(MemoryAtomicStore(database) { now++ }.commit(commitRequest(candidateId)).created)
    }

    @Test fun crossScopeAndTamperedCanonicalSourceOrApprovalAreRejectedWithoutWrites() = runBlocking {
        store.ensureNamespace(namespace())
        val candidateId = stage()
        val valid = commitRequest(candidateId)

        val attacks = listOf(
            valid.copy(namespaceId = "other-namespace"),
            valid.copy(canonicalText = "tampered"),
            valid.copy(canonicalDigest = digest("tampered")),
            valid.copy(sourceSetDigest = digest("tampered-source")),
            valid.copy(approvalRef = "forged-approval"),
        )
        store.ensureNamespace(namespace().copy(namespaceId = "other-namespace", scopeId = "user-2"))
        attacks.forEach { assertTrue(runCatching { store.commit(it) }.isFailure) }

        assertEquals("APPROVED", database.stagedMemoryCandidateDao().find(candidateId)?.state)
        assertEquals(0, scalar("SELECT COUNT(*) FROM memory_records"))
        assertEquals(0, scalar("SELECT COUNT(*) FROM memory_commit_receipts"))
        assertEquals(0, scalar("SELECT COUNT(*) FROM memory_events"))
        assertEquals(0, scalar("SELECT COUNT(*) FROM memory_index_outbox"))
    }

    @Test fun eachCriticalCommitWriteFailureRollsBackEverySideEffect() = runBlocking {
        val guardedWrites = listOf(
            "INSERT" to "memory_records",
            "INSERT" to "memory_current_versions",
            "INSERT" to "memory_evidence",
            "INSERT" to "memory_commit_receipts",
            "INSERT" to "memory_events",
            "INSERT" to "memory_index_outbox",
            "UPDATE" to "staged_memory_candidates",
        )
        guardedWrites.forEachIndexed { index, (operation, table) ->
            val namespaceId = "namespace-crash-$index"
            store.ensureNamespace(namespace().copy(namespaceId = namespaceId, profileId = "profile-$index"))
            val candidateId = stage("candidate-crash-$index")
            database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER abort_commit_$index BEFORE $operation ON $table BEGIN SELECT RAISE(ABORT, 'simulated crash'); END",
            )

            assertTrue(
                runCatching {
                    store.commit(commitRequest(candidateId).copy(namespaceId = namespaceId))
                }.isFailure,
            )

            assertEquals("APPROVED", database.stagedMemoryCandidateDao().find(candidateId)?.state)
            assertEquals(0, scalar("SELECT COUNT(*) FROM memory_records WHERE namespaceId='$namespaceId'"))
            assertEquals(0, scalar("SELECT COUNT(*) FROM memory_commit_receipts WHERE namespaceId='$namespaceId'"))
            assertEquals(0, scalar("SELECT COUNT(*) FROM memory_events WHERE namespaceId='$namespaceId'"))
            assertEquals(0, scalar("SELECT COUNT(*) FROM memory_index_outbox WHERE namespaceId='$namespaceId'"))
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER abort_commit_$index")
        }
    }

    @Test fun tombstoneGenerationBlocksRecallAcrossLateAckAndRestart() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "memory-delete-${System.nanoTime()}.db"
        database.close()
        context.deleteDatabase(name)
        database = Room.databaseBuilder(context, AgentDatabase::class.java, name)
            .allowMainThreadQueries().build()
        store = MemoryAtomicStore(database) { now++ }
        store.ensureNamespace(namespace())
        val candidateId = stage()
        store.commit(commitRequest(candidateId))

        assertEquals(
            listOf("memory:memory-$candidateId"),
            com.zhiban.rebuild.data.facts.FactIndex(database)
                .search("value", now, 10).map { it.factId },
        )

        val deletion = store.delete("namespace-1", "logical-1", "delete-digest")

        assertEquals(1L, deletion.generation)
        assertEquals(0, scalar("SELECT COUNT(*) FROM memory_records WHERE namespaceId='namespace-1'"))
        assertEquals(0, scalar("SELECT COUNT(*) FROM memory_evidence WHERE namespaceId='namespace-1'"))
        assertEquals(0, scalar("SELECT COUNT(*) FROM memory_fts WHERE namespaceId='namespace-1'"))
        assertEquals(0, scalar("SELECT COUNT(*) FROM memory_commit_receipts WHERE namespaceId='namespace-1'"))
        assertEquals(0, scalar("SELECT COUNT(*) FROM memory_events WHERE namespaceId='namespace-1'"))
        assertTrue(com.zhiban.rebuild.data.facts.FactIndex(database).search("value", now, 10).isEmpty())
        assertTrue(store.recall("namespace-1").records.isEmpty())
        assertFalse(store.ackDeletion("namespace-1", "logical-1", deletion.generation - 1, "FTS"))
        assertTrue(store.recall("namespace-1").records.isEmpty())
        database.close()
        database = Room.databaseBuilder(context, AgentDatabase::class.java, name)
            .allowMainThreadQueries().build()
        store = MemoryAtomicStore(database) { now++ }
        assertTrue(store.recall("namespace-1").records.isEmpty())
        listOf("FTS", "CACHE", "EMBEDDING").forEach {
            assertTrue(store.ackDeletion("namespace-1", "logical-1", deletion.generation, it))
        }
        assertTrue(store.recall("namespace-1").records.isEmpty())
        assertEquals(
            "ACKED",
            scalarText("SELECT barrierState FROM memory_tombstones WHERE logicalMemoryId='logical-1'"),
        )
        context.deleteDatabase(name)
        Unit
    }

    @Test fun deletionCriticalWriteFailuresRollbackGenerationBarrierAndContent() = runBlocking {
        val guardedOperations = listOf(
            "tombstone" to
                "CREATE TRIGGER abort_delete_0 BEFORE INSERT ON memory_tombstones BEGIN SELECT RAISE(ABORT, 'simulated crash'); END",
            "outbox" to
                "CREATE TRIGGER abort_delete_1 BEFORE INSERT ON memory_deletion_outbox BEGIN SELECT RAISE(ABORT, 'simulated crash'); END",
            "record" to
                "CREATE TRIGGER abort_delete_2 BEFORE DELETE ON memory_records BEGIN SELECT RAISE(ABORT, 'simulated crash'); END",
        )
        guardedOperations.forEachIndexed { index, (_, triggerSql) ->
            val namespaceId = "namespace-delete-crash-$index"
            store.ensureNamespace(namespace().copy(namespaceId = namespaceId, profileId = "delete-profile-$index"))
            val candidateId = stage("delete-crash-$index")
            store.commit(commitRequest(candidateId).copy(namespaceId = namespaceId, logicalMemoryId = "logical-$index"))
            database.openHelper.writableDatabase.execSQL(triggerSql)

            assertTrue(runCatching { store.delete(namespaceId, "logical-$index", "delete-$index") }.isFailure)

            assertEquals(0L, store.recall(namespaceId).generation)
            assertEquals(1, store.recall(namespaceId).records.size)
            assertEquals(1, scalar("SELECT COUNT(*) FROM memory_records WHERE namespaceId='$namespaceId'"))
            assertEquals(1, scalar("SELECT COUNT(*) FROM memory_evidence WHERE namespaceId='$namespaceId'"))
            assertEquals(0, scalar("SELECT COUNT(*) FROM memory_tombstones WHERE namespaceId='$namespaceId'"))
            assertEquals(0, scalar("SELECT COUNT(*) FROM memory_deletion_outbox WHERE namespaceId='$namespaceId'"))
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER abort_delete_$index")
        }
    }

    @Test fun memoryBecomesDormantAfter180DaysWithoutBeingDeleted() = runBlocking {
        store.ensureNamespace(namespace())
        val candidateId = stage("dormancy")
        store.commit(commitRequest(candidateId))
        assertEquals(1, store.recall("namespace-1").records.size)

        now += 181L * 24 * 60 * 60 * 1_000

        assertEquals(1, store.applyDormancyPolicy())
        assertTrue(store.recall("namespace-1").records.isEmpty())
        assertEquals("DORMANT", scalarText("SELECT status FROM memory_records WHERE memoryId='memory-$candidateId'"))
        assertEquals(1, scalar("SELECT COUNT(*) FROM memory_records WHERE memoryId='memory-$candidateId'"))
    }

    private suspend fun stage(idHint: String = "candidate", content: String = "value"): String {
        val stagedStore = RoomStagedMemoryCandidateStore(database)
        val candidate = stagedStore.stage(
            scope = MemoryScope.PERSON,
            scopeId = "user-1",
            content = "  $content  ",
            sourceIds = listOf("source-1"),
            sensitivity = Sensitivity.PERSONAL,
            nowEpochMs = now++,
            ttlMs = 60_000,
        )
        val approvalRef = "explicit-remember-$idHint-${candidate.id}"
        assertEquals(ApprovalWriteResult.APPROVED, stagedStore.approve(candidate.id, approvalRef, 0, now++))
        return candidate.id
    }

    private fun namespace() = MemoryNamespaceEntity(
        "namespace-1", "owner-1", "profile-1", "PERSON", "user-1", "ACTIVE", 0, 0, now++,
    )

    private fun upsertRequest(content: String) = MemoryUpsertRequest(
        namespaceId = "namespace-1",
        logicalMemoryId = "auto-user-food",
        memoryType = "PREFERENCE",
        subjectKey = "user",
        predicateKey = "food_preference",
        canonicalText = content,
        sensitivity = "PERSONAL",
        confidence = 0.99,
        sourceRef = "runtime:test-turn",
    )

    private suspend fun commitRequest(candidateId: String, canonicalText: String = "value") = MemoryCommitRequest(
        namespaceId = "namespace-1",
        candidateId = candidateId,
        approvalRef = requireNotNull(database.stagedMemoryCandidateDao().find(candidateId)?.approvalRef),
        expectedCandidateRevision = 1,
        memoryId = "memory-$candidateId",
        logicalMemoryId = "logical-1",
        memoryType = "SEMANTIC",
        subjectKey = "subject",
        predicateKey = "predicate",
        canonicalText = canonicalText,
        canonicalDigest = digest(canonicalText),
        sourceSetDigest = digest("source-1"),
    )

    private fun scalar(sql: String): Int = database.openHelper.readableDatabase.query(sql).use {
        check(it.moveToFirst())
        it.getInt(0)
    }

    private fun scalarText(sql: String): String = database.openHelper.readableDatabase.query(sql).use {
        check(it.moveToFirst())
        it.getString(0)
    }

    private fun digest(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private class SameMeaningEmbeddingGateway : EmbeddingGateway {
        private val space = EmbeddingSpace("test", "meaning", 8)

        override suspend fun activeSpace() = space

        override suspend fun embed(inputs: List<EmbeddingInput>, space: EmbeddingSpace) = inputs.map { FloatArray(8).also { vector -> vector[0] = 1f } }
    }
}
