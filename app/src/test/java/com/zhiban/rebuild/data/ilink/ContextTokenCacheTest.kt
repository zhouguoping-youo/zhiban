package com.zhiban.rebuild.data.ilink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContextTokenCacheTest {
    @Test fun returnsStoredTokenWhileFresh() {
        var now = 1_000L
        val cache = ContextTokenCache { now }
        cache.put("user-a", "token-1")
        now += 1_000L
        assertEquals("token-1", cache.get("user-a"))
    }

    @Test fun returnsNullAndEvictsAfterTtl() {
        var now = 1_000L
        val cache = ContextTokenCache { now }
        cache.put("user-a", "token-1")
        now += 111_000L // beyond the ~110s TTL
        assertNull(cache.get("user-a"))
        // Already evicted, so a second read still misses even without advancing the clock.
        assertNull(cache.get("user-a"))
    }

    @Test fun keysArePerUserAndBlankValuesIgnored() {
        val cache = ContextTokenCache { 0L }
        cache.put("user-a", "")
        cache.put("", "token-x")
        assertNull(cache.get("user-a"))
        cache.put("user-a", "token-a")
        cache.put("user-b", "token-b")
        assertEquals("token-a", cache.get("user-a"))
        assertEquals("token-b", cache.get("user-b"))
        cache.clear()
        assertNull(cache.get("user-a"))
        assertNull(cache.get("user-b"))
    }
}
