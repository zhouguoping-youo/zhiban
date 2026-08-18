package com.zhiban.rebuild.runtime.store

import com.zhiban.rebuild.data.store.RuntimeConversationTurnEntity
import com.zhiban.rebuild.data.store.RuntimeRunEntity
import com.zhiban.rebuild.data.store.RuntimeSessionEntity

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.agent.ToolAuditEntity
import com.zhiban.rebuild.data.autowrite.ChangeLogEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationHistoryGatewayTest {
    private lateinit var database: AgentDatabase

    @Before fun setup() {
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                AgentDatabase::class.java,
            )
                .allowMainThreadQueries().build()
    }

    @After fun close() = database.close()

    @Test fun listsLatestUserPreviewAndDeleteCascadesSession() = runBlocking {
        insertConversation("older", "第一段对话", 10)
        insertConversation("latest", "最近一段对话", 20)
        val gateway = RoomConversationHistoryGateway(database)

        val summaries = gateway.list()

        assertEquals(listOf("latest", "older"), summaries.map { it.sessionId })
        assertEquals("最近一段对话", summaries.first().preview)
        assertTrue(gateway.delete("latest"))
        assertEquals(listOf("older"), gateway.list().map { it.sessionId })
        assertEquals(null, database.runtimeSessionDao().find("latest"))
    }

    @Test fun realtimeExchangeIsPersistedAsCompletedRuntimeConversation() = runBlocking {
        val gateway = RoomConversationHistoryGateway(database)

        assertTrue(gateway.recordRealtimeExchange("voice-session", "exchange-12345678", "帮我安排明天", "好的，我来帮你处理。"))
        assertTrue(gateway.recordRealtimeExchange("voice-session", "exchange-12345678", "帮我安排明天", "好的，我来帮你处理。"))

        val summary = gateway.list().single()
        assertEquals("voice-session", summary.sessionId)
        assertEquals("帮我安排明天", summary.preview)
        val turns = database.runtimeConversationTurnDao().recent("voice-session", "none", 10)
        assertEquals(listOf("user", "assistant"), turns.map { it.role })
        assertEquals(listOf("帮我安排明天", "好的，我来帮你处理。"), turns.map { it.content })
        val visibleTurns = gateway.observeTurns("voice-session").first()
        assertEquals(
            listOf("turn-realtime-exchange-12345678-user", "turn-realtime-exchange-12345678-assistant"),
            visibleTurns.map { it.turnId },
        )
        val events = database.runtimeEventDao().listAfter("voice-session", 0)
        assertEquals(listOf("AssistantDelta", "RunCompleted"), events.map { it.eventType })
        assertEquals(3L, database.runtimeSessionDao().find("voice-session")?.nextSequence)
    }

    @Test fun deleteScrubsConversationLinkedAuditPayloadsBeforeRuntimeCascade() = runBlocking {
        insertConversation("private", "客户秘密", 30)
        val runId = "run-private"
        database.changeLogDao().insert(
            ChangeLogEntity(
                "change-private", runId, "calendar.schedule.update", "key-private", "CALENDAR", "schedule-1",
                "UPDATE", "before", "after", "{\"title\":\"客户秘密\"}", "AVAILABLE", 30, null,
            ),
        )
        database.toolAuditDao().insert(
            ToolAuditEntity(
                "audit-private", null, "subject", "call", "calendar.schedule.update", "audit-key-private",
                "arguments", runId, null, null, null, null, 1, "SUCCEEDED", "{\"summary\":\"客户秘密\"}", null, 30, 30,
            ),
        )

        assertTrue(RoomConversationHistoryGateway(database).delete("private"))

        assertEquals(null, database.runtimeSessionDao().find("private"))
        assertEquals("EXPIRED", database.changeLogDao().find("change-private")?.undoState)
        assertEquals("{}", database.changeLogDao().find("change-private")?.inversePayloadJson)
        assertEquals(null, database.toolAuditDao().findByIdempotencyKey("audit-key-private")?.resultJson)
    }

    private suspend fun insertConversation(sessionId: String, content: String, time: Long) {
        val runId = "run-$sessionId"
        database.runtimeSessionDao().insert(RuntimeSessionEntity(sessionId = sessionId, updatedAtEpochMs = time))
        database.runtimeRunDao().insert(
            RuntimeRunEntity(
                runId,
                sessionId,
                1,
                "SUCCEEDED",
                budgetJson = "{}",
                createdAtEpochMs = time,
                updatedAtEpochMs = time,
            ),
        )
        database.runtimeConversationTurnDao().insert(
            RuntimeConversationTurnEntity(
                "turn-$sessionId",
                sessionId,
                runId,
                "user",
                content,
                "digest",
                4,
                time,
            ),
        )
    }
}
