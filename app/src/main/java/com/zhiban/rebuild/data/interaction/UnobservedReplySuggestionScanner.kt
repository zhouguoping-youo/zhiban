package com.zhiban.rebuild.data.interaction

import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.config.AgentControlStore
import com.zhiban.rebuild.data.suggestion.AgentSuggestionEntity
import com.zhiban.rebuild.data.suggestion.AgentSuggestionNotifier
import com.zhiban.rebuild.data.suggestion.AgentSuggestionStatus
import com.zhiban.rebuild.data.suggestion.AgentSuggestionType
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Finds old outgoing messages for which no later incoming interaction was observed. */
@Singleton
class UnobservedReplySuggestionScanner @Inject internal constructor(
    private val database: AgentDatabase,
    private val controls: AgentControlStore,
    private val notifier: AgentSuggestionNotifier,
) {
    suspend fun scan(nowEpochMs: Long = System.currentTimeMillis()): Int {
        val cutoff = nowEpochMs - controls.unobservedReplyDays() * DAY_MS
        val candidates = database.contactInteractionDao().unobservedReplyFollowUps(cutoff, MAX_SUGGESTIONS_PER_SCAN)
            .filterNot { controls.isReplyOptedOut(it.contactId) }
        var insertedCount = 0
        candidates.forEach { candidate ->
            val suggestion = AgentSuggestionEntity(
                suggestionId = UUID.randomUUID().toString(),
                type = AgentSuggestionType.UNOBSERVED_REPLY,
                title = "可以跟进一下${candidate.displayName}",
                body = "发出消息后暂未观察到回复，可以看看是否需要再联系。",
                contactId = candidate.contactId,
                candidateId = candidate.outgoingSourceId,
                sourceEvent = SOURCE_EVENT,
                dedupeKey = "unobserved-reply:${candidate.outgoingSourceId}",
                status = AgentSuggestionStatus.PENDING,
                createdAtEpochMs = nowEpochMs,
                updatedAtEpochMs = nowEpochMs,
            )
            if (database.agentSuggestionDao().insert(suggestion) != -1L) insertedCount += 1
        }
        if (insertedCount > 0) notifier.publish(database.agentSuggestionDao().pendingCount(), null, nowEpochMs)
        return insertedCount
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1_000
        const val MAX_SUGGESTIONS_PER_SCAN = 20
        const val SOURCE_EVENT = "MAINTENANCE_UNOBSERVED_REPLY_SCAN"
    }
}
