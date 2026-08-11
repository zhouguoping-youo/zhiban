package com.zhiban.rebuild.data.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.calendar.SystemCalendarEvent
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.ContactImportantDateEntity
import com.zhiban.rebuild.data.contact.ContactMergeLinkEntity
import com.zhiban.rebuild.data.contact.RelationshipEdgeEntity
import com.zhiban.rebuild.data.contact.RelationshipEventEntity
import com.zhiban.rebuild.data.contact.RelationshipEventParticipantEntity
import com.zhiban.rebuild.data.contact.RelationshipPersonIds
import com.zhiban.rebuild.data.contact.SystemContactCandidate
import com.zhiban.rebuild.data.contact.SystemContactPlatformIdentity
import com.zhiban.rebuild.data.contact.SystemRawContactSnapshot
import com.zhiban.rebuild.data.crm.CrmOpportunityEntity
import com.zhiban.rebuild.data.crm.CrmOpportunityStage
import com.zhiban.rebuild.data.crm.CrmRecordStatus
import com.zhiban.rebuild.data.notification.NotificationCandidateEntity
import com.zhiban.rebuild.data.notification.NotificationInsights
import com.zhiban.rebuild.data.notification.ScheduleInsight
import com.zhiban.rebuild.runtime.context.FactEntity
import com.zhiban.rebuild.runtime.context.FactIndex
import com.zhiban.rebuild.runtime.governance.AutoWriteRepository
import com.zhiban.rebuild.runtime.governance.canonicalChangeDigest
import com.zhiban.rebuild.runtime.governance.insertVisibleAutoWrite
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentDataRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: AgentDatabase
    private lateinit var repository: AgentDataRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java)
            .addCallback(AgentDatabase.CALLBACK)
            .allowMainThreadQueries()
            .build()
        repository = AgentDataRepository(
            AgentDataDaos(
                notificationCandidateDao = database.notificationCandidateDao(),
                contactDao = database.contactDao(),
                contactIdentityDao = database.contactIdentityDao(),
                contactKnowledgeDao = database.contactKnowledgeDao(),
                contactIntelligenceDao = database.contactIntelligenceDao(),
                factDao = database.factDao(),
                changeLogDao = database.changeLogDao(),
            ),
            transactions = RoomAgentTransactionRunner(database),
            factIndex = FactIndex(database),
            autoWriteSink = AutoWriteSink { database.insertVisibleAutoWrite(it) },
            calendar = CalendarAgentDataRepository(database),
            crm = CrmAgentDataRepository(database),
            contacts = ContactAgentDataRepository(database),
            relationships = RelationshipAgentDataRepository(database),
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun contactTagIsSerializedAsJsonInsteadOfInterpolatedIntoJsonText() = runBlocking {
        val tag = "重点\"]，\"injected\":true"

        val contactId = repository.saveUserContact(
            null, "JSON 标签", null, null, null, null, tag, null, nowEpochMs = 100,
        )

        val stored = requireNotNull(database.contactDao().findRawById(contactId))
        val parsed = Json.parseToJsonElement(stored.tagsJson).jsonArray
        assertEquals(1, parsed.size)
        assertEquals(tag, parsed.single().jsonPrimitive.content)
    }

    @Test
    fun userStageChangeCannotReopenTerminalOpportunity() = runBlocking {
        database.contactDao().insert(
            ContactEntity(
                "crm-contact", "客户", "客户", null, null, null, null, null,
                "[]", "[]", null, null, "MANUAL", null, 1, 1,
            ),
        )
        database.crmDao().insertOpportunity(
            CrmOpportunityEntity(
                "crm-terminal", "已成交机会", "客户公司", "crm-contact", null,
                CrmOpportunityStage.WON, CrmRecordStatus.WON, 10_000, "CNY", 100,
                null, null, null, null, "USER_CONFIRMED", 1, 1,
            ),
        )

        val failure = runCatching {
            repository.updateCrmOpportunityStage("crm-terminal", CrmOpportunityStage.LEAD, "误操作", 2)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(CrmOpportunityStage.WON, database.crmDao().findOpportunity("crm-terminal")?.stage)
        assertEquals(CrmRecordStatus.WON, database.crmDao().findOpportunity("crm-terminal")?.status)
    }

    @Test
    fun deletingMergedContactClusterHidesDerivedFactsRelationshipsAndEvents() = runBlocking {
        val contacts = database.contactDao()
        contacts.insert(
            ContactEntity(
                "canonical-delete", "主联系人", "主联系人", null, null, null, null, null,
                "[]", "[]", null, null, "MANUAL", null, 1, 1,
            ),
        )
        contacts.insert(
            ContactEntity(
                "source-delete", "合并来源", "合并来源", null, null, null, null, null,
                "[]", "[]", null, null, "SYSTEM", null, 1, 1,
            ),
        )
        contacts.insert(
            ContactEntity(
                "other-delete", "其他人", "其他人", null, null, null, null, null,
                "[]", "[]", null, null, "MANUAL", null, 1, 1,
            ),
        )
        database.contactIdentityDao().upsertMergeLink(
            ContactMergeLinkEntity(
                "source-delete",
                "canonical-delete",
                "test",
                true,
                2,
                null,
            ),
        )
        FactIndex(database).upsert(
            FactEntity(
                "fact-delete", "CONTACT_NOTE", "只属于已删除联系人", null, "USER", null,
                "source-delete", null, 1.0, "PERSONAL", "ACTIVE", 0, null, 1, 1,
            ),
        )
        database.relationshipEdgeDao().upsert(
            RelationshipEdgeEntity(
                "edge-delete", "source-delete", "other-delete", "COLLEAGUE", "digest", "[]",
                1.0, true, null, "ACTIVE", 1, 1,
            ),
        )
        database.relationshipEventDao().upsertEvent(
            RelationshipEventEntity(
                "event-delete", "MEETING", "私密会面", null, 1, "digest", "[]", true, "ACTIVE", 1, 1,
            ),
        )
        database.relationshipEventDao().upsertParticipants(
            listOf(
                RelationshipEventParticipantEntity(
                    "participant-delete",
                    "event-delete",
                    "CONTACT",
                    "source-delete",
                    "ATTENDEE",
                    "合并来源",
                    1,
                ),
            ),
        )

        assertTrue(repository.deleteUserContact("canonical-delete"))

        assertNull(contacts.findRawById("canonical-delete"))
        assertNull(contacts.findRawById("source-delete"))
        assertTrue(FactIndex(database).search("只属于已删除联系人", 10, 10).isEmpty())
        assertTrue(database.relationshipEdgeDao().observeActive().first().isEmpty())
        assertTrue(database.relationshipEventDao().observeActive().first().isEmpty())
        assertEquals(0, database.contactIdentityDao().undoConfirmedMerge("source-delete", 10))
    }

    @Test
    fun systemImportMatchesExistingFormattedPhoneThroughCanonicalContactMethod() = runBlocking {
        val existingId = repository.saveUserContact(
            null, "已有联系人", "138-0013-8000", null, null, null, null, null, 1_000L,
        )

        val summary = repository.importConfirmedSystemContacts(
            listOf(systemContact("android-1", "通讯录姓名", "13800138000")),
            ownerPhone = null,
            ownerWechatId = null,
            ownerName = null,
            nowEpochMs = 2_000L,
        )

        assertEquals(0, summary.created)
        assertEquals(1, summary.updated)
        assertEquals(1, repository.observeRawContacts().first().size)
        assertEquals(existingId, database.contactKnowledgeDao().findContactByMethod("PHONE", "13800138000")?.contactId)
    }

    @Test
    fun duplicatePhonesInsideOneSystemImportBatchCollapseToOneContact() = runBlocking {
        val summary = repository.importConfirmedSystemContacts(
            listOf(
                systemContact("android-a", "张老师", "138-0013-8000"),
                systemContact("android-b", "张三", "+86 138 0013 8000"),
            ),
            ownerPhone = null,
            ownerWechatId = null,
            ownerName = null,
            nowEpochMs = 2_000L,
        )

        assertEquals(1, summary.created)
        assertEquals(1, summary.updated)
        assertEquals(1, repository.observeRawContacts().first().size)
        assertNotNull(database.contactKnowledgeDao().findContactByMethod("PHONE", "13800138000"))
    }

    @Test
    fun duplicateEmailsInsideOneSystemImportBatchCollapseToOneContact() = runBlocking {
        val summary = repository.importConfirmedSystemContacts(
            listOf(
                systemContact("email-a", "张老师", emails = listOf("USER@Example.com")),
                systemContact("email-b", "张三", emails = listOf(" user@example.com ")),
            ),
            ownerPhone = null,
            ownerWechatId = null,
            ownerName = null,
            nowEpochMs = 2_000L,
        )

        assertEquals(1, summary.created)
        assertEquals(1, summary.updated)
        assertEquals(1, repository.observeRawContacts().first().size)
        assertNotNull(database.contactKnowledgeDao().findContactByMethod("EMAIL", "user@example.com"))
    }

    @Test
    fun editingUserPhoneRemovesStaleNormalizedIdentity() = runBlocking {
        val contactId = repository.saveUserContact(
            null, "联系人", "138-0013-8000", "old-wechat", "旧公司", "旧职位", "旧标签", null, 1_000L,
        )

        repository.saveUserContact(
            contactId, "联系人", "13900139000", null, "新公司", "新职位", "新标签", null, 2_000L,
        )

        assertNull(database.contactKnowledgeDao().findContactByMethod("PHONE", "13800138000"))
        assertEquals(contactId, database.contactKnowledgeDao().findContactByMethod("PHONE", "13900139000")?.contactId)
        assertNull(database.contactKnowledgeDao().findContactByMethod("WECHAT", "old-wechat"))
        assertEquals(
            listOf("新公司"),
            repository.observeContactEmployments(contactId).first()
                .filter { it.isCurrent }.map { it.companyNameSnapshot },
        )
        assertEquals(listOf("新标签"), repository.observeContactFacets(contactId).first().map { it.value })
    }

    @Test
    fun auditRetentionDeletesOnlyRecordsOlderThanNinetyDayCutoff() = runBlocking {
        fun audit(id: String, createdAt: Long) = ToolAuditEntity(
            id = id, runId = null, subjectRunDigest = "subject-$id", toolCallId = "call-$id",
            toolName = "test.read", idempotencyKey = "key-$id", argumentsDigest = "digest-$id",
            status = "SUCCEEDED", resultJson = null, expiresAtEpochMs = null,
            createdAtEpochMs = createdAt, updatedAtEpochMs = createdAt,
        )
        database.toolAuditDao().insert(audit("old", 999L))
        database.toolAuditDao().insert(audit("boundary", 1_000L))
        database.toolAuditDao().insert(audit("new", 2_000L))

        assertEquals(1, database.toolAuditDao().deleteOlderThan(1_000L))
        assertEquals(2, database.toolAuditDao().count())
        assertNull(database.toolAuditDao().findByIdempotencyKey("key-old"))
        assertNotNull(database.toolAuditDao().findByIdempotencyKey("key-boundary"))
    }

    @Test
    fun directRunDeleteCascadesSummaryAndDetachesPreference() = runBlocking {
        database.agentRunDao().insert(run("run-direct-delete"))
        database.memoryDao().insert(memory("direct-summary", "RUN_SUMMARY", "run-direct-delete"))
        database.memoryDao().insert(memory("direct-preference", "USER_PREFERENCE", "run-direct-delete"))

        database.agentRunDao().deleteById("run-direct-delete")

        assertNull(database.memoryDao().findById("direct-summary"))
        assertNull(database.memoryDao().findById("direct-preference")?.sourceRunId)
    }

    @Test
    fun confirmedSystemContactImportIsIdempotentAndUpdatesTheBoundRow() = runBlocking {
        val first = systemContact("lookup-1", "张三", "13800138000", company = "星河科技")
        val initial = repository.importConfirmedSystemContacts(
            listOf(first),
            ownerPhone = null,
            ownerWechatId = null,
            ownerName = null,
            nowEpochMs = 1_000L,
        )
        val second = repository.importConfirmedSystemContacts(
            listOf(first.copy(company = "未来科技")),
            ownerPhone = null,
            ownerWechatId = null,
            ownerName = null,
            nowEpochMs = 2_000L,
        )

        assertEquals(1, initial.created)
        assertEquals(1, second.updated)
        assertEquals(1, database.contactDao().countActive())
        assertEquals("未来科技", database.contactDao().findBySource("SYSTEM_CONTACT:lookup-1")?.company)
    }

    @Test
    fun systemImportStoresObservedClaimsWithoutInventingCurrentOrConfirmedEmployment() = runBlocking {
        repository.importConfirmedSystemContacts(
            contacts = listOf(
                systemContact("observed", "丁波", "13800138000", company = "旧公司").copy(
                    title = "售前",
                    rawContacts = listOf(
                        SystemRawContactSnapshot(
                            rawContactId = 42,
                            aggregateContactId = 7,
                            lookupKey = "observed",
                            accountName = "device",
                            accountType = "local",
                            sourceId = null,
                            version = 3,
                            isDirty = false,
                            isReadOnly = false,
                            dataRows = emptyList(),
                        ),
                    ),
                ),
            ),
            ownerPhone = null,
            ownerWechatId = null,
            ownerName = null,
            nowEpochMs = 1_000L,
        )

        val contact = database.contactDao().findBySource("SYSTEM_CONTACT:observed")!!
        val person = database.contactIntelligenceDao().findPersonByContactId(contact.contactId)
        val claims = database.contactIntelligenceDao().observeClaims(contact.contactId).first()
        val employment = database.contactIntelligenceDao().observeEmployments(contact.contactId).first().single()

        assertEquals(contact.contactId, person?.personId)
        assertEquals(setOf("NAME", "PHONE", "COMPANY", "TITLE"), claims.map { it.fieldType }.toSet())
        assertTrue(claims.all { it.verificationState == "OBSERVED" })
        assertEquals("UNKNOWN", employment.currentState)
        assertEquals("OBSERVED", employment.verificationState)
        assertEquals(0.6, employment.confidence, 0.0)
        val androidLink = database.contactIntelligenceDao().androidLinksForPerson(contact.contactId).single()
        assertEquals(42, androidLink.rawContactId)
        assertEquals(3, androidLink.version)
        assertEquals("IN_SYNC", database.contactIntelligenceDao().findSyncSnapshot(androidLink.linkId)?.syncState)
        assertFalse(
            database.contactKnowledgeDao().observeEmployments(contact.contactId).first().single().userConfirmed,
        )
    }

    @Test
    fun systemImportPersistsDistinctPlatformIdentities() = runBlocking {
        repository.importConfirmedSystemContacts(
            contacts = listOf(
                systemContact(
                    sourceId = "platforms",
                    name = "李应啸",
                    platformIdentities = listOf(
                        SystemContactPlatformIdentity("WECHAT", "li-wechat"),
                        SystemContactPlatformIdentity("FEISHU", "li-feishu"),
                        SystemContactPlatformIdentity("WE_COM", "li-wecom"),
                    ),
                ),
            ),
            ownerPhone = null,
            ownerWechatId = null,
            ownerName = null,
            nowEpochMs = 1_000L,
        )

        val contact = database.contactDao().findBySource("SYSTEM_CONTACT:platforms")!!
        assertEquals("li-wechat", contact.wechatId)
        assertEquals(
            setOf("WECHAT", "FEISHU", "WE_COM"),
            database.contactIdentityDao().platformIdentities(contact.contactId).map { it.platform }.toSet(),
        )
    }

    @Test
    fun groupMessageCreatesUnresolvedSourceIdentityThenConfirmationResolvesIt() = runBlocking {
        val now = System.currentTimeMillis()
        val candidate = NotificationCandidateEntity(
            candidateId = "group-message",
            sourceKey = "group-message-key",
            packageName = "com.tencent.mm",
            appLabel = "微信",
            title = "项目群",
            body = "项目讨论内容第七版",
            postedAtEpochMs = now,
            createdAtEpochMs = now,
            platform = "WECHAT",
            conversationTitle = "项目群",
            senderName = "老张",
            isGroupChat = true,
        )

        repository.stageNotificationCandidate(candidate)

        val unresolved = database.contactIntelligenceDao().observeUnresolvedIdentities().first().single()
        assertEquals("老张", unresolved.visibleHandle)
        assertEquals("WECHAT", unresolved.sourceType)
        val groupId = stableContactKnowledgeId("observed-group", "WECHAT", "项目群")
        assertEquals("项目群", database.contactIntelligenceDao().findGroup(groupId)?.displayName)
        assertEquals(unresolved.sourceIdentityId, database.contactIntelligenceDao().membershipsForGroup(groupId).single().sourceIdentityId)

        val contactId = repository.saveUserContact(
            null, "张三", null, null, null, null, null, null, nowEpochMs = now + 500,
        )
        assertEquals("PENDING", database.notificationCandidateDao().find(candidate.candidateId)?.status)
        assertTrue(repository.confirmNotificationCandidate(candidate.candidateId, contactId, nowEpochMs = now + 1_000))

        val resolved = database.contactIntelligenceDao().findSourceIdentity(unresolved.sourceIdentityId)
        assertEquals(contactId, resolved?.personId)
        assertEquals("RESOLVED", resolved?.resolutionStatus)
    }

    @Test
    fun sameConfirmedPlatformAccountLinksReimportedCardToExistingPerson() = runBlocking {
        val identity = SystemContactPlatformIdentity("FEISHU", "ou_verified_123")
        val summary = repository.importConfirmedSystemContacts(
            contacts = listOf(
                systemContact("feishu-a", "李应啸", platformIdentities = listOf(identity)),
                systemContact("feishu-b", "李老师", platformIdentities = listOf(identity)),
            ),
            ownerPhone = null,
            ownerWechatId = null,
            ownerName = null,
            nowEpochMs = 1_000L,
        )

        assertEquals(1, summary.created)
        assertEquals(1, summary.updated)
        assertEquals(1, database.contactDao().countActive())
        assertNotNull(database.contactIdentityDao().findContactByPlatformHandle("FEISHU", "ou_verified_123"))
    }

    @Test
    fun importStagesUniqueLocalCompanyCompletionInsteadOfOverwritingProfile() = runBlocking {
        repository.importConfirmedSystemContacts(
            contacts = listOf(
                systemContact("short-company", "周国平", company = "知伴"),
                systemContact("full-company", "李应啸", company = "知伴科技（上海）有限公司"),
            ),
            ownerPhone = null,
            ownerWechatId = null,
            ownerName = null,
            nowEpochMs = 1_000L,
        )

        val contact = database.contactDao().findBySource("SYSTEM_CONTACT:short-company")!!
        assertEquals("知伴", contact.company)
        val candidates = repository.observePendingContactEnrichment(contact.contactId).first()
        assertEquals(1, candidates.size)
        assertTrue(candidates.single().proposedValueJson.contains("知伴科技（上海）有限公司"))
        assertEquals("local-contact-intelligence", candidates.single().providerId)
    }

    @Test
    fun intelligenceRefreshBackfillsLegacyWechatProfileAsPlatformIdentityIdempotently() = runBlocking {
        val contactId = repository.saveUserContact(
            null, "丁波", null, "ding-wechat", null, null, null, null, nowEpochMs = 1_000L,
        )

        repository.refreshLocalContactIntelligence()
        repository.refreshLocalContactIntelligence()

        val identities = database.contactIdentityDao().platformIdentities(contactId)
        assertEquals(1, identities.size)
        assertEquals("WECHAT", identities.single().platform)
        assertEquals("ding-wechat", identities.single().handle)
    }

    @Test
    fun deterministicIdentityLinkIsVisibleReversibleAndNotReappliedAfterUndo() = runBlocking {
        val first = testContact("duplicate-a", "丁波", "13800138000", "ding@example.com", 1_000L)
        val second = testContact("duplicate-b", "老丁", "138-0013-8000", "DING@example.com", 2_000L)
        database.contactDao().insert(first)
        database.contactDao().insert(second)

        repository.refreshLocalContactIntelligence()

        val link = database.contactIdentityDao().observeActiveMergeLinks().first().single()
        assertFalse(link.userConfirmed)
        assertEquals("duplicate-a", link.canonicalContactId)
        val receipt = AutoWriteRepository(database, context).observeReceipts().first().single {
            it.presentationType == "CONTACT_IDENTITY_LINK"
        }
        assertTrue(AutoWriteRepository(database, context).undo(receipt.changeId, 3_000L))
        assertNull(database.contactIdentityDao().activeMergeLink(link.sourceContactId))

        repository.refreshLocalContactIntelligence()

        assertTrue(database.contactIdentityDao().observeActiveMergeLinks().first().isEmpty())
    }

    @Test
    fun systemImportRunsDeterministicIdentityCleaningBeforeReturning() = runBlocking {
        database.contactDao().insert(testContact("duplicate-a", "丁波", "13800138000", "ding@example.com", 1_000L))
        database.contactDao().insert(testContact("duplicate-b", "老丁", "138-0013-8000", "DING@example.com", 2_000L))

        val summary = repository.importConfirmedSystemContacts(
            contacts = listOf(systemContact("new-contact", "新联系人", "13900139000")),
            ownerPhone = null,
            ownerWechatId = null,
            ownerName = null,
            nowEpochMs = 3_000L,
        )

        assertEquals(1, summary.automaticallyMerged)
        assertEquals(1, database.contactIdentityDao().observeActiveMergeLinks().first().size)
        assertEquals(2, repository.observeContacts().first().size)
    }

    @Test
    fun systemContactMatchingOwnerPhoneIsNeverImported() = runBlocking {
        val result = repository.importConfirmedSystemContacts(
            listOf(systemContact("self", "老周", "+86 138-0013-8000")),
            ownerPhone = "+8613800138000",
            ownerWechatId = null,
            ownerName = "老周",
        )

        assertEquals(1, result.skippedSelf)
        assertEquals(0, database.contactDao().countActive())
    }

    @Test
    fun systemContactMatchingOwnerByNormalizedPhoneVariationsIsSkipped() = runBlocking {
        val result = repository.importConfirmedSystemContacts(
            contacts = listOf(systemContact("self-phone-var", "老周", "+86 13800138000", phones = listOf("13800138000", "138-0013-8000"))),
            ownerPhone = "138 0013 8000",
            ownerWechatId = null,
            ownerName = "",
            nowEpochMs = 1_000L,
        )

        assertEquals(1, result.skippedSelf)
        assertEquals(0, result.created)
        assertEquals(0, database.contactDao().countActive())
    }

    @Test
    fun systemContactMatchingOwnerWechatIdIsSkipped() = runBlocking {
        val result = repository.importConfirmedSystemContacts(
            contacts = listOf(
                systemContact(
                    sourceId = "self-wechat",
                    name = "老周",
                    phone = null,
                    wechatIds = listOf("WX-ID_01"),
                ),
            ),
            ownerPhone = null,
            ownerWechatId = "@wx-id_01",
            ownerName = "",
            nowEpochMs = 1_000L,
        )

        assertEquals(1, result.skippedSelf)
        assertEquals("wx-id_01", result.skippedSelfWechat)
        assertEquals(0, database.contactDao().countActive())
    }

    @Test
    fun systemContactMatchingOwnerByPhoneFillsMissingIdentitySummary() = runBlocking {
        val result = repository.importConfirmedSystemContacts(
            contacts = listOf(
                systemContact(
                    sourceId = "self-identity",
                    name = "老周",
                    phone = "13800138000",
                    wechatIds = listOf("wx-self-01"),
                ),
            ),
            ownerPhone = "13800138000",
            ownerWechatId = null,
            ownerName = null,
            nowEpochMs = 1_000L,
        )

        assertEquals(1, result.skippedSelf)
        assertEquals(true, result.selfIdentityMissing)
        assertEquals("老周", result.skippedSelfName)
        assertEquals("13800138000", result.skippedSelfPhone)
        assertEquals("wx-self-01", result.skippedSelfWechat)
        assertEquals(0, database.contactDao().countActive())
    }

    @Test
    fun signedInUserCanBeAConfirmedRelationshipEndpointWithoutBecomingAContact() = runBlocking {
        val contactId = repository.saveUserContact(
            null, "周国平", null, null, null, null, null, null, nowEpochMs = 1_000L,
        )

        val edgeId = repository.saveConfirmedRelationship(
            RelationshipPersonIds.SELF,
            contactId,
            "FRIEND",
            temporalState = "CURRENT",
            nowEpochMs = 2_000L,
        )

        val edge = repository.observeRelationships().first().single()
        assertEquals(RelationshipPersonIds.SELF, edge.fromContactId)
        assertEquals(contactId, edge.toContactId)
        assertEquals(1, database.contactDao().countActive())
        val episode = database.contactIntelligenceDao().observeRelationships(contactId).first().single()
        assertEquals("FRIEND", episode.relationshipType)
        assertNull(episode.validToEpochMs)
        assertEquals("USER_CONFIRMED", episode.verificationState)

        assertTrue(repository.updateConfirmedRelationship(edgeId, "COLLEAGUE", nowEpochMs = 3_000L))
        val afterUpdate = database.contactIntelligenceDao().observeRelationships(contactId).first()
        assertEquals(2, afterUpdate.size)
        assertEquals(3_000L, afterUpdate.single { it.relationshipType == "FRIEND" }.validToEpochMs)
        assertNull(afterUpdate.single { it.relationshipType == "COLLEAGUE" }.validToEpochMs)

        assertTrue(repository.deleteConfirmedRelationship(edgeId, nowEpochMs = 4_000L))
        val closed = database.contactIntelligenceDao().observeRelationships(contactId).first()
        assertEquals(4_000L, closed.single { it.relationshipType == "COLLEAGUE" }.validToEpochMs)
    }

    @Test
    fun pastRelationshipStaysInHistoryButNotCurrentGraph() = runBlocking {
        val contactId = repository.saveUserContact(
            null, "旧同事", null, null, null, null, null, null, nowEpochMs = 1_000L,
        )

        repository.saveConfirmedRelationship(
            RelationshipPersonIds.SELF,
            contactId,
            "COLLEAGUE",
            temporalState = "PAST",
            nowEpochMs = 2_000L,
        )

        assertTrue(repository.observeRelationships().first().isEmpty())
        val history = database.contactIntelligenceDao().observeRelationships(contactId).first().single()
        assertEquals("COLLEAGUE", history.relationshipType)
        assertEquals(2_000L, history.validToEpochMs)
    }

    @Test
    fun confirmedAliasesAndPlatformIdentitiesAreStructuredAndRemovable() = runBlocking {
        val contactId = repository.saveUserContact(
            contactId = null,
            displayName = "王小明",
            phone = "13800138008",
            wechatId = null,
            company = null,
            title = null,
            tag = null,
            note = null,
            nowEpochMs = 1_000L,
        )

        val aliasId = repository.addContactAlias(contactId, " 王老师 ", nowEpochMs = 2_000L)
        val identityId = repository.addContactPlatformIdentity(contactId, "douyin", " @Wang-88 ", nowEpochMs = 3_000L)

        val aliases = repository.observeContactAliases().first()
        val identities = repository.observeContactPlatformIdentities().first()
        assertEquals("王老师", aliases.single().alias)
        assertEquals("DOUYIN", identities.single().platform)
        assertEquals("wang-88", identities.single().normalizedHandle)

        assertEquals(true, repository.deleteContactAlias(aliasId))
        assertEquals(true, repository.deleteContactPlatformIdentity(identityId))
        assertEquals(0, repository.observeContactAliases().first().size)
        assertEquals(0, repository.observeContactPlatformIdentities().first().size)
    }

    @Test
    fun confirmedSystemCalendarImportIsIdempotent() = runBlocking {
        val event = SystemCalendarEvent(
            sourceId = "42:10000",
            title = "客户会议",
            description = "准备方案",
            location = "会议室 A",
            startAtEpochMs = 10_000L,
            endAtEpochMs = 3_610_000L,
            calendarName = "工作",
        )

        val first = repository.importConfirmedSystemCalendarEvents(listOf(event), nowEpochMs = 1_000L)
        val second = repository.importConfirmedSystemCalendarEvents(
            listOf(event.copy(title = "客户方案会")),
            nowEpochMs = 2_000L,
        )

        assertEquals(1, first.created)
        assertEquals(1, second.updated)
        assertEquals("客户方案会", database.scheduleDao().findById("system-calendar-42:10000")?.title)
        assertEquals(1, database.scheduleDao().count())
    }

    @Test
    fun userScheduleReminderIsPersistedAndCanBeCleared() = runBlocking {
        val id = repository.saveUserSchedule(
            scheduleId = null,
            title = "复诊",
            startAtEpochMs = 100_000L,
            durationMinutes = 30,
            note = null,
            reminderMinutesBefore = 60,
            nowEpochMs = 1_000L,
        )
        assertEquals(60, database.scheduleDao().findById(id)?.reminderMinutesBefore)

        repository.saveUserSchedule(
            scheduleId = id,
            title = "复诊",
            startAtEpochMs = 100_000L,
            durationMinutes = 30,
            note = null,
            reminderMinutesBefore = null,
            nowEpochMs = 2_000L,
        )
        assertNull(database.scheduleDao().findById(id)?.reminderMinutesBefore)
    }

    @Test
    fun manualScheduleFromYesterdayIsRejectedWithoutWrite() = runBlocking {
        val failure = runCatching {
            repository.saveUserSchedule(
                scheduleId = null,
                title = "误选昨天",
                startAtEpochMs = 1_000L,
                durationMinutes = 30,
                note = null,
                reminderMinutesBefore = null,
                nowEpochMs = 24 * 60 * 60_000L,
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(0, database.scheduleDao().count())
    }

    @Test
    fun notificationCandidateStaysPendingUntilUserDismissesIt() = runBlocking {
        repository.stageNotificationCandidate(
            NotificationCandidateEntity(
                candidateId = "notification-1",
                sourceKey = "source-1",
                packageName = "com.example.chat",
                appLabel = "聊天",
                title = "张三",
                body = "明天见",
                postedAtEpochMs = System.currentTimeMillis(),
            ),
        )
        assertEquals(1, repository.observeNotificationCandidates().first().size)
        assertEquals(true, repository.dismissNotificationCandidate("notification-1"))
        assertEquals(0, repository.observeNotificationCandidates().first().size)
    }

    @Test
    fun notificationEvidenceRequiresExplicitContactSelection() = runBlocking {
        val contactId = repository.saveUserContact(
            null, "张三", null, null, null, null, null, null, nowEpochMs = 1_000L,
        )
        repository.stageNotificationCandidate(
            NotificationCandidateEntity(
                candidateId = "notification-confirm",
                sourceKey = "source-confirm",
                packageName = "com.example.chat",
                appLabel = "聊天",
                title = "张三",
                body = "明天见",
                postedAtEpochMs = System.currentTimeMillis(),
            ),
        )

        assertEquals(true, repository.confirmNotificationCandidate("notification-confirm", contactId, 2_000L))
        assertEquals(0, repository.observeNotificationCandidates().first().size)
        val facts = repository.observeContactFacts(contactId).first()
        assertEquals("USER_CONFIRMED_NOTIFICATION", facts.single().sourceType)
        assertEquals("CURRENT_MATTER", facts.single().factType)
        assertEquals("PERSONAL", facts.single().sensitivity)
        assertEquals("明天见", facts.single().textContent)
        assertEquals(
            true,
            database.factDao().find(facts.single().factId)?.textContent?.contains("聊天"),
        )

        assertEquals(true, repository.deleteContactFact(facts.single().factId))
        assertEquals("notification-confirm", repository.observeNotificationCandidates().first().single().candidateId)
        assertEquals(0, repository.observeContactFacts(contactId).first().size)
    }

    @Test
    fun notificationEvidencePersistsOriginalMessageBeforeDisplayNormalization() = runBlocking {
        val contactId = repository.saveUserContact(
            null, "张三", null, null, null, null, null, null, nowEpochMs = 1_000L,
        )
        repository.stageNotificationCandidate(
            NotificationCandidateEntity(
                candidateId = "notification-original",
                sourceKey = "source-original",
                packageName = "com.example.chat",
                appLabel = "聊天",
                title = "张三",
                body = "明天，需要发货",
                postedAtEpochMs = System.currentTimeMillis(),
            ),
        )

        assertEquals(true, repository.confirmNotificationCandidate("notification-original", contactId, 2_000L))

        val persisted = database.factDao().find("notification-evidence:notification-original")
        assertNotNull(persisted)
        assertEquals(true, persisted!!.textContent.contains("明天，需要发货"))
        assertEquals("明天，需要发货", repository.observeContactFacts(contactId).first().single().textContent)
    }

    @Test
    fun inferredReplyInteractionIsPersistedAsReversibleAutoWrite() = runBlocking {
        val now = System.currentTimeMillis()
        val contactId = repository.saveUserContact(
            null,
            "张三",
            null,
            null,
            null,
            null,
            null,
            null,
            nowEpochMs = now,
        )
        repository.stageNotificationCandidate(
            NotificationCandidateEntity(
                candidateId = "outgoing-1",
                sourceKey = "source-outgoing",
                packageName = "com.tencent.mm",
                appLabel = "微信",
                title = "张三",
                body = "我已经发了方案",
                postedAtEpochMs = now,
                platform = "WECHAT",
                conversationTitle = "张三",
                senderName = "你",
                direction = "OUTGOING",
                linkedContactId = contactId,
                messageKind = "MESSAGE",
            ),
        )

        repository.stageNotificationCandidate(
            NotificationCandidateEntity(
                candidateId = "incoming-1",
                sourceKey = "source-incoming",
                packageName = "com.tencent.mm",
                appLabel = "微信",
                title = "张三",
                body = "收到，我看看",
                postedAtEpochMs = now + 10_000L,
                platform = "WECHAT",
                conversationTitle = "张三",
                senderName = "张三",
                direction = "INCOMING",
                linkedContactId = contactId,
                messageKind = "MESSAGE",
            ),
        )

        val facts = repository.observeContactFacts(contactId).first()
        val inferred = facts.single { it.factType == "INTERACTION_SUMMARY" && it.sourceType == "INFERRED_NOTIFICATION" }
        assertEquals("推断你与 张三 在 张三 中有一次回复沟通", inferred.textContent)
        val receipts = database.changeLogDao().observeAutoWriteReceipts().first()
        assertEquals(1, receipts.count())
        assertEquals("AVAILABLE", receipts[0].undoState)
        assertEquals("INFERRED_NOTIFICATION", database.factDao().find(inferred.factId)?.sourceType)
        assertEquals(0.6, receipts[0].confidence ?: -1.0, 0.000_001)
    }

    @Test
    fun contactFactWithParenthesizedSourceOpensWithoutRegexFailure() = runBlocking {
        val contactId = repository.saveUserContact(
            null, "张三", null, null, null, null, null, null, nowEpochMs = 1_000L,
        )
        repository.saveConfirmedContactFact(
            contactId = contactId,
            text = "（微信）明天下午开会",
            nowEpochMs = 2_000L,
        )

        val fact = repository.observeContactFacts(contactId).first().single()

        assertEquals(true, fact.textContent.contains("开会"))
    }

    @Test
    fun notificationFactForScheduleWillSaveNormalizedTitleInsteadOfRawMessage() = runBlocking {
        val contactId = repository.saveUserContact(
            null, "王总", null, null, null, null, null, null, nowEpochMs = 1_000L,
        )
        val start = System.currentTimeMillis() + 24 * 60 * 60_000L
        repository.stageNotificationCandidate(
            NotificationCandidateEntity(
                candidateId = "notification-schedule-noisy",
                sourceKey = "source-noisy",
                packageName = "com.tencent.mm",
                appLabel = "微信",
                title = "王总",
                body = "明天确认事项，老周，明天下下午3点和我开会。",
                postedAtEpochMs = start,
                platform = "WECHAT",
                conversationTitle = "王总",
                senderName = "老周",
                messageKind = "SCHEDULE_CANDIDATE",
                insightJson = NotificationInsights(
                    schedule = ScheduleInsight("老周，明天下下午3点和我开会。", start, confidence = 0.92),
                ).toJsonOrNull(),
            ),
        )

        repository.confirmNotificationSchedule("notification-schedule-noisy")
        val schedule = database.scheduleDao().findById("notification-schedule-source-noisy")
        assertNotNull(schedule)
        assertEquals("开会", schedule!!.title)

        assertEquals(true, repository.confirmNotificationCandidate("notification-schedule-noisy", contactId))
        val fact = repository.observeContactFacts(contactId).first().single {
            it.sourceType ==
                "USER_CONFIRMED_NOTIFICATION"
        }
        assertEquals("CURRENT_MATTER", fact.factType)
        assertEquals(false, fact.textContent.contains("明天下下午3点"))
        assertEquals(false, fact.textContent.contains("老周，"))
    }

    @Test
    fun structuredMessageSuggestsExactContactButWaitsForUserConfirmation() = runBlocking {
        val now = System.currentTimeMillis()
        val contactId = repository.saveUserContact(
            null, "李雷", null, null, null, null, null, null, nowEpochMs = now,
        )
        repository.stageNotificationCandidate(
            NotificationCandidateEntity(
                candidateId = "notification-suggest",
                sourceKey = "source-suggest",
                packageName = "com.tencent.mm",
                appLabel = "微信",
                title = "产品群",
                body = "资料发你了",
                postedAtEpochMs = now,
                platform = "WECHAT",
                conversationTitle = "产品群",
                senderName = "李雷",
                isGroupChat = true,
            ),
        )

        val pending = repository.observeNotificationCandidates().first().single()
        assertEquals(contactId, pending.suggestedContactId)
        assertEquals(0, repository.observeContactFacts(contactId).first().size)

        assertEquals(true, repository.confirmNotificationCandidate(pending.candidateId, contactId, now + 1_000L))
        assertEquals(0, repository.observeNotificationCandidates().first().size)
        assertEquals(1, repository.observeContactFacts(contactId).first().size)
        assertEquals("WECHAT", repository.observeContactPlatformIdentities().first().single().platform)

        repository.stageNotificationCandidate(
            NotificationCandidateEntity(
                candidateId = "notification-followup",
                sourceKey = "source-followup",
                packageName = "com.tencent.mm",
                appLabel = "微信",
                title = "李雷",
                body = "后续资料已经补充",
                postedAtEpochMs = now + 2_000L,
                platform = "WECHAT",
                conversationTitle = "李雷",
                senderName = "李雷",
            ),
        )
        assertEquals(0, repository.observeNotificationCandidates().first().size)
        val automatic = repository.observeContactFacts(contactId).first()
            .single { it.factType == "INTERACTION_SUMMARY" }
        assertEquals("OBSERVED_NOTIFICATION", automatic.sourceType)
        assertEquals(90, automatic.ttlDays)
        val receipt = database.changeLogDao().observeAutoWriteReceipts().first().single()
        val persistedFact = requireNotNull(database.factDao().find(automatic.factId))
        assertEquals(canonicalChangeDigest(persistedFact), database.changeLogDao().find(receipt.changeId)?.afterDigest)
        assertEquals(contactId, receipt.subjectContactId)
        assertEquals("INTERACTION_SUMMARY", receipt.presentationType)
        assertEquals("AVAILABLE", receipt.undoState)
        assertEquals(true, AutoWriteRepository(database, context).undo(receipt.changeId, now + 3_000L))
        assertEquals("CORRECTED", database.changeLogDao().findAutoWriteReceipt(receipt.changeId)?.reviewState)
        assertEquals(0, AutoWriteRepository(database, context).observeUnreviewedCount().first())
        assertEquals(
            0,
            repository.observeContactFacts(contactId).first().count {
                it.factType == "INTERACTION_SUMMARY"
            },
        )
    }

    @Test
    fun uniqueExactNameAutomaticallyLinksMessageAndKeepsUndoReceipt() = runBlocking {
        val now = System.currentTimeMillis()
        val contactId = repository.saveUserContact(
            null, "唯一联系人", null, null, null, null, null, null, nowEpochMs = now,
        )

        repository.stageNotificationCandidate(
            NotificationCandidateEntity(
                candidateId = "unique-name-message",
                sourceKey = "unique-name-source",
                packageName = "com.tencent.mm",
                appLabel = "微信",
                title = "唯一联系人",
                body = "资料已经发给你",
                postedAtEpochMs = now,
                platform = "WECHAT",
                conversationTitle = "唯一联系人",
                senderName = "唯一联系人",
                direction = "INCOMING",
            ),
        )

        assertTrue(repository.observeNotificationCandidates().first().isEmpty())
        val fact = repository.observeContactFacts(contactId).first().single()
        assertEquals("INTERACTION_SUMMARY", fact.factType)
        val receipt = database.changeLogDao().observeAutoWriteReceipts().first().single()
        assertEquals(0.99, receipt.confidence ?: 0.0, 0.0)
        assertTrue(AutoWriteRepository(database, context).undo(receipt.changeId, now + 1_000L))
    }

    @Test
    fun duplicateDisplayNameNeverAutomaticallyChoosesOneContact() = runBlocking {
        val now = System.currentTimeMillis()
        repository.saveUserContact(null, "张伟", "13800138001", null, null, null, null, null, now)
        repository.saveUserContact(null, "张伟", "13800138002", null, null, null, null, null, now + 1)

        repository.stageNotificationCandidate(
            NotificationCandidateEntity(
                candidateId = "duplicate-name-message",
                sourceKey = "duplicate-name-source",
                packageName = "com.tencent.mm",
                appLabel = "微信",
                title = "张伟",
                body = "收到",
                postedAtEpochMs = now,
                platform = "WECHAT",
                conversationTitle = "张伟",
                senderName = "张伟",
                direction = "INCOMING",
            ),
        )

        val pending = repository.observeNotificationCandidates().first().single()
        assertNull(pending.linkedContactId)
        assertNull(pending.suggestedContactId)
        assertTrue(database.changeLogDao().observeAutoWriteReceipts().first().isEmpty())
    }

    @Test
    fun explicitHighConfidenceMessageAutomaticallyCreatesReversibleSchedule() = runBlocking {
        val now = System.currentTimeMillis()
        val start = now + 24 * 60 * 60_000L
        repository.saveUserContact(null, "王敏", null, null, null, null, null, null, now)

        repository.stageNotificationCandidate(
            NotificationCandidateEntity(
                candidateId = "auto-schedule-message",
                sourceKey = "auto-schedule-source",
                packageName = "com.ss.android.lark",
                appLabel = "飞书",
                title = "王敏",
                body = "明天下午三点开会",
                postedAtEpochMs = now,
                platform = "FEISHU",
                conversationTitle = "王敏",
                senderName = "王敏",
                direction = "INCOMING",
                messageKind = "SCHEDULE_CANDIDATE",
                insightJson = NotificationInsights(
                    schedule = ScheduleInsight("项目会议", start, confidence = 0.99),
                ).toJsonOrNull(),
            ),
        )

        assertTrue(repository.observeNotificationCandidates().first().isEmpty())
        val schedule =
            requireNotNull(database.scheduleDao().findById("notification-schedule-${com.zhiban.rebuild.runtime.tool.sha256("auto-schedule-source").take(32)}"))
        val receipt = database.changeLogDao().observeAutoWriteReceipts().first()
            .single { it.presentationType == "SCHEDULE_CREATE" }
        assertEquals(schedule.id, receipt.targetId)
        assertTrue(AutoWriteRepository(database, context).undo(receipt.changeId, now + 1_000L))
        assertNull(database.scheduleDao().findById(schedule.id))
    }

    @Test
    fun confirmedPlatformHandlesMatchWechatFeishuDingtalkAndQqCandidates() = runBlocking {
        val now = System.currentTimeMillis()
        val contactId = repository.saveUserContact(
            null, "跨平台联系人", null, null, null, null, null, null, nowEpochMs = now,
        )
        val platforms = listOf("WECHAT", "FEISHU", "DINGTALK", "QQ")
        platforms.forEach { platform ->
            repository.addContactPlatformIdentity(contactId, platform, " @Account-88 ", nowEpochMs = now)
            val candidateId = "platform-${platform.lowercase()}"
            repository.stageNotificationCandidate(
                NotificationCandidateEntity(
                    candidateId = candidateId,
                    sourceKey = "source-$candidateId",
                    packageName = "test.$platform",
                    appLabel = platform,
                    title = "Account-88",
                    body = "收到",
                    postedAtEpochMs = now,
                    platform = platform,
                    conversationTitle = "Account-88",
                    senderName = " account-88 ",
                ),
            )

            val matched = requireNotNull(database.notificationCandidateDao().find(candidateId))
            assertEquals(contactId, matched.suggestedContactId)
            assertEquals(1.0, matched.suggestedContactConfidence, 0.0)
            assertEquals(contactId, matched.linkedContactId)
        }
    }

    @Test
    fun legacyGenericSmsCandidatesAreRemovedFromRelationshipInbox() = runBlocking {
        val now = System.currentTimeMillis()
        repository.stageNotificationCandidate(
            NotificationCandidateEntity(
                candidateId = "generic-sms",
                sourceKey = "generic-sms-source",
                packageName = "com.samsung.android.messaging",
                appLabel = "短信",
                title = "信息",
                body = "【公共服务】天气预警通知",
                postedAtEpochMs = now,
                platform = "OTHER",
                senderName = null,
            ),
        )
        repository.stageNotificationCandidate(
            NotificationCandidateEntity(
                candidateId = "legacy-system-notification",
                sourceKey = "legacy-system-source",
                packageName = "android",
                appLabel = "系统",
                title = "设备维护",
                body = "系统通知",
                postedAtEpochMs = now,
                platform = "OTHER",
            ),
        )
        assertEquals(2, repository.observeNotificationCandidates().first().size)
        assertEquals(2, repository.purgeNonPersonalSmsCandidates())
        assertEquals(0, repository.observeNotificationCandidates().first().size)
    }

    @Test
    fun scheduleAndContactInsightsAreAppliedSeparatelyAndIdempotently() = runBlocking {
        val start = System.currentTimeMillis() + 24 * 60 * 60_000L
        val contactId = repository.saveUserContact(
            null, "王敏", null, null, null, null, null, null, nowEpochMs = 1_000L,
        )
        repository.stageNotificationCandidate(
            NotificationCandidateEntity(
                candidateId = "notification-schedule",
                sourceKey = "source-schedule",
                packageName = "com.ss.android.lark",
                appLabel = "飞书",
                title = "王敏",
                body = "明天下午三点开会",
                postedAtEpochMs = System.currentTimeMillis(),
                platform = "FEISHU",
                conversationTitle = "王敏",
                senderName = "王敏",
                messageKind = "SCHEDULE_CANDIDATE",
                insightJson = NotificationInsights(
                    schedule = ScheduleInsight("项目会议", start, confidence = 0.92),
                ).toJsonOrNull(),
            ),
        )
        assertEquals(1, repository.observeNotificationCandidates().first().size)
        assertEquals(1, repository.observeContactFacts(contactId).first().size)

        val scheduleId = repository.confirmNotificationSchedule("notification-schedule")
        assertEquals(scheduleId, repository.confirmNotificationSchedule("notification-schedule"))
        assertEquals(1, database.scheduleDao().count())
        assertEquals(0, repository.observeNotificationCandidates().first().size)
        assertEquals(1, database.scheduleDao().count())
    }

    @Test
    fun confirmedContactMergeIsNonDestructiveAndReversible() = runBlocking {
        val primary = repository.saveUserContact(null, "王小明", "13800138008", null, "星河科技", null, null, null, 1_000L)
        val duplicate = repository.saveUserContact(null, "王老师", "13800138008", null, null, null, null, null, 2_000L)

        repository.confirmContactMerge(primary, duplicate, "手机号相同", 3_000L)

        assertEquals(1, repository.observeContacts().first().size)
        assertEquals(2, repository.observeRawContacts().first().size)
        assertNotNull(database.contactDao().findRawById(duplicate))
        assertEquals(duplicate, repository.observeContactMergeLinks().first().single().sourceContactId)

        assertEquals(true, repository.undoContactMerge(duplicate, 4_000L))
        assertEquals(2, repository.observeContacts().first().size)
        assertEquals(0, repository.observeContactMergeLinks().first().size)
    }

    @Test
    fun notificationCandidateProjectsMergedContactAndUndoRestoresSource() = runBlocking {
        val canonical = repository.saveUserContact(null, "主联系人", null, null, null, null, null, null, 1_000L)
        val source = repository.saveUserContact(null, "待合并联系人", null, null, null, null, null, null, 2_000L)
        database.notificationCandidateDao().upsert(
            NotificationCandidateEntity(
                candidateId = "merge-notification",
                sourceKey = "merge-notification-source",
                packageName = "com.tencent.mm",
                appLabel = "微信",
                title = "待合并联系人",
                body = "收到",
                postedAtEpochMs = 2_000L,
                suggestedContactId = source,
                suggestedContactConfidence = 0.9,
                linkedContactId = source,
            ),
        )

        repository.confirmContactMerge(canonical, source, "用户确认同一人", 3_000L)
        val merged = repository.observeNotificationCandidates().first().single()
        assertEquals(canonical, merged.suggestedContactId)
        assertEquals(canonical, merged.linkedContactId)

        assertTrue(repository.undoContactMerge(source, 4_000L))
        val restored = repository.observeNotificationCandidates().first().single()
        assertEquals(source, restored.suggestedContactId)
        assertEquals(source, restored.linkedContactId)
    }

    @Test
    fun importantDatesFollowCanonicalContactAndUndoRestoresSource() = runBlocking {
        val canonical = repository.saveUserContact(null, "主联系人", null, null, null, null, null, null, 1_000L)
        val source = repository.saveUserContact(null, "待合并联系人", null, null, null, null, null, null, 2_000L)
        database.contactKnowledgeDao().upsertImportantDate(
            ContactImportantDateEntity(
                dateId = "birthday-source",
                contactId = source,
                kind = "BIRTHDAY",
                year = 1990,
                month = 8,
                day = 10,
                source = "USER",
                evidenceRef = null,
                userConfirmed = true,
                createdAtEpochMs = 2_000L,
                updatedAtEpochMs = 2_000L,
            ),
        )

        repository.confirmContactMerge(canonical, source, "用户确认同一人", 3_000L)
        val merged = repository.observeAllContactImportantDates().first().single()
        assertEquals(canonical, merged.contactId)
        assertEquals("主联系人", merged.displayName)

        assertTrue(repository.undoContactMerge(source, 4_000L))
        val restored = repository.observeAllContactImportantDates().first().single()
        assertEquals(source, restored.contactId)
        assertEquals("待合并联系人", restored.displayName)
    }

    private fun run(id: String) = AgentRunEntity(
        id = id,
        userInput = "test",
        status = "RECEIVED",
        pendingToolCallJson = null,
        expiresAtEpochMs = 99_999L,
        errorCode = null,
        createdAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_000L,
    )

    private fun memory(id: String, kind: String, runId: String) = MemoryEntity(
        id = id,
        kind = kind,
        content = "content",
        sourceRunId = runId,
        createdAtEpochMs = 1_000L,
    )

    private fun testContact(id: String, name: String, phone: String, email: String, createdAtEpochMs: Long) = ContactEntity(
        contactId = id,
        displayName = name,
        normalizedName = name.lowercase(),
        phone = phone,
        email = email,
        wechatId = null,
        company = null,
        title = null,
        aliasesJson = "[]",
        tagsJson = "[]",
        note = null,
        avatarUri = null,
        source = "SYSTEM_CONTACT:test-$id",
        deletedAtEpochMs = null,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = createdAtEpochMs,
    )

    private fun systemContact(
        sourceId: String,
        name: String,
        phone: String? = null,
        company: String? = null,
        wechatIds: List<String> = emptyList(),
        platformIdentities: List<SystemContactPlatformIdentity> = emptyList(),
        emails: List<String> = emptyList(),
        phones: List<String> = phone?.let { listOf(it) } ?: emptyList(),
    ) = SystemContactCandidate(
        sourceId = sourceId,
        displayName = name,
        phones = phones,
        emails = emails,
        wechatIds = wechatIds,
        platformIdentities = platformIdentities,
        company = company,
        title = null,
        note = null,
    )
}
