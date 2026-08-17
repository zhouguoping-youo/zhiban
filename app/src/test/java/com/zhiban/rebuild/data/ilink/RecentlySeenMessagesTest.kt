package com.zhiban.rebuild.data.ilink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentlySeenMessagesTest {
    @Test fun firstSeenIsNewSecondSeenIsDuplicate() {
        val seen = RecentlySeenMessages()
        assertTrue(seen.markSeen(42L))
        assertFalse(seen.markSeen(42L))
        assertTrue(seen.markSeen(43L))
    }

    @Test fun evictsOldestBeyondCapacitySoItStaysBounded() {
        val seen = RecentlySeenMessages(capacity = 2)
        assertTrue(seen.markSeen(1L))
        assertTrue(seen.markSeen(2L))
        // Adding a third evicts the oldest (1), so 1 is seen as new again while 2/3 remain known.
        assertTrue(seen.markSeen(3L))
        assertTrue(seen.markSeen(1L))
        assertFalse(seen.markSeen(1L))
    }
}
