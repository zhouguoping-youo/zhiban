package com.zhiban.rebuild.runtime.context

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.agent.ContactAgentDataRepository
import com.zhiban.rebuild.data.contact.ContactEntity
import kotlinx.coroutines.runBlocking
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
                .addCallback(AgentDatabase.CALLBACK)
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
        assertEquals("vector_partial:rebuild_pending", index.search("OKR").degradation)
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

    @Test fun incompleteBackfillStillSearchesTheIndexedSubset() = runTest {
        val gateway = FakeEmbeddingGateway()
        FactIndex(database).upsert(fact("fact:okr", "季度目标"))
        FactIndex(database).upsert(fact("fact:travel", "年度旅行"))
        val index = EmbeddingIndex(database, gateway) { now }

        assertEquals(1, index.backfillBatch(limit = 1))
        val result = index.search("目标")

        assertEquals("vector_partial:rebuild_pending", result.degradation)
        assertEquals(1, result.candidates.size)
    }

    @Test fun confirmedOwnerEmploymentIsAlwaysAvailableToAgentContext() = runBlocking {
        ContactAgentDataRepository(database).saveOwnerCurrentEmployment("知伴科技有限公司", "产品负责人", now)
        assertEquals(
            1,
            database.contactIntelligenceDao().listConfirmedOwnerEmployments(
                com.zhiban.rebuild.data.contact.RelationshipPersonIds.SELF,
            ).size,
        )
        val pipeline = RoomContextRetrievalPipeline(database, clock = { now }, pathTimeoutMs = 5_000)
        val context = QueryContext(IntentLabel.GENERAL_WORK, .8, emptyList(), null, emptyList())

        val result = pipeline.retrieve(
            inputText = "帮我整理今天的工作",
            queryContext = context,
            includeMemory = false,
            allowRemoteVector = false,
        )

        val employment = result.items.singleOrNull { it.candidate.sourceKind == "owner_employment" }?.candidate
            ?: error("owner employment missing: structured=${result.structuredCandidateCount}, degradation=${result.degradationPath}, items=${result.items}")
        assertTrue(employment.summary.contains("知伴科技有限公司"))
        assertTrue(employment.summary.contains("产品负责人"))
        assertEquals(Sensitivity.PERSONAL, employment.sensitivity)
    }

    @Test fun ownerEmploymentContextIncludesCurrentAndPastCompanies() = runBlocking {
        val contacts = ContactAgentDataRepository(database)
        contacts.saveOwnerCurrentEmployment("上一家公司", "销售", now++)
        contacts.saveOwnerCurrentEmployment("现在的公司", "负责人", now++)
        val pipeline = RoomContextRetrievalPipeline(database, clock = { now }, pathTimeoutMs = 5_000)

        val result = pipeline.retrieve(
            inputText = "哪些人是我的现同事和前同事",
            queryContext = QueryContext(IntentLabel.CONTACT_QUERY, .9, emptyList(), null, emptyList()),
            includeMemory = false,
            allowRemoteVector = false,
        )

        val summaries = result.items.filter { it.candidate.sourceKind == "owner_employment" }
            .map { it.candidate.summary }
        assertTrue(summaries.any { it.contains("当前任职") && it.contains("现在的公司") })
        assertTrue(summaries.any { it.contains("过往任职") && it.contains("上一家公司") })
    }

    @Test fun naturalChineseQuestionRecallsContactByCompanyAndNote() = runBlocking {
        database.contactDao().insert(
            ContactEntity(
                contactId = "contact-database-customer",
                displayName = "张三",
                normalizedName = "张三",
                phone = null,
                email = null,
                wechatId = null,
                company = "银河数据库科技有限公司",
                title = "采购负责人",
                aliasesJson = "[]",
                tagsJson = "[\"客户\"]",
                note = "负责数据库采购项目",
                avatarUri = null,
                source = "USER",
                deletedAtEpochMs = null,
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
            ),
        )
        val pipeline = RoomContextRetrievalPipeline(database, clock = { now }, pathTimeoutMs = 5_000)

        val result = pipeline.retrieve(
            inputText = "做数据库的那家客户是谁",
            queryContext = QueryContext(IntentLabel.CONTACT_QUERY, .9, emptyList(), null, emptyList()),
            includeMemory = false,
            allowRemoteVector = false,
        )

        assertTrue(
            "items=${result.items}, degradation=${result.degradationPath}",
            result.items.any { it.candidate.sourceRef == "contact-database-customer" },
        )
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

    @Test fun legacyNormalFactsAreEmbeddedAsPersonalAndIndexed() = runTest {
        val gateway = FakeEmbeddingGateway()
        FactIndex(database).upsert(fact("fact:legacy", "季度复盘与目标客户梳理", sensitivity = "NORMAL"))
        val index = EmbeddingIndex(database, gateway) { now }

        assertEquals(1, index.backfillBatch())

        assertEquals(Sensitivity.PERSONAL, gateway.lastInputs.single().sensitivity)
        val result = index.search("复盘")
        assertEquals(null, result.degradation)
        assertEquals("fact:legacy", result.candidates.first().id)
    }

    @Test fun oneBlockedFactDoesNotAbortTheRestOfTheBatch() = runTest {
        val gateway = FakeEmbeddingGateway()
        gateway.blockedSourceId = "fact:blocked"
        val facts = FactIndex(database)
        facts.upsert(fact("fact:good", "年度复盘"))
        facts.upsert(fact("fact:blocked", "备注里写了电话13812345678"))
        val index = EmbeddingIndex(database, gateway) { now }

        assertEquals(1, index.backfillBatch())

        val result = index.search("复盘")
        assertEquals("vector_partial:rebuild_pending", result.degradation)
        assertEquals(listOf("fact:good"), result.candidates.map { it.id })
    }

    private fun fact(id: String, text: String, sensitivity: String = "PERSONAL") = FactEntity(
        id, "NOTE", text, null, "test", id, null, null, 1.0, sensitivity, "ACTIVE", -1, null, now, now++,
    )

    private class FakeEmbeddingGateway : EmbeddingGateway {
        var space = EmbeddingSpace("provider-a", "embed-v1", 8)
        var lastInputs: List<EmbeddingInput> = emptyList()
        var blockedSourceId: String? = null
        override suspend fun activeSpace() = space
        override suspend fun embed(inputs: List<EmbeddingInput>, space: EmbeddingSpace): List<FloatArray> {
            lastInputs = inputs
            return inputs.map { input ->
                if (input.sourceId == blockedSourceId) error("EMBEDDING_SENSITIVE_INPUT_BLOCKED")
                val text = input.text
                when {
                    text.contains("旅行") || text.contains("酒店") -> FloatArray(8).also { it[1] = 1f }
                    else -> FloatArray(8).also { it[0] = 1f }
                }
            }
        }
    }
}
