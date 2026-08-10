package com.zhiban.rebuild.data.contact

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.contact.enrichment.CompanyEnrichmentCoordinator
import com.zhiban.rebuild.data.contact.enrichment.CompanyRegistryGateway
import com.zhiban.rebuild.data.contact.enrichment.CompanyRegistryMatch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompanyEnrichmentCoordinatorTest {
    private lateinit var database: AgentDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AgentDatabase::class.java,
        ).addCallback(AgentDatabase.CALLBACK).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun registryResultIsStagedWithoutChangingContactProfile() = runBlocking {
        database.contactDao().insert(contact(company = "星河科技"))
        val gateway = RecordingGateway()
        val subject = CompanyEnrichmentCoordinator(database, gateway)

        val result = subject.refresh(nowEpochMs = 100)

        assertEquals(listOf("星河科技"), gateway.queries)
        assertEquals(1, result.stagedCandidates)
        val candidate = database.contactKnowledgeDao().observePendingEnrichment("contact-1").first().single()
        assertEquals("company-registry:qichacha", candidate.providerId)
        assertEquals("ORGANIZATION", candidate.fieldKind)
        assertEquals("星河科技", database.contactDao().findRawById("contact-1")!!.company)
        assertNull(database.contactKnowledgeDao().findOrganization("not-created-before-confirmation"))
    }

    @Test
    fun activeCandidatePreventsRepeatedRegistryLookup() = runBlocking {
        database.contactDao().insert(contact(company = "星河科技"))
        val gateway = RecordingGateway()
        val subject = CompanyEnrichmentCoordinator(database, gateway)

        subject.refresh(nowEpochMs = 100)
        val second = subject.refresh(nowEpochMs = 101)

        assertEquals(1, gateway.queries.size)
        assertEquals(0, second.queriedCompanies)
        assertEquals(0, second.stagedCandidates)
    }

    private fun contact(company: String) = ContactEntity(
        contactId = "contact-1",
        displayName = "张三",
        normalizedName = "张三",
        phone = null,
        email = null,
        wechatId = null,
        company = company,
        title = null,
        aliasesJson = "[]",
        tagsJson = "[]",
        note = null,
        avatarUri = null,
        source = "SYSTEM_CONTACT",
        deletedAtEpochMs = null,
        createdAtEpochMs = 1,
        updatedAtEpochMs = 1,
    )

    private class RecordingGateway : CompanyRegistryGateway {
        val queries = mutableListOf<String>()
        override val isConfigured: Boolean = true

        override suspend fun search(companyHint: String): List<CompanyRegistryMatch> {
            queries += companyHint
            return listOf(
                CompanyRegistryMatch(
                    providerRecordId = "qcc-1",
                    canonicalName = "星河科技有限公司",
                    creditCode = "91310000TEST",
                    registrationStatus = "存续",
                    registeredAddress = "上海市徐汇区",
                    confidence = 0.96,
                    matchReasons = listOf("名称高度一致"),
                ),
            )
        }
    }
}
