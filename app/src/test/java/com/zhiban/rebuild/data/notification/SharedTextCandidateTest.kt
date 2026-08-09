package com.zhiban.rebuild.data.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedTextCandidateTest {
    @Test
    fun blankShareIsRejected() {
        assertNull(sharedTextCandidate("chat.app", "聊天", "  ", "\n\t", 1_000L))
    }

    @Test
    fun sharedTextIsNormalizedBoundedAndDeterministic() {
        val first = sharedTextCandidate(
            sourcePackage = "chat.app",
            sourceLabel = "聊天",
            subject = " 张三 ",
            body = "明天\n 下午三点见",
            nowEpochMs = 1_000L,
        )!!
        val repeated = sharedTextCandidate(
            sourcePackage = "chat.app",
            sourceLabel = "聊天",
            subject = "张三",
            body = "明天 下午三点见",
            nowEpochMs = 2_000L,
        )!!

        assertEquals(first.candidateId, repeated.candidateId)
        assertEquals(first.sourceKey, repeated.sourceKey)
        assertEquals("张三", first.title)
        assertEquals("明天 下午三点见", first.body)
        assertEquals("聊天", first.appLabel)
    }

    @Test
    fun sharedTextUsesSafeFallbacksAndLengthLimits() {
        val candidate = sharedTextCandidate(
            sourcePackage = " ",
            sourceLabel = " ",
            subject = "题".repeat(250),
            body = "文".repeat(700),
            nowEpochMs = 1_000L,
        )!!

        assertEquals("manual-share", candidate.packageName)
        assertEquals("手动分享", candidate.appLabel)
        assertEquals(200, candidate.title?.length)
        assertEquals(500, candidate.body?.length)
    }
}
