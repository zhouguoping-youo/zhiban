package com.zhiban.rebuild.data.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class OrganizationNamesTest {
    @Test
    fun `normalization keeps the supplied legal name and only cleans whitespace`() {
        assertEquals(
            "平凯星辰（北京）科技有限公司",
            normalizeOrganizationFullName("  平凯星辰（北京）科技有限公司  "),
        )
        assertEquals("OpenAI China", normalizeOrganizationFullName("OpenAI   China"))
    }
}
