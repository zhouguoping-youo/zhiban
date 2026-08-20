package com.zhiban.rebuild.data.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenshotVisionCandidateFormatterTest {
    @Test
    fun `ocr with no known field requests vision fallback`() {
        assertFalse(ScreenshotVisionCandidateFormatter.isStructuredOcr("张三\n销售经理"))
    }

    @Test
    fun `ocr with phone or company stays local`() {
        assertTrue(ScreenshotVisionCandidateFormatter.isStructuredOcr("张三 13800138000"))
        assertTrue(ScreenshotVisionCandidateFormatter.isStructuredOcr("平凯星辰科技有限公司"))
    }

    @Test
    fun `vision json becomes bounded candidate card text`() {
        val result = ScreenshotVisionCandidateFormatter.formatCandidate(
            """{"contacts":[{"name":"张三","phone":"13800138000","company":"平凯星辰科技有限公司"}],"schedules":[]}""",
        )
        assertEquals("联系人候选 · name: 张三 · phone: 13800138000 · company: 平凯星辰科技有限公司", result)
    }

    @Test
    fun `malformed vision output is rejected`() {
        assertEquals(null, ScreenshotVisionCandidateFormatter.formatCandidate("not-json"))
    }
}
