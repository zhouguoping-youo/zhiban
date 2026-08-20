package com.zhiban.rebuild.data.calllog

import com.zhiban.rebuild.data.agent.formatCallDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallLogRepositoryTest {
    @Test
    fun onlyConnectedCallsAreEligibleForConversationNotes() {
        assertTrue(isEligibleForCallNote("INCOMING", 1))
        assertTrue(isEligibleForCallNote("OUTGOING", 60))
        assertFalse(isEligibleForCallNote("MISSED", 0))
        assertFalse(isEligibleForCallNote("REJECTED", 0))
        assertFalse(isEligibleForCallNote("BLOCKED", 0))
    }

    @Test
    fun followUpRequiresRecentConnectedCallWithPositiveDuration() {
        val now = 100L * 24 * 60 * 60_000L
        assertTrue(isEligibleForCallFollowUp("INCOMING", 1, now - 60_000L, now))
        assertFalse(isEligibleForCallFollowUp("MISSED", 0, now - 60_000L, now))
        assertFalse(isEligibleForCallFollowUp("REJECTED", 10, now - 60_000L, now))
        assertFalse(isEligibleForCallFollowUp("OUTGOING", 30, now - 25L * 60 * 60_000L, now))
    }

    @Test
    fun shortCallDurationIsNotRoundedUpToOneMinute() {
        assertEquals("0 分钟", formatCallDuration(0))
        assertEquals("不到 1 分钟", formatCallDuration(1))
        assertEquals("不到 1 分钟", formatCallDuration(59))
        assertEquals("1 分钟", formatCallDuration(60))
        assertEquals("2 分钟", formatCallDuration(90))
    }
}
