package com.zhiban.rebuild.data.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 发送者静默键 (platform, normalizedHandle) 的推导规则——静默过滤与收件箱折叠共用。 */
class SenderMuteTest {
    @Test
    fun normalizeSenderHandleLowercasesAndStripsWhitespaceAndAtPrefix() {
        assertEquals("lilaotou", normalizeSenderHandle("WECHAT", "LiLaotou"))
        assertEquals("lilaotou", normalizeSenderHandle("WECHAT", " Li Laotou "))
        assertEquals("lilaotou", normalizeSenderHandle("WECHAT", "@lilaotou"))
        assertEquals("lilaotou", normalizeSenderHandle("WECHAT", "@ Li Laotou "))
    }

    @Test
    fun normalizeSenderHandleStripsUnreadCountTagLikeTheParsePipeline() {
        assertEquals("张三", normalizeSenderHandle("WECHAT", "[3条]张三"))
        assertEquals("zhangsan", normalizeSenderHandle("WECHAT", "[12条] zhangsan"))
        assertEquals("zhangsan", normalizeSenderHandle("WECHAT", "zhangsan"))
    }

    @Test
    fun normalizeSenderHandleUsesPhoneCanonicalizationForSms() {
        assertEquals("13800138000", normalizeSenderHandle("SMS", "+86 138-0013-8000"))
        assertEquals("13800138000", normalizeSenderHandle("SMS", "138 0013 8000"))
        assertEquals("+12125550123", normalizeSenderHandle("SMS", "+1 (212) 555-0123"))
    }

    @Test
    fun normalizeSenderHandleRejectsBlankOrInvalidSenders() {
        assertNull(normalizeSenderHandle("WECHAT", "   "))
        assertNull(normalizeSenderHandle("WECHAT", null))
        assertNull(normalizeSenderHandle("SMS", "12")) // 号码过短,不算有效身份
        assertNull(normalizeSenderHandle("SMS", "未知号码"))
    }
}
