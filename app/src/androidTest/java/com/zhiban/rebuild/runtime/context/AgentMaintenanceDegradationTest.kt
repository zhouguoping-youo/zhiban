package com.zhiban.rebuild.runtime.context

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.agent.AgentRunEntity
import com.zhiban.rebuild.data.agent.MemoryEntity
import com.zhiban.rebuild.data.calendar.ScheduleReminderRegistrar
import com.zhiban.rebuild.data.completion.CompletionHandoff
import com.zhiban.rebuild.data.completion.ContactCompletionRepository
import com.zhiban.rebuild.data.completion.FakeOutreachGenerator
import com.zhiban.rebuild.data.config.AgentControlStore
import com.zhiban.rebuild.data.contact.CommonGroupRelationshipScanner
import com.zhiban.rebuild.data.interaction.SilentContactSuggestionScanner
import com.zhiban.rebuild.data.interaction.UnobservedReplySuggestionScanner
import com.zhiban.rebuild.data.suggestion.AgentSuggestionNotifier
import com.zhiban.rebuild.data.suggestion.AgentSuggestionRepository
import com.zhiban.rebuild.data.suggestion.ImportantDateSuggestionScanner
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
        val result = coordinator(FailingEmbeddingGateway(IllegalStateException("private detail"))).run(10_000L)

        assertEquals(setOf("embedding_backfill:failure"), result.degradationReasons)
    }

    @Test fun embeddingCancellationIsPropagated() = runTest {
        var cancelled = false
        try {
            coordinator(FailingEmbeddingGateway(CancellationException("cancel"))).run(10_000L)
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    @Test fun maintenanceRetiresLegacyRunsWithoutDeletingDetachedPreferences() = runTest {
        val now = 200L * 24 * 60 * 60 * 1_000
        database.agentRunDao().insert(AgentRunEntity("legacy-run", null, "SUCCEEDED", null, 1, null, null, 1L, 1L))
        database.memoryDao().insert(MemoryEntity("preference", "USER_PREFERENCE", "偏好简洁", "legacy-run", 1, 1L))
        database.memoryDao().insert(MemoryEntity("summary", "RUN_SUMMARY", "旧摘要", "legacy-run", 1, 1L))
        database.memoryDao().insert(MemoryEntity("orphan", "RUN_SUMMARY", "孤立摘要", null, 1, 1L))

        val result = coordinator(NoEmbeddingGateway).run(now)

        assertEquals(1, result.retiredLegacyRunsDeleted)
        assertEquals(1, result.retiredLegacyMemoriesDeleted)
        assertEquals(null, database.agentRunDao().findById("legacy-run"))
        assertEquals(null, database.memoryDao().findById("summary"))
        assertEquals(null, database.memoryDao().findById("orphan"))
        assertEquals(null, database.memoryDao().findById("preference")?.sourceRunId)
    }

    private class FailingEmbeddingGateway(private val failure: RuntimeException) : EmbeddingGateway {
        override suspend fun activeSpace(): EmbeddingSpace? = throw failure

        override suspend fun embed(inputs: List<EmbeddingInput>, space: EmbeddingSpace): List<FloatArray> = error("not reached")
    }

    private object NoEmbeddingGateway : EmbeddingGateway {
        override suspend fun activeSpace(): EmbeddingSpace? = null

        override suspend fun embed(inputs: List<EmbeddingInput>, space: EmbeddingSpace): List<FloatArray> = emptyList()
    }

    private fun coordinator(gateway: EmbeddingGateway): AgentMaintenanceCoordinator {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val controls = AgentControlStore(context, "maintenance_test_${System.nanoTime()}")
        val notifier = AgentSuggestionNotifier(context, controls)
        val suggestions = AgentSuggestionRepository(
            database,
            ContactCompletionRepository(database, CompletionHandoff { _, _, _ -> false }, FakeOutreachGenerator(), controls),
            ScheduleReminderRegistrar { _, _, _ -> },
            notifier,
        )
        return AgentMaintenanceCoordinator(
            database,
            gateway,
            notifier,
            SilentContactSuggestionScanner(database, controls, notifier),
            UnobservedReplySuggestionScanner(database, controls, notifier),
            ImportantDateSuggestionScanner(database, suggestions),
            CommonGroupRelationshipScanner(database),
        )
    }
}
