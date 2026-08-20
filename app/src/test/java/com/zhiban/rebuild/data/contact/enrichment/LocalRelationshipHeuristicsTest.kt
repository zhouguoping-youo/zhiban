package com.zhiban.rebuild.data.contact.enrichment

import com.zhiban.rebuild.data.contact.ContactEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 本地规则关系推断测试：覆盖亲属/客户/供应商/同学/同事/朋友六类信号 + 未命中回落。
 */
class LocalRelationshipHeuristicsTest {

    private fun contact(displayName: String = "张三", company: String? = null, title: String? = null, tagsJson: String = "[]", note: String? = null) =
        ContactEntity(
            contactId = "ct-x",
            displayName = displayName,
            normalizedName = displayName,
            phone = null,
            email = null,
            wechatId = null,
            company = company,
            title = title,
            aliasesJson = "[]",
            tagsJson = tagsJson,
            note = note,
            avatarUri = null,
            source = "TEST",
            deletedAtEpochMs = null,
            createdAtEpochMs = 0L,
            updatedAtEpochMs = 0L,
        )

    @Test
    fun `亲属称谓推断为家人`() {
        val result = LocalRelationshipHeuristics.infer(contact(), "爸，明天回来吃饭")
        assertNotNull(result)
        assertEquals("FAMILY", result!!.relationType)
        assertEquals(0.95, result.confidence, 0.0)
    }

    @Test
    fun `商务往来词推断为客户`() {
        val result = LocalRelationshipHeuristics.infer(contact(), "报价单明天发您，合同细节再对")
        assertNotNull(result)
        assertEquals("CUSTOMER", result!!.relationType)
        assert(result.confidence >= 0.85)
    }

    @Test
    fun `供货物流词推断为供应商`() {
        val result = LocalRelationshipHeuristics.infer(contact(), "货已发出，物流单号明天给")
        assertNotNull(result)
        assertEquals("SUPPLIER", result!!.relationType)
    }

    @Test
    fun `同学学校词推断为同学`() {
        val result = LocalRelationshipHeuristics.infer(contact(), "老同学，毕业十年聚会安排一下")
        assertNotNull(result)
        assertEquals("CLASSMATE", result!!.relationType)
    }

    @Test
    fun `老师导师教授不再误判为同学`() {
        listOf("老师您好", "请导师审阅", "教授明天有课").forEach { evidence ->
            assertNull(LocalRelationshipHeuristics.infer(contact(), evidence))
        }
    }

    @Test
    fun `共事语义推断为同事但置信低于自动写阈值`() {
        val result = LocalRelationshipHeuristics.infer(contact(), "明天下午部门开会，周报记得交")
        assertNotNull(result)
        assertEquals("COLLEAGUE", result!!.relationType)
        assert(result.confidence < 0.85)
    }

    @Test
    fun `社交词推断为朋友但置信低于自动写阈值`() {
        val result = LocalRelationshipHeuristics.infer(contact(), "周末有空吗，出来聚聚吃饭")
        assertNotNull(result)
        assertEquals("FRIEND", result!!.relationType)
        assert(result.confidence < 0.85)
    }

    @Test
    fun `联系人备注中的客户词也能命中`() {
        val result = LocalRelationshipHeuristics.infer(
            contact(tagsJson = """["客户"]""", note = "对接人"),
            "明天把方案发我",
        )
        assertNotNull(result)
        assertEquals("CUSTOMER", result!!.relationType)
    }

    @Test
    fun `无信号文本返回null交给LLM`() {
        val result = LocalRelationshipHeuristics.infer(contact(), "在吗，好的，收到")
        assertNull(result)
    }

    @Test
    fun `客户词多于供货词时偏向客户`() {
        val result = LocalRelationshipHeuristics.infer(
            contact(),
            "报价确认了，明天发货，发票一起开",
        )
        assertNotNull(result)
        assertEquals("CUSTOMER", result!!.relationType)
    }
}
