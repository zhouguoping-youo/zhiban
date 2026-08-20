package com.zhiban.rebuild.data.contact.enrichment

import com.zhiban.rebuild.runtime.personalization.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelationshipInferenceParseTest {

    @Test
    fun `owner background contains current company and occupations`() {
        val background = UserProfile(
            name = "周国平",
            preferredName = "老周",
            company = "平凯星辰（北京）科技有限公司",
            occupations = setOf("销售经理", "湖北湖南区域"),
        ).toRelationshipInferenceBackground()

        assertTrue(background.contains("公司全称=平凯星辰（北京）科技有限公司"))
        assertTrue(background.contains("销售经理"))
    }

    @Test
    fun `classmate is accepted by relationship schema`() {
        val inferred = parseInferredRelationship(
            """{"relationType":"CLASSMATE","confidence":0.88,"evidence":"大学同班"}""",
        )
        assertEquals("CLASSMATE", inferred?.relationType)
    }
    @Test
    fun parsesCleanInferenceJson() {
        val inferred = parseInferredRelationship(
            """{"relationType":"CUSTOMER","confidence":0.92,"evidence":"对方多次提到采购与报价"}""",
        )

        assertNotNull(inferred)
        assertEquals("CUSTOMER", inferred!!.relationType)
        assertEquals(0.92, inferred.confidence, 0.000_001)
        assertEquals("对方多次提到采购与报价", inferred.evidence)
    }

    @Test
    fun toleratesProseAroundJson() {
        val inferred = parseInferredRelationship(
            "推断结果：```json\n{\"relationType\":\"COLLEAGUE\",\"confidence\":0.88,\"evidence\":\"同公司\"}\n```",
        )

        assertNotNull(inferred)
        assertEquals("COLLEAGUE", inferred!!.relationType)
    }

    @Test
    fun rejectsUninferableTypesAndOutOfRangeConfidence() {
        assertNull(parseInferredRelationship("""{"relationType":"BOSS","confidence":0.9,"evidence":"x"}"""))
        assertNull(parseInferredRelationship("没有 JSON"))
        assertNull(parseInferredRelationship("""{"relationType":"FRIEND"}"""))
    }

    @Test
    fun confidenceIsClampedAndEvidenceCapped() {
        val inferred = parseInferredRelationship(
            """{"relationType":"FAMILY","confidence":-0.2,"evidence":"${"证".repeat(400)}"}""",
        )

        assertNotNull(inferred)
        assertEquals(0.0, inferred!!.confidence, 0.0)
        assertEquals(200, inferred.evidence.length)
    }

    @Test
    fun companyNamesMatch_handlesFullVsShortName() {
        // 3 字简称(如"九州通")应能匹配全称——正是真机漏判场景
        assertTrue(companyNamesMatch("九州通", "武汉九州通医药集团股份有限公司"))
        assertTrue(companyNamesMatch("武汉九州通医药集团股份有限公司", "九州通"))
        // 精确相等
        assertTrue(companyNamesMatch("九州通医药集团", "九州通医药集团"))
        // 2 字简称(如"腾讯")也判同公司(可撤销自动边,错了可一键撤销)
        assertTrue(companyNamesMatch("腾讯", "深圳市腾讯计算机系统有限公司"))
        // 单字不匹配(信息量不足)
        assertFalse(companyNamesMatch("通", "九州通"))
        // 无关公司:不匹配
        assertFalse(companyNamesMatch("九州通", "国药控股股份有限公司"))
        assertFalse(companyNamesMatch("九州通", "恒瑞医药"))
    }

    @Test
    fun normalizedCompanyKey_acceptsShortAndFullNames() {
        // 3 字简称必须能出 key,否则本人公司为简称时整批同事漏判
        assertNotNull("九州通".normalizedCompanyKey())
        assertNotNull("武汉九州通医药集团股份有限公司".normalizedCompanyKey())
        // 含空格/大小写归一化
        assertEquals("shenzhentencent", "  Shenzhen Tencent  ".normalizedCompanyKey())
        // 单字仍拒绝(信息量不足)
        assertNull("通".normalizedCompanyKey())
    }
}
