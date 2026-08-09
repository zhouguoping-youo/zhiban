package com.zhiban.rebuild.data.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactFactDisplayNormalizerTest {
    @Test
    fun `removes attribution wrappers without changing message body`() {
        val cases = mapOf(
            "你（张三） · 微信说：明天 15 点开评审会" to "开评审会",
            "【系统消息】对方提到：报价单已经收到" to "报价单已经收到",
            "客服回复：合同需要重新盖章" to "合同需要重新盖章",
            "对方（老周） · 微信说：明天确认事项，老周，明天下下午3点和我开会。" to "开会",
        )
        cases.forEach { (raw, expected) ->
            assertEquals(
                expected,
                ContactFactDisplayNormalizer.normalize("USER_CONFIRMED_NOTIFICATION", "CURRENT_MATTER", raw),
            )
        }
    }

    @Test
    fun `does not remove ordinary comma-prefixed sentence`() {
        assertEquals(
            "明天，需要发货",
            ContactFactDisplayNormalizer.normalize("USER_CONFIRMED", "CONTACT_MEMORY", "明天，需要发货"),
        )
    }

    @Test
    fun `empty value returns placeholder label`() {
        assertEquals(
            "已确认的沟通内容",
            ContactFactDisplayNormalizer.normalize("USER_CONFIRMED", "CONTACT_MEMORY", ""),
        )
    }

    @Test
    fun `blank only value returns placeholder label`() {
        assertEquals(
            "已确认的沟通内容",
            ContactFactDisplayNormalizer.normalize("USER_CONFIRMED_NOTIFICATION", "CURRENT_MATTER", "   \t  "),
        )
    }

    @Test
    fun `non source aware english text is preserved`() {
        assertEquals(
            "Hello world meeting notes",
            ContactFactDisplayNormalizer.normalize("OTHER_SOURCE", "OTHER_TYPE", "Hello world meeting notes"),
        )
    }

    @Test
    fun `non source aware overlong text is truncated with ellipsis`() {
        val long = "项目对齐".repeat(40) // 160 chars, no temporal leading words
        val result = ContactFactDisplayNormalizer.normalize("OTHER_SOURCE", "OTHER_TYPE", long)
        assertTrue("result must end with ellipsis but was: …${result.takeLast(6)}", result.endsWith("…"))
        assertTrue("result must be <= 120 chars but was ${result.length}", result.length <= 120)
    }

    @Test
    fun `source aware strips leading system notification prefix`() {
        val result = ContactFactDisplayNormalizer.normalize(
            "USER_CONFIRMED_NOTIFICATION",
            "CURRENT_MATTER",
            "系统通知：周五上午对齐需求评审",
        )
        assertTrue("core content should be kept, got: $result", result.contains("对齐需求"))
        assertTrue("system prefix should be dropped, got: $result", !result.startsWith("系统通知"))
    }

    @Test
    fun `important date fact type is treated as source aware`() {
        val result = ContactFactDisplayNormalizer.normalize(
            "USER_CONFIRMED",
            "IMPORTANT_DATE",
            "【提醒】下周三是李雷的生日",
        )
        assertTrue("important date should yield non-blank content, got: $result", result.isNotBlank())
        assertTrue("wrapper bracket should be dropped, got: $result", !result.startsWith("【"))
    }
}
