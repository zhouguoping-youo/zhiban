package com.zhiban.agent.memory

/** Stable module boundary used by Runtime, tools and settings. No Room or Android type crosses it. */
interface MemoryGate {
    suspend fun ensureNamespace(namespace: MemoryNamespace)
    suspend fun commit(request: MemoryCommit): MemoryCommitReceipt
    suspend fun recall(namespaceId: String): MemorySnapshot
    suspend fun search(request: MemoryQuery): MemoryQueryResult
    suspend fun delete(namespaceId: String, logicalMemoryId: String, commandDigest: String): MemoryDeletionReceipt
    suspend fun applyDormancyPolicy(): Int
}

data class MemoryNamespace(
    val namespaceId: String,
    val ownerUserId: String,
    val profileId: String,
    val scopeType: String,
    val scopeId: String,
    val createdAtEpochMs: Long,
)

data class MemoryCommit(
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

data class MemoryCommitReceipt(val created: Boolean, val memoryId: String, val recordVersion: Long)
data class MemoryDeletionReceipt(val generation: Long, val created: Boolean)
data class MemoryRecord(
    val memoryId: String,
    val logicalMemoryId: String,
    val memoryType: String,
    val canonicalText: String,
    val sensitivity: String,
    val updatedAtEpochMs: Long,
)
data class MemorySnapshot(val generation: Long, val records: List<MemoryRecord>)
data class MemoryQuery(val namespaceId: String, val ownerUserId: String, val profileId: String, val query: String, val limit: Int, val tokenBudget: Int)
data class MemoryQueryItem(
    val memoryId: String,
    val logicalMemoryId: String,
    val canonicalText: String,
    val sensitivity: String,
    val sourceRefs: List<String>,
    val score: Double,
)
data class MemoryQueryResult(
    val items: List<MemoryQueryItem>,
    val estimatedTokens: Int,
    val semanticSearchDegraded: Boolean,
    val degradationReasons: List<String> = emptyList(),
)
