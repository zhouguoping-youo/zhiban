package com.zhiban.rebuild.runtime.observability

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.store.RuntimeEventEntity
import com.zhiban.rebuild.data.store.RuntimeRunEntity
import com.zhiban.rebuild.data.store.RuntimeSessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentDiagnosticBundleServiceTest {
    private lateinit var context: Context
    private lateinit var database: AgentDatabase

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java)
            .addCallback(AgentDatabase.CALLBACK).allowMainThreadQueries().build()
    }

    @After fun tearDown() = database.close()

    @Test fun exportIsContentFreeEvenWhenEventPayloadContainsPrivateData() = runBlocking {
        database.runtimeSessionDao().insert(RuntimeSessionEntity("private-session", updatedAtEpochMs = 10))
        database.runtimeRunDao().insert(
            RuntimeRunEntity(
                "private-run",
                "private-session",
                1,
                "SUCCEEDED",
                budgetJson = "{}",
                createdAtEpochMs = 10,
                updatedAtEpochMs = 30,
            ),
        )
        database.runtimeEventDao().insert(
            RuntimeEventEntity(
                eventId = "event-1", schemaVersion = 1, eventType = "RunReceived",
                sessionId = "private-session", runId = "private-run", attemptId = null,
                sequence = 1, correlationId = "private-correlation", producerVersion = "test",
                payloadJson = "{\"text\":\"张三 13812345678 person@example.com\",\"api_key\":\"sk-CANARY-12345678\"}",
                createdAtEpochMs = 10, fencingEpoch = 1,
            ),
        )

        val file = AgentDiagnosticBundleService(context, AgentTraceService(database)).create(100)
        val exported = file.readText()
        assertTrue(exported.contains("CONTENT_FREE_REDACTED"))
        assertTrue(exported.contains("\"status\": \"SUCCEEDED\""))
        listOf(
            "张三",
            "13812345678",
            "person@example.com",
            "sk-CANARY",
            "private-run",
            "private-session",
            "private-correlation",
            "payloadJson",
        )
            .forEach { forbidden -> assertFalse("leaked $forbidden", exported.contains(forbidden)) }
    }
}
