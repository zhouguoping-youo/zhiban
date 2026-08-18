package com.zhiban.rebuild.data.reply

import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.notification.NotificationCandidateDao
import com.zhiban.rebuild.data.notification.NotificationCandidateEntity
import com.zhiban.rebuild.runtime.config.AgentControlStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * P1-7 FORWARDED 不可逆回归:转发后 X 分钟仍无 OUTGOING(用户取消了微信分享)时整组回退 PENDING,
 * 卡片重生;有 OUTGOING 仍升级 SENT_CONFIRMED;刚转发不久则不动。
 */
class ReplyForwardedRevertRegressionTest {

    private fun forwardedRow(forwardedAtEpochMs: Long) = ReplySuggestionEntity(
        suggestionId = "rs-1",
        candidateId = "cand-1",
        threadKey = "WECHAT|张三",
        contactId = "c1",
        draft = "草稿",
        draftIndex = 0,
        status = ReplySuggestionStatus.FORWARDED,
        createdAtEpochMs = 1_000L,
        forwardedAtEpochMs = forwardedAtEpochMs,
    )

    private fun coordinator(
        replyDao: ReplySuggestionDao,
        notificationDao: NotificationCandidateDao,
    ): ReplySuggestionCoordinator {
        val database = mockk<AgentDatabase>(relaxed = true)
        coEvery { database.replySuggestionDao() } returns replyDao
        coEvery { database.notificationCandidateDao() } returns notificationDao
        return ReplySuggestionCoordinator(database, mockk(relaxed = true), mockk(relaxed = true))
    }

    @Test fun staleForwardedGroupWithoutOutgoingRevertsToPending() = runTest {
        val replyDao = mockk<ReplySuggestionDao>(relaxed = true)
        val notificationDao = mockk<NotificationCandidateDao>(relaxed = true)
        coEvery { replyDao.forwardedGroups() } returns listOf(forwardedRow(forwardedAtEpochMs = 1_000L))
        coEvery { notificationDao.threadMessages(any(), any(), any(), any()) } returns emptyList()

        coordinator(replyDao, notificationDao).processOnce()

        coVerify { replyDao.revertThreadToPending("WECHAT|张三") }
        coVerify(exactly = 0) { replyDao.markThreadSentConfirmed(any(), any(), any()) }
    }

    @Test fun recentForwardIsLeftUntouchedWhileUserActsInWechat() = runTest {
        val replyDao = mockk<ReplySuggestionDao>(relaxed = true)
        val notificationDao = mockk<NotificationCandidateDao>(relaxed = true)
        val justForwarded = System.currentTimeMillis() - 1_000
        coEvery { replyDao.forwardedGroups() } returns listOf(forwardedRow(forwardedAtEpochMs = justForwarded))
        coEvery { notificationDao.threadMessages(any(), any(), any(), any()) } returns emptyList()

        coordinator(replyDao, notificationDao).processOnce()

        coVerify(exactly = 0) { replyDao.revertThreadToPending(any()) }
        coVerify(exactly = 0) { replyDao.markThreadSentConfirmed(any(), any(), any()) }
    }

    @Test fun outgoingAfterForwardStillConfirmsTheGroup() = runTest {
        val replyDao = mockk<ReplySuggestionDao>(relaxed = true)
        val notificationDao = mockk<NotificationCandidateDao>(relaxed = true)
        coEvery { replyDao.forwardedGroups() } returns listOf(forwardedRow(forwardedAtEpochMs = 1_000L))
        coEvery { notificationDao.threadMessages(any(), any(), any(), any()) } returns listOf(
            NotificationCandidateEntity(
                candidateId = "out-1",
                sourceKey = "sk-out-1",
                packageName = "com.tencent.mm",
                appLabel = "微信",
                title = "张三",
                body = "收到",
                postedAtEpochMs = 2_000L,
                platform = "WECHAT",
                conversationTitle = "张三",
                senderName = "我",
                direction = "OUTGOING",
                isGroupChat = false,
            ),
        )

        coordinator(replyDao, notificationDao).processOnce()

        coVerify { replyDao.markThreadSentConfirmed("WECHAT|张三", ReplySuggestionStatus.SENT_CONFIRMED, any()) }
        coVerify(exactly = 0) { replyDao.revertThreadToPending(any()) }
    }
}
