package com.zhiban.rebuild.data.reply

import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.runtime.config.AgentControlStore
import kotlinx.coroutines.flow.Flow

/**
 * Read/write surface the reply-suggestion UI uses. Kept separate from [com.zhiban.rebuild.data.agent.AgentDataRepository]
 * (which is at the file-size audit ceiling): the card observes PENDING groups and transitions them, and forwarding
 * delegates the last mile to [ReplyDeliveryExecutor] — the draft is handed to the user, never auto-sent.
 *
 * Public type with an internal constructor (mirrors AgentDataRepository) so it can cross into the public
 * RelationViewModel while its construction stays behind DI.
 */
class ReplySuggestionRepository internal constructor(
    private val database: AgentDatabase,
    private val deliveryExecutor: ReplyDeliveryExecutor,
    private val controls: AgentControlStore,
) {
    private val dao get() = database.replySuggestionDao()

    /** All live PENDING rows; the coordinator's 24h expiry keeps this bounded. Grouped by candidateId in the UI. */
    fun observePending(): Flow<List<ReplySuggestionEntity>> = dao.observePending(sinceEpochMs = 0L)

    /**
     * Hand the chosen draft to the user (jump to WeChat prefilled) and, only if the handoff actually launched,
     * mark the whole group FORWARDED. The subsequent accessibility capture upgrades it to SENT_CONFIRMED.
     */
    suspend fun forward(candidateId: String, platform: String, recipientDisplayName: String, draft: String): ReplyDeliveryResult {
        val result = deliveryExecutor.deliver(platform, recipientDisplayName, draft)
        if (result.launched) {
            dao.markGroupForwarded(candidateId, ReplySuggestionStatus.FORWARDED, System.currentTimeMillis())
        }
        return result
    }

    suspend fun dismiss(candidateId: String) {
        dao.markGroupDismissed(candidateId, ReplySuggestionStatus.DISMISSED)
    }

    /** "不再为该联系人建议": drop every live group for the contact and block future generation for them. */
    suspend fun optOutContact(contactId: String) {
        controls.setReplyOptOut(contactId, true)
        dao.dismissPendingForContact(contactId)
    }
}
