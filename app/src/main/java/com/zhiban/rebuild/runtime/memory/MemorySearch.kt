package com.zhiban.rebuild.runtime.memory

import androidx.room.withTransaction
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.memory.MemoryRecordEntity
import com.zhiban.rebuild.runtime.context.EmbeddingGateway
import com.zhiban.rebuild.runtime.context.EmbeddingIndex
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

/** Scope-fenced exact + lexical + semantic retrieval. It returns data only, never prompt instructions. */
internal class MemorySearch(
    private val database: AgentDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val embeddingGateway: EmbeddingGateway? = null,
) {
    suspend fun search(request: MemorySearchQuery): MemorySearchResult {
        val normalized = validateRequest(request)
        val namespace = database.memoryPersistenceDao().namespace(request.namespaceId)
        requireNotNull(namespace) { "NAMESPACE_NOT_FOUND" }
        require(namespace.ownerUserId == request.ownerUserId && namespace.profileId == request.profileId) {
            "MEMORY_SCOPE_MISMATCH"
        }
        val vectorAttempt = semanticSearch(normalized)
        val vectorResult = vectorAttempt?.value
        return searchLocalAndMerge(request, normalized, vectorResult, vectorAttempt?.degradation)
    }

    private suspend fun semanticSearch(normalized: String) = embeddingGateway?.let { gateway ->
        attemptRetrieval("memory_vector") {
            EmbeddingIndex(database, gateway, clock)
                .search(normalized, VECTOR_CANDIDATE_LIMIT, factType = MEMORY_FACT_TYPE)
        }
    }

    private suspend fun searchLocalAndMerge(
        request: MemorySearchQuery,
        normalized: String,
        vectorResult: com.zhiban.rebuild.runtime.context.VectorSearchResult?,
        vectorFailure: String?,
    ): MemorySearchResult = database.withTransaction {
        val vectorMemoryIds = vectorResult?.candidates.orEmpty()
            .mapNotNull { candidate ->
                candidate.id.takeIf { it.startsWith(MEMORY_FACT_PREFIX) }
                    ?.removePrefix(MEMORY_FACT_PREFIX)
            }
            .distinct()

        val dao = database.memoryPersistenceDao()
        val terms = searchTerms(normalized)
        val ftsQuery = terms.joinToString(" OR ") { "\"$it\"" }.ifBlank { "\"__no_match__\"" }
        val now = clock()
        val exact = dao.exactCandidates(request.namespaceId, normalized, now, 64)
        val semantic = if (vectorMemoryIds.isEmpty()) {
            emptyList()
        } else {
            dao.currentRecordsByMemoryIds(request.namespaceId, vectorMemoryIds, now)
                .sortedBy { vectorMemoryIds.indexOf(it.memoryId) }
        }
        val ftsAttempt = attemptRetrieval("memory_fts") {
            dao.ftsCandidates(request.namespaceId, ftsQuery, now, 64)
        }
        val substringAttempt = attemptRetrieval("memory_substring") {
            dao.substringCandidates(request.namespaceId, terms, now, 64)
        }
        val fts = ftsAttempt.value.orEmpty()
        val substring = substringAttempt.value.orEmpty()
        val exactIds = exact.map { it.memoryId to it.recordVersion }.toSet()
        val semanticIds = semantic.map { it.memoryId to it.recordVersion }.toSet()
        val substringIds = substring.map { it.memoryId to it.recordVersion }.toSet()
        var tokens = 0
        val items = (exact + semantic + substring + fts).distinctBy { it.memoryId to it.recordVersion }
            .sortedWith(
                compareByDescending<MemoryRecordEntity> {
                    (it.memoryId to it.recordVersion) in exactIds
                }.thenByDescending {
                    (it.memoryId to it.recordVersion) in semanticIds
                }.thenByDescending {
                    (it.memoryId to it.recordVersion) in substringIds
                }.thenByDescending { it.confidence }
                    .thenByDescending { it.observedAtEpochMs },
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
                        when (record.memoryId to record.recordVersion) {
                            in exactIds -> 1.0
                            in semanticIds -> 0.9
                            in substringIds -> 0.8
                            else -> 0.6
                        },
                    )
                }
            }.take(request.limit)
        MemorySearchResult(
            items,
            tokens,
            semanticSearchDegraded = embeddingGateway == null ||
                vectorFailure != null ||
                vectorResult?.degradation != null,
            degradationReasons = listOfNotNull(
                vectorFailure,
                vectorResult?.degradation,
                ftsAttempt.degradation,
                substringAttempt.degradation,
            ).distinct(),
        )
    }

    private fun validateRequest(request: MemorySearchQuery): String {
        val normalized = request.query.trim().replace(Regex("\\s+"), " ")
        require(normalized.isNotBlank() && normalized.toByteArray().size <= 4096) { "INVALID_RETRIEVAL_REQUEST" }
        require(request.limit in 1..50 && request.tokenBudget in 0..32_768) { "INVALID_RETRIEVAL_REQUEST" }
        return normalized
    }

    private fun estimateTokens(value: String) = ceil(value.toByteArray().size / 4.0).toInt().coerceAtLeast(1)

    private fun searchTerms(query: String): List<String> {
        val latinTerms = Regex("[A-Za-z0-9_]+")
            .findAll(query)
            .map { it.value.lowercase() }
        val hanTerms = Regex("[\\u3400-\\u4DBF\\u4E00-\\u9FFF]+")
            .findAll(query)
            .flatMap { match ->
                val value = match.value
                if (value.length <= MAX_WHOLE_HAN_TERM_LENGTH) {
                    sequenceOf(value)
                } else {
                    value.windowedSequence(size = HAN_FRAGMENT_LENGTH)
                }
            }
            .filterNot(CHINESE_QUERY_STOP_FRAGMENTS::contains)
        return (latinTerms + hanTerms)
            .filter { it.length >= MIN_SEARCH_TERM_LENGTH }
            .distinct()
            .take(MAX_SEARCH_TERMS)
            .toList()
    }

    private companion object {
        const val MIN_SEARCH_TERM_LENGTH = 2
        const val HAN_FRAGMENT_LENGTH = 2
        const val MAX_WHOLE_HAN_TERM_LENGTH = 4
        const val MAX_SEARCH_TERMS = 16
        const val VECTOR_CANDIDATE_LIMIT = 32
        const val MEMORY_FACT_TYPE = "AGENT_MEMORY"
        const val MEMORY_FACT_PREFIX = "memory:"
        val CHINESE_QUERY_STOP_FRAGMENTS = setOf("的是", "是谁", "哪个", "什么", "怎么", "如何", "那家", "这家", "一个")
    }
}
