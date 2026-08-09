package com.zhiban.rebuild.data.contact

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.agent.ContactAgentDataRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactEnrichmentConfirmTest {
    private lateinit var db: AgentDatabase
    private lateinit var repository: ContactAgentDataRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AgentDatabase::class.java)
            .addCallback(AgentDatabase.CALLBACK).allowMainThreadQueries().build()
        repository = ContactAgentDataRepository(db)
    }

    @After fun tearDown() = db.close()

    @Test fun confirmAppliesScalarFieldToBlankProfileAndResolvesCandidate() = runBlocking {
        db.contactDao().insert(contact(company = null, title = null))
        val candidate = candidate(fieldKind = "EMPLOYMENT", value = """{"title":"采购经理","company":"星河科技"}""")
        repository.stageContactEnrichmentCandidate(candidate)

        val applied = repository.applyContactEnrichmentCandidate(candidate)

        assertTrue(applied)
        val updated = db.contactDao().findRawById("c1")!!
        assertEquals("采购经理", updated.title)
        assertEquals("星河科技", updated.company)
        assertTrue(db.contactKnowledgeDao().observePendingEnrichment("c1").first().isEmpty())
    }

    @Test fun confirmNeverOverwritesExistingNonBlankValue() = runBlocking {
        db.contactDao().insert(contact(company = "既有公司", title = null))
        val candidate = candidate(fieldKind = "EMPLOYMENT", value = """{"title":"采购经理","company":"新公司"}""")
        repository.stageContactEnrichmentCandidate(candidate)

        repository.applyContactEnrichmentCandidate(candidate)

        val updated = db.contactDao().findRawById("c1")!!
        assertEquals("既有公司", updated.company) // untouched
        assertEquals("采购经理", updated.title) // blank field filled
    }

    @Test fun confirmNonScalarFieldResolvesWithoutProfileWrite() = runBlocking {
        db.contactDao().insert(contact(company = null, title = null))
        val candidate = candidate(fieldKind = "ADDRESS", value = """{"formattedAddress":"上海市徐汇区"}""")
        repository.stageContactEnrichmentCandidate(candidate)

        val applied = repository.applyContactEnrichmentCandidate(candidate)

        assertFalse(applied) // no scalar profile field for ADDRESS
        assertTrue(db.contactKnowledgeDao().observePendingEnrichment("c1").first().isEmpty())
    }

    @Test fun rejectMarksCandidateDismissed() = runBlocking {
        db.contactDao().insert(contact(company = null, title = null))
        val candidate = candidate(fieldKind = "ORGANIZATION", value = """{"company":"星河科技"}""")
        repository.stageContactEnrichmentCandidate(candidate)

        val resolved = repository.resolveContactEnrichmentCandidate(candidate.candidateId, accepted = false)

        assertTrue(resolved)
        assertTrue(db.contactKnowledgeDao().observePendingEnrichment("c1").first().isEmpty())
        assertNull(db.contactDao().findRawById("c1")!!.company)
    }

    @Test fun purgeExpiredEnrichmentRemovesOnlyExpiredRows() = runBlocking {
        db.contactDao().insert(contact(company = null, title = null))
        val now = System.currentTimeMillis()
        repository.stageContactEnrichmentCandidate(candidate(id = "expired", expiresAt = now - 1))
        repository.stageContactEnrichmentCandidate(candidate(id = "fresh", expiresAt = now + 60_000))

        val purged = db.contactKnowledgeDao().purgeExpiredEnrichment(now)

        assertEquals(1, purged)
        val remaining = db.contactKnowledgeDao().observePendingEnrichment("c1").first()
        assertEquals(listOf("fresh"), remaining.map { it.candidateId })
    }

    private fun contact(company: String?, title: String?) = ContactEntity(
        "c1", "张三", "张三", null, null, null, company, title, "[]", "[]", null, null, "USER", null, 1, 2,
    )

    private fun candidate(
        id: String = "cand-1",
        fieldKind: String = "ORGANIZATION",
        value: String = "{}",
        expiresAt: Long = System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1_000,
    ) = ContactEnrichmentCandidateEntity(
        candidateId = id,
        contactId = "c1",
        providerId = "stepfun-llm",
        fieldKind = fieldKind,
        proposedValueJson = value,
        sourceRef = "llm:stepfun",
        confidence = 0.9,
        status = "PENDING",
        observedAtEpochMs = 1,
        expiresAtEpochMs = expiresAt,
        createdAtEpochMs = 1,
        updatedAtEpochMs = 1,
    )
}
