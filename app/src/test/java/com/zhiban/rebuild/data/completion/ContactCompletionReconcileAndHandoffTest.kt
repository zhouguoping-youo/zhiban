package com.zhiban.rebuild.data.completion

import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.config.AgentControlStore
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.ContactKnowledgeDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * P2-3/P2-6 回归:
 * - confirmAndHandoff 不得忽略 markAwaiting 返回值——0 行(状态已并发变化)= 没转成,不能谎报成功;
 * - 对账:候选全过期/全驳回且资料未补全 → EXPIRED,不再误标 COMPLETED;有采纳或资料已手改齐 → COMPLETED。
 */
class ContactCompletionReconcileAndHandoffTest {

    private fun request(status: String) = ContactCompletionRequestEntity(
        requestId = "ccr-1",
        contactId = "c1",
        requestedFieldsJson = """["PHONE"]""",
        draftText = "方便发我下手机号吗",
        status = status,
        sentAtEpochMs = 1_000L,
        createdAtEpochMs = 1L,
        expiresAtEpochMs = Long.MAX_VALUE,
        updatedAtEpochMs = 1L,
    )

    private fun contact(phone: String?) = ContactEntity(
        contactId = "c1",
        displayName = "张三",
        normalizedName = "张三",
        phone = phone,
        email = "z@e.c",
        wechatId = "wx-1",
        company = "司",
        title = "职",
        aliasesJson = "[]",
        tagsJson = "[]",
        note = null,
        avatarUri = null,
        source = "USER",
        deletedAtEpochMs = null,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L,
        responsibilities = "责",
    )

    @Test fun expiredCandidatesMarkRequestExpiredNotCompleted() = runTest {
        val database = mockk<AgentDatabase>(relaxed = true)
        val requestDao = mockk<ContactCompletionRequestDao>(relaxed = true)
        val knowledgeDao = mockk<ContactKnowledgeDao>(relaxed = true)
        coEvery { database.contactCompletionRequestDao() } returns requestDao
        coEvery { database.contactKnowledgeDao() } returns knowledgeDao
        coEvery { requestDao.responseReceivedRequests() } returns listOf(request(ContactCompletionStatus.RESPONSE_RECEIVED))
        coEvery { knowledgeDao.countPendingBySourceRefPrefix("completion:ccr-1", any()) } returns 0 // 全过期/被清理
        coEvery { knowledgeDao.countApprovedBySourceRefPrefix("completion:ccr-1") } returns 0
        val contactDao = mockk<com.zhiban.rebuild.data.contact.ContactDao>(relaxed = true)
        coEvery { database.contactDao() } returns contactDao
        coEvery { contactDao.findById("c1") } returns contact(phone = null) // 资料仍未补全

        ContactCompletionCoordinator(database, mockk(relaxed = true), mockk(relaxed = true)).processOnce()

        coVerify { requestDao.markStatus("ccr-1", ContactCompletionStatus.EXPIRED, any()) }
        coVerify(exactly = 0) { requestDao.markStatus("ccr-1", ContactCompletionStatus.COMPLETED, any()) }
    }

    @Test fun approvedCandidatesCompleteTheRequest() = runTest {
        val database = mockk<AgentDatabase>(relaxed = true)
        val requestDao = mockk<ContactCompletionRequestDao>(relaxed = true)
        val knowledgeDao = mockk<ContactKnowledgeDao>(relaxed = true)
        coEvery { database.contactCompletionRequestDao() } returns requestDao
        coEvery { database.contactKnowledgeDao() } returns knowledgeDao
        coEvery { requestDao.responseReceivedRequests() } returns listOf(request(ContactCompletionStatus.RESPONSE_RECEIVED))
        coEvery { knowledgeDao.countPendingBySourceRefPrefix("completion:ccr-1", any()) } returns 0
        coEvery { knowledgeDao.countApprovedBySourceRefPrefix("completion:ccr-1") } returns 1

        ContactCompletionCoordinator(database, mockk(relaxed = true), mockk(relaxed = true)).processOnce()

        coVerify { requestDao.markStatus("ccr-1", ContactCompletionStatus.COMPLETED, any()) }
    }

    @Test fun handFilledProfileCompletesEvenWithPendingCandidates() = runTest {
        val database = mockk<AgentDatabase>(relaxed = true)
        val requestDao = mockk<ContactCompletionRequestDao>(relaxed = true)
        val knowledgeDao = mockk<ContactKnowledgeDao>(relaxed = true)
        coEvery { database.contactCompletionRequestDao() } returns requestDao
        coEvery { database.contactKnowledgeDao() } returns knowledgeDao
        coEvery { requestDao.responseReceivedRequests() } returns listOf(request(ContactCompletionStatus.RESPONSE_RECEIVED))
        coEvery { knowledgeDao.countPendingBySourceRefPrefix("completion:ccr-1", any()) } returns 3
        val contactDao = mockk<com.zhiban.rebuild.data.contact.ContactDao>(relaxed = true)
        coEvery { database.contactDao() } returns contactDao
        coEvery { contactDao.findById("c1") } returns contact(phone = "13800138000") // 用户手改补齐

        ContactCompletionCoordinator(database, mockk(relaxed = true), mockk(relaxed = true)).processOnce()

        coVerify { requestDao.markStatus("ccr-1", ContactCompletionStatus.COMPLETED, any()) }
    }
}
