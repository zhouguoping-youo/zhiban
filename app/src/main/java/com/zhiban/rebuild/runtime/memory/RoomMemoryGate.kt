package com.zhiban.rebuild.runtime.memory

import com.zhiban.agent.memory.*
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.memory.MemoryNamespaceEntity

/** Room adapter. Runtime depends on the :agent:memory contract, while persistence stays replaceable. */
internal class RoomMemoryGate(database: AgentDatabase, private val clock: () -> Long = System::currentTimeMillis) : MemoryGate {
    private val atomic = MemoryAtomicStore(database, clock)
    private val search = MemorySearch(database, clock)

    override suspend fun ensureNamespace(namespace: MemoryNamespace) = atomic.ensureNamespace(
        MemoryNamespaceEntity(
            namespace.namespaceId, namespace.ownerUserId, namespace.profileId, namespace.scopeType,
            namespace.scopeId, "ACTIVE", 0, 0, namespace.createdAtEpochMs,
        ),
    )

    override suspend fun commit(request: MemoryCommit): MemoryCommitReceipt {
        val result = atomic.commit(
            MemoryCommitRequest(
                request.namespaceId, request.candidateId, request.approvalRef,
                request.expectedCandidateRevision, request.memoryId, request.logicalMemoryId, request.memoryType,
                request.subjectKey, request.predicateKey, request.canonicalText, request.canonicalDigest, request.sourceSetDigest,
            ),
        )
        return MemoryCommitReceipt(result.created, result.memoryId, result.recordVersion)
    }

    override suspend fun recall(namespaceId: String): MemorySnapshot {
        val snapshot = atomic.recall(namespaceId)
        return MemorySnapshot(
            snapshot.generation,
            snapshot.records.map {
                MemoryRecord(
                    it.memoryId,
                    it.logicalMemoryId,
                    it.memoryType,
                    it.canonicalText,
                    it.sensitivity,
                    it.txFromEpochMs,
                )
            },
        )
    }

    override suspend fun search(request: MemoryQuery): MemoryQueryResult {
        val result = search.search(
            MemorySearchQuery(
                request.namespaceId,
                request.ownerUserId,
                request.profileId,
                request.query,
                request.limit,
                request.tokenBudget,
            ),
        )
        return MemoryQueryResult(
            result.items.map {
                MemoryQueryItem(
                    it.memoryId,
                    it.logicalMemoryId,
                    it.canonicalText,
                    it.sensitivity,
                    it.sourceRefs,
                    it.score,
                )
            },
            result.estimatedTokens,
            result.semanticSearchDegraded,
            result.degradationReasons,
        )
    }

    override suspend fun delete(namespaceId: String, logicalMemoryId: String, commandDigest: String): MemoryDeletionReceipt {
        val result = atomic.delete(namespaceId, logicalMemoryId, commandDigest)
        return MemoryDeletionReceipt(result.generation, result.created)
    }

    override suspend fun applyDormancyPolicy(): Int = atomic.applyDormancyPolicy()
}
