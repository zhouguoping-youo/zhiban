package com.zhiban.rebuild.data.communication

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RecentHandoffLaunchGuardTest {
    @Test
    fun failedLaunchReservationCanBeReleasedForImmediateRetry() {
        var now = 1_000L
        val guard = RecentHandoffLaunchGuard { now }

        val failedReservation = guard.reserve("WECHAT|老张|收到")
        assertNotNull(failedReservation)
        assertNull(guard.reserve("WECHAT|老张|收到"))

        guard.release(requireNotNull(failedReservation))
        now += 1

        assertNotNull(guard.reserve("WECHAT|老张|收到"))
    }
}
