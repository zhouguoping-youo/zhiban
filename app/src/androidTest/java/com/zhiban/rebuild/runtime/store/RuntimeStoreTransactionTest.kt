package com.zhiban.rebuild.runtime.store

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.foundation.sha256
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 三个 0 直接测试的事务热点(审计测试盲区3)的最小真库覆盖:命令受理/快照、工具执行记录、
 * 命令失败收容——全部走内存库真实事务路径。
 */
@RunWith(AndroidJUnit4::class)
class RuntimeStoreTransactionTest {
    private lateinit var database: AgentDatabase

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java)
            .addCallback(AgentDatabase.CALLBACK).allowMainThreadQueries().build()
    }

    @After fun tearDown() = database.close()

    @Test fun acceptExternalCommandAndProjectionSnapshotRoundTrip() = runBlocking {
        val store = RoomRuntimeStore(database, "test-producer")
        store.acceptStart(
            commandId = "cmd-1",
            sessionId = "session-1",
            runId = "run-1",
            payloadJson = """{"input":"你好"}""",
            nowEpochMs = 1_000L,
        )
        store.appendEvent(
            RuntimeEventDraft(
                eventId = "event-1",
                eventType = "RunStarted",
                sessionId = "session-1",
                runId = "run-1",
                attemptId = "run-1",
                causationId = "run-1",
                correlationId = "run-1",
                payloadJson = "{}",
                createdAtEpochMs = 1_000L,
            ),
            ownerId = "local-user",
            fencingEpoch = 0L,
            nowEpochMs = 1_000L,
        )

        val snapshot = store.projectionSnapshot("session-1", "ui")

        assertNotNull(snapshot)
        assertTrue(snapshot.currentRevision >= 1)
    }

    @Test fun recordToolSuccessThenToolResultRoundTrip() = runBlocking {
        val store = RoomRuntimeStore(database, "test-producer")
        store.acceptStart("cmd-2", "session-2", "run-2", """{"input":"你好"}""", 1_000L)
        val idempotencyKey = "key-1"
        store.recordToolSuccess(
            executionId = "exec-1",
            runId = "run-2",
            logicalStepId = "step-1",
            toolName = "calendar.schedule.search",
            toolSpecVersion = 1,
            canonicalInputDigest = sha256("input"),
            idempotencyKey = idempotencyKey,
            resultRef = "result-1",
            safeResultJson = """{"count":0}""",
            ownerId = "local-user",
            fencingEpoch = 0L,
            nowEpochMs = 1_000L,
        )

        val recorded = store.toolResult(idempotencyKey)

        assertNotNull(recorded)
        assertEquals("run-2", recorded!!.runId)
        assertEquals("SUCCEEDED", recorded.status)
        assertEquals("""{"count":0}""", recorded.safeResultJson)
    }

    @Test fun claimedCommandFailureIsContainedAndRecoverable() = runBlocking {
        val store = RoomRuntimeStore(database, "test-producer")
        store.acceptStart("cmd-3", "session-3", "run-3", """{"input":"你好"}""", 1_000L)
        val claimed = store.processClaimedCommand("cmd-3", "local-user", 0L, 2_000L)

        assertTrue(claimed)
        val snapshot = store.projectionSnapshot("session-3", "ui")
        assertNotNull(snapshot)

        val recovered = store.nextRecoverableLeaseExpiry(3_000L)
        assertNull(recovered) // 未过期租约不可恢复
    }
}
