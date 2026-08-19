package com.zhiban.rebuild.data.contact

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 提及匹配与批量解析回归(P1-性能3 重写后):内存匹配与旧 SQL 语义一致——
 * 大小写敏感 contains、含合并源名、按显示名长度降序、同长按更新时间降序;
 * findByIds 与逐条 findById 的 canonical 解析结果一致。
 */
@RunWith(AndroidJUnit4::class)
class ContactMentionLookupTest {
    private lateinit var database: AgentDatabase

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java)
            .addCallback(AgentDatabase.CALLBACK).allowMainThreadQueries().build()
    }

    @After fun tearDown() = database.close()

    private suspend fun insert(contactId: String, displayName: String, updatedAt: Long) {
        database.contactDao().insert(
            ContactEntity(
                contactId = contactId,
                displayName = displayName,
                normalizedName = displayName.lowercase(),
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
                updatedAtEpochMs = updatedAt,
                responsibilities = null,
            ),
        )
    }

    @Test fun mentionMatchesDisplayNameAndOrderByLength() = runBlocking {
        insert("c-zhang", "张三", updatedAt = 10)
        insert("c-zhang-sanfeng", "张三丰", updatedAt = 20)
        insert("c-li", "李四", updatedAt = 30)

        val dao = database.contactDao()

        assertEquals(listOf("c-zhang-sanfeng", "c-zhang"), dao.findMentionedCandidates("我和张三丰聊过").map { it.contactId })
        assertEquals(listOf("c-li"), dao.findMentionedCandidates("李四在吗").map { it.contactId })
        assertEquals(emptyList<String>(), dao.findMentionedCandidates("王五").map { it.contactId })
    }

    @Test fun mentionMatchesMergedSourceName() = runBlocking {
        insert("c-canonical", "张三", updatedAt = 10)
        insert("c-source", "小张", updatedAt = 20)
        database.contactIdentityDao().upsertMergeLink(
            ContactMergeLinkEntity(
                sourceContactId = "c-source",
                canonicalContactId = "c-canonical",
                reason = "测试合并",
                userConfirmed = true,
                createdAtEpochMs = 1,
                undoneAtEpochMs = null,
            ),
        )

        // "小张"(源名)也能命中,并解析到 canonical 实体。
        val matched = database.contactDao().findMentionedCandidates("和小张说了")
        assertEquals(listOf("c-canonical"), matched.map { it.contactId })
    }

    @Test fun observeContactsFillsCanonicalFromMergedSource() = runBlocking {
        // 回归:observeContacts 曾换用 observeActive(源行被 SQL 排除),导致合并联系人的字段回填
        // 静默失效。这里锚定:canonical 缺 phone 时,列表里必须回填源联系人的 phone。
        insert("c-canonical", "张三", updatedAt = 10)
        insert("c-source", "小张", updatedAt = 20).also {
            database.contactDao().insert(
                ContactEntity(
                    contactId = "c-source",
                    displayName = "小张",
                    normalizedName = "小张",
                    phone = "13900139000",
                    email = null,
                    wechatId = null,
                    company = null,
                    title = null,
                    aliasesJson = "[]",
                    tagsJson = "[]",
                    note = null,
                    avatarUri = null,
                    source = "SYSTEM_CONTACT:1",
                    deletedAtEpochMs = null,
                    createdAtEpochMs = 1,
                    updatedAtEpochMs = 20,
                    responsibilities = null,
                ),
            )
        }
        database.contactIdentityDao().upsertMergeLink(
            ContactMergeLinkEntity(
                sourceContactId = "c-source",
                canonicalContactId = "c-canonical",
                reason = "测试合并",
                userConfirmed = true,
                createdAtEpochMs = 1,
                undoneAtEpochMs = null,
            ),
        )

        val listed = database.contactDao().let { dao ->
            com.zhiban.rebuild.data.agent.ContactAgentDataRepository(database).observeContacts().first()
        }

        assertEquals(listOf("c-canonical"), listed.map { it.contactId })
        assertEquals("13900139000", listed.single().phone)
    }

    @Test fun findByIdsMatchesFindByIdCanonicalSemantics() = runBlocking {
        insert("c-canonical", "张三", updatedAt = 10)
        insert("c-source", "小张", updatedAt = 20)
        database.contactIdentityDao().upsertMergeLink(
            ContactMergeLinkEntity(
                sourceContactId = "c-source",
                canonicalContactId = "c-canonical",
                reason = "测试合并",
                userConfirmed = true,
                createdAtEpochMs = 1,
                undoneAtEpochMs = null,
            ),
        )

        val dao = database.contactDao()
        val resolution = dao.resolveCanonicalIds(listOf("c-source", "c-canonical"))
            .associate { it.inputId to it.canonicalId }
        assertEquals("c-canonical", resolution["c-source"])
        assertEquals("c-canonical", resolution["c-canonical"])

        val batch = dao.findByIds(listOf("c-canonical")).associateBy { it.contactId }
        assertEquals(dao.findById("c-source")!!.contactId, batch["c-canonical"]!!.contactId)
        assertEquals(dao.findById("c-canonical")!!.contactId, batch["c-canonical"]!!.contactId)
    }
}
