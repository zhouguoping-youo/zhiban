package com.zhiban.rebuild.data.ilink

import com.zhiban.rebuild.data.ilink.network.ILINK_SESSION_EXPIRED_CODE
import com.zhiban.rebuild.data.ilink.network.IlinkBotTransport
import com.zhiban.rebuild.data.ilink.network.IlinkSessionExpiredException
import com.zhiban.rebuild.runtime.provider.ProviderFailure
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * P1-5 会话过期双路径回归:HTTP 401/403 与协议层 ret=-14 同语义,都必须 markSessionExpired + 清
 * cursor/上下文令牌;瞬态失败(可重试)则保持会话与 cursor 不动。
 */
class IlinkSessionExpiredHandlingTest {

    private fun fetchCoordinator(
        transport: IlinkBotTransport,
        credentialStore: IlinkBotCredentialStore,
        cursorStore: IlinkCursorStore,
    ) = IlinkFetchCoordinator(transport, mockk(relaxed = true), credentialStore, cursorStore, mockk(relaxed = true))

    /** hasUsableBinding=true 且 withSession 真正执行 block,模拟"已绑定可用"状态。 */
    private fun liveSession(credentialStore: IlinkBotCredentialStore) {
        coEvery { credentialStore.hasUsableBinding() } returns true
        coEvery { credentialStore.withSession<Any?>(any()) } coAnswers {
            firstArg<suspend (ByteArray, IlinkBotBinding) -> Any?>().invoke(
                byteArrayOf(),
                IlinkBotBinding("bot", "owner", "https://ilinkai.weixin.qq.com", 1L, 1L),
            )
        }
    }

    @Test fun fetchHttp401MarksSessionExpiredAndClearsCursor() = runTest {
        val transport = mockk<IlinkBotTransport>(relaxed = true)
        val credentialStore = mockk<IlinkBotCredentialStore>(relaxed = true)
        val cursorStore = mockk<IlinkCursorStore>(relaxed = true)
        liveSession(credentialStore)
        coEvery { transport.getUpdates(any(), any(), any()) } throws ProviderFailure(ILINK_SESSION_EXPIRED_CODE, retryable = false)

        fetchCoordinator(transport, credentialStore, cursorStore).fetchOnce()

        coVerify { credentialStore.markSessionExpired() }
        coVerify { cursorStore.clear() }
    }

    @Test fun fetchProtocolMinus14StillMarksSessionExpired() = runTest {
        val transport = mockk<IlinkBotTransport>(relaxed = true)
        val credentialStore = mockk<IlinkBotCredentialStore>(relaxed = true)
        val cursorStore = mockk<IlinkCursorStore>(relaxed = true)
        liveSession(credentialStore)
        coEvery { transport.getUpdates(any(), any(), any()) } throws IlinkSessionExpiredException()

        fetchCoordinator(transport, credentialStore, cursorStore).fetchOnce()

        coVerify { credentialStore.markSessionExpired() }
        coVerify { cursorStore.clear() }
    }

    @Test fun fetchTransientFailureKeepsSessionAndCursor() = runTest {
        val transport = mockk<IlinkBotTransport>(relaxed = true)
        val credentialStore = mockk<IlinkBotCredentialStore>(relaxed = true)
        val cursorStore = mockk<IlinkCursorStore>(relaxed = true)
        liveSession(credentialStore)
        coEvery { transport.getUpdates(any(), any(), any()) } throws ProviderFailure("TIMEOUT", retryable = true)

        fetchCoordinator(transport, credentialStore, cursorStore).fetchOnce()

        coVerify(exactly = 0) { credentialStore.markSessionExpired() }
        coVerify(exactly = 0) { cursorStore.clear() }
    }

    @Test fun sendHttp401ClearsSessionAndContextTokensThenPropagates() = runTest {
        val transport = mockk<IlinkBotTransport>(relaxed = true)
        val credentialStore = mockk<IlinkBotCredentialStore>(relaxed = true)
        val contextTokenCache = mockk<ContextTokenCache>(relaxed = true)
        liveSession(credentialStore)
        coEvery { transport.sendMessage(any(), any(), any()) } throws ProviderFailure(ILINK_SESSION_EXPIRED_CODE, retryable = false)

        val sender = IlinkMessageSender(transport, mockk(relaxed = true), credentialStore, contextTokenCache)
        try {
            sender.sendText("user-1", "你好", "client-1")
            fail("会话过期的 ProviderFailure 应上抛给工具层")
        } catch (failure: ProviderFailure) {
            assertEquals(ILINK_SESSION_EXPIRED_CODE, failure.code)
        }

        coVerify { credentialStore.markSessionExpired() }
        coVerify { contextTokenCache.clear() }
    }
}
