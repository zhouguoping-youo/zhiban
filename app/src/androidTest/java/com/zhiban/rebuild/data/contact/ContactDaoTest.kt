package com.zhiban.rebuild.data.contact

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.facts.FactEntity
import com.zhiban.rebuild.data.facts.FactIndex
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactDaoTest {
    private lateinit var db: AgentDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AgentDatabase::class.java)
            .addCallback(AgentDatabase.CALLBACK).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    @Test fun searchFindsRealContactAndRolesRemainScoped() = runBlocking {
        val dao = db.contactDao()
        dao.insert(
            ContactEntity("c1", "张三", "张三", "13800138000", "san@example.com", "zs", "知伴科技", "经理", "[\"老张\"]", "[\"重点客户\"]", "负责项目", null, "USER", null, 1, 2),
        )
        dao.upsertRole(ContactRoleEntity("c1", "crm", "CUSTOMER", .9, true, "{}", 1, 2))

        val byName = dao.searchNatural("张三", 20)
        val byCompany = dao.searchNatural("知伴科技", 20)

        assertEquals("c1", byName.single().contactId)
        assertEquals("c1", byCompany.single().contactId)
        assertEquals("CUSTOMER", dao.roles("c1").single().roleType)
        assertTrue(dao.findById("missing") == null)
    }

    @Test fun naturalSearchMergesTermsInOneQuery() = runBlocking {
        val contacts = db.contactDao()
        contacts.insert(
            ContactEntity("c-zhang", "张三丰", "张三丰", null, null, null, "知伴科技", null, "[]", "[]", null, null, "USER", null, 1, 2),
        )
        contacts.insert(
            ContactEntity("c-li", "李四", "李四", "13800138001", null, null, null, null, "[]", "[]", null, null, "USER", null, 1, 3),
        )

        // 多词一次检索:两词分别命中两个联系人;显示名精确匹配优先。
        val multi = contacts.searchNatural("张三丰 李四", 20)
        assertTrue(multi.any { it.contactId == "c-zhang" })
        assertTrue(multi.any { it.contactId == "c-li" })
        // 命中词数多者优先("张三丰" 命中整词+bigram 两个 term)。
        assertEquals("c-zhang", multi.first().contactId)

        // 中文 bigram 走 instr 链仍可命中(FTS simple tokenizer 不分词)。
        assertEquals(listOf("c-zhang"), contacts.searchNatural("三丰", 20).map { it.contactId })
        // 电话号码(在 FTS 内容里)同样命中。
        assertEquals(listOf("c-li"), contacts.searchNatural("13800138001", 20).map { it.contactId })
    }

    @Test fun mergedSourceIsHiddenFromSearchAndGraphUntilUndo() = runBlocking {
        val contacts = db.contactDao()
        val identities = db.contactIdentityDao()
        val relationships = db.relationshipEdgeDao()
        val knowledge = db.contactKnowledgeDao()
        contacts.insert(
            ContactEntity("canonical", "王小明", "王小明", null, null, null, null, null, "[]", "[]", null, null, "USER", null, 1, 2),
        )
        contacts.insert(
            ContactEntity("source", "王老师", "王老师", "13800138008", null, null, null, null, "[]", "[]", null, null, "USER", null, 1, 3),
        )
        contacts.insert(
            ContactEntity("other", "李老师", "李老师", null, null, null, null, null, "[]", "[]", null, null, "USER", null, 1, 3),
        )
        contacts.upsertRole(ContactRoleEntity("source", "crm", "CUSTOMER", .9, true, null, 1, 2))
        knowledge.upsertMethods(
            listOf(
                ContactMethodEntity(
                    "method-source", "source", "PHONE", "138-0013-8008", "13800138008", null,
                    true, "USER", null, 1.0, true, 2, 1, 2,
                ),
            ),
        )
        db.factDao().upsert(
            FactEntity(
                "fact-source", "CONTACT_NOTE", "王老师负责交付", null, "USER", null,
                "source", null, 1.0, "PERSONAL", "ACTIVE", 0, null, 1, 2,
            ),
        )
        identities.upsertAlias(
            ContactAliasEntity("alias-source", "source", "王老师", "王老师", "USER_ALIAS", "USER", true, 2),
        )
        relationships.upsert(
            RelationshipEdgeEntity(
                "edge-source", "source", "canonical", "COLLEAGUE", "digest", "[]", 1.0, true, null, "ACTIVE", 1, 2,
            ),
        )
        relationships.upsert(
            RelationshipEdgeEntity(
                "edge-other", "source", "other", "COLLEAGUE", "digest-2", "[]", 1.0, true, null, "ACTIVE", 1, 2,
            ),
        )
        identities.upsertMergeLink(ContactMergeLinkEntity("source", "canonical", "手机号相同", true, 4, null))

        // 整词"王老师"经 bigram 拆出"老师"会同时召回"李老师"——断言改为 first()
        // (canonical 整词+bigram 共命中 3 个 term,排序居首),而非 single()。
        assertEquals("canonical", contacts.searchNatural("王老师", 20).first().contactId)
        assertEquals("13800138008", contacts.searchNatural("13800138008", 20).single().phone)
        assertEquals("canonical", contacts.findById("source")?.contactId)
        assertEquals("13800138008", contacts.findById("source")?.phone)
        assertEquals("source", contacts.findRawById("source")?.contactId)
        assertEquals("CUSTOMER", contacts.roles("canonical").single().roleType)
        assertEquals("canonical", identities.observeAliases().first().single().contactId)
        assertEquals("method-source", knowledge.observeMethods("canonical").first().single().methodId)
        assertEquals("fact-source", db.factDao().observeByContact("canonical", 5).first().single().factId)
        assertEquals("edge-other", relationships.touching(listOf("canonical"), 20).single().edgeId)
        assertEquals("canonical", relationships.touching(listOf("canonical"), 20).single().fromContactId)
        assertTrue(relationships.contactSummaries(listOf("source", "canonical")).none { it.contactId == "source" })

        assertEquals(1, identities.undoConfirmedMerge("source", 5))

        // undo 后 source 恢复独立;显示名精确匹配优先,source 居首。
        assertEquals("source", contacts.searchNatural("王老师", 20).first().contactId)
        assertEquals("source", contacts.findById("source")?.contactId)
        assertTrue(knowledge.observeMethods("canonical").first().isEmpty())
        assertTrue(db.factDao().observeByContact("canonical", 5).first().isEmpty())
        assertEquals(
            setOf("edge-source", "edge-other"),
            relationships.touching(listOf("source"), 20).map {
                it.edgeId
            }.toSet(),
        )
    }

    @Test fun legacySoftDeletedContactCannotLeakThroughFactsOrGraph() = runBlocking {
        val contacts = db.contactDao()
        contacts.insert(
            ContactEntity("deleted", "已删", "已删", null, null, null, null, null, "[]", "[]", null, null, "USER", null, 1, 1),
        )
        contacts.insert(
            ContactEntity("active", "保留", "保留", null, null, null, null, null, "[]", "[]", null, null, "USER", null, 1, 1),
        )
        FactIndex(db).upsert(
            FactEntity(
                "legacy-fact", "CONTACT_NOTE", "不应召回的秘密", null, "USER", null,
                "deleted", null, 1.0, "PERSONAL", "ACTIVE", 0, null, 1, 1,
            ),
        )
        db.relationshipEdgeDao().upsert(
            RelationshipEdgeEntity(
                "legacy-edge", "deleted", "active", "COLLEAGUE", "digest", "[]", 1.0,
                true, null, "ACTIVE", 1, 1,
            ),
        )

        assertEquals(1, contacts.softDelete("deleted", 2))

        assertTrue(FactIndex(db).search("不应召回的秘密", 10, 10).isEmpty())
        assertTrue(db.factDao().recent(10, 10).isEmpty())
        assertTrue(db.relationshipEdgeDao().observeActive().first().isEmpty())
    }

    @Test fun findByIdReturnsResponsibilitiesAndFallsBackToMergedSource() = runBlocking {
        val contacts = db.contactDao()
        val identities = db.contactIdentityDao()
        // 直接回读：联系人自身带 responsibilities，findById 不得丢（回归：曾漏列被读成 null）。
        contacts.insert(
            ContactEntity("direct", "张三", "张三", null, null, null, null, null, "[]", "[]", null, null, "USER", null, 1, 2, responsibilities = "湖北/湖南销售"),
        )
        // 合并回退：canonical 无、source 有，应回退到 source 的值（与 phone/email 等字段同策略）。
        contacts.insert(
            ContactEntity("canonical", "王小明", "王小明", null, null, null, null, null, "[]", "[]", null, null, "USER", null, 1, 2),
        )
        contacts.insert(
            ContactEntity("source", "王老师", "王老师", null, null, null, null, null, "[]", "[]", null, null, "USER", null, 1, 3, responsibilities = "交付"),
        )
        identities.upsertMergeLink(ContactMergeLinkEntity("source", "canonical", "同一人", true, 4, null))

        assertEquals("湖北/湖南销售", contacts.findById("direct")?.responsibilities)
        assertEquals("交付", contacts.findById("canonical")?.responsibilities)
        assertEquals("交付", contacts.findById("source")?.responsibilities)
    }
}
