package com.zhiban.rebuild.runtime.spi

import kotlinx.coroutines.flow.Flow

interface RuntimeCommandGateway {
    suspend fun accept(command: RuntimeUiCommand): CommandReceipt
}

data class StoredRuntimeEvent(
    val eventId: String,
    val eventType: String,
    val sessionId: String,
    val runId: String,
    val attemptId: String?,
    val sequence: Long,
    val schemaVersion: Int,
    val producerVersion: String,
    val payloadJson: String,
)

data class StoredProjectionSnapshot(
    val sessionId: String,
    val projectionName: String,
    val lastAppliedSequence: Long,
    val currentRevision: Long,
    val snapshotSchemaVersion: Int,
    val snapshotProducerVersion: String,
    val snapshotJson: String?,
)

data class RuntimeProjectionStream(val snapshot: StoredProjectionSnapshot, val events: Flow<List<StoredRuntimeEvent>>)

interface RuntimeProjectionGateway {
    suspend fun snapshotAndObserve(sessionId: String, projectionName: String, afterSequenceExclusive: Long): RuntimeProjectionStream

    /**
     * The persisted assistant turn for [runId], or null if it was never saved. The compact run-status
     * snapshot never carries the streamed body, so a reconnect whose watermark already passed the deltas
     * would otherwise replay to an empty bubble; this backfills the durable copy instead.
     */
    suspend fun assistantTurnText(sessionId: String, runId: String): String? = null

    /**
     * The staged content behind an opaque [candidateId] (e.g. the memory body a confirmation card is
     * about to commit), resolved in-memory at display time. The content is never written to the durable
     * event journal; it lives only in the short-lived staging area until the decision clears it.
     */
    suspend fun stagedCandidateContent(candidateId: String): String? = null
}
