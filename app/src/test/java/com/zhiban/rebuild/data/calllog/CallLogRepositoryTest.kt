package com.zhiban.rebuild.data.calllog

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
}
