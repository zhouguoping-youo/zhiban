package com.zhiban.rebuild.runtime.context

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentMaintenanceDegradationTest {
    private lateinit var database: AgentDatabase

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AgentDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun close() = database.close()

    @Test fun embeddingFailureProducesFixedDegradationReason() = runTest {
        val result = AgentMaintenanceCoordinator(database, FailingEmbeddingGateway(IllegalStateException("private detail"))).run(10_000L)

        assertEquals(setOf("embedding_backfill:failure"), result.degradationReasons)
    }

    @Test fun embeddingCancellationIsPropagated() = runTest {
        var cancelled = false
        try {
            AgentMaintenanceCoordinator(database, FailingEmbeddingGateway(CancellationException("cancel"))).run(10_000L)
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    private class FailingEmbeddingGateway(private val failure: RuntimeException) : EmbeddingGateway {
        override suspend fun activeSpace(): EmbeddingSpace? = throw failure

        override suspend fun embed(inputs: List<EmbeddingInput>, space: EmbeddingSpace): List<FloatArray> = error("not reached")
    }
}
