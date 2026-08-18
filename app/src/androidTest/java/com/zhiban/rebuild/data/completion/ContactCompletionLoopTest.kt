package com.zhiban.rebuild.data.completion

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.agent.ContactAgentDataRepository
import com.zhiban.rebuild.data.contact.ContactProfileField
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 内存库整闭环(对应计划 §验证 4 的脚本,用真组件串联):
 * saveUserContact(email/responsibilities 往返) → prepareOutreach 落 DRAFTED → confirmAndHandoff 转 AWAITING
 * → 对方回复进协调器 → 解析落候选 + 转 RESPONSE_RECEIVED → 用户确认入档(fill-only 写 contacts)
 * → 再扫对账转 COMPLETED。确定性手机号抽取不需 LLM;FakeProfileStore(null) 让组织类询问安全落空。
 */
@RunWith(AndroidJUnit4::class)
class ContactCompletionLoopTest {
    private lateinit var database: AgentDatabase
    private lateinit var controls: AgentControlStore
    private lateinit var contacts: ContactAgentDataRepository
    private lateinit var handoff: FakeHandoff
    private lateinit var repository: ContactCompletionRepository
    private lateinit var coordinator: ContactCompletionCoordinator

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("agent_controls_test", Context.MODE_PRIVATE).edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java)
            .addCallback(AgentDatabase.CALLBACK).allowMainThreadQueries().build()
        controls = AgentControlStore(context, "agent_controls_test")
        contacts = ContactAgentDataRepository(database)
        handoff = FakeHandoff(available = true)
        val generator = FakeGenerator("张三你好，方便把你的手机号发我一下吗？")
        repository = ContactCompletionRepository(database, handoff.impl, generator, controls)
        val parser = ContactCompletionResponseParser(UnusedProvider, FakeProfileStore(null))
        coordinator = ContactCompletionCoordinator(database, parser, controls)
    }

    @After fun tearDown() = database.close()

    @Test fun fullCompletionLoopClosesEndToEnd() = runBlocking {
        val dao = database.contactCompletionRequestDao()

        // 1) 建档(Phase 1):email/responsibilities 经 saveUserContact 往返落库;唯缺 phone,微信可达。
        val contactId = contacts.saveUserContact(
            contactId = null,
            displayName = "张三",
            phone = null,
            wechatId = "wx-zhangsan",
            company = "星河科技有限公司",
            title = "采购经理",
            tag = null,
            note = null,
            email = "zhangsan@example.com",
            responsibilities = "华南区采购",
        )
        val saved = database.contactDao().findById(contactId)!!
        assertEquals("zhangsan@example.com", saved.email)
        assertEquals("华南区采购", saved.responsibilities)
        assertNull(saved.phone)

        // 2) 起草(Phase 4):落 DRAFTED,只问 PHONE。
        val draft = repository.prepareOutreach(contactId)!!
        assertEquals(listOf(ContactProfileField.PHONE), draft.fields)
        assertEquals(ContactCompletionStatus.DRAFTED, dao.findById(draft.requestId)!!.status)

        // 3) 确认 → 半自动跳转(Phase 4):转 AWAITING_REPLY,记录发出时间。
        assertTrue(repository.confirmAndHandoff(draft.requestId, draft.draftText))
        val awaiting = dao.findById(draft.requestId)!!
        assertEquals(ContactCompletionStatus.AWAITING_REPLY, awaiting.status)
        val sentAt = awaiting.sentAtEpochMs!!

        // 4) 对方回复(Phase 5):晚于发出的 1:1 来消息 → 抽字段、落候选、转 RESPONSE_RECEIVED。
        insertIncomingReply(contactId, "我电话13800138000", postedAt = sentAt + 1_000)
        coordinator.processOnce()
        val responded = dao.findById(draft.requestId)!!
        assertEquals(ContactCompletionStatus.RESPONSE_RECEIVED, responded.status)
        val candidate = database.contactKnowledgeDao().findEnrichmentCandidate(responded.responseCandidateId!!)!!
        assertEquals("COMMUNICATION_METHOD", candidate.fieldKind)
        assertEquals("contact-completion-outreach", candidate.providerId)

        // 5) 用户确认入档(Phase 6):fill-only 只补空——phone 写入,既有公司/职责不动。
        assertTrue(contacts.applyContactEnrichmentCandidate(candidate))
        val filled = database.contactDao().findById(contactId)!!
        assertEquals("13800138000", filled.phone)
        assertEquals("星河科技有限公司", filled.company)
        assertEquals("华南区采购", filled.responsibilities)

        // 6) 对账(Phase 7):候选已处理且资料已完整 → COMPLETED,闭环收口。
        coordinator.processOnce()
        assertEquals(ContactCompletionStatus.COMPLETED, dao.findById(draft.requestId)!!.status)
    }

    @Test fun loopStopsAtDraftWhenWechatUnavailable() = runBlocking {
        val contactId = contacts.saveUserContact(
            contactId = null, displayName = "李四", phone = null, wechatId = "wx-lisi",
            company = "公司", title = "职位", tag = null, note = null, email = "l@e.c", responsibilities = "责",
        )
        val draft = repository.prepareOutreach(contactId)!!
        handoff.available = false

        // 微信未装:确认返回 false、保持 DRAFTED、不进入后续闭环。
        assertEquals(false, repository.confirmAndHandoff(draft.requestId, draft.draftText))
        assertEquals(ContactCompletionStatus.DRAFTED, database.contactCompletionRequestDao().findById(draft.requestId)!!.status)
    }

    @Test fun reOutreachAfterExpiryOverwritesStaleCandidateAndAdvances() = runBlocking {
        val dao = database.contactCompletionRequestDao()

        // 1) 建档(同主闭环):唯缺 phone。
        val contactId = contacts.saveUserContact(
            contactId = null, displayName = "王五", phone = null, wechatId = "wx-wangwu",
            company = "司", title = "职", tag = null, note = null, email = "w@e.c", responsibilities = "责",
        )
        // 2) 第一轮:触达→确认→回复→RESPONSE_RECEIVED,候选 PENDING 但用户一直没处理。
        val first = repository.prepareOutreach(contactId)!!
        assertTrue(repository.confirmAndHandoff(first.requestId, first.draftText))
        val sentAt1 = dao.findById(first.requestId)!!.sentAtEpochMs!!
        insertIncomingReply(contactId, "我电话13900139000", postedAt = sentAt1 + 1_000)
        coordinator.processOnce()
        assertEquals(ContactCompletionStatus.RESPONSE_RECEIVED, dao.findById(first.requestId)!!.status)

        // 3) 请求 7 天过期(候选仍 PENDING)→ 同一字段集再次触达,确定性 requestId 复用、旧行被 REPLACE。
        dao.markStatus(first.requestId, ContactCompletionStatus.EXPIRED, System.currentTimeMillis())
        val second = repository.prepareOutreach(contactId)!!
        assertEquals(first.requestId, second.requestId)
        assertTrue(repository.confirmAndHandoff(second.requestId, second.draftText))
        val sentAt2 = dao.findById(second.requestId)!!.sentAtEpochMs!!

        // 4) 二次回复带新值:旧 PENDING 候选必须被覆盖、状态推进 RESPONSE_RECEIVED,不卡 AWAITING。
        insertIncomingReply(contactId, "我电话13700137000", postedAt = sentAt2 + 1_000)
        coordinator.processOnce()
        val responded = dao.findById(second.requestId)!!
        assertEquals(ContactCompletionStatus.RESPONSE_RECEIVED, responded.status)
        val candidate = database.contactKnowledgeDao().findEnrichmentCandidate(responded.responseCandidateId!!)!!
        assertTrue(candidate.proposedValueJson.contains("13700137000"))
        assertTrue(!candidate.proposedValueJson.contains("13900139000"))
    }

    private suspend fun insertIncomingReply(contactId: String, body: String, postedAt: Long) {
        database.notificationCandidateDao().upsert(
            NotificationCandidateEntity(
                candidateId = "reply-${postedAt}",
                sourceKey = "sk-reply-$postedAt",
                packageName = "com.tencent.mm",
                appLabel = "微信",
                title = "张三",
                body = body,
                postedAtEpochMs = postedAt,
                platform = "WECHAT",
                conversationTitle = "张三",
                senderName = "张三",
                direction = "INCOMING",
                isGroupChat = false,
                linkedContactId = contactId,
            ),
        )
    }

    private class FakeGenerator(private val draft: String?) :
        ContactCompletionOutreachGenerator(UnusedProvider, FakeProfileStore(null)) {
        override suspend fun generateDraft(
            contactName: String,
            fields: List<ContactProfileField>,
            businessContext: String?,
            requestKey: String,
        ): String? = draft
    }

    private class FakeHandoff(var available: Boolean) {
        val impl = CompletionHandoff { _, _, _ -> available }
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
