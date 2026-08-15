package com.zhiban.rebuild.runtime.tool

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.runtime.governance.AutoWriteRepository
import com.zhiban.rebuild.runtime.kernel.PersistentRuntimeKernel
import com.zhiban.rebuild.runtime.kernel.RuntimeSignal
import com.zhiban.rebuild.runtime.store.RoomRuntimeStore
import com.zhiban.rebuild.runtime.store.RuntimeAttemptEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryUpsertAutoWriteTest {
    private lateinit var context: Context
    private lateinit var database: AgentDatabase
    private lateinit var store: RoomRuntimeStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java).allowMainThreadQueries().build()
        store = RoomRuntimeStore(database, "memory-upsert-test")
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun highConfidencePreferenceIsVisibleAndCanBeUndone() = runBlocking {
        val route = routeContext()
        val plan = plan(route)

        val result = MemoryUpsertDomainWriter(database, store).executeAuto(plan, route)

        assertEquals(
            listOf("回答时先给结论"),
            database.memoryPersistenceDao().recall(RoomMemoryToolExecutor.GLOBAL_NAMESPACE, 100).map { it.canonicalText },
        )
        val change = database.changeLogDao().listByRun(route.runId).single()
        assertNotNull(database.changeLogDao().findAutoWriteReceipt(change.changeId))
        assertTrue(result.safeResultJson.contains("undoAvailable"))
        assertTrue(AutoWriteRepository(database, context).undo(change.changeId, 101))
        assertTrue(database.memoryPersistenceDao().recall(RoomMemoryToolExecutor.GLOBAL_NAMESPACE, 102).isEmpty())
    }

    @Test
    fun lowConfidenceOrDirectIdentifierFallsBackToConfirmation() = runBlocking {
        val route = routeContext()
        val binding = MemoryUpsertToolBinding(
            RuntimeToolCatalog.production().requireRegistered(MemoryUpsertToolBinding.TOOL_NAME),
            store,
            MemoryUpsertDomainWriter(database, store),
        )

        val lowConfidence = binding.reversibleWriteReadiness(request(0.8, "回答时先给结论"), route)
        val directIdentifier = binding.reversibleWriteReadiness(request(0.99, "手机号是13800000000"), route)

        assertFalse(lowConfidence.ready)
        assertEquals("auto_write:evidence_insufficient", lowConfidence.reasonCode())
        assertFalse(directIdentifier.ready)
        assertEquals("auto_write:policy_rejected", directIdentifier.reasonCode())
    }

    private fun request(confidence: Double, content: String) = RuntimeToolCallRequest(
        providerCallId = "call-readiness-$confidence",
        name = MemoryUpsertToolBinding.TOOL_NAME,
        argumentsJson = buildJsonObject {
            put("content", content)
            put("memoryType", "PREFERENCE")
            put("subjectKey", "user")
            put("predicateKey", "response.style")
            put("sensitivity", "PERSONAL")
            put("evidenceSummary", "用户明确表达")
            put("confidence", confidence)
            put("sourceRef", "conversation:test")
        }.toString(),
    )

    private fun plan(route: RuntimeToolRouteContext) = buildJsonObject {
        put("toolName", MemoryUpsertToolBinding.TOOL_NAME)
        put("providerCallId", "call-memory-upsert")
        put("logicalStepId", "step-memory-upsert")
        put("proposalId", "proposal-memory-upsert")
        put("payloadRef", "memory-upsert-payload")
        put("revision", 1)
        put("canonicalInputDigest", sha256("memory-upsert-payload"))
        put("idempotencyKey", sha256("memory-upsert-idempotency"))
        put("runId", route.runId)
        put("attemptId", route.attemptId)
        put("logicalMemoryId", "logical-auto-response-style")
        put("content", "回答时先给结论")
        put("memoryType", "PREFERENCE")
        put("subjectKey", "user")
        put("predicateKey", "response.style")
        put("sensitivity", "PERSONAL")
        put("evidenceSummary", "用户明确表达")
        put("confidence", 0.99)
        put("sourceRef", "conversation:test")
    }

    private suspend fun routeContext(): RuntimeToolRouteContext {
        store.acceptStart("start-memory-upsert", "session-memory-upsert", "run-memory-upsert", "{}", 1)
        val lease = store.claimSession("session-memory-upsert", "owner", 2, 1_000)
        val kernel = PersistentRuntimeKernel(store)
        kernel.transition("run-memory-upsert", RuntimeSignal.BeginContext, "owner", lease.leaseEpoch, 3)
        kernel.transition("run-memory-upsert", RuntimeSignal.ContextReady, "owner", lease.leaseEpoch, 4)
        database.runtimeAttemptDao().insert(
            RuntimeAttemptEntity("attempt-memory-upsert", "run-memory-upsert", 1, "ACTIVE", 4, 4),
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE runtime_runs SET activeAttemptId = ? WHERE runId = ?",
            arrayOf("attempt-memory-upsert", "run-memory-upsert"),
        )
        return RuntimeToolRouteContext(
            "run-memory-upsert",
            "session-memory-upsert",
            "attempt-memory-upsert",
            "owner",
            lease.leaseEpoch,
            1,
            30,
        )
    }
}
