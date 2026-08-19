package com.zhiban.rebuild.data.contact.enrichment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RelationshipInferenceParseTest {
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
}
