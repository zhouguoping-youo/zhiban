package com.zhiban.rebuild.data.reply

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyWorthinessAnalyzerTest {
    private fun verdict(text: String?, attribution: Boolean = true, laterOutgoing: Boolean = false) =
        ReplyWorthinessAnalyzer.evaluate(text, attribution, laterOutgoing)

    @Test fun `direct question is reply-worthy`() {
        val v = verdict("明天上午的合同能发我一份吗？")
        assertTrue(v.worthy)
        assertEquals("REPLY_WORTHY", v.reasonCode)
    }

    @Test fun `question suffix without punctuation is worthy`() {
        assertTrue(verdict("这周五你能来吗").worthy)
        assertTrue(verdict("在吗").worthy)
    }

    @Test fun `task directed at the user is worthy`() {
        assertTrue(verdict("把最新报价发我一下").worthy)
        assertTrue(verdict("麻烦尽快给我个回复").worthy)
    }

    @Test fun `marketing is vetoed`() {
        val v = verdict("【限时促销】全场五折，回复TD退订")
        assertFalse(v.worthy)
        assertEquals("MARKETING_OR_SENSITIVE", v.reasonCode)
    }

    @Test fun `otp or sensitive content is vetoed`() {
        val v = verdict("您的验证码是123456，请勿泄露")
        assertFalse(v.worthy)
        assertEquals("MARKETING_OR_SENSITIVE", v.reasonCode)
    }

    @Test fun `casual chatter is below threshold`() {
        val v = verdict("哈哈")
        assertFalse(v.worthy)
        assertEquals("LOW_SIGNAL", v.reasonCode)
    }

    @Test fun `plain statement stays below threshold`() {
        val v = verdict("今天天气不错")
        assertFalse(v.worthy)
        assertEquals("LOW_SIGNAL", v.reasonCode)
    }

    @Test fun `short ack is low value`() {
        assertFalse(verdict("好的").worthy)
        assertFalse(verdict("嗯嗯").worthy)
    }

    @Test fun `emoji only is low value`() {
        assertFalse(verdict("😂😂😂").worthy)
    }

    @Test fun `empty text is rejected`() {
        assertEquals("EMPTY", verdict("").reasonCode)
        assertEquals("EMPTY", verdict("   ").reasonCode)
    }

    @Test fun `missing forward target vetoes even a question`() {
        val v = verdict("在吗？", attribution = false)
        assertFalse(v.worthy)
        assertEquals("NO_FORWARD_TARGET", v.reasonCode)
    }

    @Test fun `already answered thread vetoes`() {
        val v = verdict("在吗？", laterOutgoing = true)
        assertFalse(v.worthy)
        assertEquals("ALREADY_REPLIED", v.reasonCode)
    }
}
