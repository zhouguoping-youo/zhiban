package com.zhiban.rebuild.runtime.context
import android.util.Log
import androidx.room.withTransaction
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.runtime.runSuspendCatching
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        // Isolate each row so a fact the export gate blocks (a direct identifier in free
        // text, e.g. a phone number inside a schedule note) skips only itself instead of
        // aborting the whole batch — that all-or-nothing failure was the backfill deadlock.
        val indexed = facts.mapNotNull { fact ->
            val input = EmbeddingInput(
                text = fact.textContent,
                sensitivity = fact.sensitivity.toSensitivity(),
                purpose = EmbeddingPurpose.LOCAL_INDEX_CONTENT,
                sourceKind = "fact",
                sourceId = fact.factId,
            )
            runSuspendCatching { gateway.embed(listOf(input), space).single().also { validate(it, space) } }
                .onFailure { failure ->
                    // Log only the exception class, never the message: a blocked row may carry a
                    // direct identifier (e.g. a phone in free text) that must not reach logcat.
                    Log.w(LOG_TAG, "embedding backfill row skipped (${failure.javaClass.simpleName})")
                }
                .getOrNull()
                ?.let { vector -> fact to vector }
        }
        if (indexed.isEmpty()) return 0
        database.withTransaction {
            indexed.forEach { (fact, vector) ->
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
        return indexed.size
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
        val rows = database.embeddingVectorDao().active(
            space.providerId,
            space.modelId,
            space.dimensions,
            clock(),
            MAX_SCAN,
        )
        if (rows.isEmpty() && missing > 0) {
            return VectorSearchResult(emptyList(), "vector_skipped:rebuild_pending")
        }
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
        val ranked = rows.mapNotNull { row ->
            val vector = runSuspendCatching { decodeCached(row) }.getOrNull() ?: return@mapNotNull null
            row.factId to cosine(queryVector, vector)
        }.sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first }).take(limit)
        val factsById = database.factDao().findByIds(ranked.map { it.first }).associateBy(FactEntity::factId)
        return VectorSearchResult(
            ranked.mapNotNull { (factId, _) ->
                factsById[factId]?.let { fact ->
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
            degradation = if (missing > 0) "vector_partial:rebuild_pending" else null,
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

    // Legacy facts carry the free-text label "NORMAL" (ordinary personal data), which is not
    // a Sensitivity enum value. Fail-closing it to SENSITIVE tripped the export gate and stalled
    // the whole backfill; NORMAL maps to PERSONAL (passes the gate, still redacted on export).
    private fun String.toSensitivity(): Sensitivity = when (uppercase()) {
        "PUBLIC" -> Sensitivity.PUBLIC
        "PERSONAL" -> Sensitivity.PERSONAL
        "NORMAL" -> Sensitivity.PERSONAL
        "SENSITIVE" -> Sensitivity.SENSITIVE
        else -> Sensitivity.SENSITIVE
    }

    /**
     * 进程级解码缓存(P2:过去每次检索对最多 2000 个向量全量解码,1024 维约 8MB 瞬时分配;
     * EmbeddingIndex 每次检索新建实例,缓存必须放伴生)。key=embeddingId@generatedAtEpochMs,
     * 向量重新生成即失效;LRU 上限约 8MB。
     */
    private suspend fun decodeCached(row: EmbeddingVectorEntity): FloatArray {
        val key = "${row.embeddingId}@${row.generatedAtEpochMs}"
        decodeCacheMutex.withLock {
            decodeCache[key]?.let { return it.vector }
        }
        val decoded = decode(row.vectorBlob, row.dimensions)
        decodeCacheMutex.withLock { decodeCache[key] = CachedVector(decoded) }
        return decoded
    }

    companion object {
        private const val MAX_SCAN = 2_000
        private const val DECODE_CACHE_MAX_ENTRIES = 2_048
        private val decodeCache = object : LinkedHashMap<String, CachedVector>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedVector>?): Boolean = size > DECODE_CACHE_MAX_ENTRIES
        }
        private val decodeCacheMutex = Mutex()
        private data class CachedVector(val vector: FloatArray)
        private const val LOG_TAG = "EmbeddingIndex"
    }
}
