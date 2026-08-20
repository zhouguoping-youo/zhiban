package com.zhiban.rebuild.data.completion

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.config.AgentControlStore
import com.zhiban.rebuild.data.contact.ContactEnrichmentCandidateEntity
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.notification.NotificationCandidateEntity
import com.zhiban.rebuild.provider.CapabilitySnapshot
import com.zhiban.rebuild.provider.ModelEvent
import com.zhiban.rebuild.provider.ModelRequest
import com.zhiban.rebuild.provider.ProviderAdapter
import com.zhiban.rebuild.provider.ProviderProfile
import com.zhiban.rebuild.provider.ProviderProfileStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives [ContactCompletionCoordinator.processOnce] against an in-memory database with a real parser
 * (deterministic phone extraction needs no LLM; FakeProfileStore(null) makes any org-field ask safely empty).
 * Covers the reply-detection gates: 群聊排除、归因置信度、晚于发出、无进行中请求、全局开关、超时过期，
 * 以及抽到字段才转 RESPONSE_RECEIVED 并落候选、"回复无字段"保持 AWAITING 的负路径。
 */
@RunWith(AndroidJUnit4::class)
class ContactCompletionCoordinatorTest {
    private lateinit var database: AgentDatabase
    private lateinit var controls: AgentControlStore
    private lateinit var coordinator: ContactCompletionCoordinator

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("agent_controls_test", Context.MODE_PRIVATE).edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java)
            .addCallback(AgentDatabase.CALLBACK).allowMainThreadQueries().build()
        controls = AgentControlStore(context, "agent_controls_test")
        val parser = ContactCompletionResponseParser(UnusedProvider, FakeProfileStore(null))
        coordinator = ContactCompletionCoordinator(database, parser, controls)
    }

    @After fun tearDown() = database.close()

    @Test fun stagesCandidateAndMarksResponseReceived() = runBlocking {
        insertContact("c1")
        val sentAt = System.currentTimeMillis() - 10_000
        seedAwaitingRequest("c1", """["PHONE"]""", sentAt)
        insertIncoming("msg-1", "c1", body = "我电话13800138000", postedAt = sentAt + 5_000)

        coordinator.processOnce()

        val candidate = database.contactKnowledgeDao().findEnrichmentCandidate("cc-ccr-1-COMMUNICATION_METHOD")
        assertNotNull(candidate)
        assertTrue(candidate!!.proposedValueJson.contains("13800138000"))
        val request = database.contactCompletionRequestDao().findById("ccr-1")!!
        assertEquals(ContactCompletionStatus.RESPONSE_RECEIVED, request.status)
        assertEquals("cc-ccr-1-COMMUNICATION_METHOD", request.responseCandidateId)
        assertNotNull(request.respondedAtEpochMs)
    }

    @Test fun excludesGroupChatReplies() = runBlocking {
        insertContact("c1")
        val sentAt = System.currentTimeMillis() - 10_000
        seedAwaitingRequest("c1", """["PHONE"]""", sentAt)
        insertIncoming("msg-1", "c1", body = "我电话13800138000", postedAt = sentAt + 5_000, isGroup = true)

        coordinator.processOnce()

        assertNull(database.contactKnowledgeDao().findEnrichmentCandidate("cc-ccr-1-COMMUNICATION_METHOD"))
        assertEquals(ContactCompletionStatus.AWAITING_REPLY, database.contactCompletionRequestDao().findById("ccr-1")!!.status)
    }

    @Test fun ignoresReplyPostedBeforeSend() = runBlocking {
        insertContact("c1")
        val sentAt = System.currentTimeMillis() - 10_000
        seedAwaitingRequest("c1", """["PHONE"]""", sentAt)
        insertIncoming("msg-1", "c1", body = "我电话13800138000", postedAt = sentAt - 5_000) // 早于发出

        coordinator.processOnce()

        assertNull(database.contactKnowledgeDao().findEnrichmentCandidate("cc-ccr-1-COMMUNICATION_METHOD"))
        assertEquals(ContactCompletionStatus.AWAITING_REPLY, database.contactCompletionRequestDao().findById("ccr-1")!!.status)
    }

    @Test fun ignoresWhenNoAwaitingRequest() = runBlocking {
        insertContact("c1")
        insertIncoming("msg-1", "c1", body = "我电话13800138000", postedAt = System.currentTimeMillis())

        coordinator.processOnce()

        assertNull(database.contactKnowledgeDao().findEnrichmentCandidate("cc-ccr-1-COMMUNICATION_METHOD"))
    }

    @Test fun ignoresLowConfidenceAttribution() = runBlocking {
        insertContact("c1")
        val sentAt = System.currentTimeMillis() - 10_000
        seedAwaitingRequest("c1", """["PHONE"]""", sentAt)
        insertIncoming("msg-1", null, body = "我电话13800138000", postedAt = sentAt + 5_000, suggestedContactId = "c1", confidence = 0.4)

        coordinator.processOnce()

        assertNull(database.contactKnowledgeDao().findEnrichmentCandidate("cc-ccr-1-COMMUNICATION_METHOD"))
    }

    @Test fun usesSuggestedContactWhenConfidenceHighEnough() = runBlocking {
        insertContact("c1")
        val sentAt = System.currentTimeMillis() - 10_000
        seedAwaitingRequest("c1", """["PHONE"]""", sentAt)
        insertIncoming("msg-1", null, body = "我电话13800138000", postedAt = sentAt + 5_000, suggestedContactId = "c1", confidence = 0.9)

        coordinator.processOnce()

        assertNotNull(database.contactKnowledgeDao().findEnrichmentCandidate("cc-ccr-1-COMMUNICATION_METHOD"))
    }

    @Test fun noFieldReplyStaysAwaitingAndStagesNothing() = runBlocking {
        insertContact("c1")
        val sentAt = System.currentTimeMillis() - 10_000
        seedAwaitingRequest("c1", """["PHONE"]""", sentAt)
        insertIncoming("msg-1", "c1", body = "好的回头说", postedAt = sentAt + 5_000)

        coordinator.processOnce()

        assertNull(database.contactKnowledgeDao().findEnrichmentCandidate("cc-ccr-1-COMMUNICATION_METHOD"))
        // 抽不到字段:保持 AWAITING(7 天后 EXPIRED),不误标 RESPONSE_RECEIVED。
        assertEquals(ContactCompletionStatus.AWAITING_REPLY, database.contactCompletionRequestDao().findById("ccr-1")!!.status)
    }

    @Test fun attributedReplyStagesWechatIdentityStub() = runBlocking {
        // 无 wechatId 也无 WECHAT 身份的联系人：对方在微信里回复（归因成功）即证明微信可达，
        // 自动挂 WECHAT stub（handle=发送者显示名、userConfirmed=false、source=COMPLETION_REPLY），
        // 之后微信号字段不再算缺失、后续触达闸门自然通过——每个联系人只需操作一次。
        insertContact("c1", wechatId = null)
        val sentAt = System.currentTimeMillis() - 10_000
        seedAwaitingRequest("c1", """["PHONE"]""", sentAt)
        insertIncoming("msg-1", "c1", body = "好的回头说", postedAt = sentAt + 5_000)

        coordinator.processOnce()

        val identities = database.contactIdentityDao().platformIdentities("c1")
        val stub = identities.firstOrNull { it.platform == "WECHAT" }
        assertNotNull(stub)
        assertEquals("张三", stub!!.handle)
        assertEquals("COMPLETION_REPLY", stub.source)
        assertEquals(false, stub.userConfirmed)
    }

    @Test fun wechatIdentityStubNotDuplicatedOrOverwritten() = runBlocking {
        // 已有 WECHAT 身份（含已确认真值）时不重复挂、不覆盖。
        insertContact("c1", wechatId = null)
        insertWechatIdentity("c1", handle = "real-wxid", confirmed = true)
        val sentAt = System.currentTimeMillis() - 10_000
        seedAwaitingRequest("c1", """["PHONE"]""", sentAt)
        insertIncoming("msg-1", "c1", body = "好的回头说", postedAt = sentAt + 5_000)

        coordinator.processOnce()

        val identities = database.contactIdentityDao().platformIdentities("c1")
        assertEquals(1, identities.count { it.platform == "WECHAT" })
        assertEquals("real-wxid", identities.first { it.platform == "WECHAT" }.handle)
    }

    @Test fun expiresStaleAwaitingRequests() = runBlocking {
        insertContact("c1")
        val now = System.currentTimeMillis()
        seedAwaitingRequest("c1", """["PHONE"]""", sentAt = now - 8 * 24 * 3600_000L, expiresAt = now - 1_000)

        coordinator.processOnce()

        assertEquals(ContactCompletionStatus.EXPIRED, database.contactCompletionRequestDao().findById("ccr-1")!!.status)
    }

    @Test fun respectsGlobalDisableButStillExpires() = runBlocking {
        insertContact("c1")
        val sentAt = System.currentTimeMillis() - 10_000
        seedAwaitingRequest("c1", """["PHONE"]""", sentAt)
        insertIncoming("msg-1", "c1", body = "我电话13800138000", postedAt = sentAt + 5_000)
        controls.saveContactCompletionEnabled(false)

        coordinator.processOnce()

        assertNull(database.contactKnowledgeDao().findEnrichmentCandidate("cc-ccr-1-COMMUNICATION_METHOD"))
        assertEquals(ContactCompletionStatus.AWAITING_REPLY, database.contactCompletionRequestDao().findById("ccr-1")!!.status)
    }

    @Test fun reconcileCompletesWhenCandidatesResolved() = runBlocking {
        insertContact("c1")
        seedResponseReceived("c1")
        insertEnrichment("ccr-1", status = "APPROVED") // 候选已处理

        coordinator.processOnce()

        assertEquals(ContactCompletionStatus.COMPLETED, database.contactCompletionRequestDao().findById("ccr-1")!!.status)
    }

    @Test fun reconcileStaysWhenCandidatesStillPending() = runBlocking {
        insertContact("c1")
        seedResponseReceived("c1")
        insertEnrichment("ccr-1", status = "PENDING") // 候选仍待用户确认

        coordinator.processOnce()

        assertEquals(ContactCompletionStatus.RESPONSE_RECEIVED, database.contactCompletionRequestDao().findById("ccr-1")!!.status)
    }

    @Test fun reconcileCompletesWhenContactManuallyFilled() = runBlocking {
        // 用户绕过候选直接手改补齐:即便候选仍 PENDING,也按"资料已完整"收敛 COMPLETED。
        insertContact("c1", phone = "13800138000", email = "a@b.c", company = "司", title = "职", responsibilities = "责")
        seedResponseReceived("c1")
        insertEnrichment("ccr-1", status = "PENDING")

        coordinator.processOnce()

        assertEquals(ContactCompletionStatus.COMPLETED, database.contactCompletionRequestDao().findById("ccr-1")!!.status)
    }

    private suspend fun insertContact(
        contactId: String,
        phone: String? = null,
        email: String? = null,
        wechatId: String? = "wx-1",
        company: String? = null,
        title: String? = null,
        responsibilities: String? = null,
    ) {
        database.contactDao().insert(
            ContactEntity(
                contactId = contactId,
                displayName = "张三",
                normalizedName = "张三",
                phone = phone,
                email = email,
                wechatId = wechatId,
                company = company,
                title = title,
                aliasesJson = "[]",
                tagsJson = "[]",
                note = null,
                avatarUri = null,
                source = "USER",
                deletedAtEpochMs = null,
                createdAtEpochMs = 1,
                updatedAtEpochMs = 1,
                responsibilities = responsibilities,
            ),
        )
    }

    private suspend fun insertWechatIdentity(contactId: String, handle: String, confirmed: Boolean) {
        val now = System.currentTimeMillis()
        database.contactIdentityDao().upsertPlatformIdentity(
            com.zhiban.rebuild.data.contact.ContactPlatformIdentityEntity(
                identityId = "id-$handle",
                contactId = contactId,
                platform = "WECHAT",
                handle = handle,
                normalizedHandle = handle.lowercase(),
                platformUserId = null,
                source = "USER_INPUT",
                userConfirmed = confirmed,
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
            ),
        )
    }

    private suspend fun seedAwaitingRequest(
        contactId: String,
        requestedFieldsJson: String,
        sentAt: Long,
        expiresAt: Long = System.currentTimeMillis() + 7L * 24 * 3600_000L,
        requestId: String = "ccr-1",
    ) {
        database.contactCompletionRequestDao().upsert(
            ContactCompletionRequestEntity(
                requestId = requestId,
                contactId = contactId,
                requestedFieldsJson = requestedFieldsJson,
                draftText = "方便发我下手机号吗",
                status = ContactCompletionStatus.AWAITING_REPLY,
                sentAtEpochMs = sentAt,
                createdAtEpochMs = sentAt,
                expiresAtEpochMs = expiresAt,
                updatedAtEpochMs = sentAt,
            ),
        )
    }

    private suspend fun insertIncoming(
        candidateId: String,
        linkedContactId: String?,
        body: String,
        postedAt: Long,
        isGroup: Boolean = false,
        suggestedContactId: String? = null,
        confidence: Double = 0.0,
    ) {
        database.notificationCandidateDao().upsert(
            NotificationCandidateEntity(
                candidateId = candidateId,
                sourceKey = "sk-$candidateId",
                packageName = "com.tencent.mm",
                appLabel = "微信",
                title = "张三",
                body = body,
                postedAtEpochMs = postedAt,
                platform = "WECHAT",
                conversationTitle = "张三",
                senderName = "张三",
                direction = "INCOMING",
                isGroupChat = isGroup,
                suggestedContactId = suggestedContactId,
                suggestedContactConfidence = confidence,
                linkedContactId = linkedContactId,
            ),
        )
    }

    private suspend fun seedResponseReceived(contactId: String, requestId: String = "ccr-1") {
        val now = System.currentTimeMillis()
        database.contactCompletionRequestDao().upsert(
            ContactCompletionRequestEntity(
                requestId = requestId,
                contactId = contactId,
                requestedFieldsJson = """["PHONE"]""",
                draftText = "方便发我下手机号吗",
                status = ContactCompletionStatus.RESPONSE_RECEIVED,
                sentAtEpochMs = now - 5_000,
                respondedAtEpochMs = now - 1_000,
                responseCandidateId = "cc-$requestId-COMMUNICATION_METHOD",
                createdAtEpochMs = now - 5_000,
                expiresAtEpochMs = now + 7L * 24 * 3600_000L,
                updatedAtEpochMs = now - 1_000,
            ),
        )
    }

    private suspend fun insertEnrichment(requestId: String, status: String) {
        val now = System.currentTimeMillis()
        database.contactKnowledgeDao().insertEnrichmentCandidateIfAbsent(
            ContactEnrichmentCandidateEntity(
                candidateId = "cc-$requestId-COMMUNICATION_METHOD",
                contactId = "c1",
                providerId = "contact-completion-outreach",
                fieldKind = "COMMUNICATION_METHOD",
                proposedValueJson = """{"phone":"13800138000"}""",
                sourceRef = "completion:$requestId:COMMUNICATION_METHOD",
                confidence = 0.9,
                status = status,
                observedAtEpochMs = now,
                expiresAtEpochMs = now + 30L * 24 * 3600_000L,
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
            ),
        )
    }

    private object UnusedProvider : ProviderAdapter {
        override suspend fun probe(profile: ProviderProfile): CapabilitySnapshot = error("not used")
        override fun stream(request: ModelRequest): Flow<ModelEvent> = error("not used")
        override fun cancel(requestId: String): Boolean = false
    }

    private class FakeProfileStore(private val profile: ProviderProfile?) : ProviderProfileStore {
        override suspend fun load(): ProviderProfile? = profile
        override suspend fun save(profile: ProviderProfile) = Unit
        override suspend fun clear() = Unit
    }
}
