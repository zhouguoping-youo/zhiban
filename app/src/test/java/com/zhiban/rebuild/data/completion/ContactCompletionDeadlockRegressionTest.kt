package com.zhiban.rebuild.data.completion

import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.contact.ContactEnrichmentCandidateEntity
import com.zhiban.rebuild.data.contact.ContactKnowledgeDao
import com.zhiban.rebuild.runtime.config.AgentControlStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P1-1 补全卡死链的 JVM 回归(androidTest 真库版见 ContactCompletionLoopTest /
 * ContactCompletionRepositoryTest):①闸门把未处理完的 RESPONSE_RECEIVED 请求也视为进行中;②二次回复的
 * 候选即使与旧候选同 PK,状态也必须推进 RESPONSE_RECEIVED,不能依赖插入是否成功。
 */
class ContactCompletionDeadlockRegressionTest {

    @Test fun prepareOutreachBlockedWhileUnresolvedResponseReceivedRequestActive() = runTest {
        val database = mockk<AgentDatabase>(relaxed = true)
        val requestDao = mockk<ContactCompletionRequestDao>(relaxed = true)
        val controls = mockk<AgentControlStore>(relaxed = true)
        coEvery { controls.contactCompletionEnabled() } returns true
        coEvery { database.contactCompletionRequestDao() } returns requestDao
        coEvery { requestDao.countActiveForContact("c1", any()) } returns 1

        val repository = ContactCompletionRepository(database, mockk(relaxed = true), mockk(relaxed = true), controls)

        assertNull(repository.prepareOutreach("c1"))
    }

    @Test fun secondReplyAfterExpiredReOutreachStillMarksResponseReceived() = runTest {
        val database = mockk<AgentDatabase>(relaxed = true)
        val requestDao = mockk<ContactCompletionRequestDao>(relaxed = true)
        val knowledgeDao = mockk<ContactKnowledgeDao>(relaxed = true)
        val parser = mockk<ContactCompletionResponseParser>(relaxed = true)
        val controls = mockk<AgentControlStore>(relaxed = true)

        coEvery { database.contactCompletionRequestDao() } returns requestDao
        coEvery { database.contactKnowledgeDao() } returns knowledgeDao

        val request = ContactCompletionRequestEntity(
            requestId = "ccr-1",
            contactId = "c1",
            requestedFieldsJson = """["PHONE"]""",
            draftText = "方便发我下手机号吗",
            status = ContactCompletionStatus.AWAITING_REPLY,
            sentAtEpochMs = 1_000L,
            createdAtEpochMs = 1L,
            expiresAtEpochMs = Long.MAX_VALUE,
            updatedAtEpochMs = 1L,
        )
        // 与旧周期完全相同的确定性候选 PK:过去会 IGNORE 返回 -1、状态永远不推进。
        val staged = ContactEnrichmentCandidateEntity(
            candidateId = "cc-ccr-1-COMMUNICATION_METHOD",
            contactId = "c1",
            providerId = "contact-completion-outreach",
            fieldKind = "COMMUNICATION_METHOD",
            proposedValueJson = """{"phone":"13800138000"}""",
            sourceRef = "completion:ccr-1:COMMUNICATION_METHOD",
            confidence = 0.9,
            status = "PENDING",
            observedAtEpochMs = 2_000L,
            expiresAtEpochMs = 2_000L + 30L * 24 * 60 * 60 * 1_000,
            createdAtEpochMs = 2_000L,
            updatedAtEpochMs = 2_000L,
        )
        val coordinator = ContactCompletionCoordinator(database, parser, controls)

        // 事务包装由 processCandidate 的 withTransaction 承担(R10),这里直测事务体内的落库+状态推进。
        coordinator.stageCandidatesAndMark(request, listOf(staged), 2_000L)

        coVerify { knowledgeDao.upsertEnrichmentCandidate(staged) }
        coVerify { requestDao.markResponseReceived("ccr-1", "cc-ccr-1-COMMUNICATION_METHOD", any()) }
    }
}
