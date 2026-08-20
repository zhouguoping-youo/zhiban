package com.zhiban.rebuild.data.completion

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.config.AgentControlStore
import com.zhiban.rebuild.data.communication.SmartForwardHandoff
import com.zhiban.rebuild.data.communication.SmartForwardOutcome
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.ContactPlatformIdentityEntity
import com.zhiban.rebuild.data.contact.ContactProfileField
import com.zhiban.rebuild.provider.CapabilitySnapshot
import com.zhiban.rebuild.provider.ModelEvent
import com.zhiban.rebuild.provider.ModelRequest
import com.zhiban.rebuild.provider.ProviderAdapter
import com.zhiban.rebuild.provider.ProviderProfile
import com.zhiban.rebuild.provider.ProviderProfileStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives [ContactCompletionRepository] against an in-memory database with a stubbed generator and a fake
 * [CompletionHandoff]. Covers the prepareOutreach 闸门（总开关/免打扰/单活跃请求/资料已完整）、确定性
 * requestId 幂等，以及 confirmAndHandoff 的 DRAFTED→AWAITING_REPLY 状态机（含微信未装时保持 DRAFTED)。
 * 知伴绝不代发——handoff 只表示"已打开分享面板"。不要求预知对方微信号（分享面板用户亲选联系人）。
 */
@RunWith(AndroidJUnit4::class)
class ContactCompletionRepositoryTest {
    private lateinit var database: AgentDatabase
    private lateinit var controls: AgentControlStore
    private lateinit var generator: FakeGenerator
    private lateinit var handoff: FakeHandoff
    private lateinit var repository: ContactCompletionRepository

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Isolated prefs file — never touch the device's real "agent_controls".
        context.getSharedPreferences("agent_controls_test", Context.MODE_PRIVATE).edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java)
            .addCallback(AgentDatabase.CALLBACK).allowMainThreadQueries().build()
        controls = AgentControlStore(context, "agent_controls_test")
        generator = FakeGenerator("张三你好，方便把你的手机号发我一下吗？")
        handoff = FakeHandoff(available = true)
        repository = ContactCompletionRepository(database, handoff.impl, generator, controls)
    }

    @After fun tearDown() = database.close()

    @Test fun prepareOutreachReturnsNullWhenGloballyDisabled() = runBlocking {
        insertContact("c1", phone = null, wechatId = "wx-1")
        controls.saveContactCompletionEnabled(false)

        assertNull(repository.prepareOutreach("c1"))
        assertEquals(0, generator.calls)
    }

    @Test fun prepareOutreachReturnsNullWhenOptedOut() = runBlocking {
        insertContact("c1", phone = null, wechatId = "wx-1")
        controls.setCompletionOptOut("c1", true)

        assertNull(repository.prepareOutreach("c1"))
        assertEquals(0, generator.calls)
    }

    @Test fun prepareOutreachReturnsNullWhenActiveRequestExists() = runBlocking {
        insertContact("c1", phone = null, wechatId = "wx-1")
        seedRequest("c1", ContactCompletionStatus.DRAFTED)

        assertNull(repository.prepareOutreach("c1"))
        assertEquals(0, generator.calls) // 每联系人至多一个进行中请求：不再起草
    }

    @Test fun prepareOutreachReturnsNullWhenResponseReceivedRequestExists() = runBlocking {
        insertContact("c1", phone = null, wechatId = "wx-1")
        seedRequest("c1", ContactCompletionStatus.RESPONSE_RECEIVED)

        // 回复已收到但候选未处理完:再触达会复用确定性 requestId 覆盖掉 RESPONSE_RECEIVED 行、丢回复,必须拦下。
        assertNull(repository.prepareOutreach("c1"))
        assertEquals(0, generator.calls)
    }

    @Test fun prepareOutreachReturnsNullWhenProfileComplete() = runBlocking {
        insertContact("c1", phone = "13800000000", email = "a@b.c", wechatId = "wx-1", company = "司", title = "职", responsibilities = "责")

        assertNull(repository.prepareOutreach("c1"))
        assertEquals(0, generator.calls) // 无缺失字段不起草
    }

    @Test fun prepareOutreachDraftsEvenWithoutWechatHandle() = runBlocking {
        // 无 wechatId 也无 WECHAT 平台身份：依然起草——触达走微信分享面板由用户亲选联系人，
        // 无需预知对方微信号；对方回复归因成功后协调器自动挂 WECHAT stub 身份（每联系人操作一次）。
        insertContact("c1", phone = null, wechatId = null, email = "a@b.c", company = "司", title = "职", responsibilities = "责")

        val draft = repository.prepareOutreach("c1")

        assertNotNull(draft)
        // 无微信时缺失字段本身含 WECHAT——草稿里也会请对方补微信号，合理。
        assertEquals(listOf(ContactProfileField.PHONE, ContactProfileField.WECHAT), draft!!.fields)
    }

    @Test fun prepareOutreachReachableViaWechatPlatformIdentity() = runBlocking {
        // 除 phone 外填满,使缺失恰好=[PHONE];无 wechatId 但有 WECHAT 平台身份(stub handle)也算可达。
        insertContact("c1", phone = null, email = "a@b.c", wechatId = null, company = "司", title = "职", responsibilities = "责")
        insertWechatIdentity("c1")

        val draft = repository.prepareOutreach("c1")

        assertNotNull(draft)
        assertEquals(listOf(ContactProfileField.PHONE), draft!!.fields)
    }

    @Test fun prepareOutreachDraftsAndPersistsWhenReachableAndIncomplete() = runBlocking {
        insertContact("c1", phone = null, email = "a@b.c", wechatId = "wx-1", company = "司", title = "职", responsibilities = "责")

        val draft = repository.prepareOutreach("c1")

        assertNotNull(draft)
        assertEquals("c1", draft!!.contactId)
        assertEquals("张三", draft.contactName)
        assertEquals(listOf(ContactProfileField.PHONE), draft.fields)
        assertEquals("张三你好，方便把你的手机号发我一下吗？", draft.draftText)
        val row = database.contactCompletionRequestDao().findById(draft.requestId)
        assertNotNull(row)
        assertEquals(ContactCompletionStatus.DRAFTED, row!!.status)
        assertEquals("c1", row.contactId)
        assertTrue(row.expiresAtEpochMs > row.createdAtEpochMs)
    }

    @Test fun prepareOutreachIsIdempotentForSameFieldSet() = runBlocking {
        insertContact("c1", phone = null, wechatId = "wx-1")

        val first = repository.prepareOutreach("c1")!!
        // 进行中有活跃请求会触发单活跃闸门;先撤掉再重起草,验证确定性 requestId 覆盖而非重复。
        repository.cancel(first.requestId)
        val second = repository.prepareOutreach("c1")!!

        assertEquals(first.requestId, second.requestId)
        assertEquals(1, database.contactCompletionRequestDao().countActiveForContact("c1", System.currentTimeMillis()))
    }

    @Test fun confirmAndHandoffMarksAwaitingOnSuccess() = runBlocking {
        insertContact("c1", phone = null, wechatId = "wx-1")
        val draft = repository.prepareOutreach("c1")!!
        val edited = "张三，方便发我一下手机号吗？（已改）"

        assertTrue(repository.confirmAndHandoff(draft.requestId, edited))

        assertEquals(1, handoff.calls)
        assertEquals(edited, handoff.lastMessage) // 跳转的是编辑后的最终稿
        val row = database.contactCompletionRequestDao().findById(draft.requestId)!!
        assertEquals(ContactCompletionStatus.AWAITING_REPLY, row.status)
        assertEquals(edited, row.draftText) // 行记录实际发出的文本
        assertNotNull(row.sentAtEpochMs)
    }

    @Test fun smartForwardPrefillsAndNeverCallsShareFallbackWhenItSucceeds() = runBlocking {
        insertContact("c1", phone = null, wechatId = "wx-1")
        val draft = repository.prepareOutreach("c1") ?: error("draft expected")
        controls.saveSmartForwardEnabled(true)
        val smartCalls = mutableListOf<String>()
        val smartRepository = ContactCompletionRepository(
            database,
            handoff.impl,
            generator,
            controls,
            SmartForwardHandoff { name, message ->
                smartCalls += "$name:$message"
                SmartForwardOutcome.PREFILLED
            },
        )

        assertTrue(smartRepository.confirmAndHandoff(draft.requestId, "最终草稿"))
        assertEquals(listOf("张三:最终草稿"), smartCalls)
        assertEquals(0, handoff.calls)
    }

    @Test fun confirmAndHandoffPreservesDraftWhenWechatUnavailable() = runBlocking {
        insertContact("c1", phone = null, wechatId = "wx-1")
        val draft = repository.prepareOutreach("c1")!!
        handoff.available = false

        assertFalse(repository.confirmAndHandoff(draft.requestId, "改写后的稿子"))

        assertEquals(1, handoff.calls)
        // 微信未装/不可达:保持 DRAFTED 且原稿不被改写,绝不谎报已发送。
        val row = database.contactCompletionRequestDao().findById(draft.requestId)!!
        assertEquals(ContactCompletionStatus.DRAFTED, row.status)
        assertEquals(draft.draftText, row.draftText)
    }

    @Test fun confirmAndHandoffRejectsNonDraftedRequest() = runBlocking {
        insertContact("c1", phone = null, wechatId = "wx-1")
        seedRequest("c1", ContactCompletionStatus.AWAITING_REPLY, requestId = "ccr-x")

        assertFalse(repository.confirmAndHandoff("ccr-x", "任何文本"))
        assertEquals(0, handoff.calls) // 非 DRAFTED 不再二次跳转
    }

    @Test fun confirmAndHandoffRejectsBlankText() = runBlocking {
        insertContact("c1", phone = null, wechatId = "wx-1")
        val draft = repository.prepareOutreach("c1")!!

        assertFalse(repository.confirmAndHandoff(draft.requestId, "   "))

        assertEquals(0, handoff.calls) // 空稿不跳转
        assertEquals(ContactCompletionStatus.DRAFTED, database.contactCompletionRequestDao().findById(draft.requestId)!!.status)
    }

    @Test fun cancelMarksCancelled() = runBlocking {
        insertContact("c1", phone = null, wechatId = "wx-1")
        val draft = repository.prepareOutreach("c1")!!

        repository.cancel(draft.requestId)

        assertEquals(ContactCompletionStatus.CANCELLED, database.contactCompletionRequestDao().findById(draft.requestId)!!.status)
    }

    @Test fun optOutContactSetsFlagAndCancelsActive() = runBlocking {
        insertContact("c1", phone = null, wechatId = "wx-1")
        val draft = repository.prepareOutreach("c1")!!

        repository.optOutContact("c1")

        assertTrue(controls.isCompletionOptedOut("c1"))
        assertEquals(ContactCompletionStatus.CANCELLED, database.contactCompletionRequestDao().findById(draft.requestId)!!.status)
        // 免打扰后再触达即被闸门拦下。
        assertNull(repository.prepareOutreach("c1"))
    }

    @Test fun observeActionableEmitsActiveRequest() = runBlocking {
        insertContact("c1", phone = null, wechatId = "wx-1")
        val draft = repository.prepareOutreach("c1")!!

        val active = repository.observeActionable("c1").first()

        assertEquals(listOf(draft.requestId), active.map { it.requestId })
    }

    private suspend fun insertContact(
        contactId: String,
        name: String = "张三",
        phone: String? = null,
        email: String? = null,
        wechatId: String? = null,
        company: String? = null,
        title: String? = null,
        responsibilities: String? = null,
    ) {
        database.contactDao().insert(
            ContactEntity(
                contactId = contactId,
                displayName = name,
                normalizedName = name,
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

    private suspend fun insertWechatIdentity(contactId: String) {
        database.contactIdentityDao().upsertPlatformIdentity(
            ContactPlatformIdentityEntity(
                identityId = "id-$contactId",
                contactId = contactId,
                platform = "WECHAT",
                handle = "wx_handle",
                normalizedHandle = "wx_handle",
                platformUserId = null,
                source = "USER",
                userConfirmed = true,
                createdAtEpochMs = 1,
                updatedAtEpochMs = 1,
            ),
        )
    }

    private suspend fun seedRequest(contactId: String, status: String, requestId: String = "ccr-seed") {
        val now = System.currentTimeMillis()
        database.contactCompletionRequestDao().upsert(
            ContactCompletionRequestEntity(
                requestId = requestId,
                contactId = contactId,
                requestedFieldsJson = """["PHONE"]""",
                draftText = "方便发我下手机号吗",
                status = status,
                createdAtEpochMs = now,
                expiresAtEpochMs = now + 1_000_000,
                updatedAtEpochMs = now,
            ),
        )
    }

    /** 覆写 [ContactCompletionOutreachGenerator.generateDraft] 返回固定草稿,provider 绝不触网。 */
    private class FakeGenerator(private val draft: String?) : ContactCompletionOutreachGenerator(UnusedProvider, UnusedProfileStore) {
        var calls = 0
            private set

        override suspend fun generateDraft(contactName: String, fields: List<ContactProfileField>, businessContext: String?, requestKey: String): String? {
            calls++
            return draft
        }
    }

    /** fun-interface 缝隙,模拟微信在/不在;记录调用次数与最后一次文本以验证"不二次跳转/发的是最终稿"。 */
    private class FakeHandoff(var available: Boolean) {
        var calls = 0
            private set
        var lastMessage: String? = null
            private set
        val impl = CompletionHandoff { _, _, message ->
            calls++
            lastMessage = message
            available
        }
    }

    private object UnusedProvider : ProviderAdapter {
        override suspend fun probe(profile: ProviderProfile): CapabilitySnapshot = error("not used")
        override fun stream(request: ModelRequest): Flow<ModelEvent> = error("not used")
        override fun cancel(requestId: String): Boolean = false
    }

    private object UnusedProfileStore : ProviderProfileStore {
        override suspend fun load(): ProviderProfile? = null
        override suspend fun save(profile: ProviderProfile) = Unit
        override suspend fun clear() = Unit
    }
}
