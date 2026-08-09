package com.zhiban.rebuild.runtime.memory

import androidx.room.withTransaction
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.runtime.context.attemptRetrieval
import kotlin.math.ceil

data class MemorySearchItem(
    val memoryId: String,
    val logicalMemoryId: String,
    val canonicalText: String,
    val sensitivity: String,
    val sourceRefs: List<String>,
    val score: Double,
)

data class MemorySearchResult(
    val items: List<MemorySearchItem>,
    val estimatedTokens: Int,
    /** True until an embedding retriever is configured; exact+FTS remains fully usable. */
    val semanticSearchDegraded: Boolean,
    /** Fixed, non-sensitive reason codes for partial retrieval failure. */
    val degradationReasons: List<String>,
)

data class MemorySearchQuery(val namespaceId: String, val ownerUserId: String, val profileId: String, val query: String, val limit: Int, val tokenBudget: Int)

/** Scope-fenced exact + FTS retrieval. It returns data only, never executable prompt instructions. */
internal class MemorySearch(private val database: AgentDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    suspend fun search(request: MemorySearchQuery): MemorySearchResult = database.withTransaction {
        val normalized = request.query.trim().replace(Regex("\\s+"), " ")
        require(normalized.isNotBlank() && normalized.toByteArray().size <= 4096) { "INVALID_RETRIEVAL_REQUEST" }
        require(request.limit in 1..50 && request.tokenBudget in 0..32_768) { "INVALID_RETRIEVAL_REQUEST" }
        val dao = database.memoryPersistenceDao()
        val namespace = requireNotNull(dao.namespace(request.namespaceId)) { "NAMESPACE_NOT_FOUND" }
        require(namespace.ownerUserId == request.ownerUserId && namespace.profileId == request.profileId) { "MEMORY_SCOPE_MISMATCH" }
        val terms = Regex("[\\p{L}\\p{N}_]+").findAll(normalized).map { it.value }.take(32).toList()
        val ftsQuery = terms.joinToString(" AND ") { "\"$it\"" }.ifBlank { "\"__no_match__\"" }
        val now = clock()
        val exact = dao.exactCandidates(request.namespaceId, normalized, now, 64)
        val ftsAttempt = attemptRetrieval("memory_fts") {
            dao.ftsCandidates(request.namespaceId, ftsQuery, now, 64)
        }
        val fts = ftsAttempt.value.orEmpty()
        val exactIds = exact.map { it.memoryId to it.recordVersion }.toSet()
        var tokens = 0
        val items = (exact + fts).distinctBy { it.memoryId to it.recordVersion }
            .sortedWith(
                compareByDescending<MemoryRecordEntity> {
                    (it.memoryId to it.recordVersion) in exactIds
                }.thenByDescending { it.confidence },
            )
            .mapNotNull { record ->
                val sources = dao.evidence(request.namespaceId, record.memoryId, record.recordVersion, 16).map { it.sourceRef }
                val cost = estimateTokens(record.canonicalText) + sources.sumOf(::estimateTokens)
                if (tokens + cost > request.tokenBudget) {
                    null
                } else {
                    tokens += cost
                    MemorySearchItem(
                        record.memoryId,
                        record.logicalMemoryId,
                        record.canonicalText,
                        record.sensitivity,
                        sources,
                        if ((record.memoryId to record.recordVersion) in exactIds) 1.0 else 0.6,
                    )
                }
            }.take(request.limit)
        MemorySearchResult(
            items,
            tokens,
            semanticSearchDegraded = true,
            degradationReasons = listOfNotNull(ftsAttempt.degradation),
        )
    }

    private fun estimateTokens(value: String) = ceil(value.toByteArray().size / 4.0).toInt().coerceAtLeast(1)
}
