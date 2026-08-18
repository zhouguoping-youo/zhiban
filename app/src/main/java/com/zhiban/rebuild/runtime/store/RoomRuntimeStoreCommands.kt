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
import com.zhiban.rebuild.runtime.runSuspendCatching
import com.zhiban.rebuild.runtime.spi.CommandReceipt
import com.zhiban.rebuild.runtime.spi.CommandReceiptStatus
import com.zhiban.rebuild.runtime.spi.RUNTIME_SCHEMA_VERSION
import com.zhiban.rebuild.runtime.spi.RuntimeAction
import com.zhiban.rebuild.runtime.spi.RuntimeCommandStatus
import com.zhiban.rebuild.runtime.spi.RuntimeRunStatus
import com.zhiban.rebuild.runtime.spi.RuntimeUiCommand
import com.zhiban.rebuild.runtime.spi.StagedApprovalContent
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

internal suspend fun RoomRuntimeStore.processClaimedCommand(commandId: String, ownerId: String, fencingEpoch: Long, nowEpochMs: Long): Boolean = database.withTransaction {
    val command = requireNotNull(database.runtimeCommandInboxDao().find(commandId))
    requireActiveLease(command.sessionId, ownerId, fencingEpoch, nowEpochMs)
    if (command.commandType != "Start") return@withTransaction processRunAction(command, fencingEpoch, nowEpochMs)
    val inputRef = Json.parseToJsonElement(
        command.payloadJson,
    ).jsonObject.getValue("inputRef").jsonPrimitive.content
    val staged = database.runtimeInputStagingDao().find(inputRef)
    if (staged == null || staged.expiresAtEpochMs <= nowEpochMs) {
        return@withTransaction failExpiredOrMissingInput(
            ClaimedStartFailureCtx(command, commandId, staged, inputRef, fencingEpoch, nowEpochMs),
        )
    }
    commitStagedInputAndComplete(
        ClaimedStartCommitCtx(command, commandId, staged, inputRef, fencingEpoch, nowEpochMs),
    )
}

internal data class ClaimedStartFailureCtx(
    val command: RuntimeCommandInboxEntity,
    val commandId: String,
    val staged: RuntimeInputStagingEntity?,
    val inputRef: String,
    val fencingEpoch: Long,
    val nowEpochMs: Long,
)

internal data class ClaimedStartCommitCtx(
    val command: RuntimeCommandInboxEntity,
    val commandId: String,
    val staged: RuntimeInputStagingEntity,
    val inputRef: String,
    val fencingEpoch: Long,
    val nowEpochMs: Long,
)

internal suspend fun RoomRuntimeStore.failExpiredOrMissingInput(ctx: ClaimedStartFailureCtx): Boolean {
    val (command, commandId, staged, inputRef, fencingEpoch, nowEpochMs) = ctx
    if (staged != null) database.runtimeInputStagingDao().delete(inputRef)
    val runId = requireNotNull(command.runId)
    val run = requireNotNull(database.runtimeRunDao().find(runId))
    val current = RuntimeRunStatus.valueOf(run.status)
    val target = RuntimeStateMachine.reduce(current, RuntimeSignal.FinalFailure)
    val failure = appendEventInTransaction(
        RuntimeEventDraft(
            "event-input-failed-$commandId", "InputUnavailable", command.sessionId, runId, null,
            commandId, runId, "{\"errorCode\":\"INPUT_EXPIRED_OR_MISSING\"}", nowEpochMs,
        ),
        fencingEpoch,
    )
    check(
        database.runtimeRunDao().transition(
            runId,
            current.name,
            target.name,
            failure.sequence,
            nowEpochMs,
        ) == 1,
    )
    check(
        database.runtimeCommandInboxDao().fail(
            commandId,
            "{\"errorCode\":\"INPUT_EXPIRED_OR_MISSING\"}",
            nowEpochMs,
        ) ==
            1,
    )
    return false
}

internal suspend fun RoomRuntimeStore.commitStagedInputAndComplete(ctx: ClaimedStartCommitCtx): Boolean {
    val (command, commandId, staged, inputRef, fencingEpoch, nowEpochMs) = ctx
    val runId = requireNotNull(command.runId)
    val run = requireNotNull(database.runtimeRunDao().find(runId))
    val current = RuntimeRunStatus.valueOf(run.status)
    val target = RuntimeStateMachine.reduce(current, RuntimeSignal.BeginContext)
    val safePayload = buildJsonObject {
        put("utf8Length", staged.utf8Length)
        put("sha256Digest", staged.sha256Digest)
    }.toString()
    appendEventInTransaction(
        RuntimeEventDraft("event-input-$commandId", "InputCommitted", command.sessionId, runId, null, commandId, runId, safePayload, nowEpochMs),
        fencingEpoch,
    )
    database.runtimeRunInputDao().insert(
        RuntimeRunInputEntity(
            staged.inputRef,
            runId,
            staged.rawText,
            staged.utf8Length,
            staged.sha256Digest,
            staged.expiresAtEpochMs,
        ),
    )
    val conversationText = userFacingConversationText(staged.rawText)
    database.runtimeConversationTurnDao().insert(
        RuntimeConversationTurnEntity(
            "turn-$runId-user",
            command.sessionId,
            runId,
            "user",
            conversationText,
            sha256(conversationText),
            estimateTurnTokens(conversationText),
            nowEpochMs,
        ),
    )
    val transitionEvent = appendEventInTransaction(
        RuntimeEventDraft("event-context-$commandId", "ContextAssemblyStarted", command.sessionId, runId, null, commandId, runId, "{}", nowEpochMs),
        fencingEpoch,
    )
    check(
        database.runtimeRunDao().transition(
            runId,
            current.name,
            target.name,
            transitionEvent.sequence,
            nowEpochMs,
        ) ==
            1,
    )
    val projectionPayload = buildJsonObject {
        put("runId", runId)
        put("status", target.name)
    }.toString()
    val envelope = RuntimeProjectionEnvelopeCodec.encode(projectionPayload, producerVersion)
    val inserted = database.runtimeProjectionDao().insert(
        RuntimeProjectionEntity("ui", command.sessionId, transitionEvent.sequence, envelope, nowEpochMs),
    ) != -1L
    check(
        inserted ||
            database.runtimeProjectionDao().advance(
                "ui",
                command.sessionId,
                transitionEvent.sequence,
                envelope,
                nowEpochMs,
            ) ==
            1,
    )
    check(database.runtimeInputStagingDao().delete(inputRef) == 1)
    check(database.runtimeCommandInboxDao().complete(commandId, "{\"status\":\"ACCEPTED\"}", nowEpochMs) == 1)
    return true
}

internal suspend fun RoomRuntimeStore.processRunAction(command: RuntimeCommandInboxEntity, fencingEpoch: Long, nowEpochMs: Long): Boolean {
    val runId = requireNotNull(command.runId)
    val run = requireNotNull(database.runtimeRunDao().find(runId))
    val current = RuntimeRunStatus.valueOf(run.status)
    if (command.commandType == "Undo") {
        return processUndoAction(command, runId, current, fencingEpoch, nowEpochMs)
    }
    if (command.commandType in setOf("FeedbackPositive", "FeedbackNegative")) {
        return processFeedbackAction(command, runId, current, fencingEpoch, nowEpochMs)
    }
    if (command.commandType == "Approve" || command.commandType == "Reject") {
        if (!approvalMatches(command, runId)) {
            check(
                database.runtimeCommandInboxDao().fail(
                    command.commandId,
                    "{\"errorCode\":\"APPROVAL_MISMATCH\"}",
                    nowEpochMs,
                ) ==
                    1,
            )
            return false
        }
    }
    if (command.commandType == "Resume") {
        return processResumeAction(command, runId, current, fencingEpoch, nowEpochMs)
    }
    return processSignalDispatch(command, runId, current, fencingEpoch, nowEpochMs)
}

internal suspend fun RoomRuntimeStore.processUndoAction(
    command: RuntimeCommandInboxEntity,
    runId: String,
    current: RuntimeRunStatus,
    fencingEpoch: Long,
    nowEpochMs: Long,
): Boolean {
    if (current != RuntimeRunStatus.SUCCEEDED) {
        check(
            database.runtimeCommandInboxDao().fail(
                command.commandId,
                "{\"errorCode\":\"RUN_NOT_SUCCEEDED\"}",
                nowEpochMs,
            ) ==
                1,
        )
        return false
    }
    val payload = runSuspendCatching { Json.parseToJsonElement(command.payloadJson).jsonObject }.getOrNull()
    val changeId = payload?.get("payloadRef")?.jsonPrimitive?.content
    val change = changeId?.let { ChangeUndoCoordinator(database).undoInTransaction(it, runId, nowEpochMs) }
    if (change == null) {
        check(
            database.runtimeCommandInboxDao().fail(
                command.commandId,
                "{\"errorCode\":\"CHANGE_NOT_UNDOABLE\"}",
                nowEpochMs,
            ) ==
                1,
        )
        return false
    }
    val event = appendEventInTransaction(
        RuntimeEventDraft(
            "event-undo-${command.commandId}", "ChangeUndone", command.sessionId, runId, null,
            command.commandId, runId,
            buildJsonObject {
                put("changeId", change.changeId)
                put("targetDomain", change.targetDomain)
                put("targetId", change.targetId)
            }.toString(),
            nowEpochMs,
        ),
        fencingEpoch,
    )
    check(
        database.runtimeRunDao().transition(runId, current.name, current.name, event.sequence, nowEpochMs) == 1,
    )
    check(
        database.runtimeCommandInboxDao().complete(command.commandId, "{\"status\":\"UNDONE\"}", nowEpochMs) ==
            1,
    )
    return true
}

internal suspend fun RoomRuntimeStore.processFeedbackAction(
    command: RuntimeCommandInboxEntity,
    runId: String,
    current: RuntimeRunStatus,
    fencingEpoch: Long,
    nowEpochMs: Long,
): Boolean {
    if (current !in
        setOf(RuntimeRunStatus.SUCCEEDED, RuntimeRunStatus.CANCELLED, RuntimeRunStatus.FAILED_FINAL)
    ) {
        check(
            database.runtimeCommandInboxDao().fail(
                command.commandId,
                "{\"errorCode\":\"RUN_NOT_TERMINAL\"}",
                nowEpochMs,
            ) ==
                1,
        )
        return false
    }
    val rating = if (command.commandType == "FeedbackPositive") "POSITIVE" else "NEGATIVE"
    val event = appendEventInTransaction(
        RuntimeEventDraft(
            "event-feedback-${command.commandId}", "UserFeedbackRecorded", command.sessionId, runId, null,
            command.commandId, runId, "{\"rating\":\"$rating\"}", nowEpochMs,
        ),
        fencingEpoch,
    )
    check(
        database.runtimeRunDao().transition(runId, current.name, current.name, event.sequence, nowEpochMs) == 1,
    )
    check(
        database.runtimeCommandInboxDao().complete(
            command.commandId,
            "{\"status\":\"RECORDED\"}",
            nowEpochMs,
        ) ==
            1,
    )
    return true
}

internal suspend fun RoomRuntimeStore.processResumeAction(
    command: RuntimeCommandInboxEntity,
    runId: String,
    current: RuntimeRunStatus,
    fencingEpoch: Long,
    nowEpochMs: Long,
): Boolean {
    if (current in
        setOf(RuntimeRunStatus.SUCCEEDED, RuntimeRunStatus.CANCELLED, RuntimeRunStatus.FAILED_FINAL)
    ) {
        check(
            database.runtimeCommandInboxDao().fail(
                command.commandId,
                "{\"errorCode\":\"RUN_TERMINAL\"}",
                nowEpochMs,
            ) ==
                1,
        )
        return false
    }
    val event = appendEventInTransaction(
        RuntimeEventDraft(
            "event-action-${command.commandId}", "RunResumed", command.sessionId, runId, null,
            command.commandId, runId, "{\"status\":\"${current.name}\"}", nowEpochMs,
        ),
        fencingEpoch,
    )
    check(
        database.runtimeRunDao().transition(runId, current.name, current.name, event.sequence, nowEpochMs) == 1,
    )
    check(
        database.runtimeCommandInboxDao().complete(command.commandId, "{\"status\":\"RESUMED\"}", nowEpochMs) ==
            1,
    )
    return true
}

internal suspend fun RoomRuntimeStore.processSignalDispatch(
    command: RuntimeCommandInboxEntity,
    runId: String,
    current: RuntimeRunStatus,
    fencingEpoch: Long,
    nowEpochMs: Long,
): Boolean {
    val signal = when (command.commandType) {
        "Approve" -> RuntimeSignal.Approved

        "Reject" -> RuntimeSignal.Rejected

        "Cancel" -> RuntimeSignal.Cancel

        "Retry" -> RuntimeSignal.Retry

        else -> {
            check(
                database.runtimeCommandInboxDao().fail(
                    command.commandId,
                    "{\"errorCode\":\"UNSUPPORTED_COMMAND\"}",
                    nowEpochMs,
                ) ==
                    1,
            )
            return false
        }
    }
    val target = runSuspendCatching { RuntimeStateMachine.reduce(current, signal) }.getOrElse {
        check(
            database.runtimeCommandInboxDao().fail(
                command.commandId,
                "{\"errorCode\":\"POLICY_REJECTED\"}",
                nowEpochMs,
            ) ==
                1,
        )
        return false
    }
    val eventPayload = if (command.commandType == "Approve" ||
        command.commandType == "Reject"
    ) {
        approvalAuditPayload(command)
    } else {
        "{}"
    }
    val event = appendEventInTransaction(
        RuntimeEventDraft(
            "event-action-${command.commandId}", "${command.commandType}Applied", command.sessionId, runId,
            null, command.commandId, runId, eventPayload, nowEpochMs,
        ),
        fencingEpoch,
    )
    check(database.runtimeRunDao().transition(runId, current.name, target.name, event.sequence, nowEpochMs) == 1)
    appendRunStatusUiProjection(command, runId, target, event, nowEpochMs)
    if (command.commandType == "Reject" ||
        (command.commandType == "Cancel" && current == RuntimeRunStatus.AWAITING_CONFIRMATION)
    ) {
        database.runtimeApprovalStagingDao().deleteByRunId(runId)
    }
    check(
        database.runtimeCommandInboxDao().complete(command.commandId, "{\"status\":\"APPLIED\"}", nowEpochMs) == 1,
    )
    return true
}

internal suspend fun RoomRuntimeStore.appendRunStatusUiProjection(
    command: RuntimeCommandInboxEntity,
    runId: String,
    target: RuntimeRunStatus,
    event: RuntimeEventEntity,
    nowEpochMs: Long,
) {
    val envelope =
        RuntimeProjectionEnvelopeCodec.encode(
            buildJsonObject {
                put("runId", runId)
                put("status", target.name)
            }.toString(),
            producerVersion,
        )
    val inserted =
        database.runtimeProjectionDao().insert(
            RuntimeProjectionEntity("ui", command.sessionId, event.sequence, envelope, nowEpochMs),
        ) !=
            -1L
    check(
        inserted ||
            database.runtimeProjectionDao().advance(
                "ui",
                command.sessionId,
                event.sequence,
                envelope,
                nowEpochMs,
            ) ==
            1,
    )
}

internal suspend fun RoomRuntimeStore.approvalMatches(command: RuntimeCommandInboxEntity, runId: String): Boolean {
    val requested = database.runtimeEventDao().latestByType(runId, "ApprovalRequested") ?: return false
    val expected =
        runSuspendCatching { Json.parseToJsonElement(requested.payloadJson).jsonObject }.getOrNull() ?: return false
    val actual = runSuspendCatching { Json.parseToJsonElement(command.payloadJson).jsonObject }.getOrNull() ?: return false
    val expectedProposal = expected["proposalId"]?.jsonPrimitive?.content ?: return false
    val expectedPayload = expected["payloadRef"]?.jsonPrimitive?.content ?: return false
    return actual["proposalId"]?.jsonPrimitive?.content == expectedProposal &&
        actual["payloadRef"]?.jsonPrimitive?.content == expectedPayload
}

internal fun RoomRuntimeStore.approvalAuditPayload(command: RuntimeCommandInboxEntity): String {
    val payload = Json.parseToJsonElement(command.payloadJson).jsonObject
    val proposalId = payload.getValue("proposalId").jsonPrimitive.content
    val payloadRef = payload.getValue("payloadRef").jsonPrimitive.content
    val digest = MessageDigest.getInstance("SHA-256").digest(payloadRef.toByteArray())
        .joinToString("") { "%02x".format(it) }
    return buildJsonObject {
        put("proposalId", proposalId)
        put("payloadDigest", digest)
    }.toString()
}

internal suspend fun RoomRuntimeStore.startAttempt(request: AttemptStartRequest) = database.withTransaction {
    val run = requireNotNull(database.runtimeRunDao().find(request.runId))
    requireActiveLease(run.sessionId, request.ownerId, request.fencingEpoch, request.nowEpochMs)
    database.runtimeAttemptDao().insert(
        RuntimeAttemptEntity(request.attemptId, request.runId, request.ordinal, "ACTIVE", request.nowEpochMs, request.nowEpochMs),
    )
    val event = appendEventInTransaction(
        RuntimeEventDraft(
            eventId = "event-attempt-${request.attemptId}",
            eventType = "ProviderAttemptStarted",
            sessionId = run.sessionId,
            runId = request.runId,
            attemptId = request.attemptId,
            causationId = request.attemptId,
            correlationId = request.runId,
            payloadJson = "{}",
            createdAtEpochMs = request.nowEpochMs,
        ),
        request.fencingEpoch,
    )
    check(database.runtimeRunDao().startAttempt(request.runId, request.attemptId, event.sequence, request.nowEpochMs) == 1) {
        "run cannot start attempt"
    }
}

internal suspend fun RoomRuntimeStore.startObservationAttempt(request: AttemptStartRequest) = database.withTransaction {
    val run = requireNotNull(database.runtimeRunDao().find(request.runId))
    requireActiveLease(run.sessionId, request.ownerId, request.fencingEpoch, request.nowEpochMs)
    check(run.status == RuntimeRunStatus.OBSERVING.name)
    run.activeAttemptId?.let { activeId ->
        database.runtimeAttemptDao().listByRunId(request.runId).firstOrNull {
            it.attemptId == activeId &&
                it.status == "ACTIVE"
        }
            ?.let { check(database.runtimeAttemptDao().finish(activeId, "SUPERSEDED", request.nowEpochMs) == 1) }
    }
    database.runtimeAttemptDao().insert(
        RuntimeAttemptEntity(request.attemptId, request.runId, request.ordinal, "ACTIVE", request.nowEpochMs, request.nowEpochMs),
    )
    val event = appendEventInTransaction(
        RuntimeEventDraft(
            eventId = "event-attempt-${request.attemptId}",
            eventType = "ObservationStarted",
            sessionId = run.sessionId,
            runId = request.runId,
            attemptId = request.attemptId,
            causationId = request.attemptId,
            correlationId = request.runId,
            payloadJson = "{}",
            createdAtEpochMs = request.nowEpochMs,
        ),
        request.fencingEpoch,
    )
    check(database.runtimeRunDao().startObservationAttempt(request.runId, request.attemptId, event.sequence, request.nowEpochMs) == 1)
}


