package com.zhiban.rebuild.ui.tabs

import com.zhiban.rebuild.data.notification.NotificationCandidateEntity
import com.zhiban.rebuild.data.notification.NotificationInsights
import com.zhiban.rebuild.data.notification.ScheduleInsight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationCandidateReviewTest {
    @Test
    fun ambiguousIdentityAndScheduleAreReducedToOneClearDecision() {
        val candidate = candidate(
            senderName = "王敏",
            scheduleConfidence = 0.92,
        )

        val review = buildNotificationCandidateReview(candidate, matchedContactName = null)

        assertEquals("识别到一项安排，还不能确定联系人", review.headline)
        assertEquals("联系人库中没有找到可唯一验证的身份", review.reason)
        assertEquals("消息中的人 · 王敏", review.contactLine)
        assertTrue(review.scheduleLine.orEmpty().startsWith("安排 · "))
    }

    @Test
    fun uncertainExistingMatchExplainsWhyItWasNotWrittenAutomatically() {
        val candidate = candidate(
            senderName = "老张",
            suggestedContactId = "contact-1",
        )

        val review = buildNotificationCandidateReview(candidate, matchedContactName = "张伟")

        assertEquals("可能是张伟", review.headline)
        assertTrue(review.reason.contains("不足以安全自动关联"))
        assertEquals("可能联系人 · 张伟", review.contactLine)
    }

    private fun candidate(senderName: String, suggestedContactId: String? = null, scheduleConfidence: Double? = null) = NotificationCandidateEntity(
        candidateId = "candidate-1",
        sourceKey = "source-1",
        packageName = "com.tencent.mm",
        appLabel = "微信",
        title = senderName,
        body = "下周三下午三点见面",
        postedAtEpochMs = 1_700_000_000_000L,
        senderName = senderName,
        suggestedContactId = suggestedContactId,
        suggestedContactConfidence = if (suggestedContactId == null) 0.0 else 0.9,
        insightJson = scheduleConfidence?.let {
            NotificationInsights(
                ScheduleInsight(
                    title = "见面",
                    startAtEpochMs = 1_700_100_000_000L,
                    confidence = it,
                ),
            ).toJsonOrNull()
        },
    )
}
