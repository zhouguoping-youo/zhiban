package com.zhiban.rebuild.data.completion

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.notification.NotificationCandidateEntity
import com.zhiban.rebuild.runtime.config.AgentControlStore
import com.zhiban.rebuild.runtime.provider.CapabilitySnapshot
import com.zhiban.rebuild.runtime.provider.ModelEvent
import com.zhiban.rebuild.runtime.provider.ModelRequest
import com.zhiban.rebuild.runtime.provider.ProviderAdapter
import com.zhiban.rebuild.runtime.provider.ProviderProfile
import com.zhiban.rebuild.runtime.provider.ProviderProfileStore
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

    private suspend fun insertContact(contactId: String) {
        database.contactDao().insert(
            ContactEntity(
                contactId = contactId,
                displayName = "张三",
                normalizedName = "张三",
                phone = null,
                email = null,
                wechatId = "wx-1",
                company = null,
                title = null,
                aliasesJson = "[]",
                tagsJson = "[]",
                note = null,
                avatarUri = null,
                source = "USER",
                deletedAtEpochMs = null,
                createdAtEpochMs = 1,
                updatedAtEpochMs = 1,
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
