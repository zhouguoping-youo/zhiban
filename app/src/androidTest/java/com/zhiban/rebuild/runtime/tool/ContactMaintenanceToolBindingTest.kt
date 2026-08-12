package com.zhiban.rebuild.runtime.tool

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.OwnerContactLinkEntity
import com.zhiban.rebuild.data.contact.PersonEmploymentEpisodeEntity
import com.zhiban.rebuild.data.contact.PersonEntity
import com.zhiban.rebuild.data.contact.SourceIdentityEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactMaintenanceToolBindingTest {
    @Test
    fun readOnlyToolReturnsRealIssuesWithoutContactChannels() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java).build()
        try {
            database.contactDao().insert(contact())
            database.contactIntelligenceDao().upsertPerson(
                PersonEntity(
                    "contact-maintenance-test",
                    "contact-maintenance-test",
                    "待核实联系人",
                    "待核实联系人",
                    "CONTACT",
                    "ACTIVE",
                    1,
                    NOW,
                ),
            )
            database.contactIntelligenceDao().upsertEmployment(unknownEmployment())
            database.contactIntelligenceDao().upsertSourceIdentity(
                SourceIdentityEntity(
                    sourceIdentityId = "wechat-group-laozhang",
                    personId = null,
                    sourceType = "WECHAT",
                    accountScope = "DEVICE_OBSERVED",
                    tenantId = null,
                    stableExternalId = null,
                    visibleHandle = "老张",
                    normalizedHandle = "老张",
                    conversationScopeId = "项目群",
                    resolutionStatus = "UNRESOLVED",
                    confidence = 0.55,
                    sourceRef = "notification-1",
                    firstObservedAtEpochMs = NOW - 1_000,
                    lastObservedAtEpochMs = NOW,
                ),
            )
            val binding = ContactMaintenanceToolBinding(
                RuntimeToolCatalog.production().requireRegistered("contact.maintenance.list"),
                database.contactDao(),
                database.contactIdentityDao(),
                database.contactIntelligenceDao(),
                database.contactKnowledgeDao(),
                { ContactOwnerProfileSnapshot("老周", setOf("销售")) },
            )

            val result = binding.executeReadOnly(
                RuntimeToolCallRequest("call-1", "contact.maintenance.list", "{}"),
                RuntimeToolRouteContext("run", "session", "attempt", "owner", 1, 1, NOW),
            )
            val json = Json.parseToJsonElement(result.safeResultJson).jsonObject
            val item = json.getValue("items").jsonArray.single().jsonObject

            assertEquals("待核实联系人", item.getValue("displayName").jsonPrimitive.content)
            assertEquals("1", json.getValue("count").jsonPrimitive.content)
            assertEquals("1", json.getValue("totalContactCount").jsonPrimitive.content)
            assertEquals("1", json.getValue("totalIssueCount").jsonPrimitive.content)
            assertEquals("1", json.getValue("returnedCount").jsonPrimitive.content)
            assertEquals("1", json.getValue("deferredRelationshipEvidenceCount").jsonPrimitive.content)
            assertEquals("1", json.getValue("unresolvedIdentityCount").jsonPrimitive.content)
            val owner = json.getValue("ownerProfile").jsonObject
            assertEquals("true", owner.getValue("identityKnown").jsonPrimitive.content)
            assertEquals("false", owner.getValue("contactCardLinked").jsonPrimitive.content)
            assertEquals(
                "非工作关系可按各自证据继续整理；仅当判断同事或上下级时，再询问你目前任职的公司全称",
                owner.getValue("nextStep").jsonPrimitive.content,
            )
            assertEquals("true", owner.getValue("relationshipClassificationReady").jsonPrimitive.content)
            assertEquals("false", owner.getValue("workRelationshipClassificationReady").jsonPrimitive.content)
            val prerequisites = owner.getValue("relationshipPrerequisites").jsonObject
            assertEquals(
                "false",
                prerequisites.getValue("SERVICE").jsonObject.getValue("requiresCurrentEmployment").jsonPrimitive.content,
            )
            assertEquals(
                "true",
                prerequisites.getValue("WORK").jsonObject.getValue("requiresCurrentEmployment").jsonPrimitive.content,
            )
            assertEquals(
                "老张",
                json.getValue("unresolvedIdentities").jsonArray.single().jsonObject.getValue("visibleHandle").jsonPrimitive.content,
            )
            assertEquals(null, item["phone"])
        } finally {
            database.close()
        }
    }

    @Test
    fun readOnlyToolSeparatesWholeLibraryCountsFromReturnedPage() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java).build()
        try {
            repeat(55) { index -> database.contactDao().insert(contact(id = "contact-$index")) }
            val binding = binding(database)

            val result = binding.executeReadOnly(
                RuntimeToolCallRequest("call-page", "contact.maintenance.list", "{\"limit\":50}"),
                RuntimeToolRouteContext("run", "session", "attempt", "owner", 1, 1, NOW),
            )
            val json = Json.parseToJsonElement(result.safeResultJson).jsonObject

            assertEquals("55", json.getValue("totalContactCount").jsonPrimitive.content)
            assertEquals("55", json.getValue("totalIssueCount").jsonPrimitive.content)
            assertEquals("50", json.getValue("returnedCount").jsonPrimitive.content)
            assertEquals("true", json.getValue("truncated").jsonPrimitive.content)
        } finally {
            database.close()
        }
    }

    @Test
    fun readOnlyToolReportsDuplicateReviewAndConfirmedOwnerAnchor() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java).build()
        try {
            database.contactDao().insert(contact(id = "owner", phone = "138-0013-8000"))
            database.contactDao().insert(contact(id = "duplicate", phone = "13800138000"))
            database.contactIntelligenceDao().upsertPerson(
                PersonEntity("owner", "owner", "我", "我", "CONTACT", "ACTIVE", 1, NOW),
            )
            database.contactIntelligenceDao().upsertEmployment(ownerEmployment())
            database.contactKnowledgeDao().upsertOwnerContactLink(
                OwnerContactLinkEntity("owner", "用户确认", true, NOW, null),
            )

            val result = binding(database).executeReadOnly(
                RuntimeToolCallRequest("call-owner", "contact.maintenance.list", "{}"),
                RuntimeToolRouteContext("run", "session", "attempt", "owner", 1, 1, NOW),
            )
            val json = Json.parseToJsonElement(result.safeResultJson).jsonObject
            val owner = json.getValue("ownerProfile").jsonObject

            assertEquals("1", json.getValue("duplicateReviewCount").jsonPrimitive.content)
            assertEquals("true", owner.getValue("identityKnown").jsonPrimitive.content)
            assertEquals("老周", owner.getValue("knownName").jsonPrimitive.content)
            assertEquals("true", owner.getValue("contactCardLinked").jsonPrimitive.content)
            assertEquals("true", owner.getValue("currentEmploymentConfirmed").jsonPrimitive.content)
            assertEquals("true", owner.getValue("relationshipClassificationReady").jsonPrimitive.content)
            assertEquals("true", owner.getValue("workRelationshipClassificationReady").jsonPrimitive.content)
        } finally {
            database.close()
        }
    }

    private fun binding(database: AgentDatabase) = ContactMaintenanceToolBinding(
        RuntimeToolCatalog.production().requireRegistered("contact.maintenance.list"),
        database.contactDao(),
        database.contactIdentityDao(),
        database.contactIntelligenceDao(),
        database.contactKnowledgeDao(),
        { ContactOwnerProfileSnapshot("老周", setOf("销售"), true) },
    )

    private fun contact(id: String = "contact-maintenance-test", phone: String? = null) = ContactEntity(
        contactId = id,
        displayName = "待核实联系人",
        normalizedName = "待核实联系人$id",
        phone = phone,
        email = null,
        wechatId = null,
        company = null,
        title = null,
        aliasesJson = "[]",
        tagsJson = "[]",
        note = null,
        avatarUri = null,
        source = "SYSTEM_CONTACT:test",
        deletedAtEpochMs = null,
        createdAtEpochMs = 1,
        updatedAtEpochMs = 1,
    )

    private fun ownerEmployment() = PersonEmploymentEpisodeEntity(
        episodeId = "owner-employment",
        personId = "owner",
        organizationId = null,
        companyNameSnapshot = "知伴科技",
        department = null,
        title = "负责人",
        validFromEpochMs = NOW - 1_000,
        validToEpochMs = null,
        temporalPrecision = "DAY",
        currentState = "CURRENT",
        sourceRef = "USER_PROFILE",
        confidence = 1.0,
        verificationState = "USER_CONFIRMED",
        status = "ACTIVE",
        recordedAtEpochMs = NOW,
        updatedAtEpochMs = NOW,
    )

    private fun unknownEmployment() = ownerEmployment().copy(
        episodeId = "unknown-employment",
        personId = "contact-maintenance-test",
        validFromEpochMs = null,
        currentState = "UNKNOWN",
        sourceRef = "SYSTEM_CONTACT:test",
        confidence = 0.6,
        verificationState = "OBSERVED",
    )

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
