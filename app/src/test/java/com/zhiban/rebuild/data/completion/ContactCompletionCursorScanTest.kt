package com.zhiban.rebuild.data.completion

import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.config.AgentControlStore
import com.zhiban.rebuild.data.notification.NotificationCandidateDao
import com.zhiban.rebuild.data.notification.NotificationCandidateEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * P2-5 游标式扫描回归:7 天窗口内消息超过一页(20 条)时必须逐页拉完,不能再固定取最新 20 条
 * 导致旧回复永久错过;游标随扫描推进并持久化。
 */
class ContactCompletionCursorScanTest {

    private fun message(index: Int) = NotificationCandidateEntity(
        candidateId = "n-$index",
        sourceKey = "sk-$index",
        packageName = "com.tencent.mm",
        appLabel = "微信",
        title = "张三",
        body = "消息 $index",
        postedAtEpochMs = index * 1_000L,
        platform = "WECHAT",
        conversationTitle = "张三",
        senderName = "张三",
        direction = "INCOMING",
        isGroupChat = false,
        linkedContactId = "c1",
    )

    @Test fun scanPullsEveryPageBeyondTwentyMessagesAndAdvancesCursor() = runTest {
        val database = mockk<AgentDatabase>(relaxed = true)
        val requestDao = mockk<ContactCompletionRequestDao>(relaxed = true)
        val notificationDao = mockk<NotificationCandidateDao>(relaxed = true)
        val controls = mockk<AgentControlStore>(relaxed = true)
        coEvery { database.contactCompletionRequestDao() } returns requestDao
        coEvery { database.notificationCandidateDao() } returns notificationDao
        coEvery { controls.contactCompletionEnabled() } returns true
        coEvery { controls.completionScanCursor() } returns 0L
        // findAwaitingForContact 返回 null:候选逐条处理提前返回,本测试只关心分页与游标。
        coEvery { requestDao.findAwaitingForContact(any(), any()) } returns null

        val page1 = (1..20).map(::message)
        val page2 = (21..23).map(::message)
        coEvery { notificationDao.incomingAttributedAfter(any(), any(), 0L, 20) } returns page1
        coEvery { notificationDao.incomingAttributedAfter(any(), any(), 20_000L, 20) } returns page2

        ContactCompletionCoordinator(database, mockk(relaxed = true), controls).processOnce()

        coVerify { notificationDao.incomingAttributedAfter(any(), any(), 0L, 20) }
        coVerify { notificationDao.incomingAttributedAfter(any(), any(), 20_000L, 20) }
        coVerify { controls.saveCompletionScanCursor(23_000L) }
    }

    @Test fun scanStopsAfterPartialFinalPage() = runTest {
        val database = mockk<AgentDatabase>(relaxed = true)
        val requestDao = mockk<ContactCompletionRequestDao>(relaxed = true)
        val notificationDao = mockk<NotificationCandidateDao>(relaxed = true)
        val controls = mockk<AgentControlStore>(relaxed = true)
        coEvery { database.contactCompletionRequestDao() } returns requestDao
        coEvery { database.notificationCandidateDao() } returns notificationDao
        coEvery { controls.contactCompletionEnabled() } returns true
        coEvery { controls.completionScanCursor() } returns 5_000L
        coEvery { requestDao.findAwaitingForContact(any(), any()) } returns null
        coEvery { notificationDao.incomingAttributedAfter(any(), any(), 5_000L, 20) } returns (6..8).map(::message)

        ContactCompletionCoordinator(database, mockk(relaxed = true), controls).processOnce()

        coVerify { controls.saveCompletionScanCursor(8_000L) }
    }
}
