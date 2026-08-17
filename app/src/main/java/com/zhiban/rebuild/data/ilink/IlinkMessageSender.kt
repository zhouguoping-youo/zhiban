package com.zhiban.rebuild.data.ilink

import com.zhiban.rebuild.data.ilink.network.IlinkBotTransport
import com.zhiban.rebuild.data.ilink.network.IlinkOutboundMessage
import com.zhiban.rebuild.data.ilink.network.IlinkSendResult
import com.zhiban.rebuild.data.ilink.network.IlinkSessionExpiredException
import com.zhiban.rebuild.runtime.provider.ProviderFailure
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Performs the actual WeChat iLink send for [com.zhiban.rebuild.runtime.tool.WechatSendToolBinding].
 *
 * Ordering mirrors `StepFunWebSearchGateway`: outbound-gate first, then credentials, then network.
 * Retries reuse the same `client_id` so the server's dedup makes a retry after a timeout safe (it
 * will not double-deliver). A `ret: -14` marks the binding expired and clears the short-lived
 * context tokens, then propagates so the user is told to re-bind.
 */
@Singleton
internal class IlinkMessageSender @Inject constructor(
    private val transport: IlinkBotTransport,
    private val gate: IlinkOutboundGate,
    private val credentialStore: IlinkBotCredentialStore,
    private val contextTokenCache: ContextTokenCache,
) {
    data class SentMessage(val messageId: Long?, val clientId: String, val threadedIntoConversation: Boolean)

    /**
     * @param clientId stable per logical message (derived from the tool idempotency key) so that a
     * retry after an unknown outcome reuses the same id and the server dedups instead of
     * double-delivering.
     */
    suspend fun sendText(toUserId: String, text: String, clientId: String): SentMessage {
        require(toUserId.isNotBlank()) { "ILINK_RECIPIENT_INVALID" }
        require(text.isNotBlank() && text.length <= MAX_TEXT_CHARS) { "ILINK_MESSAGE_INVALID" }
        require(clientId.isNotBlank() && clientId.length <= MAX_CLIENT_ID_CHARS) { "ILINK_CLIENT_ID_INVALID" }
        gate.requireSendAllowed("ilink-send-${UUID.randomUUID()}", text.toByteArray().size.toLong())
        return credentialStore.withSession { token, binding ->
            // Thread into the live conversation when we hold a fresh context token for it; otherwise
            // send as a standalone push (empty token).
            val contextToken = contextTokenCache.get(toUserId).orEmpty()
            val outbound = IlinkOutboundMessage(toUserId, clientId, text, contextToken)
            val result = sendWithRetry(token, binding.baseUrl, outbound)
            credentialStore.markValidated(System.currentTimeMillis())
            SentMessage(result.messageId, clientId, contextToken.isNotEmpty())
        }
    }

    private suspend fun sendWithRetry(token: ByteArray, baseUrl: String, outbound: IlinkOutboundMessage): IlinkSendResult {
        var attempt = 0
        while (true) {
            try {
                return transport.sendMessage(token, baseUrl, outbound)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (expired: IlinkSessionExpiredException) {
                handleSessionExpired()
                throw expired
            } catch (failure: ProviderFailure) {
                if (!failure.retryable || attempt >= RETRY_BACKOFF_MS.size) throw failure
                delay(RETRY_BACKOFF_MS[attempt])
                attempt += 1
            }
        }
    }

    private fun handleSessionExpired() {
        credentialStore.markSessionExpired()
        contextTokenCache.clear()
    }

    private companion object {
        const val MAX_TEXT_CHARS = 2_000
        const val MAX_CLIENT_ID_CHARS = 64
        val RETRY_BACKOFF_MS = longArrayOf(200L, 800L)
    }
}
