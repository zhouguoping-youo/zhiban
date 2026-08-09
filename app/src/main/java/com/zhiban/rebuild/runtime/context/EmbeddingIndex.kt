package com.zhiban.rebuild.runtime.context
import androidx.room.withTransaction
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.runtime.runSuspendCatching
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.sqrt

data class VectorSearchResult(val candidates: List<RetrievalCandidate>, val degradation: String? = null)

/** Local vector persistence/search; vectors from different semantic spaces are never mixed. */
internal class EmbeddingIndex(
    private val database: AgentDatabase,
    private val gateway: EmbeddingGateway,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun backfillBatch(limit: Int = 32): Int {
        require(limit in 1..128)
        val space = gateway.activeSpace() ?: return 0
        val facts = database.factDao().missingEmbeddings(
            clock(),
            space.providerId,
            space.modelId,
            space.dimensions,
            limit,
        )
        if (facts.isEmpty()) return 0
        val vectors = gateway.embed(
            facts.map { fact ->
                EmbeddingInput(
                    text = fact.textContent,
                    sensitivity = fact.sensitivity.toSensitivity(),
                    purpose = EmbeddingPurpose.LOCAL_INDEX_CONTENT,
                    sourceKind = "fact",
                    sourceId = fact.factId,
                )
            },
            space,
        )
        require(vectors.size == facts.size) { "EMBEDDING_RESULT_COUNT_MISMATCH" }
        database.withTransaction {
            facts.zip(vectors).forEach { (fact, vector) ->
                validate(vector, space)
                database.embeddingVectorDao().upsert(
                    EmbeddingVectorEntity(
                        embeddingId = "emb-${digest("${fact.factId}|${space.providerId}|${space.modelId}").take(32)}",
                        factId = fact.factId,
                        providerId = space.providerId,
                        modelId = space.modelId,
                        dimensions = space.dimensions,
                        vectorBlob = encode(vector),
                        generatedAtEpochMs = clock(),
                        modelVersion = space.modelVersion,
                    ),
                )
            }
        }
        return facts.size
    }

    suspend fun search(query: String, limit: Int = 20): VectorSearchResult {
        require(limit in 1..100)
        val space = gateway.activeSpace() ?: return VectorSearchResult(emptyList(), "vector_skipped:not_configured")
        val missing = database.factDao().missingEmbeddingCount(
            clock(),
            space.providerId,
            space.modelId,
            space.dimensions,
        )
        if (missing > 0) return VectorSearchResult(emptyList(), "vector_skipped:rebuild_pending")
        val queryVector = gateway.embed(
            listOf(
                EmbeddingInput(
                    text = query,
                    sensitivity = Sensitivity.PERSONAL,
                    purpose = EmbeddingPurpose.USER_QUERY,
                    sourceKind = "user_query",
                    sourceId = "current",
                ),
            ),
            space,
        ).singleOrNull()
            ?: return VectorSearchResult(emptyList(), "vector_skipped:invalid_response")
        validate(queryVector, space)
        val rows = database.embeddingVectorDao().active(
            space.providerId,
            space.modelId,
            space.dimensions,
            clock(),
            MAX_SCAN,
        )
        val ranked = rows.mapNotNull { row ->
            val vector = runSuspendCatching { decode(row.vectorBlob, row.dimensions) }.getOrNull() ?: return@mapNotNull null
            row.factId to cosine(queryVector, vector)
        }.sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first }).take(limit)
        return VectorSearchResult(
            ranked.mapNotNull { (factId, _) ->
                database.factDao().find(factId)?.let { fact ->
                    RetrievalCandidate(
                        id = fact.factId,
                        sourceKind = fact.factType.lowercase(),
                        sourceRef = fact.sourceRef ?: fact.factId,
                        summary = fact.textContent,
                        entityRefs = listOfNotNull(fact.contactId),
                        timestampEpochMs = fact.updatedAtEpochMs,
                        sensitivity = fact.sensitivity.toSensitivity(),
                    )
                }
            },
        )
    }

    private fun validate(vector: FloatArray, space: EmbeddingSpace) {
        require(vector.size == space.dimensions) { "EMBEDDING_DIMENSION_MISMATCH" }
        require(vector.all { it.isFinite() }) { "EMBEDDING_NON_FINITE" }
        require(vector.any { it != 0f }) { "EMBEDDING_ZERO_VECTOR" }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size || a.isEmpty()) return -1.0
        var dot = 0.0
        var aa = 0.0
        var bb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            aa += a[i] * a[i]
            bb += b[i] * b[i]
        }
        return if (aa == 0.0 || bb == 0.0) -1.0 else dot / (sqrt(aa) * sqrt(bb))
    }

    private fun encode(vector: FloatArray): ByteArray = ByteBuffer.allocate(vector.size * Float.SIZE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN).also { buffer -> vector.forEach(buffer::putFloat) }.array()

    private fun decode(bytes: ByteArray, dimensions: Int): FloatArray {
        require(bytes.size == dimensions * Float.SIZE_BYTES) { "EMBEDDING_BLOB_INVALID" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(dimensions) { buffer.float }
    }

    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun String.toSensitivity(): Sensitivity = runCatching { Sensitivity.valueOf(this) }.getOrDefault(Sensitivity.SENSITIVE)

    companion object {
        private const val MAX_SCAN = 2_000
    }
}
