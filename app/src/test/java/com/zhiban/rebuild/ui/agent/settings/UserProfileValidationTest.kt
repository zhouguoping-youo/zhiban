package com.zhiban.rebuild.ui.agent.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserProfileValidationTest {
    @Test
    fun `valid 11-digit phone starting with 1 passes`() {
        assertTrue(isValidPhone("13800000000"))
    }

    @Test
    fun `phone with wrong length fails`() {
        assertFalse(isValidPhone("1380000000"))
        assertFalse(isValidPhone("138000000000"))
    }

    @Test
    fun `phone not starting with 1 fails`() {
        assertFalse(isValidPhone("23800000000"))
    }

    @Test
    fun `phone with non-digit characters fails`() {
        assertFalse(isValidPhone("1380000000a"))
        assertFalse(isValidPhone("138 0000 000"))
    }

    @Test
    fun `platform key and label round-trip`() {
        assertEquals("feishu", platformKey("飞书"))
        assertEquals("wecom", platformKey("企微"))
        assertEquals("dingtalk", platformKey("钉钉"))
        assertEquals("qq", platformKey("QQ"))
        assertEquals("飞书", platformLabel("feishu"))
        assertEquals("QQ", platformLabel("qq"))
    }

    @Test
    fun `occupation options contain the twelve choices`() {
        assertEquals(12, OCCUPATION_OPTIONS.size)
        assertTrue(OCCUPATION_OPTIONS.contains("销售/商务"))
        assertTrue(OCCUPATION_OPTIONS.contains("其他"))
    }

    @Test
    fun `extra account platforms are the four supported`() {
        assertEquals(listOf("飞书", "企微", "钉钉", "QQ"), EXTRA_ACCOUNT_PLATFORMS)
    }
}
