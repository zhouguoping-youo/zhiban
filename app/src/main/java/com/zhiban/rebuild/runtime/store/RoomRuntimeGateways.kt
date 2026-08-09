package com.zhiban.rebuild.runtime.store

import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.runtime.spi.CommandReceipt
import com.zhiban.rebuild.runtime.spi.CommandReceiptStatus
import com.zhiban.rebuild.runtime.spi.RuntimeCommandGateway
import com.zhiban.rebuild.runtime.spi.RuntimeProjectionGateway
import com.zhiban.rebuild.runtime.spi.RuntimeProjectionStream
import com.zhiban.rebuild.runtime.spi.RuntimeUiCommand
import com.zhiban.rebuild.runtime.spi.RuntimeV2FeatureFlag
import com.zhiban.rebuild.runtime.spi.StoredProjectionSnapshot
import com.zhiban.rebuild.runtime.spi.StoredRuntimeEvent
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal class RoomRuntimeGateways(
    database: AgentDatabase,
    producerVersion: String,
    private val featureFlag: RuntimeV2FeatureFlag = RuntimeV2FeatureFlag { true },
    private val clock: () -> Long = System::currentTimeMillis,
) : RuntimeCommandGateway,
    RuntimeProjectionGateway {
    private val store = RoomRuntimeStore(database, producerVersion)

    override suspend fun accept(command: RuntimeUiCommand): CommandReceipt {
        if (!featureFlag.isEnabled()) {
            store.clearStagedInputs(clock())
            return CommandReceipt(
                CommandReceiptStatus.REJECTED,
                command.commandId,
                command.expectedRevision,
                "RUNTIME_V2_DISABLED",
            )
        }
        return store.acceptExternalCommand(command, clock())
    }

    override suspend fun snapshotAndObserve(sessionId: String, projectionName: String, afterSequenceExclusive: Long): RuntimeProjectionStream {
        val stored = store.projectionSnapshot(sessionId, projectionName)
        val events = store.observeEventsAfter(sessionId, afterSequenceExclusive)
            .map { batch -> batch.map { it.toStoredEvent() } }
            .distinctUntilChanged()
        return RuntimeProjectionStream(
            snapshot = StoredProjectionSnapshot(
                sessionId = stored.sessionId,
                projectionName = stored.projectionName,
                lastAppliedSequence = stored.lastAppliedSequence,
                currentRevision = stored.currentRevision,
                snapshotSchemaVersion = stored.snapshotSchemaVersion,
                snapshotProducerVersion = stored.snapshotProducerVersion,
                snapshotJson = stored.snapshotJson,
            ),
            events = events,
        )
    }

    override suspend fun assistantTurnText(sessionId: String, runId: String): String? = store.assistantTurnText(sessionId, runId)

    override suspend fun stagedCandidateContent(candidateId: String): String? = store.stagedCandidateContent(candidateId)

    private fun RuntimeEventEntity.toStoredEvent() = StoredRuntimeEvent(
        eventId = eventId,
        eventType = eventType,
        sessionId = sessionId,
        runId = runId,
        attemptId = attemptId,
        sequence = sequence,
        schemaVersion = schemaVersion,
        producerVersion = producerVersion,
        payloadJson = payloadJson,
    )
}
