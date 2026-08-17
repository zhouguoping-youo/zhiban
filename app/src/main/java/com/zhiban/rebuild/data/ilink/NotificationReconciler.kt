package com.zhiban.rebuild.data.ilink

import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.ilink.network.IlinkInboundMessage
import com.zhiban.rebuild.data.notification.NotificationCandidateEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reconciles inbound iLink messages with the notification candidates the listener already staged.
 *
 * Three jobs, in increasing order of care:
 *  1. Cache each conversation's `context_token` (safe; enables reply threading in the sender).
 *  2. Replace a candidate's notification-truncated body with the full message text — only on an
 *     unambiguous text match, so the Agent judges the complete demand instead of a 20–50 char stub.
 *  3. Learn the sender's iLink `userId` onto a contact — only when exactly one candidate matches the
 *     text AND that candidate already carries a high-confidence contact suggestion. The "never
 *     attach collection to the wrong contact" rule outranks coverage, so ambiguous bursts are skipped
 *     rather than guessed.
 */
@Singleton
internal class NotificationReconciler @Inject constructor(
    private val database: AgentDatabase,
    private val resolver: ContactWechatResolver,
    private val contextTokenCache: ContextTokenCache,
) {
    /** Reconcile one page of inbound messages. Returns how many candidates were upgraded to full text. */
    suspend fun reconcile(messages: List<IlinkInboundMessage>, nowEpochMs: Long): Int {
        var upgraded = 0
        for (message in messages) {
            if (!message.isUserAuthored) continue
            if (!seenMarker.markSeen(message.messageId)) continue
            message.contextToken?.let { contextTokenCache.put(message.fromUserId, it) }
            val fullText = message.text?.trim().orEmpty()
            if (fullText.isEmpty()) continue
            val matched = matchSingleCandidate(message, fullText, nowEpochMs) ?: continue
            if (upgradeBody(matched, fullText)) upgraded += 1
            learnSender(message, matched, nowEpochMs)
        }
        return upgraded
    }

    /** Exactly one recent pending WeChat candidate whose visible text is contained in the full message. */
    private suspend fun matchSingleCandidate(message: IlinkInboundMessage, fullText: String, nowEpochMs: Long): NotificationCandidateEntity? {
        val since = nowEpochMs - MATCH_WINDOW_MS
        val candidates = database.notificationCandidateDao().recentPendingByPlatform(PLATFORM_WECHAT, since)
        val matches = candidates.filter { candidate ->
            val visible = normalize(candidate.body)
            visible.length >= MIN_MATCH_CHARS && normalize(fullText).contains(visible.take(MAX_PREFIX_CHARS))
        }
        return matches.singleOrNull()
    }

    private suspend fun upgradeBody(candidate: NotificationCandidateEntity, fullText: String): Boolean {
        if (fullText == candidate.body?.trim()) return false
        database.notificationCandidateDao().upsert(candidate.copy(body = fullText.take(MAX_BODY_CHARS)))
        return true
    }

    /** Learn the sender's userId onto the matched contact when the existing suggestion is confident. */
    private suspend fun learnSender(message: IlinkInboundMessage, matched: NotificationCandidateEntity, nowEpochMs: Long) {
        val contactId = matched.suggestedContactId ?: return
        if (matched.suggestedContactConfidence < LEARN_CONFIDENCE_THRESHOLD) return
        resolver.learnUserId(contactId, message.fromUserId, nowEpochMs)
    }

    private fun normalize(value: String?): String = value.orEmpty().replace(Regex("\\s+"), " ").trim()

    private companion object {
        const val PLATFORM_WECHAT = "WECHAT"
        const val MATCH_WINDOW_MS = 10 * 60_000L
        const val MIN_MATCH_CHARS = 6
        const val MAX_PREFIX_CHARS = 40
        const val MAX_BODY_CHARS = 1_000
        const val LEARN_CONFIDENCE_THRESHOLD = 0.9

        // Bounded dedup so a message delivered twice (notification trigger + a later poll) is not
        // reconciled twice.
        val seenMarker = RecentlySeenMessages()
    }
}

/** Tiny bounded LRU of recently processed iLink `message_id`s. */
internal class RecentlySeenMessages(private val capacity: Int = 1_000) {
    private val seen = LinkedHashSet<String>()

    @Synchronized
    fun markSeen(messageId: Long): Boolean {
        val key = messageId.toString()
        if (!seen.add(key)) return false
        if (seen.size > capacity) seen.remove(seen.first())
        return true
    }
}
