package com.zhiban.rebuild.runtime.store

import androidx.room.withTransaction
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.agent.PLAN_STATUS_ACTIVE
import com.zhiban.rebuild.data.agent.PLAN_STATUS_TERMINAL
import com.zhiban.rebuild.data.agent.PlanDefinitionEntity
import com.zhiban.rebuild.data.agent.PlanEdgeEntity
import com.zhiban.rebuild.data.agent.PlanNodeEntity
import com.zhiban.rebuild.data.agent.PlanRunEntity
import com.zhiban.rebuild.data.agent.PlanVersionEntity
import com.zhiban.rebuild.data.agent.ScheduleEntity
import com.zhiban.rebuild.data.agent.ToolAuditEntity
import com.zhiban.rebuild.data.contact.StagedContactCandidateEntity
import com.zhiban.rebuild.runtime.context.FactEntity
import com.zhiban.rebuild.runtime.context.FactIndex
import com.zhiban.rebuild.runtime.context.StagedMemoryCandidateEntity
import com.zhiban.rebuild.runtime.governance.ChangeLogEntity
import com.zhiban.rebuild.runtime.governance.ChangeUndoCoordinator
import com.zhiban.rebuild.runtime.governance.ContactCreateCandidateCall
import com.zhiban.rebuild.runtime.governance.RelationshipCandidateCall
import com.zhiban.rebuild.runtime.kernel.RuntimeSignal
import com.zhiban.rebuild.runtime.kernel.RuntimeStateMachine
import com.zhiban.rebuild.runtime.memory.RoomMemoryGate
import com.zhiban.rebuild.runtime.spi.CommandReceipt
import com.zhiban.rebuild.runtime.spi.CommandReceiptStatus
import com.zhiban.rebuild.runtime.spi.RUNTIME_SCHEMA_VERSION
import com.zhiban.rebuild.runtime.spi.RuntimeAction
import com.zhiban.rebuild.runtime.spi.RuntimeCommandStatus
import com.zhiban.rebuild.runtime.spi.RuntimeRunStatus
import com.zhiban.rebuild.runtime.spi.RuntimeUiCommand
import com.zhiban.rebuild.runtime.tool.CalendarMutationToolBinding
import com.zhiban.rebuild.runtime.tool.MemoryRememberToolCall
import com.zhiban.rebuild.runtime.tool.ScheduleCreateToolCall
import com.zhiban.rebuild.runtime.tool.sha256
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class RoomCommandStore(
    private val database: AgentDatabase,
    private val producerVersion: String,
    private val allocateSequence: suspend (String, Long) -> Long,
    private val currentRevision: suspend (String) -> Long,
    private val appendEventInTransaction: suspend (RuntimeEventDraft, Long) -> RuntimeEventEntity,
) {
    suspend fun consumeRunInput(runId: String): Boolean = database.withTransaction {
        database.runtimeRunInputDao().deleteByRunId(runId) == 1
    }

    suspend fun clearStagedInputs(nowEpochMs: Long) = database.withTransaction {
        database.runtimeInputStagingDao().deleteExpired(Long.MAX_VALUE)
        database.runtimeRunInputDao().deleteExpired(nowEpochMs)
    }

    suspend fun acceptExternalCommand(command: RuntimeUiCommand, nowEpochMs: Long): CommandReceipt = database.withTransaction {
        val resolvedRunId = when (command) {
            is RuntimeUiCommand.Start -> command.runId ?: "run-${command.commandId}"
            else -> requireNotNull(command.runId)
        }
        val commandType = commandType(command)
        val payloadJson = canonicalPayload(command, resolvedRunId)
        val existing = database.runtimeCommandInboxDao().find(command.commandId)
        if (existing != null) {
            val currentRevision = currentRevision(existing.sessionId)
            val sameInput = existing.commandType == commandType &&
                existing.sessionId == command.sessionId && existing.runId == resolvedRunId &&
                existing.payloadJson == payloadJson
            return@withTransaction CommandReceipt(
                if (sameInput) CommandReceiptStatus.DUPLICATE else CommandReceiptStatus.CONFLICT,
                command.commandId,
                currentRevision,
                if (sameInput) null else "COMMAND_ID_CONFLICT",
            )
        }

        val session = database.runtimeSessionDao().find(command.sessionId)
        val revision = session?.nextSequence?.minus(1) ?: 0
        // Cancellation is an idempotent safety control. Provider deltas can advance the
        // revision between the user's tap and this transaction; rejecting that stale
        // cancellation would leave the provider running and make the stop button flaky.
        // The run/session binding and state machine are still validated below.
        val isCancellation = command is RuntimeUiCommand.RunAction &&
            command.action == RuntimeAction.CANCEL
        if (revision != command.expectedRevision && !isCancellation) {
            return@withTransaction CommandReceipt(
                CommandReceiptStatus.CONFLICT,
                command.commandId,
                revision,
                "REVISION_CONFLICT",
            )
        }

        if (command is RuntimeUiCommand.Start) {
            if (session == null) {
                database.runtimeSessionDao().insert(
                    RuntimeSessionEntity(sessionId = command.sessionId, updatedAtEpochMs = nowEpochMs),
                )
            }
            if (database.runtimeRunDao().insert(
                    RuntimeRunEntity(
                        runId = resolvedRunId,
                        sessionId = command.sessionId,
                        schemaVersion = RUNTIME_SCHEMA_VERSION,
                        status = RuntimeRunStatus.RECEIVED.name,
                        budgetJson = "{}",
                        createdAtEpochMs = nowEpochMs,
                        updatedAtEpochMs = nowEpochMs,
                    ),
                ) == -1L
            ) {
                return@withTransaction CommandReceipt(
                    CommandReceiptStatus.CONFLICT,
                    command.commandId,
                    revision,
                    "RUN_ALREADY_EXISTS",
                )
            }
        } else {
            val run = database.runtimeRunDao().find(resolvedRunId)
            if (run?.sessionId != command.sessionId) {
                return@withTransaction CommandReceipt(
                    CommandReceiptStatus.REJECTED,
                    command.commandId,
                    revision,
                    "RUN_NOT_FOUND",
                )
            }
        }

        persistAcceptedCommand(command, resolvedRunId, commandType, payloadJson, nowEpochMs)
    }

    private suspend fun persistAcceptedCommand(
        command: RuntimeUiCommand,
        resolvedRunId: String,
        commandType: String,
        payloadJson: String,
        nowEpochMs: Long,
    ): CommandReceipt {
        check(
            database.runtimeCommandInboxDao().insert(
                RuntimeCommandInboxEntity(
                    commandId = command.commandId,
                    schemaVersion = RUNTIME_SCHEMA_VERSION,
                    commandType = commandType,
                    sessionId = command.sessionId,
                    runId = resolvedRunId,
                    correlationId = resolvedRunId,
                    payloadJson = payloadJson,
                    status = RuntimeCommandStatus.PENDING.name,
                    createdAtEpochMs = nowEpochMs,
                    updatedAtEpochMs = nowEpochMs,
                ),
            ) != -1L,
        )
        val event = appendEventInTransaction(
            RuntimeEventDraft(
                eventId = "event-${command.commandId}",
                eventType = commandEventType(command),
                sessionId = command.sessionId,
                runId = resolvedRunId,
                attemptId = null,
                causationId = command.commandId,
                correlationId = resolvedRunId,
                payloadJson = payloadJson,
                createdAtEpochMs = nowEpochMs,
            ),
            0,
        )
        check(
            database.runtimeCommandInboxDao().persistReceipt(
                command.commandId,
                "${CommandReceiptStatus.ACCEPTED.name}|${command.commandId}|${event.sequence}",
                nowEpochMs,
            ) == 1,
        )
        return CommandReceipt(CommandReceiptStatus.ACCEPTED, command.commandId, event.sequence)
    }

    suspend fun projectionSnapshot(sessionId: String, projectionName: String): ProjectionSnapshotRecord = database.withTransaction {
        val projection = database.runtimeProjectionDao().find(projectionName, sessionId)
        val envelope = projection?.snapshotJson?.let(RuntimeProjectionEnvelopeCodec::decode)
        ProjectionSnapshotRecord(
            sessionId = sessionId,
            projectionName = projectionName,
            lastAppliedSequence = projection?.consumedSequence ?: 0,
            currentRevision = currentRevision(sessionId),
            snapshotSchemaVersion = envelope?.schemaVersion ?: 0,
            snapshotProducerVersion = envelope?.producerVersion ?: "unknown",
            snapshotJson = envelope?.payloadJson,
        )
    }

    fun observeEventsAfter(sessionId: String, afterSequenceExclusive: Long) = database.runtimeEventDao().observeAfter(sessionId, afterSequenceExclusive)

    private fun commandType(command: RuntimeUiCommand): String = when (command) {
        is RuntimeUiCommand.Start -> "Start"

        is RuntimeUiCommand.ResolveUserOperation -> "ResolveUserOperation"

        is RuntimeUiCommand.RunAction -> when (command.action) {
            RuntimeAction.STEER -> "Steer"
            RuntimeAction.APPROVE -> "Approve"
            RuntimeAction.REJECT -> "Reject"
            RuntimeAction.CANCEL -> "Cancel"
            RuntimeAction.RETRY -> "Retry"
            RuntimeAction.RESUME -> "Resume"
            RuntimeAction.UNDO -> "Undo"
            RuntimeAction.START -> "Start"
            RuntimeAction.FEEDBACK_POSITIVE -> "FeedbackPositive"
            RuntimeAction.FEEDBACK_NEGATIVE -> "FeedbackNegative"
        }
    }

    private fun commandEventType(command: RuntimeUiCommand): String = when (command) {
        is RuntimeUiCommand.Start -> "RunReceived"
        is RuntimeUiCommand.ResolveUserOperation, is RuntimeUiCommand.RunAction -> "CommandEnqueued"
    }

    private fun canonicalPayload(command: RuntimeUiCommand, resolvedRunId: String): String = buildJsonObject {
        put("type", commandType(command))
        put("sessionId", command.sessionId)
        put("runId", resolvedRunId)
        put("clientActionId", command.clientActionId)
        put("expectedRevision", command.expectedRevision)
        put("surfaceId", command.surfaceId)
        when (command) {
            is RuntimeUiCommand.Start -> put("inputRef", command.inputRef)

            is RuntimeUiCommand.RunAction -> {
                command.proposalId?.let { put("proposalId", it) }
                command.payloadRef?.let { put("payloadRef", it) }
            }

            is RuntimeUiCommand.ResolveUserOperation -> {
                put("requestId", command.requestId)
                put("result", command.result.name)
                command.resultRef?.let { put("resultRef", it) }
            }
        }
    }.toString()

    private fun encodeReceipt(receipt: RuntimeCommandReceipt): String =
        listOf(receipt.commandId, receipt.runId, receipt.acceptedSequence.toString()).joinToString("|")

    private fun decodeReceipt(value: String?): RuntimeCommandReceipt {
        val parts = requireNotNull(value).split('|')
        return RuntimeCommandReceipt(parts[0], parts[1], parts[2].toLong())
    }
}
