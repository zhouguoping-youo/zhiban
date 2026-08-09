package com.zhiban.rebuild.runtime.context

import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.agent.CrmAgentDataRepository
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
    val degradationReasons: Set<String> = emptySet(),
)

/** Idempotent cold-start maintenance for Agent-owned context and memory projections. */
@Singleton
internal class AgentMaintenanceCoordinator @Inject constructor(private val database: AgentDatabase, private val embeddingGateway: EmbeddingGateway) {
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
            deleted,
            dormant,
            expiredAudits,
            changeUndoExpired,
            oldChangesDeleted,
            factFtsRebuilt,
            crmSuggestionsExpired,
            enrichmentExpired,
            degradationReasons,
        )
    }

    private companion object {
        const val AUDIT_RETENTION_MS = 90L * 24 * 60 * 60 * 1_000
        const val CHANGE_UNDO_WINDOW_MS = 90L * 24 * 60 * 60 * 1_000
        const val CHANGE_LOG_RETENTION_MS = 365L * 24 * 60 * 60 * 1_000
        const val MAINTENANCE_BATCH_SIZE = 256
        const val EMBEDDING_BACKFILL_FAILURE = "embedding_backfill:failure"
    }
}
