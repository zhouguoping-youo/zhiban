package com.zhiban.rebuild.data.contact.enrichment

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.OwnerContactLinkEntity
import com.zhiban.rebuild.data.contact.RelationshipPersonIds
import com.zhiban.rebuild.data.facts.FactEntity
import com.zhiban.rebuild.runtime.governance.ChangeUndoApplierImpl
import com.zhiban.rebuild.runtime.personalization.UserProfileStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RelationshipInferenceCoordinatorTest {
    private lateinit var context: Context
    private lateinit var database: AgentDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java)
            .addCallback(AgentDatabase.CALLBACK)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = database.close()

    private fun contact(id: String, name: String, company: String?) = ContactEntity(
        contactId = id,
        displayName = name,
        normalizedName = name.lowercase(),
        phone = null,
        email = null,
        wechatId = null,
        company = company,
        title = null,
        aliasesJson = "[]",
        tagsJson = "[]",
        note = null,
        avatarUri = null,
        source = "MANUAL",
        deletedAtEpochMs = null,
        createdAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_000L,
    )

    private fun interaction(contactId: String, text: String) = FactEntity(
        factId = "interaction-$contactId-$text",
        factType = "INTERACTION_SUMMARY",
        textContent = text,
        structuredDataJson = null,
        sourceType = "OBSERVED_NOTIFICATION",
        sourceRef = "source",
        contactId = contactId,
        skillId = null,
        confidence = 1.0,
        sensitivity = "PERSONAL",
        status = "ACTIVE",
        ttlDays = 90,
        expiresAtEpochMs = System.currentTimeMillis() + 90L * 24 * 60 * 60 * 1_000,
        createdAtEpochMs = System.currentTimeMillis(),
        updatedAtEpochMs = System.currentTimeMillis(),
    )

    @Test
    fun sameCompanyContactsGetAutoColleagueEdgesWithReceipt() = runBlocking {
        database.contactDao().insert(contact("owner", "我", "平凯星辰（北京）科技有限公司武汉分公司"))
        database.contactDao().insert(contact("c1", "李倩", "平凯星辰（北京）科技有限公司武汉分公司"))
        database.contactDao().insert(contact("c2", "王敏", "别家公司"))
        database.contactKnowledgeDao().upsertOwnerContactLink(
            OwnerContactLinkEntity(contactId = "owner", reason = "本人", userConfirmed = true, createdAtEpochMs = 1_000L, undoneAtEpochMs = null),
        )
        coordinator(null).processOnce()

        val edges = database.relationshipEdgeDao().touching(listOf(RelationshipPersonIds.SELF), 100)
        assertEquals(1, edges.size)
        assertEquals("c1", edges[0].toContactId)
        assertEquals("COLLEAGUE", edges[0].relationType)
        assertFalse(edges[0].userConfirmed)

        val receipt = database.changeLogDao().observeAutoWriteReceipts().first().single()
        assertEquals("RELATIONSHIP_INFERRED", receipt.presentationType)
        assertEquals("AVAILABLE", receipt.undoState)
        assertTrue(receipt.contentPreview.orEmpty().contains("同事"))

        // 幂等:再次扫描不重复写边。
        coordinator(null).processOnce()
        assertEquals(1, database.relationshipEdgeDao().touching(listOf(RelationshipPersonIds.SELF), 100).size)

        // 撤销:软停用,图谱不再显示。
        assertTrue(ChangeUndoApplierImpl(database).undoVisible(receipt.changeId, System.currentTimeMillis()))
        assertEquals(0, database.relationshipEdgeDao().touching(listOf(RelationshipPersonIds.SELF), 100).size)

        // 撤销后幂等键仍在,不会被重新写回。
        coordinator(null).processOnce()
        assertEquals(0, database.relationshipEdgeDao().touching(listOf(RelationshipPersonIds.SELF), 100).size)
    }

    @Test
    fun llmInferenceAutoWritesHighConfidenceAndSuggestsUncertain() = runBlocking {
        database.contactDao().insert(contact("c1", "周国平", null))
        database.factDao().upsert(interaction("c1", "微信互动 · 周国平 · 对方发来 · 最近提到：采购与报价"))
        database.factDao().upsert(interaction("c1", "微信互动 · 周国平 · 对方发来 · 最近提到：合同与付款"))

        coordinator(InferredRelationship("CUSTOMER", 0.93, "对方多次提到采购报价")).processOnce()

        val edges = database.relationshipEdgeDao().touching(listOf(RelationshipPersonIds.SELF), 100)
        assertEquals(1, edges.size)
        assertEquals("CUSTOMER", edges[0].relationType)
        assertEquals(1, database.changeLogDao().observeAutoWriteReceipts().first().size)
    }

    @Test
    fun uncertainInferenceBecomesRelationshipSuggestionCard() = runBlocking {
        database.contactDao().insert(contact("c1", "张三", null))
        database.factDao().upsert(interaction("c1", "微信互动 · 张三 · 对方发来 · 最近提到：好久不见"))

        coordinator(InferredRelationship("FRIEND", 0.7, "互动偏闲聊")).processOnce()

        assertTrue(database.relationshipEdgeDao().touching(listOf(RelationshipPersonIds.SELF), 100).isEmpty())
        val suggestions = database.contactKnowledgeDao().observePendingEnrichment("c1").first()
        assertEquals(1, suggestions.size)
        assertEquals("RELATIONSHIP", suggestions[0].fieldKind)
        assertTrue(suggestions[0].proposedValueJson.contains("FRIEND"))
        assertEquals("互动推断", suggestions[0].sourceRef)
        // 已付钱打终态标记:再次扫描不再重复抽。
        coordinator(InferredRelationship("FRIEND", 0.9, "x")).processOnce()
        assertEquals(1, database.contactKnowledgeDao().observePendingEnrichment("c1").first().size)
    }

    private fun coordinator(inferred: InferredRelationship?) = RelationshipInferenceCoordinator(
        database,
        RelationshipTypeExtraction { _, _, _ -> inferred },
        UserProfileStore(
            context,
            "test-profile-${System.currentTimeMillis()}",
            "test-avatar",
        ),
    )
}
