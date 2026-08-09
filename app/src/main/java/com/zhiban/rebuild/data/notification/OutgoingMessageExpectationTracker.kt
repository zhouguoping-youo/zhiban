package com.zhiban.rebuild.data.notification

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-local handshake between a ZhiBan communication handoff and the optional accessibility
 * observer. Message text is deliberately not persisted to preferences or disk.
 */
@Singleton
class OutgoingMessageExpectationTracker @Inject constructor() {
    data class Expectation(
        val platform: String,
        val recipient: String,
        val message: String,
        val createdAtEpochMs: Long,
        val sendClickedAtEpochMs: Long? = null,
    )

    private val lock = Any()
    private var expectation: Expectation? = null

    fun record(platform: String, recipient: String, message: String, nowEpochMs: Long) {
        synchronized(lock) {
            expectation = Expectation(
                platform = platform,
                recipient = recipient.normalizedRecipient(),
                message = message.normalizedMessage(),
                createdAtEpochMs = nowEpochMs,
            )
        }
    }

    fun markSendClicked(platform: String, nowEpochMs: Long): Expectation? = synchronized(lock) {
        val current = validExpectation(nowEpochMs) ?: return@synchronized null
        if (current.platform != platform) return@synchronized null
        current.copy(sendClickedAtEpochMs = nowEpochMs).also { expectation = it }
    }

    fun armed(platform: String, nowEpochMs: Long): Expectation? = synchronized(lock) {
        validExpectation(nowEpochMs)
            ?.takeIf { it.platform == platform && it.sendClickedAtEpochMs != null }
    }

    fun pending(platform: String, nowEpochMs: Long): Expectation? = synchronized(lock) {
        validExpectation(nowEpochMs)?.takeIf { it.platform == platform }
    }

    fun consume(expected: Expectation) {
        synchronized(lock) {
            if (expectation == expected) expectation = null
        }
    }

    fun clear() {
        synchronized(lock) { expectation = null }
    }

    private fun validExpectation(nowEpochMs: Long): Expectation? {
        val current = expectation ?: return null
        if (nowEpochMs - current.createdAtEpochMs > EXPECTATION_TTL_MS) {
            expectation = null
            return null
        }
        return current
    }

    private fun String.normalizedMessage(): String = replace(Regex("\\s+"), " ").trim().take(MAX_MESSAGE_CHARS)

    private fun String.normalizedRecipient(): String = replace(Regex("\\s+"), " ").trim().take(MAX_RECIPIENT_CHARS)

    private companion object {
        const val EXPECTATION_TTL_MS = 10 * 60_000L
        const val MAX_MESSAGE_CHARS = 2_000
        const val MAX_RECIPIENT_CHARS = 200
    }
}
