package com.zhiban.rebuild.runtime.store

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionConversationContextTest {
    private lateinit var database: AgentDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AgentDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun olderTurnsAreCompactedOnceAndLatestTurnsRemainVerbatim() = runBlocking {
        val sessionId = "session-context"
        database.runtimeSessionDao().insert(RuntimeSessionEntity(sessionId = sessionId, updatedAtEpochMs = 1))
        repeat(15) { index ->
            val runId = "run-$index"
            database.runtimeRunDao().insert(
                RuntimeRunEntity(
                    runId = runId,
                    sessionId = sessionId,
                    schemaVersion = 1,
                    status = "SUCCEEDED",
                    budgetJson = "{}",
                    createdAtEpochMs = index.toLong() + 1,
                    updatedAtEpochMs = index.toLong() + 1,
                ),
            )
            database.runtimeConversationTurnDao().insert(
                RuntimeConversationTurnEntity(
                    turnId = "turn-$index",
                    sessionId = sessionId,
                    runId = runId,
                    role = if (index % 2 == 0) "user" else "assistant",
                    content = "message-$index",
                    contentDigest = "digest-$index",
                    tokenEstimate = 2,
                    createdAtEpochMs = index.toLong() + 1,
                ),
            )
        }
        val store = RoomRuntimeStore(database, "test")

        val first = store.conversationContext(sessionId, "current")
        val second = store.conversationContext(sessionId, "current")

        assertEquals((3..14).map { "message-$it" }, first.recentTurns.map { it.content })
        assertTrue(first.summary.orEmpty().contains("message-0"))
        assertTrue(first.summary.orEmpty().contains("message-2"))
        assertFalse(first.summary.orEmpty().contains("message-3"))
        assertEquals(first.summary, second.summary)
    }
}
