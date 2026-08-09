package com.zhiban.rebuild.data.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutgoingMessageExpectationTrackerTest {
    @Test
    fun expectationRequiresMatchingPlatformAndSendClick() {
        val tracker = OutgoingMessageExpectationTracker()
        tracker.record("WECHAT", "  周   国平  ", "  测试   消息  ", 1_000L)

        assertNull(tracker.armed("WECHAT", 1_001L))
        assertEquals("周 国平", tracker.pending("WECHAT", 1_001L)?.recipient)
        assertEquals("测试 消息", tracker.pending("WECHAT", 1_001L)?.message)
        assertNull(tracker.markSendClicked("QQ", 1_002L))

        val armed = tracker.markSendClicked("WECHAT", 1_003L)
        assertEquals("测试 消息", armed?.message)
        assertEquals(armed, tracker.armed("WECHAT", 1_004L))
    }

    @Test
    fun consumedAndExpiredExpectationsCannotBeReused() {
        val tracker = OutgoingMessageExpectationTracker()
        tracker.record("WECHAT", "周国平", "消息", 1_000L)
        val armed = requireNotNull(tracker.markSendClicked("WECHAT", 1_100L))
        tracker.consume(armed)
        assertNull(tracker.armed("WECHAT", 1_101L))

        tracker.record("WECHAT", "周国平", "消息2", 2_000L)
        assertNull(tracker.markSendClicked("WECHAT", 2_000L + 10 * 60_000L + 1))
        assertTrue(true)
    }
}
