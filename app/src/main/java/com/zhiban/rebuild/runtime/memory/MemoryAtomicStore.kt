package com.zhiban.rebuild.runtime.memory

import androidx.room.withTransaction
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.facts.FactEntity
import com.zhiban.rebuild.data.facts.FactIndex
import com.zhiban.rebuild.data.memory.MemoryCommitReceiptEntity
import com.zhiban.rebuild.data.memory.MemoryCurrentVersionEntity
import com.zhiban.rebuild.data.memory.MemoryDeletionOutboxEntity
import com.zhiban.rebuild.data.memory.MemoryEventEntity
import com.zhiban.rebuild.data.memory.MemoryEvidenceEntity
import com.zhiban.rebuild.data.memory.MemoryFtsEntity
import com.zhiban.rebuild.data.memory.MemoryIndexOutboxEntity
import com.zhiban.rebuild.data.memory.MemoryNamespaceEntity
import com.zhiban.rebuild.data.memory.MemoryRecordEntity
import com.zhiban.rebuild.data.memory.MemoryTombstoneEntity
import com.zhiban.rebuild.data.memory.StagedMemoryCandidateEntity
import java.security.MessageDigest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class MemoryCommitRequest(
    val namespaceId: String,
    val candidateId: String,
    val approvalRef: String,
    val expectedCandidateRevision: Long,
    val memoryId: String,
    val logicalMemoryId: String,
    val memoryType: String,
    val subjectKey: String,
    val predicateKey: String,
    val canonicalText: String,
    val canonicalDigest: String,
    val sourceSetDigest: String,
)

data class MemoryCommitResult(val created: Boolean, val memoryId: String, val recordVersion: Long)
data class MemoryRecallSnapshot(val generation: Long, val records: List<MemoryRecordEntity>)
data class MemoryDeletionResult(val generation: Long, val created: Boolean)
data class MemoryUpsertRequest(
    val namespaceId: String,
    val logicalMemoryId: String,
    val memoryType: String,
    val subjectKey: String,
    val predicateKey: String,
    val canonicalText: String,
    val sensitivity: String,
    val confidence: Double,
    val sourceRef: String,
)

data class MemoryUpsertResult(
    val changed: Boolean,
    val created: Boolean,
    val memoryId: String,
    val recordVersion: Long,
    val beforeDigest: String?,
    val afterDigest: String,
    val inversePayloadJson: String,
)

/** Atomic Room boundary for durable memory commits and deletion read barriers. */
internal class MemoryAtomicStore(private val database: AgentDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao get() = database.memoryPersistenceDao()

    suspend fun ensureNamespace(namespace: MemoryNamespaceEntity) {
        dao.insertNamespace(namespace)
        val existing = requireNotNull(dao.namespace(namespace.namespaceId))
        require(
            existing.ownerUserId == namespace.ownerUserId && existing.profileId == namespace.profileId &&
                existing.scopeType == namespace.scopeType && existing.scopeId == namespace.scopeId,
        ) {
            "NAMESPACE_IDENTITY_CONFLICT"
        }
    }

    private class CommitPreparation(
        val candidate: com.zhiban.rebuild.data.memory.StagedMemoryCandidateEntity,
        val sources: List<String>,
        val canonicalText: String,
        val idempotentResult: MemoryCommitResult?,
    )

    suspend fun commit(request: MemoryCommitRequest): MemoryCommitResult = database.withTransaction {
        val preparation = validateAndCheckIdempotency(request)
        if (preparation.idempotentResult != null) return@withTransaction preparation.idempotentResult

        val now = clock()
        val version = 1L
        persistMemoryRecord(request, preparation, now, version)
        emitCommitArtifacts(request, preparation, now, version)
        MemoryCommitResult(true, request.memoryId, version)
    }

    private suspend fun validateAndCheckIdempotency(request: MemoryCommitRequest): CommitPreparation {
        require(request.approvalRef.isNotBlank() && request.canonicalDigest.isNotBlank())
        val namespace = requireNotNull(dao.namespace(request.namespaceId)) { "NAMESPACE_NOT_FOUND" }
        val candidate =
            requireNotNull(database.stagedMemoryCandidateDao().find(request.candidateId)) { "CANDIDATE_NOT_FOUND" }
        require(candidate.approvalRef == request.approvalRef) { "APPROVAL_CLAIM_MISMATCH" }
        require(namespace.scopeType == candidate.scope && namespace.scopeId == candidate.scopeId.orEmpty()) {
            "CANDIDATE_NAMESPACE_SCOPE_MISMATCH"
        }
        val sources = parseSources(
            candidate.sourceIdsJson,
        ).map(String::trim).filter(String::isNotEmpty).distinct().sorted()
        require(request.sourceSetDigest == digest(sources.joinToString("\n"))) { "SOURCE_SET_DIGEST_MISMATCH" }
        require(
            request.canonicalText == canonicalize(request.canonicalText) &&
                request.canonicalDigest == digest(request.canonicalText),
        ) { "CANONICAL_PAYLOAD_MISMATCH" }
        dao.receipt(request.namespaceId, request.candidateId)?.let { existing ->
            require(
                candidate.state == "CONSUMED" && existing.approvalRef == request.approvalRef &&
                    existing.canonicalDigest == request.canonicalDigest,
            ) { "MEMORY_COMMIT_CONFLICT" }
            return CommitPreparation(
                candidate,
                sources,
                "",
                MemoryCommitResult(false, existing.memoryId, existing.recordVersion),
            )
        }
        require(
            candidate.state == "APPROVED" && candidate.revision == request.expectedCandidateRevision &&
                candidate.content != null,
        ) {
            "CANDIDATE_NOT_APPROVED"
        }
        val canonicalText = canonicalize(candidate.content)
        require(request.canonicalText == canonicalText && request.canonicalDigest == digest(canonicalText)) {
            "CANDIDATE_DIGEST_MISMATCH"
        }
        require(dao.current(request.namespaceId, request.logicalMemoryId) == null) { "CURRENT_MEMORY_EXISTS" }
        return CommitPreparation(candidate, sources, canonicalText, null)
    }

    private suspend fun persistMemoryRecord(request: MemoryCommitRequest, preparation: CommitPreparation, now: Long, version: Long) {
        val candidate = preparation.candidate
        val canonicalText = preparation.canonicalText
        val sources = preparation.sources
        dao.insertRecord(
            MemoryRecordEntity(
                request.namespaceId, request.memoryId, version, request.logicalMemoryId, request.memoryType,
                request.subjectKey, request.predicateKey, canonicalText, canonicalText,
                request.canonicalDigest, candidate.sensitivity, 1.0, 1.0, "ACTIVE", null, null,
                now, now, null, now, null, 1, request.sourceSetDigest,
            ),
        )
        dao.insertCurrent(
            MemoryCurrentVersionEntity(request.namespaceId, request.logicalMemoryId, version, request.memoryId, 0),
        )
        dao.insertEvidence(
            sources.mapIndexed { index, source ->
                MemoryEvidenceEntity(
                    request.namespaceId, request.memoryId, version, "evidence-${request.candidateId}-$index",
                    "SOURCE", source, digest(source), now, candidate.contentDigest, "USER", candidate.sensitivity,
                )
            },
        )
    }

    private suspend fun emitCommitArtifacts(request: MemoryCommitRequest, preparation: CommitPreparation, now: Long, version: Long) {
        val candidate = preparation.candidate
        val canonicalText = preparation.canonicalText
        dao.insertReceipt(
            MemoryCommitReceiptEntity(
                request.namespaceId,
                request.candidateId,
                request.approvalRef,
                request.canonicalDigest,
                request.memoryId,
                version,
                now,
            ),
        )
        dao.insertEvent(
            MemoryEventEntity(
                "event-memory-${request.candidateId}-committed",
                request.namespaceId,
                request.candidateId,
                request.memoryId,
                version,
                "MemoryCommitted",
                request.canonicalDigest,
                now,
            ),
        )
        val jobs = listOf("FTS", "EMBEDDING").map { index ->
            MemoryIndexOutboxEntity(
                "index-${request.namespaceId}-${request.memoryId}-$version-$index",
                request.namespaceId,
                request.memoryId,
                version,
                index,
                request.canonicalDigest,
                "PENDING",
                now,
            )
        }
        check(dao.insertIndexJobs(jobs).all { it != -1L })
        // FTS is a local deterministic index and is committed atomically. The outbox remains the
        // source for optional remote embedding indexes, so offline mode never blocks recall.
        dao.insertFts(MemoryFtsEntity(request.namespaceId, request.memoryId, version, canonicalText))
        FactIndex(database).upsert(
            FactEntity(
                factId = "memory:${request.memoryId}", factType = "AGENT_MEMORY", textContent = canonicalText,
                structuredDataJson = null, sourceType = "USER_CONFIRMED_MEMORY", sourceRef = request.namespaceId,
                contactId = null, skillId = null, confidence = 1.0, sensitivity = candidate.sensitivity,
                status = "ACTIVE", ttlDays = 0, expiresAtEpochMs = null,
                createdAtEpochMs = now, updatedAtEpochMs = now,
            ),
        )
        check(
            database.stagedMemoryCandidateDao().consume(
                request.candidateId,
                request.approvalRef,
                request.expectedCandidateRevision,
                now,
            ) == 1,
        ) { "CANDIDATE_CONSUME_CONFLICT" }
    }

    suspend fun recall(namespaceId: String): MemoryRecallSnapshot = database.withTransaction {
        val namespace = requireNotNull(dao.namespace(namespaceId))
        val now = clock()
        MemoryRecallSnapshot(namespace.invalidationGeneration, dao.recall(namespaceId, now))
    }

    suspend fun upsertReversible(request: MemoryUpsertRequest): MemoryUpsertResult = database.withTransaction {
        validateUpsert(request)
        requireNotNull(dao.namespace(request.namespaceId)) { "NAMESPACE_NOT_FOUND" }
        val canonical = canonicalize(request.canonicalText)
        val current = dao.current(request.namespaceId, request.logicalMemoryId)
        val previous = current?.let { dao.record(request.namespaceId, it.memoryId, it.recordVersion) }
        if (previous?.canonicalText == canonical) {
            return@withTransaction unchangedUpsert(previous)
        }
        val now = clock()
        val memoryId = current?.memoryId
            ?: "memory-auto-${digest("${request.namespaceId}|${request.logicalMemoryId}").take(24)}"
        val version = (current?.recordVersion ?: 0) + 1
        val record = autoRecord(request, canonical, memoryId, version, now)
        dao.insertRecord(record)
        if (current == null) {
            dao.insertCurrent(MemoryCurrentVersionEntity(request.namespaceId, request.logicalMemoryId, version, memoryId, 0))
        } else {
            check(dao.supersedeRecord(request.namespaceId, memoryId, current.recordVersion, now) == 1)
            check(dao.moveCurrentVersion(request.namespaceId, request.logicalMemoryId, memoryId, current.recordVersion, version) == 1)
            dao.deleteFts(request.namespaceId, memoryId, current.recordVersion)
        }
        indexAutoRecord(request, record, now)
        MemoryUpsertResult(
            changed = true,
            created = current == null,
            memoryId = memoryId,
            recordVersion = version,
            beforeDigest = previous?.canonicalDigest,
            afterDigest = record.canonicalDigest,
            inversePayloadJson = buildJsonObject {
                put("namespaceId", request.namespaceId)
                put("logicalMemoryId", request.logicalMemoryId)
                put("memoryId", memoryId)
                put("expectedVersion", version)
                previous?.let { put("previousVersion", it.recordVersion) }
            }.toString(),
        )
    }

    suspend fun undoReversible(
        namespaceId: String,
        logicalMemoryId: String,
        memoryId: String,
        expectedVersion: Long,
        expectedDigest: String,
        previousVersion: Long?,
    ): Boolean = database.withTransaction {
        val current = dao.current(namespaceId, logicalMemoryId) ?: return@withTransaction false
        if (current.memoryId != memoryId || current.recordVersion != expectedVersion) return@withTransaction false
        val currentRecord = dao.record(namespaceId, memoryId, expectedVersion) ?: return@withTransaction false
        if (currentRecord.canonicalDigest != expectedDigest) return@withTransaction false
        dao.deleteFts(namespaceId, memoryId, expectedVersion)
        if (previousVersion == null) {
            if (dao.deleteCurrent(namespaceId, logicalMemoryId, memoryId, expectedVersion) != 1) return@withTransaction false
            FactIndex(database).delete("memory:$memoryId")
            return@withTransaction dao.deleteRecord(namespaceId, memoryId, expectedVersion) == 1
        }
        val previous = dao.record(namespaceId, memoryId, previousVersion) ?: return@withTransaction false
        check(dao.moveCurrentVersion(namespaceId, logicalMemoryId, memoryId, expectedVersion, previousVersion) == 1)
        check(dao.reactivateRecord(namespaceId, memoryId, previousVersion) == 1)
        dao.insertFts(MemoryFtsEntity(namespaceId, memoryId, previousVersion, previous.canonicalText))
        putMemoryFact(previous, "AGENT_AUTO_MEMORY_UNDO")
        dao.deleteRecord(namespaceId, memoryId, expectedVersion) == 1
    }

    private fun validateUpsert(request: MemoryUpsertRequest) {
        require(request.logicalMemoryId.length in 1..128)
        require(request.memoryType in setOf("PREFERENCE", "FACT"))
        require(request.subjectKey.length in 1..128 && request.predicateKey.length in 1..128)
        require(request.canonicalText.trim().length in 1..500)
        require(request.sensitivity in setOf("PUBLIC", "PERSONAL"))
        require(request.confidence in 0.0..1.0 && request.sourceRef.length in 1..500)
    }

    private fun unchangedUpsert(record: MemoryRecordEntity) = MemoryUpsertResult(
        changed = false,
        created = false,
        memoryId = record.memoryId,
        recordVersion = record.recordVersion,
        beforeDigest = record.canonicalDigest,
        afterDigest = record.canonicalDigest,
        inversePayloadJson = "{}",
    )

    private fun autoRecord(request: MemoryUpsertRequest, canonical: String, memoryId: String, version: Long, now: Long) = MemoryRecordEntity(
        request.namespaceId, memoryId, version, request.logicalMemoryId, request.memoryType,
        request.subjectKey, request.predicateKey, canonical, canonical, digest(canonical),
        request.sensitivity, request.confidence, 1.0, "ACTIVE", null, null, now, now,
        null, now, null, 1, digest(request.sourceRef),
    )

    private suspend fun indexAutoRecord(request: MemoryUpsertRequest, record: MemoryRecordEntity, now: Long) {
        dao.insertEvidence(
            listOf(
                MemoryEvidenceEntity(
                    request.namespaceId, record.memoryId, record.recordVersion,
                    "evidence-auto-${digest(request.sourceRef).take(24)}", "SOURCE", request.sourceRef,
                    digest(request.sourceRef), now, record.canonicalDigest, "USER", request.sensitivity,
                ),
            ),
        )
        val jobs = listOf("FTS", "EMBEDDING").map { type ->
            MemoryIndexOutboxEntity(
                "index-${request.namespaceId}-${record.memoryId}-${record.recordVersion}-$type",
                request.namespaceId,
                record.memoryId,
                record.recordVersion,
                type,
                record.canonicalDigest,
                "PENDING",
                now,
            )
        }
        check(dao.insertIndexJobs(jobs).all { it != -1L })
        dao.insertFts(MemoryFtsEntity(request.namespaceId, record.memoryId, record.recordVersion, record.canonicalText))
        putMemoryFact(record, "AGENT_AUTO_MEMORY")
    }

    private suspend fun putMemoryFact(record: MemoryRecordEntity, sourceType: String) {
        FactIndex(database).upsert(
            FactEntity(
                factId = "memory:${record.memoryId}", factType = "AGENT_MEMORY",
                textContent = record.canonicalText, structuredDataJson = null,
                sourceType = sourceType, sourceRef = record.namespaceId, contactId = null,
                skillId = null, confidence = record.confidence, sensitivity = record.sensitivity,
                status = "ACTIVE", ttlDays = 0, expiresAtEpochMs = null,
                createdAtEpochMs = record.createdAtEpochMs, updatedAtEpochMs = clock(),
            ),
        )
    }

    /** Keeps old memory auditable while removing it from normal recall after 180 days. */
    suspend fun applyDormancyPolicy(): Int = dao.markDormant(clock() - DORMANCY_MS)

    suspend fun delete(namespaceId: String, logicalMemoryId: String, commandDigest: String): MemoryDeletionResult = database.withTransaction {
        require(commandDigest.isNotBlank())
        dao.tombstone(namespaceId, logicalMemoryId)?.let { existing ->
            require(existing.deleteCommandDigest == commandDigest) { "DELETE_COMMAND_CONFLICT" }
            return@withTransaction MemoryDeletionResult(existing.deletionRevision, false)
        }
        val namespace = requireNotNull(dao.namespace(namespaceId))
        val current = requireNotNull(dao.current(namespaceId, logicalMemoryId)) { "CURRENT_MEMORY_NOT_FOUND" }
        requireNotNull(dao.record(namespaceId, current.memoryId, current.recordVersion))
        val generation = namespace.invalidationGeneration + 1
        check(dao.bumpGeneration(namespaceId, namespace.invalidationGeneration) == 1) { "GENERATION_CONFLICT" }
        val now = clock()
        dao.putTombstone(
            MemoryTombstoneEntity(
                namespaceId,
                logicalMemoryId,
                current.memoryId,
                current.recordVersion,
                commandDigest,
                generation,
                "PENDING",
                now,
            ),
        )
        dao.deleteFts(namespaceId, current.memoryId, current.recordVersion)
        FactIndex(database).delete("memory:${current.memoryId}")
        val job = MemoryDeletionOutboxEntity(
            "delete-$namespaceId-$logicalMemoryId-$generation",
            namespaceId,
            logicalMemoryId,
            generation,
            encodedTargets(DELETION_TARGETS),
            "PENDING",
            now,
        )
        check(dao.insertDeletionJobs(listOf(job)).single() != -1L)
        check(dao.deleteRecord(namespaceId, current.memoryId, current.recordVersion) == 1)
        MemoryDeletionResult(generation, true)
    }

    suspend fun ackDeletion(namespaceId: String, logicalMemoryId: String, generation: Long, targetIndex: String): Boolean = database.withTransaction {
        val job = dao.deletionJob(namespaceId, logicalMemoryId, generation) ?: return@withTransaction false
        if (job.status != "PENDING") return@withTransaction false
        val remaining = parseTargets(job.targetIndex).toMutableSet()
        if (!remaining.remove(targetIndex)) return@withTransaction false
        check(
            dao.updateDeletionProgress(
                job.jobId,
                job.targetIndex,
                encodedTargets(remaining),
                if (remaining.isEmpty()) "ACKED" else "PENDING",
            ) == 1,
        )
        if (remaining.isEmpty()) {
            check(dao.finishBarrier(namespaceId, logicalMemoryId, generation) == 1)
        }
        true
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun canonicalize(value: String): String = value.trim().replace(Regex("\\s+"), " ")

    private fun parseSources(json: String): List<String> = kotlinx.serialization.json.Json.decodeFromString(json)

    private fun encodedTargets(targets: Collection<String>) = targets.sorted().joinToString(",")
    private fun parseTargets(value: String): Set<String> = value.takeIf(String::isNotBlank)?.split(',')?.toSet().orEmpty()

    private companion object {
        val DELETION_TARGETS = setOf("FTS", "CACHE", "EMBEDDING")
        const val DORMANCY_MS = 180L * 24 * 60 * 60 * 1_000
    }
}
