package com.zhiban.rebuild.ui.agent.projection

import com.zhiban.rebuild.runtime.spi.CommandReceipt
import com.zhiban.rebuild.runtime.spi.PendingUserOperationState
import com.zhiban.rebuild.runtime.spi.RUNTIME_SCHEMA_VERSION
import com.zhiban.rebuild.runtime.spi.RuntimeCommandGateway
import com.zhiban.rebuild.runtime.spi.RuntimeProjectionGateway
import com.zhiban.rebuild.runtime.spi.RuntimeRunStatus
import com.zhiban.rebuild.runtime.spi.RuntimeUiClient
import com.zhiban.rebuild.runtime.spi.RuntimeUiCommand
import com.zhiban.rebuild.runtime.spi.RuntimeUiEvent
import com.zhiban.rebuild.runtime.spi.SessionProjection
import com.zhiban.rebuild.runtime.spi.StoredProjectionSnapshot
import com.zhiban.rebuild.runtime.spi.StoredRuntimeEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class GatewayRuntimeUiClient(
    private val commandGateway: RuntimeCommandGateway,
    private val projectionGateway: RuntimeProjectionGateway,
    private val projectionName: String = "ui",
    private val reducer: AgentSessionReducer = AgentSessionReducer(),
) : RuntimeUiClient {
    override suspend fun dispatch(command: RuntimeUiCommand): CommandReceipt = commandGateway.accept(command)

    override suspend fun getSessionProjection(sessionId: String): SessionProjection {
        val stream = projectionGateway.snapshotAndObserve(sessionId, projectionName, 0)
        var projection = decodeSnapshot(stream.snapshot)
        val events = stream.events.first()
        // Change metadata and the pending approval are deliberately kept out of the compact run-status
        // snapshot. Rehydrate their latest state from the durable journal even when below the watermark,
        // otherwise a run that was already AWAITING_CONFIRMATION when the snapshot was taken loses its
        // pendingApproval on (re)connect — the run status survives (it is in the snapshot) but the
        // confirmation card vanishes and the input stays locked with no way to approve.
        events.asSequence()
            .filter { it.sequence <= projection.lastAppliedSequence }
            .map { it.toUiEvent() }
            .forEach { event ->
                projection = when (event) {
                    is RuntimeUiEvent.ChangeCommitted -> projection.copy(
                        lastChangeId = event.changeId,
                        undoAvailable = true,
                        allowedActions = projection.allowedActions + com.zhiban.rebuild.runtime.spi.RuntimeAction.UNDO,
                    )

                    is RuntimeUiEvent.ChangeUndone -> projection.copy(
                        undoAvailable = false,
                        allowedActions = projection.allowedActions - com.zhiban.rebuild.runtime.spi.RuntimeAction.UNDO,
                    )

                    is RuntimeUiEvent.ApprovalRequested -> projection.copy(
                        // Only resurrect the pending approval when the run is still waiting on it.
                        // Replaying a historical ApprovalRequested after the run already reached a
                        // terminal state would revive a dead confirmation card.
                        pendingApproval = if (projection.runStatus == RuntimeRunStatus.AWAITING_CONFIRMATION) {
                            com.zhiban.rebuild.runtime.spi.PendingApprovalProjection(
                                event.proposalId,
                                event.payloadRef,
                                event.title,
                                event.platform,
                                event.recipient,
                                event.message,
                                event.scheduleStartAtEpochMs,
                                event.scheduleDurationMinutes,
                                event.scheduleReminderMinutesBefore,
                                event.scheduleNote,
                                // The ApprovalRequested payload stays redacted; the body comes from the
                                // transient snapshot via pendingDetails.
                                event.details ?: projection.pendingDetails,
                                event.candidateId,
                            )
                        } else {
                            projection.pendingApproval
                        },
                    )

                    else -> projection
                }
            }
        events.forEach { stored ->
            projection = reducer.reduce(projection, stored.toUiEvent())
        }
        // Reconnect backfill: the compact snapshot never carries the streamed body, so when its
        // watermark already passed this run's deltas (a follow-up Start, a RejectApplied marker, or an
        // interrupted stream) the replay above skips them and leaves assistantText empty even though the
        // turn was durably saved. Restore it from the conversation journal so a list body never vanishes.
        val activeRunId = projection.runId
        if (projection.assistantText.isBlank() && activeRunId != null) {
            projectionGateway.assistantTurnText(sessionId, activeRunId)?.let { restored ->
                projection = projection.copy(assistantText = restored, assistantFinal = true)
            }
        }
        return projection
    }

    override fun observeSession(sessionId: String, afterSequenceExclusive: Long): Flow<RuntimeUiEvent> = flow {
        val stream = projectionGateway.snapshotAndObserve(sessionId, projectionName, afterSequenceExclusive)
        stream.events.collect { batch ->
            batch.forEach { stored -> emit(stored.toUiEvent()) }
        }
    }

    override suspend fun stagedCandidateContent(candidateId: String): String? = projectionGateway.stagedCandidateContent(candidateId)

    private fun decodeSnapshot(snapshot: StoredProjectionSnapshot): SessionProjection {
        val payload = snapshot.snapshotJson
        if (payload == null) {
            return SessionProjection(
                sessionId = snapshot.sessionId,
                lastAppliedSequence = snapshot.lastAppliedSequence,
                revision = snapshot.currentRevision,
            )
        }
        if (snapshot.snapshotSchemaVersion != RUNTIME_SCHEMA_VERSION) {
            return SessionProjection(
                sessionId = snapshot.sessionId,
                lastAppliedSequence = snapshot.lastAppliedSequence,
                revision = snapshot.currentRevision,
                runStatus = RuntimeRunStatus.FAILED_FINAL,
                readOnly = true,
                allowedActions = emptySet(),
            )
        }
        val value = parseObject(payload).getOrElse {
            return SessionProjection(
                sessionId = snapshot.sessionId,
                lastAppliedSequence = snapshot.lastAppliedSequence,
                revision = snapshot.currentRevision,
                runStatus = RuntimeRunStatus.FAILED_FINAL,
                safeFailureCode = SNAPSHOT_PAYLOAD_INVALID,
                degradationReasons = setOf(SNAPSHOT_PAYLOAD_INVALID),
                readOnly = true,
                allowedActions = emptySet(),
            )
        }
        val status =
            (value.string("runStatus") ?: value.string("status"))?.let {
                runCatching { RuntimeRunStatus.valueOf(it) }.getOrNull()
            }
                ?: RuntimeRunStatus.RECEIVED
        return SessionProjection(
            sessionId = snapshot.sessionId,
            runId = value.string("runId"),
            lastAppliedSequence = snapshot.lastAppliedSequence,
            revision = snapshot.currentRevision,
            runStatus = status,
            recoveryNeeded = value.boolean("recoveryNeeded") ?: false,
            assistantText = value.string("assistantText").orEmpty(),
            assistantFinal = value.boolean("assistantFinal") ?: false,
            safeFailureCode = value.string("safeFailureCode"),
            readOnly = value.boolean("readOnly") ?: false,
            // Confirmation body shown on the card (e.g. memory content). Only present while the run is
            // AWAITING_CONFIRMATION; the snapshot is a single overwritten row, so it never lingers.
            pendingDetails = value.string("details"),
            allowedActions = allowedActionsFor(status, value.boolean("recoveryNeeded") ?: false),
        )
    }

    private fun StoredRuntimeEvent.toUiEvent(): RuntimeUiEvent {
        if (schemaVersion != RUNTIME_SCHEMA_VERSION) {
            return RuntimeUiEvent.UnsupportedSchema(sessionId, runId, sequence, sequence, schemaVersion)
        }
        val payload = parseObject(payloadJson).getOrElse {
            return RuntimeUiEvent.ProjectionDegraded(
                sessionId = sessionId,
                runId = runId,
                sequence = sequence,
                revision = sequence,
                reasonCode = EVENT_PAYLOAD_INVALID,
            )
        }
        return when (eventType) {
            "RunReceived" -> status(RuntimeRunStatus.RECEIVED)

            "ContextAssemblyStarted" -> status(RuntimeRunStatus.ASSEMBLING_CONTEXT)

            "ProviderAttemptStarted" -> attemptId?.let {
                RuntimeUiEvent.AssistantAttemptStarted(sessionId, requireNotNull(runId), it, sequence, sequence)
            } ?: status(RuntimeRunStatus.INFERENCING)

            "PlanProposed" -> status(RuntimeRunStatus.VALIDATING_PLAN)

            "ToolExecutionStarted" -> status(RuntimeRunStatus.EXECUTING)

            "ToolSucceeded" -> toChangeCommittedFromTool(payload)

            "ObservationStarted" -> status(RuntimeRunStatus.OBSERVING)

            "ObservationCompleted" -> status(RuntimeRunStatus.SUCCEEDED)

            "RunCompleted" -> status(RuntimeRunStatus.SUCCEEDED)

            "RunCancelled" -> status(RuntimeRunStatus.CANCELLED)

            "CancelRequested", "ToolCancelRequested" -> status(RuntimeRunStatus.CANCEL_REQUESTED)

            "RunFailedRetryable" -> status(RuntimeRunStatus.FAILED_RETRYABLE, payload.string("errorCode"))

            "RunFailed", "RunFailedFinal", "PolicyBlocked" -> status(
                RuntimeRunStatus.FAILED_FINAL,
                payload.string("errorCode"),
            )

            "ApprovalRequested" -> toApprovalRequestedEvent(payload)

            // A rejected/cancelled confirmation resolves the card: map the applied marker to a status
            // change so the reducer clears pendingApproval. Without this the event fell through to
            // JournalAdvanced and the confirmation card lingered forever even though the run was REJECTED.
            "RejectApplied" -> status(RuntimeRunStatus.CANCELLED)

            // An approved confirmation moves the run into execution.
            "ApproveApplied" -> status(RuntimeRunStatus.EXECUTING)

            "AssistantDelta" -> toAssistantDeltaEvent(payload)

            "BudgetWarning", "BudgetExceeded", "ProviderUsageRecorded" -> toBudgetChangedEvent(payload)

            "ContextChunkSelected" -> toSourceAttachedEvent(payload)

            "PendingUserOperationRequested" -> toUserOperationRequestedEvent(payload)

            "PendingUserOperationCompleted",
            "PendingUserOperationCancelled",
            "PendingUserOperationExpired",
            -> toUserOperationResolvedEvent(payload)

            "ContactCandidateCreated" -> toContactCandidateEvent(payload)

            "ChangeUndone" -> toChangeUndoneEvent(payload)

            else -> RuntimeUiEvent.JournalAdvanced(sessionId, runId, sequence, sequence)
        }
    }

    private fun StoredRuntimeEvent.toChangeCommittedFromTool(payload: JsonObject): RuntimeUiEvent = payload.string("changeId")
        ?.takeIf(String::isNotBlank)
        ?.let { changeId ->
            RuntimeUiEvent.ChangeCommitted(
                sessionId = sessionId,
                runId = requireNotNull(runId),
                sequence = sequence,
                revision = sequence,
                changeId = changeId,
                targetDomain = payload.string("targetDomain")
                    ?: if (payload.string("scheduleId") != null) "CALENDAR" else "TOOL",
                targetId = payload.string("scheduleId")
                    ?: payload.string("contactId")
                    ?: payload.string("relationshipId")
                    ?: "",
            )
        }
        ?: status(RuntimeRunStatus.OBSERVING)

    private fun StoredRuntimeEvent.toApprovalRequestedEvent(payload: JsonObject): RuntimeUiEvent = RuntimeUiEvent.ApprovalRequested(
        sessionId, runId, sequence, sequence,
        proposalId = payload.string("proposalId").orEmpty(),
        payloadRef = payload.string("payloadRef").orEmpty(),
        title = payload.string("title").orEmpty(),
        platform = payload.string("platform"),
        recipient = payload.string("recipient"),
        message = payload.string("message"),
        scheduleStartAtEpochMs = payload.long("startAtEpochMs"),
        scheduleDurationMinutes = payload.int("durationMinutes"),
        scheduleReminderMinutesBefore = payload.int("reminderMinutesBefore"),
        scheduleNote = payload.string("note"),
        details = payload.string("details"),
        candidateId = payload.string("candidateId"),
    )

    private fun StoredRuntimeEvent.toAssistantDeltaEvent(payload: JsonObject): RuntimeUiEvent = attemptId?.let { attempt ->
        RuntimeUiEvent.AssistantDelta(
            sessionId, runId, attempt, sequence, sequence,
            ordinal = payload.long("ordinal") ?: sequence,
            part = payload.string("part").orEmpty(),
            final = payload.boolean("final") ?: false,
            providerOffset = payload.long("providerOffset"),
        )
    } ?: RuntimeUiEvent.UnsupportedSchema(sessionId, runId, sequence, sequence, schemaVersion)

    private fun StoredRuntimeEvent.toBudgetChangedEvent(payload: JsonObject): RuntimeUiEvent = RuntimeUiEvent.BudgetChanged(
        sessionId,
        runId,
        sequence,
        sequence,
        usedTokens = payload.int("usedTokens") ?: 0,
        maxTokens = payload.int("maxTokens") ?: 0,
    )

    private fun StoredRuntimeEvent.toSourceAttachedEvent(payload: JsonObject): RuntimeUiEvent = RuntimeUiEvent.SourceAttached(
        sessionId,
        runId,
        sequence,
        sequence,
        sourceId = payload.string("sourceId").orEmpty(),
        label = payload.string("label").orEmpty(),
    )

    private fun StoredRuntimeEvent.toUserOperationRequestedEvent(payload: JsonObject): RuntimeUiEvent = RuntimeUiEvent.UserOperationRequested(
        sessionId,
        runId,
        sequence,
        sequence,
        requestId = payload.string("requestId").orEmpty(),
        type = payload.string("operationType").orEmpty(),
        expiresAtEpochMs = payload.long("expiresAtEpochMs") ?: 0,
    )

    private fun StoredRuntimeEvent.toUserOperationResolvedEvent(payload: JsonObject): RuntimeUiEvent = RuntimeUiEvent.UserOperationResolved(
        sessionId,
        runId,
        sequence,
        sequence,
        requestId = payload.string("requestId").orEmpty(),
        state = when (eventType) {
            "PendingUserOperationCompleted" -> PendingUserOperationState.COMPLETED
            "PendingUserOperationCancelled" -> PendingUserOperationState.CANCELLED
            else -> PendingUserOperationState.EXPIRED
        },
    )

    private fun StoredRuntimeEvent.toContactCandidateEvent(payload: JsonObject): RuntimeUiEvent = RuntimeUiEvent.ChangeCommitted(
        sessionId,
        requireNotNull(runId),
        sequence,
        sequence,
        changeId = payload.string("changeId").orEmpty(),
        targetDomain = "CONTACT",
        targetId = payload.string("contactId").orEmpty(),
    )

    private fun StoredRuntimeEvent.toChangeUndoneEvent(payload: JsonObject): RuntimeUiEvent = RuntimeUiEvent.ChangeUndone(
        sessionId,
        requireNotNull(runId),
        sequence,
        sequence,
        changeId = payload.string("changeId").orEmpty(),
    )

    private fun StoredRuntimeEvent.status(status: RuntimeRunStatus, safeFailureCode: String? = null) = RuntimeUiEvent.RunStatusChanged(
        sessionId,
        runId,
        sequence,
        sequence,
        status,
        safeFailureCode,
    )

    private fun parseObject(value: String): Result<JsonObject> = runCatching {
        Json.parseToJsonElement(value).jsonObject
    }

    private fun JsonObject.string(key: String) = get(key)?.jsonPrimitive?.content
    private fun JsonObject.long(key: String) = get(key)?.jsonPrimitive?.longOrNull
    private fun JsonObject.int(key: String) = get(key)?.jsonPrimitive?.intOrNull
    private fun JsonObject.boolean(key: String) = get(key)?.jsonPrimitive?.booleanOrNull

    private companion object {
        const val SNAPSHOT_PAYLOAD_INVALID = "projection_snapshot_payload_invalid"
        const val EVENT_PAYLOAD_INVALID = "projection_event_payload_invalid"
    }
}
