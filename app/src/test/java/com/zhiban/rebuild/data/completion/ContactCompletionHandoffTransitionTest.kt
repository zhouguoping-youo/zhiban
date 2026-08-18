package com.zhiban.rebuild.data.completion

import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.runtime.config.AgentControlStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * P2-3 回归:confirmAndHandoff 不得忽略 markAwaiting 返回值——0 行(状态已并发变化)= 没转成,
 * 不能谎报已进入等待回复。
 */
class ContactCompletionHandoffTransitionTest {

    @Test fun confirmAndHandoffReturnsFalseWhenMarkAwaitingAffectsNoRow() = runTest {
        val database = mockk<AgentDatabase>(relaxed = true)
        val requestDao = mockk<ContactCompletionRequestDao>(relaxed = true)
        val contactDao = mockk<com.zhiban.rebuild.data.contact.ContactDao>(relaxed = true)
        coEvery { database.contactCompletionRequestDao() } returns requestDao
        coEvery { database.contactDao() } returns contactDao
        coEvery { requestDao.findById("ccr-1") } returns ContactCompletionRequestEntity(
            requestId = "ccr-1",
            contactId = "c1",
            requestedFieldsJson = """["PHONE"]""",
            draftText = "方便发我下手机号吗",
            status = ContactCompletionStatus.DRAFTED,
            sentAtEpochMs = 1_000L,
            createdAtEpochMs = 1L,
            expiresAtEpochMs = Long.MAX_VALUE,
            updatedAtEpochMs = 1L,
        )
        coEvery { contactDao.findById("c1") } returns ContactEntity(
            contactId = "c1",
            displayName = "张三",
            normalizedName = "张三",
            phone = null,
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
        coEvery { requestDao.markAwaiting(any(), any(), any(), any(), any()) } returns 0 // 状态已并发变化
        val handoff = CompletionHandoff { _, _, _ -> true }

        val repository = ContactCompletionRepository(database, handoff, mockk(relaxed = true), mockk(relaxed = true))

        assertFalse(repository.confirmAndHandoff("ccr-1", "你好"))
    }
}
