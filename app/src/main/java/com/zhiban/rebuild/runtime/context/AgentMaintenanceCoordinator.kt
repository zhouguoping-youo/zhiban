package com.zhiban.rebuild.runtime.context

import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.agent.CrmAgentDataRepository
import com.zhiban.rebuild.data.facts.FactIndex
import com.zhiban.rebuild.data.interaction.SilentContactSuggestionScanner
import com.zhiban.rebuild.data.interaction.UnobservedReplySuggestionScanner
import com.zhiban.rebuild.data.suggestion.AgentSuggestionNotifier
import com.zhiban.rebuild.runtime.memory.RoomMemoryGate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

data class AgentMaintenanceResult(
    val expiredFactsDeleted: Int,
    val memoriesDormant: Int,
    val expiredAuditsDeleted: Int,
    val changeUndoExpired: Int,
    val oldChangesDeleted: Int,
    val factFtsRebuilt: Boolean,
    val crmSuggestionsExpired: Int,
    val enrichmentExpired: Int,
    val notificationExpired: Int = 0,
    val agentSuggestionsExpired: Int = 0,
    val silenceSuggestionsCreated: Int = 0,
    val unobservedReplySuggestionsCreated: Int = 0,
    val degradationReasons: Set<String> = emptySet(),
)

/** Idempotent cold-start maintenance for Agent-owned context and memory projections. */
@Singleton
internal class AgentMaintenanceCoordinator @Inject constructor(
    private val database: AgentDatabase,
    private val embeddingGateway: EmbeddingGateway,
    private val suggestionNotifier: AgentSuggestionNotifier,
    private val silentContactScanner: SilentContactSuggestionScanner,
    private val unobservedReplyScanner: UnobservedReplySuggestionScanner,
) {
    suspend fun run(nowEpochMs: Long = System.currentTimeMillis()): AgentMaintenanceResult {
        val facts = FactIndex(database)
        var deleted = 0
        do {
            val batch = facts.deleteExpired(nowEpochMs, 128)
            deleted += batch
        } while (batch == 128)
        val factFtsRebuilt = facts.repairIfInconsistent()
        val dormant = RoomMemoryGate(database) { nowEpochMs }.applyDormancyPolicy()
        val auditDao = database.toolAuditDao()
        val expiredAudits = auditDao.deleteExpired(nowEpochMs) +
            auditDao.deleteOlderThan(nowEpochMs - AUDIT_RETENTION_MS)
        val changeLogDao = database.changeLogDao()
        var changeUndoExpired = 0
        do {
            val batch = changeLogDao.expireUndoBefore(nowEpochMs - CHANGE_UNDO_WINDOW_MS, MAINTENANCE_BATCH_SIZE)
            changeUndoExpired += batch
        } while (batch == MAINTENANCE_BATCH_SIZE)
        var oldChangesDeleted = 0
        do {
            val batch = changeLogDao.deleteTerminalBefore(nowEpochMs - CHANGE_LOG_RETENTION_MS, MAINTENANCE_BATCH_SIZE)
            oldChangesDeleted += batch
        } while (batch == MAINTENANCE_BATCH_SIZE)
        val crmSuggestionsExpired = database.crmDao()
            .expirePendingSuggestionsBefore(nowEpochMs - CrmAgentDataRepository.SUGGESTION_TTL_MS, nowEpochMs)
        val enrichmentExpired = database.contactKnowledgeDao().purgeExpiredEnrichment(nowEpochMs)
        // 通知候选的过期/DISMISSED 清理从"每条通知的暂存事务内"移到维护周期(P2:暂存事务不再
        // 夹带 30 天前的批量 DELETE,每条通知的事务更短)。
        val notificationExpired = database.notificationCandidateDao()
            .clearExpiredOrDismissed(nowEpochMs - 30L * 24 * 60 * 60 * 1_000)
        val agentSuggestionsExpired = database.agentSuggestionDao()
            .expirePending(nowEpochMs - AGENT_SUGGESTION_TTL_MS, nowEpochMs)
        val imminentSchedules = database.agentSuggestionDao()
            .imminentSchedules(nowEpochMs, nowEpochMs + SCHEDULE_ESCALATION_WINDOW_MS)
        suggestionNotifier.publishScheduleEscalation(imminentSchedules, nowEpochMs)
        val silenceSuggestionsCreated = if (silentContactScanner.scan(nowEpochMs)) 1 else 0
        val unobservedReplySuggestionsCreated = unobservedReplyScanner.scan(nowEpochMs)
        // One bounded batch per startup; retrieval remains FTS-only until every active fact is rebuilt.
        val degradationReasons = try {
            EmbeddingIndex(database, embeddingGateway) { nowEpochMs }.backfillBatch(32)
            emptySet()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            setOf(EMBEDDING_BACKFILL_FAILURE)
        }
        return AgentMaintenanceResult(
            expiredFactsDeleted = deleted,
            memoriesDormant = dormant,
            expiredAuditsDeleted = expiredAudits,
            changeUndoExpired = changeUndoExpired,
            oldChangesDeleted = oldChangesDeleted,
            factFtsRebuilt = factFtsRebuilt,
            crmSuggestionsExpired = crmSuggestionsExpired,
            enrichmentExpired = enrichmentExpired,
            notificationExpired = notificationExpired,
            agentSuggestionsExpired = agentSuggestionsExpired,
            silenceSuggestionsCreated = silenceSuggestionsCreated,
            unobservedReplySuggestionsCreated = unobservedReplySuggestionsCreated,
            degradationReasons = degradationReasons,
        )
    }

    private companion object {
        const val AUDIT_RETENTION_MS = 90L * 24 * 60 * 60 * 1_000
        const val CHANGE_UNDO_WINDOW_MS = 90L * 24 * 60 * 60 * 1_000
        const val CHANGE_LOG_RETENTION_MS = 365L * 24 * 60 * 60 * 1_000
        const val MAINTENANCE_BATCH_SIZE = 256
        const val EMBEDDING_BACKFILL_FAILURE = "embedding_backfill:failure"
        const val AGENT_SUGGESTION_TTL_MS = 7L * 24 * 60 * 60 * 1_000
        const val SCHEDULE_ESCALATION_WINDOW_MS = 24L * 60 * 60 * 1_000
    }
}
