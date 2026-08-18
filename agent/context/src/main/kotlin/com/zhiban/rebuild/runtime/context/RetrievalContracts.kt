package com.zhiban.rebuild.runtime.context

enum class RetrievalPath { STRUCTURED, FTS, VECTOR, GRAPH }

data class EmbeddingSpace(val providerId: String, val modelId: String, val dimensions: Int, val modelVersion: String? = null) {
    init {
        require(providerId.matches(Regex("[A-Za-z0-9._-]{2,80}")))
        require(modelId.matches(Regex("[A-Za-z0-9._-]{2,128}")))
        require(dimensions in 8..16_384)
    }
}

enum class EmbeddingPurpose { LOCAL_INDEX_CONTENT, USER_QUERY, CONFIGURATION_PROBE, HEALTH_CHECK }

data class EmbeddingInput(val text: String, val sensitivity: Sensitivity, val purpose: EmbeddingPurpose, val sourceKind: String, val sourceId: String) {
    init {
        require(text.toByteArray().size in 1..100_000)
        require(sourceKind.matches(Regex("[A-Za-z0-9._-]{1,80}")))
        require(sourceId.matches(Regex("[A-Za-z0-9._:-]{1,160}")))
    }
}

/** Provider-neutral embedding boundary. Implementations must return one finite vector per input. */
interface EmbeddingGateway {
    suspend fun activeSpace(): EmbeddingSpace?
    suspend fun embed(inputs: List<EmbeddingInput>, space: EmbeddingSpace): List<FloatArray>
}

data class RetrievalCandidate(
    val id: String,
    val sourceKind: String,
    val sourceRef: String,
    val summary: String,
    val entityRefs: List<String> = emptyList(),
    val timestampEpochMs: Long? = null,
    val sensitivity: Sensitivity = Sensitivity.PERSONAL,
)

data class RankedRetrievalCandidate(val candidate: RetrievalCandidate, val score: Double, val contributingPaths: Set<RetrievalPath>)

data class ContextRetrievalResult(
    val items: List<RankedRetrievalCandidate>,
    val structuredCandidateCount: Int,
    val degradationPath: List<String>,
    val estimatedTokens: Int,
)

fun ContextRetrievalResult.reranked(orderedIds: List<String>, degradation: String? = null): ContextRetrievalResult {
    val byId = items.associateBy { it.candidate.id }
    val reordered = (
        orderedIds.mapNotNull(byId::get) + items.filterNot {
            it.candidate.id in orderedIds
        }
        ).distinctBy { it.candidate.id }
    return copy(items = reordered, degradationPath = (degradationPath + listOfNotNull(degradation)).distinct())
}

fun ContextRetrievalResult.withDegradations(reasons: List<String>): ContextRetrievalResult = copy(degradationPath = (degradationPath + reasons).distinct())

fun reciprocalRankFusion(resultLists: List<Pair<RetrievalPath, List<RetrievalCandidate>>>, k: Int = 60, limit: Int = 15): List<RankedRetrievalCandidate> {
    require(k > 0 && limit in 1..100)
    data class Accumulator(var candidate: RetrievalCandidate, var score: Double = 0.0, val paths: MutableSet<RetrievalPath> = linkedSetOf())
    val scores = linkedMapOf<String, Accumulator>()
    resultLists.forEach { (path, candidates) ->
        candidates.distinctBy { it.id }.forEachIndexed { rank, candidate ->
            val accumulator = scores.getOrPut(candidate.id) { Accumulator(candidate) }
            accumulator.score += 1.0 / (k + rank + 1)
            accumulator.paths += path
            if (candidate.summary.length > accumulator.candidate.summary.length) accumulator.candidate = candidate
        }
    }
    return scores.values
        .sortedWith(compareByDescending<Accumulator> { it.score }.thenBy { it.candidate.id })
        .take(limit)
        .map { RankedRetrievalCandidate(it.candidate, it.score, it.paths.toSet()) }
}
