package com.zhiban.rebuild.data.completion

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.ContactPlatformIdentityEntity
import com.zhiban.rebuild.data.contact.ContactProfileField
import com.zhiban.rebuild.runtime.config.AgentControlStore
import com.zhiban.rebuild.runtime.provider.CapabilitySnapshot
import com.zhiban.rebuild.runtime.provider.ModelEvent
import com.zhiban.rebuild.runtime.provider.ModelRequest
import com.zhiban.rebuild.runtime.provider.ProviderAdapter
import com.zhiban.rebuild.runtime.provider.ProviderProfile
import com.zhiban.rebuild.runtime.provider.ProviderProfileStore
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
 * [CompletionHandoff]. Covers the prepareOutreach 闸门（总开关/免打扰/单活跃请求/资料已完整/微信不可达）、微信
 * 平台身份可达、确定性 requestId 幂等，以及 confirmAndHandoff 的 DRAFTED→AWAITING_REPLY 状态机（含微信未装时
 * 保持 DRAFTED)。知伴绝不代发——handoff 只表示"已打开预填面板"。
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

    @Test fun prepareOutreachReturnsNullWhenProfileComplete() = runBlocking {
        insertContact("c1", phone = "13800000000", email = "a@b.c", wechatId = "wx-1", company = "司", title = "职", responsibilities = "责")

        assertNull(repository.prepareOutreach("c1"))
        assertEquals(0, generator.calls) // 无缺失字段不起草
    }

    @Test fun prepareOutreachReturnsNullWhenNotWechatReachable() = runBlocking {
        insertContact("c1", phone = null, wechatId = null) // 无 wechatId 也无 WECHAT 平台身份

        assertNull(repository.prepareOutreach("c1"))
        assertEquals(0, generator.calls) // 触达只走微信，不可达不起草
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

        assertTrue(repository.confirmAndHandoff(draft.requestId))

        assertEquals(1, handoff.calls)
        val row = database.contactCompletionRequestDao().findById(draft.requestId)!!
        assertEquals(ContactCompletionStatus.AWAITING_REPLY, row.status)
        assertNotNull(row.sentAtEpochMs)
    }

    @Test fun confirmAndHandoffPreservesDraftWhenWechatUnavailable() = runBlocking {
        insertContact("c1", phone = null, wechatId = "wx-1")
        val draft = repository.prepareOutreach("c1")!!
        handoff.available = false

        assertFalse(repository.confirmAndHandoff(draft.requestId))

        assertEquals(1, handoff.calls)
        // 微信未装/不可达:保持 DRAFTED,绝不谎报已发送。
        assertEquals(ContactCompletionStatus.DRAFTED, database.contactCompletionRequestDao().findById(draft.requestId)!!.status)
    }

    @Test fun confirmAndHandoffRejectsNonDraftedRequest() = runBlocking {
        insertContact("c1", phone = null, wechatId = "wx-1")
        seedRequest("c1", ContactCompletionStatus.AWAITING_REPLY, requestId = "ccr-x")

        assertFalse(repository.confirmAndHandoff("ccr-x"))
        assertEquals(0, handoff.calls) // 非 DRAFTED 不再二次跳转
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
    private class FakeGenerator(private val draft: String?) :
        ContactCompletionOutreachGenerator(UnusedProvider, UnusedProfileStore) {
        var calls = 0
            private set

        override suspend fun generateDraft(
            contactName: String,
            fields: List<ContactProfileField>,
            businessContext: String?,
            requestKey: String,
        ): String? {
            calls++
            return draft
        }
    }

    /** fun-interface 缝隙,模拟微信在/不在;记录调用次数以验证"不二次跳转"。 */
    private class FakeHandoff(var available: Boolean) {
        var calls = 0
            private set
        val impl = CompletionHandoff { _, _, _ ->
            calls++
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
