package com.zhiban.rebuild.data.contact.enrichment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageContactInfoExtractorTest {
    @Test
    fun parsesCleanExtractionJsonAndNormalizesPhone() {
        val fields = parseExtractedFields(
            """{"fields":[
                {"kind":"COMPANY","value":"平凯星辰（北京）科技有限公司","confidence":0.96},
                {"kind":"PHONE","value":"+86 134-7611-0061","confidence":0.99}
            ]}""",
        )

        assertEquals(2, fields.size)
        assertEquals(MessageContactFieldKinds.COMPANY, fields[0].kind)
        assertEquals("平凯星辰（北京）科技有限公司", fields[0].value)
        assertEquals(0.96, fields[0].confidence, 0.000_001)
        assertEquals(MessageContactFieldKinds.PHONE, fields[1].kind)
        assertEquals("13476110061", fields[1].value)
    }

    @Test
    fun toleratesProseAndFencesAroundJson() {
        val raw = "好的，根据消息提取结果如下：\n```json\n{\"fields\":[{\"kind\":\"TITLE\",\"value\":\"销售总监\",\"confidence\":0.9}]}\n```\n以上。"
        val fields = parseExtractedFields(raw)

        assertEquals(1, fields.size)
        assertEquals(MessageContactFieldKinds.TITLE, fields[0].kind)
        assertEquals("销售总监", fields[0].value)
    }

    @Test
    fun dropsUnknownKindsInvalidPhonesAndClampsConfidence() {
        val fields = parseExtractedFields(
            """{"fields":[
                {"kind":"ADDRESS","value":"武汉","confidence":0.9},
                {"kind":"PHONE","value":"123","confidence":0.9},
                {"kind":"COMPANY","value":"某公司","confidence":1.5},
                {"kind":"PHONE","value":"","confidence":0.9}
            ]}""",
        )

        assertEquals(1, fields.size)
        assertEquals(MessageContactFieldKinds.COMPANY, fields[0].kind)
        assertEquals(1.0, fields[0].confidence, 0.0)
    }

    @Test
    fun garbageReturnsEmpty() {
        assertTrue(parseExtractedFields("").isEmpty())
        assertTrue(parseExtractedFields("完全没有 JSON 的文本").isEmpty())
        assertTrue(parseExtractedFields("{\"other\":1}").isEmpty())
    }
}
