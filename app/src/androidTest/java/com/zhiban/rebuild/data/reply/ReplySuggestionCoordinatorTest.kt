package com.zhiban.rebuild.data.reply

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.config.AgentControlStore
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives [ReplySuggestionCoordinator.processOnce] against an in-memory database with a stubbed draft
 * generator. Covers the plan §14 unit requirements: worthiness/attribution/opt-out gating, per-candidate
 * and per-thread dedupe, 24h expiry, and the FORWARDED → SENT_CONFIRMED reconciliation.
 */
@RunWith(AndroidJUnit4::class)
class ReplySuggestionCoordinatorTest {
    private lateinit var database: AgentDatabase
    private lateinit var controls: AgentControlStore
    private lateinit var generator: FakeGenerator
    private lateinit var coordinator: ReplySuggestionCoordinator

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Isolated prefs file — never touch the device's real "agent_controls".
        context.getSharedPreferences("agent_controls_test", Context.MODE_PRIVATE).edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java)
            .addCallback(AgentDatabase.CALLBACK).allowMainThreadQueries().build()
        controls = AgentControlStore(context, "agent_controls_test")
        generator = FakeGenerator(listOf("好的张总，明早十点前发您", "收到，明天上午给您回复"))
        coordinator = ReplySuggestionCoordinator(database, generator, controls)
    }

    @After fun tearDown() = database.close()

    @Test fun generatesPendingGroupForAttributedQuestion() = runBlocking {
        insertContact("contact-1", "张三")
        insertIncoming("cand-1")

        coordinator.processOnce()

        val group = database.replySuggestionDao().findByCandidateId("cand-1")
        assertEquals(2, group.size)
        assertTrue(group.all { it.status == ReplySuggestionStatus.PENDING })
        assertEquals("张三", group.first().contactName)
        assertTrue(group.first().incomingExcerpt.isNotBlank())
        assertEquals(1, generator.calls)
    }

    @Test fun generatesForQqAndWeWorkPlatforms() = runBlocking {
        insertContact("contact-1", "张三")
        insertContact("contact-2", "李四")
        insertIncoming("cand-qq", conversationTitle = "张三", contactId = "contact-1", platform = "QQ")
        insertIncoming("cand-wework", conversationTitle = "李四", contactId = "contact-2", platform = "WEWORK")

        coordinator.processOnce()

        assertEquals(2, database.replySuggestionDao().findByCandidateId("cand-qq").size)
        assertEquals(2, database.replySuggestionDao().findByCandidateId("cand-wework").size)
        assertEquals(2, generator.calls)
    }

    @Test fun dedupesPerCandidateAcrossRuns() = runBlocking {
        insertContact("contact-1", "张三")
        insertIncoming("cand-1")

        coordinator.processOnce()
        coordinator.processOnce()

        assertEquals(1, generator.calls)
        assertEquals(2, database.replySuggestionDao().findByCandidateId("cand-1").size)
    }

    @Test fun suppressesSecondGroupWhileThreadHasPending() = runBlocking {
        insertContact("contact-1", "张三")
        insertIncoming("cand-1")
        coordinator.processOnce()
        insertIncoming("cand-2", postedAt = System.currentTimeMillis() + 1)

        coordinator.processOnce()

        assertEquals(1, generator.calls)
        assertTrue(database.replySuggestionDao().findByCandidateId("cand-2").isEmpty())
    }

    @Test fun skipsLowConfidenceAttribution() = runBlocking {
        insertContact("contact-1", "张三")
        insertIncoming("cand-1", confidence = 0.4)

        coordinator.processOnce()

        assertEquals(0, generator.calls)
        assertTrue(database.replySuggestionDao().findByCandidateId("cand-1").isEmpty())
    }

    @Test fun skipsLowValueMessage() = runBlocking {
        insertContact("contact-1", "张三")
        insertIncoming("cand-1", body = "哈哈")

        coordinator.processOnce()

        assertEquals(0, generator.calls)
        assertTrue(database.replySuggestionDao().findByCandidateId("cand-1").isEmpty())
    }

    @Test fun skipsWhenUserAlreadyReplied() = runBlocking {
        insertContact("contact-1", "张三")
        val incomingAt = System.currentTimeMillis() - 5_000
        insertIncoming("cand-1", postedAt = incomingAt)
        insertOutgoing("out-1", postedAt = incomingAt + 1_000)

        coordinator.processOnce()

        assertEquals(0, generator.calls)
        assertTrue(database.replySuggestionDao().findByCandidateId("cand-1").isEmpty())
    }

    @Test fun skipsOptedOutContact() = runBlocking {
        insertContact("contact-1", "张三")
        insertIncoming("cand-1")
        controls.setReplyOptOut("contact-1", true)

        coordinator.processOnce()

        assertEquals(0, generator.calls)
        assertTrue(database.replySuggestionDao().findByCandidateId("cand-1").isEmpty())
    }

    @Test fun skipsWhenGloballyDisabled() = runBlocking {
        insertContact("contact-1", "张三")
        insertIncoming("cand-1")
        controls.saveReplySuggestionsEnabled(false)

        coordinator.processOnce()

        assertEquals(0, generator.calls)
        assertTrue(database.replySuggestionDao().findByCandidateId("cand-1").isEmpty())
    }

    @Test fun expiresStalePendingSuggestions() = runBlocking {
        val stale = System.currentTimeMillis() - 25L * 60 * 60 * 1_000
        database.replySuggestionDao().upsertAll(
            listOf(suggestion("rs-old", "cand-old", status = ReplySuggestionStatus.PENDING, createdAt = stale)),
        )

        coordinator.processOnce()

        val rows = database.replySuggestionDao().findByCandidateId("cand-old")
        assertEquals(ReplySuggestionStatus.EXPIRED, rows.first().status)
    }

    @Test fun confirmsForwardedGroupWhenOutgoingCaptured() = runBlocking {
        insertContact("contact-1", "张三")
        insertIncoming("cand-1")
        coordinator.processOnce()
        val forwardAt = System.currentTimeMillis()
        database.replySuggestionDao().markGroupForwarded("cand-1", ReplySuggestionStatus.FORWARDED, forwardAt)
        insertOutgoing("out-1", postedAt = forwardAt + 2_000)

        coordinator.processOnce()

        val rows = database.replySuggestionDao().findByCandidateId("cand-1")
        assertTrue(rows.all { it.status == ReplySuggestionStatus.SENT_CONFIRMED })
        // The already-forwarded candidate must not trigger a fresh generation.
        assertEquals(1, generator.calls)
    }

    private fun suggestion(id: String, candidateId: String, status: String, createdAt: Long) = ReplySuggestionEntity(
        suggestionId = id,
        candidateId = candidateId,
        threadKey = "WECHAT|张三",
        contactId = "contact-1",
        draft = "好的",
        draftIndex = 0,
        status = status,
        createdAtEpochMs = createdAt,
    )

    private suspend fun insertContact(contactId: String, name: String) {
        database.contactDao().insert(
            ContactEntity(
                contactId = contactId,
                displayName = name,
                normalizedName = name,
                phone = null,
                email = null,
                wechatId = null,
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

    private suspend fun insertIncoming(
        candidateId: String,
        conversationTitle: String? = "张三",
        body: String = "明天上午的合同能发我一份吗？",
        contactId: String? = "contact-1",
        confidence: Double = 0.9,
        postedAt: Long = System.currentTimeMillis(),
        platform: String = "WECHAT",
    ) {
        database.notificationCandidateDao().upsert(
            NotificationCandidateEntity(
                candidateId = candidateId,
                sourceKey = "sk-$candidateId",
                packageName = "com.tencent.mm",
                appLabel = "微信",
                title = conversationTitle,
                body = body,
                postedAtEpochMs = postedAt,
                platform = platform,
                conversationTitle = conversationTitle,
                senderName = conversationTitle,
                direction = "INCOMING",
                suggestedContactId = contactId,
                suggestedContactConfidence = confidence,
            ),
        )
    }

    private suspend fun insertOutgoing(candidateId: String, conversationTitle: String? = "张三", postedAt: Long) {
        database.notificationCandidateDao().upsert(
            NotificationCandidateEntity(
                candidateId = candidateId,
                sourceKey = "sk-$candidateId",
                packageName = "com.tencent.mm",
                appLabel = "微信",
                title = conversationTitle,
                body = "好的，明天发你",
                postedAtEpochMs = postedAt,
                platform = "WECHAT",
                conversationTitle = conversationTitle,
                senderName = "我",
                direction = "OUTGOING",
                linkedContactId = "contact-1",
            ),
        )
    }
}

private class FakeProfileStore : ProviderProfileStore {
    override suspend fun load(): ProviderProfile? = null
    override suspend fun save(profile: ProviderProfile) = Unit
    override suspend fun clear() = Unit
}

private class FakeProviderAdapter : ProviderAdapter {
    override suspend fun probe(profile: ProviderProfile): CapabilitySnapshot = throw UnsupportedOperationException()
    override fun stream(request: ModelRequest): Flow<ModelEvent> = throw UnsupportedOperationException()
    override fun cancel(requestId: String): Boolean = false
}

private class FakeGenerator(private val drafts: List<String>) : ReplyDraftGenerator(FakeProviderAdapter(), FakeProfileStore()) {
    var calls = 0
        private set

    override suspend fun generateDrafts(context: ReplyDraftContext): List<String> {
        calls++
        return drafts
    }
}
