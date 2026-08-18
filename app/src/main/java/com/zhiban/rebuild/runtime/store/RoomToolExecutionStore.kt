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
import com.zhiban.rebuild.data.autowrite.ChangeLogEntity
import com.zhiban.rebuild.data.contact.StagedContactCandidateEntity
import com.zhiban.rebuild.data.facts.FactEntity
import com.zhiban.rebuild.data.facts.FactIndex
import com.zhiban.rebuild.data.memory.StagedMemoryCandidateEntity
import com.zhiban.rebuild.data.store.RuntimeEventEntity
import com.zhiban.rebuild.data.store.RuntimeToolExecutionEntity
import com.zhiban.rebuild.foundation.sha256
import com.zhiban.rebuild.runtime.governance.ChangeUndoCoordinator
import com.zhiban.rebuild.runtime.governance.ContactCreateCandidateCall
import com.zhiban.rebuild.runtime.governance.RelationshipCandidateCall
import com.zhiban.rebuild.runtime.kernel.RuntimeSignal
import com.zhiban.rebuild.runtime.kernel.RuntimeStateMachine
import com.zhiban.rebuild.runtime.memory.RoomMemoryGate
import com.zhiban.rebuild.runtime.plan.RuntimePlanRecorder
import com.zhiban.rebuild.runtime.plan.RuntimePlanStep
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
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class ApprovedToolExecutionRequest(
    val runId: String,
    val providerCallId: String,
    val logicalStepId: String,
    val toolName: String,
    val toolSpecVersion: Int,
    val canonicalInputDigest: String,
    val idempotencyKey: String,
    val safeResultJson: String,
    val ownerId: String,
    val fencingEpoch: Long,
    val nowEpochMs: Long,
)

internal data class ApprovedExternalToolReservationRequest(
    val runId: String,
    val providerCallId: String,
    val logicalStepId: String,
    val toolName: String,
    val toolSpecVersion: Int,
    val canonicalInputDigest: String,
    val idempotencyKey: String,
    val ownerId: String,
    val fencingEpoch: Long,
    val nowEpochMs: Long,
)

internal data class ApprovedExternalToolReservation(val execution: RuntimeToolExecutionEntity, val acquired: Boolean)

internal class RoomToolExecutionStore(
    private val database: AgentDatabase,
    private val requireActiveLease: suspend (String, String, Long, Long) -> Unit,
    private val appendEventInTransaction: suspend (RuntimeEventDraft, Long) -> RuntimeEventEntity,
) {
    private val planRecorder = RuntimePlanRecorder(database)

    suspend fun recordToolSuccess(
        executionId: String,
        runId: String,
        logicalStepId: String,
        toolName: String,
        toolSpecVersion: Int,
        canonicalInputDigest: String,
        idempotencyKey: String,
        resultRef: String,
        safeResultJson: String,
        ownerId: String,
        fencingEpoch: Long,
        nowEpochMs: Long,
    ) = database.withTransaction {
        val run = requireNotNull(database.runtimeRunDao().find(runId))
        requireActiveLease(run.sessionId, ownerId, fencingEpoch, nowEpochMs)
        val existing = database.runtimeToolExecutionDao().findByKey(idempotencyKey)
        if (existing != null) {
            check(existing.canonicalInputDigest == canonicalInputDigest) { "idempotency key payload conflict" }
            return@withTransaction existing
        }
        RuntimeToolExecutionEntity(
            executionId = executionId, runId = runId, logicalStepId = logicalStepId, toolName = toolName,
            toolSpecVersion = toolSpecVersion, canonicalInputDigest = canonicalInputDigest,
            idempotencyKey = idempotencyKey, status = "SUCCEEDED", resultRef = resultRef,
            safeResultJson = safeResultJson, fencingEpoch = fencingEpoch,
            createdAtEpochMs = nowEpochMs, updatedAtEpochMs = nowEpochMs,
        ).also { database.runtimeToolExecutionDao().insert(it) }
    }

    suspend fun toolResult(idempotencyKey: String): RuntimeToolExecutionEntity? = database.runtimeToolExecutionDao().findByKey(idempotencyKey)

    suspend fun reserveApprovedExternalTool(request: ApprovedExternalToolReservationRequest): ApprovedExternalToolReservation = database.withTransaction {
        val run = requireNotNull(database.runtimeRunDao().find(request.runId))
        requireActiveLease(run.sessionId, request.ownerId, request.fencingEpoch, request.nowEpochMs)
        database.runtimeToolExecutionDao().findByKey(request.idempotencyKey)?.let { existing ->
            check(existing.canonicalInputDigest == request.canonicalInputDigest) { "idempotency key payload conflict" }
            return@withTransaction ApprovedExternalToolReservation(existing, acquired = false)
        }
        check(run.status == RuntimeRunStatus.EXECUTING.name) { "REMOTE_TOOL_RUN_NOT_EXECUTING" }
        val execution = RuntimeToolExecutionEntity(
            executionId = "exec-${sha256(request.idempotencyKey).take(32)}",
            runId = request.runId,
            logicalStepId = request.logicalStepId,
            toolName = request.toolName,
            toolSpecVersion = request.toolSpecVersion,
            canonicalInputDigest = request.canonicalInputDigest,
            idempotencyKey = request.idempotencyKey,
            providerCallId = request.providerCallId,
            attemptId = requireNotNull(run.activeAttemptId),
            status = "IN_PROGRESS",
            fencingEpoch = request.fencingEpoch,
            createdAtEpochMs = request.nowEpochMs,
            updatedAtEpochMs = request.nowEpochMs,
        )
        database.runtimeToolExecutionDao().insert(execution)
        ApprovedExternalToolReservation(execution, acquired = true)
    }

    suspend fun abandonApprovedExternalToolReservation(executionId: String, fencingEpoch: Long) {
        database.runtimeToolExecutionDao().deleteReservation(executionId, fencingEpoch)
    }

    /** Atomically records a confirmation-gated remote result and enters the common observation loop. */
    suspend fun completeApprovedRemoteTool(request: ApprovedToolExecutionRequest): RuntimeToolExecutionEntity = database.withTransaction {
        val runId = request.runId
        val run = requireNotNull(database.runtimeRunDao().find(runId))
        requireActiveLease(run.sessionId, request.ownerId, request.fencingEpoch, request.nowEpochMs)
        val existing = database.runtimeToolExecutionDao().findByKey(request.idempotencyKey)
        if (existing?.status == "SUCCEEDED") {
            check(existing.canonicalInputDigest == request.canonicalInputDigest) { "idempotency key payload conflict" }
            return@withTransaction existing
        }
        check(run.status == RuntimeRunStatus.EXECUTING.name) { "REMOTE_TOOL_RUN_NOT_EXECUTING" }
        val attemptId = requireNotNull(run.activeAttemptId)
        val resultRef = "result-${sha256(request.safeResultJson).take(24)}"
        val execution = if (existing == null) {
            RuntimeToolExecutionEntity(
                executionId = "exec-${sha256(request.idempotencyKey).take(32)}", runId = runId,
                logicalStepId = request.logicalStepId, toolName = request.toolName, toolSpecVersion = request.toolSpecVersion,
                canonicalInputDigest = request.canonicalInputDigest, idempotencyKey = request.idempotencyKey,
                providerCallId = request.providerCallId, attemptId = attemptId, status = "SUCCEEDED",
                resultRef = resultRef, safeResultJson = request.safeResultJson,
                fencingEpoch = request.fencingEpoch,
                createdAtEpochMs = request.nowEpochMs,
                updatedAtEpochMs = request.nowEpochMs,
            ).also { database.runtimeToolExecutionDao().insert(it) }
        } else {
            check(existing.status == "IN_PROGRESS") { "REMOTE_TOOL_RESERVATION_INVALID" }
            check(existing.canonicalInputDigest == request.canonicalInputDigest) { "idempotency key payload conflict" }
            check(existing.runId == runId && existing.toolName == request.toolName) { "REMOTE_TOOL_RESERVATION_MISMATCH" }
            check(
                database.runtimeToolExecutionDao().completeReserved(
                    existing.executionId,
                    resultRef,
                    request.safeResultJson,
                    request.fencingEpoch,
                    request.nowEpochMs,
                ) == 1,
            )
            requireNotNull(database.runtimeToolExecutionDao().findByKey(request.idempotencyKey))
        }
        database.runtimeAttemptDao().listByRunId(runId).firstOrNull {
            it.attemptId == attemptId && it.status == "ACTIVE"
        }
            ?.let { check(database.runtimeAttemptDao().finish(attemptId, "SUCCEEDED", request.nowEpochMs) == 1) }
        val event = appendEventInTransaction(
            RuntimeEventDraft(
                "event-remote-tool-${sha256("$runId:${request.providerCallId}").take(24)}", "ToolSucceeded",
                run.sessionId, runId, attemptId, request.providerCallId, runId,
                buildJsonObject {
                    put("toolName", request.toolName)
                    put("resultDigest", sha256(request.safeResultJson))
                }.toString(),
                request.nowEpochMs,
            ),
            request.fencingEpoch,
        )
        check(
            database.runtimeRunDao().transition(
                runId,
                RuntimeRunStatus.EXECUTING.name,
                RuntimeRunStatus.OBSERVING.name,
                event.sequence,
                request.nowEpochMs,
            ) ==
                1,
        )
        database.runtimeApprovalStagingDao().deleteByRunId(runId)
        execution
    }

    /** Records an auto-executed read tool and moves the run into the observation loop atomically. */
    suspend fun completeReadOnlyTool(
        runId: String,
        providerCallId: String,
        toolName: String,
        toolSpecVersion: Int,
        argumentsDigest: String,
        safeResultJson: String,
        ownerId: String,
        fencingEpoch: Long,
        nowEpochMs: Long,
    ): RuntimeToolExecutionEntity = database.withTransaction {
        val run = requireNotNull(database.runtimeRunDao().find(runId))
        requireActiveLease(run.sessionId, ownerId, fencingEpoch, nowEpochMs)
        check(run.status in setOf(RuntimeRunStatus.INFERENCING.name, RuntimeRunStatus.OBSERVING.name))
        val attemptId = requireNotNull(run.activeAttemptId)
        val idempotencyKey = sha256("$runId|$providerCallId|$toolName|$argumentsDigest")
        database.runtimeToolExecutionDao().findByKey(idempotencyKey)?.let { return@withTransaction it }
        planRecorder.record(
            RuntimePlanStep(
                runId = runId,
                attemptId = attemptId,
                providerCallId = providerCallId,
                logicalStepId = "step-$providerCallId",
                toolName = toolName,
                toolSpecVersion = toolSpecVersion,
                requiresApproval = false,
                inputDigest = argumentsDigest,
                nowEpochMs = nowEpochMs,
            ),
        )
        val execution = RuntimeToolExecutionEntity(
            executionId = "exec-${sha256(idempotencyKey).take(32)}", runId = runId,
            logicalStepId = "step-$providerCallId", toolName = toolName, toolSpecVersion = toolSpecVersion,
            canonicalInputDigest = argumentsDigest, idempotencyKey = idempotencyKey,
            providerCallId = providerCallId, attemptId = attemptId, status = "SUCCEEDED",
            resultRef = "result-${sha256(safeResultJson).take(24)}", safeResultJson = safeResultJson,
            fencingEpoch = fencingEpoch, createdAtEpochMs = nowEpochMs, updatedAtEpochMs = nowEpochMs,
        )
        database.runtimeToolExecutionDao().insert(execution)
        check(database.runtimeAttemptDao().finish(attemptId, "SUCCEEDED", nowEpochMs) == 1)
        val event = appendEventInTransaction(
            RuntimeEventDraft(
                "event-read-tool-${sha256("$runId:$providerCallId").take(24)}", "ToolSucceeded",
                run.sessionId, runId, attemptId, providerCallId, runId,
                buildJsonObject {
                    put("toolName", toolName)
                    put("resultDigest", sha256(safeResultJson))
                }.toString(),
                nowEpochMs,
            ),
            fencingEpoch,
        )
        check(
            database.runtimeRunDao().transition(
                runId,
                run.status,
                RuntimeRunStatus.OBSERVING.name,
                event.sequence,
                nowEpochMs,
            ) ==
                1,
        )
        execution
    }

    /** Records a rejected model-generated call without persisting its raw arguments. */
    suspend fun recordInvalidToolArguments(
        runId: String,
        providerCallId: String,
        toolName: String,
        argumentsDigest: String,
        safeResultJson: String,
        terminal: Boolean,
        ownerId: String,
        fencingEpoch: Long,
        nowEpochMs: Long,
    ): RuntimeToolExecutionEntity = database.withTransaction {
        val run = requireNotNull(database.runtimeRunDao().find(runId))
        requireActiveLease(run.sessionId, ownerId, fencingEpoch, nowEpochMs)
        val idempotencyKey = sha256("$runId|$providerCallId|$toolName|$argumentsDigest|invalid")
        database.runtimeToolExecutionDao().findByKey(idempotencyKey)?.let { return@withTransaction it }
        check(run.status in setOf(RuntimeRunStatus.INFERENCING.name, RuntimeRunStatus.OBSERVING.name))
        val attemptId = requireNotNull(run.activeAttemptId)
        val execution = RuntimeToolExecutionEntity(
            executionId = "exec-${sha256(idempotencyKey).take(32)}",
            runId = runId,
            logicalStepId = "step-$providerCallId",
            toolName = toolName,
            toolSpecVersion = 1,
            canonicalInputDigest = argumentsDigest,
            idempotencyKey = idempotencyKey,
            providerCallId = providerCallId,
            attemptId = attemptId,
            status = "FAILED",
            resultRef = "result-${sha256(safeResultJson).take(24)}",
            safeResultJson = safeResultJson,
            fencingEpoch = fencingEpoch,
            createdAtEpochMs = nowEpochMs,
            updatedAtEpochMs = nowEpochMs,
        )
        database.runtimeToolExecutionDao().insert(execution)
        check(database.runtimeAttemptDao().finish(attemptId, "FAILED", nowEpochMs) == 1)
        val toolFailed = appendEventInTransaction(
            RuntimeEventDraft(
                "event-tool-arguments-${sha256("$runId:$providerCallId").take(24)}",
                "ToolFailed",
                run.sessionId,
                runId,
                attemptId,
                providerCallId,
                runId,
                buildJsonObject {
                    put("toolName", toolName)
                    put("errorCode", "INVALID_TOOL_ARGUMENTS")
                }.toString(),
                nowEpochMs,
            ),
            fencingEpoch,
        )
        val terminalEvent = if (terminal) {
            appendEventInTransaction(
                RuntimeEventDraft(
                    "event-tool-arguments-$attemptId-terminal",
                    "RunFailedFinal",
                    run.sessionId,
                    runId,
                    attemptId,
                    providerCallId,
                    runId,
                    "{\"errorCode\":\"INVALID_TOOL_ARGUMENTS\"}",
                    nowEpochMs,
                ),
                fencingEpoch,
            )
        } else {
            toolFailed
        }
        val targetStatus = if (terminal) {
            RuntimeRunStatus.FAILED_FINAL.name
        } else {
            RuntimeRunStatus.OBSERVING.name
        }
        check(
            database.runtimeRunDao().transition(
                runId,
                run.status,
                targetStatus,
                terminalEvent.sequence,
                nowEpochMs,
            ) == 1,
        )
        execution
    }
}
