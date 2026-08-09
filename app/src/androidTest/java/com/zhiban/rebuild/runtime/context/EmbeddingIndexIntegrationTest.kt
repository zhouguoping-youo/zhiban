package com.zhiban.rebuild.runtime.context

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmbeddingIndexIntegrationTest {
    private lateinit var database: AgentDatabase
    private var now = 10_000L

    @Before fun setup() {
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                AgentDatabase::class.java,
            )
                .allowMainThreadQueries().build()
    }

    @After fun close() = database.close()

    @Test fun backfillEnablesVectorPathAndFactMutationInvalidatesStaleVector() = runTest {
        val gateway = FakeEmbeddingGateway()
        val facts = FactIndex(database)
        facts.upsert(fact("fact:okr", "季度目标与关键结果复盘"))
        facts.upsert(fact("fact:travel", "年度旅行与酒店计划"))
        val index = EmbeddingIndex(database, gateway) { now }

        assertEquals("vector_skipped:rebuild_pending", index.search("OKR").degradation)
        assertEquals(2, index.backfillBatch())
        val vector = index.search("OKR")
        assertEquals("fact:okr", vector.candidates.first().id)
        assertEquals(null, vector.degradation)

        val pipeline = RoomContextRetrievalPipeline(
            database,
            clock = { now },
            embeddingGateway = gateway,
            pathTimeoutMs = 5_000,
        )
        val context = QueryContext(IntentLabel.GENERAL_WORK, .8, emptyList(), null, emptyList())
        val result = pipeline.retrieve("OKR", context, includeMemory = false)
        assertTrue(
            "items=${result.items}, degradation=${result.degradationPath}",
            result.items.any {
                it.candidate.id ==
                    "fact:okr" &&
                    RetrievalPath.VECTOR in it.contributingPaths
            },
        )
        assertFalse(result.degradationPath.any { it.startsWith("vector_skipped") })

        facts.upsert(fact("fact:okr", "季度目标已经改为客户成功"))
        assertEquals("vector_skipped:rebuild_pending", index.search("OKR").degradation)
        assertEquals(1, index.backfillBatch())
        assertEquals("fact:okr", index.search("OKR").candidates.first().id)
    }

    @Test fun providerSpaceSwitchDoesNotMixOldVectorsAndWaitsForCompleteRebuild() = runTest {
        val gateway = FakeEmbeddingGateway()
        FactIndex(database).upsert(fact("fact:okr", "季度目标"))
        val index = EmbeddingIndex(database, gateway) { now }
        index.backfillBatch()
        assertEquals(null, index.search("OKR").degradation)

        gateway.space = EmbeddingSpace("provider-b", "embed-v2", 8)
        assertEquals("vector_skipped:rebuild_pending", index.search("OKR").degradation)
        assertEquals(1, index.backfillBatch())
        assertEquals(null, index.search("OKR").degradation)
        assertEquals(1, database.embeddingVectorDao().active("provider-a", "embed-v1", 8, now, 10).size)
        assertEquals(1, database.embeddingVectorDao().active("provider-b", "embed-v2", 8, now, 10).size)
    }

    @Test fun sensitiveFactsAreNeverOfferedToEmbeddingGatewayOrCountedAsPending() = runTest {
        val gateway = FakeEmbeddingGateway()
        val facts = FactIndex(database)
        facts.upsert(fact("fact:company", "示例科技销售经理"))
        facts.upsert(fact("fact:relation", "张三与李四的关系边", sensitivity = "SENSITIVE"))
        val index = EmbeddingIndex(database, gateway) { now }

        assertEquals(1, index.backfillBatch())
        assertEquals(listOf("fact:company"), gateway.lastInputs.map { it.sourceId })
        assertTrue(gateway.lastInputs.none { it.sensitivity == Sensitivity.SENSITIVE })
        assertEquals(null, index.search("销售").degradation)
    }

    private fun fact(id: String, text: String, sensitivity: String = "PERSONAL") = FactEntity(
        id, "NOTE", text, null, "test", id, null, null, 1.0, sensitivity, "ACTIVE", -1, null, now, now++,
    )

    private class FakeEmbeddingGateway : EmbeddingGateway {
        var space = EmbeddingSpace("provider-a", "embed-v1", 8)
        var lastInputs: List<EmbeddingInput> = emptyList()
        override suspend fun activeSpace() = space
        override suspend fun embed(inputs: List<EmbeddingInput>, space: EmbeddingSpace): List<FloatArray> {
            lastInputs = inputs
            return inputs.map { input ->
                val text = input.text
                when {
                    text.contains("旅行") || text.contains("酒店") -> FloatArray(8).also { it[1] = 1f }
                    else -> FloatArray(8).also { it[0] = 1f }
                }
            }
        }
    }
}
