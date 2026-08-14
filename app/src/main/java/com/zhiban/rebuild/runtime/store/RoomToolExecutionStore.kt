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

internal class RoomToolExecutionStore(
    private val database: AgentDatabase,
    private val requireActiveLease: suspend (String, String, Long, Long) -> Unit,
    private val appendEventInTransaction: suspend (RuntimeEventDraft, Long) -> RuntimeEventEntity,
) {
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

    /** Atomically records a confirmation-gated remote result and enters the common observation loop. */
    suspend fun completeApprovedRemoteTool(
        runId: String,
        providerCallId: String,
        logicalStepId: String,
        toolName: String,
        toolSpecVersion: Int,
        canonicalInputDigest: String,
        idempotencyKey: String,
        safeResultJson: String,
        ownerId: String,
        fencingEpoch: Long,
        nowEpochMs: Long,
    ): RuntimeToolExecutionEntity = database.withTransaction {
        val run = requireNotNull(database.runtimeRunDao().find(runId))
        requireActiveLease(run.sessionId, ownerId, fencingEpoch, nowEpochMs)
        database.runtimeToolExecutionDao().findByKey(idempotencyKey)?.let { existing ->
            check(existing.canonicalInputDigest == canonicalInputDigest) { "idempotency key payload conflict" }
            return@withTransaction existing
        }
        check(run.status == RuntimeRunStatus.EXECUTING.name) { "REMOTE_TOOL_RUN_NOT_EXECUTING" }
        val attemptId = requireNotNull(run.activeAttemptId)
        val execution = RuntimeToolExecutionEntity(
            executionId = "exec-${sha256(idempotencyKey).take(32)}", runId = runId,
            logicalStepId = logicalStepId, toolName = toolName, toolSpecVersion = toolSpecVersion,
            canonicalInputDigest = canonicalInputDigest, idempotencyKey = idempotencyKey,
            providerCallId = providerCallId, attemptId = attemptId, status = "SUCCEEDED",
            resultRef = "result-${sha256(safeResultJson).take(24)}", safeResultJson = safeResultJson,
            fencingEpoch = fencingEpoch, createdAtEpochMs = nowEpochMs, updatedAtEpochMs = nowEpochMs,
        )
        database.runtimeToolExecutionDao().insert(execution)
        database.runtimeAttemptDao().listByRunId(runId).firstOrNull {
            it.attemptId == attemptId && it.status == "ACTIVE"
        }
            ?.let { check(database.runtimeAttemptDao().finish(attemptId, "SUCCEEDED", nowEpochMs) == 1) }
        val event = appendEventInTransaction(
            RuntimeEventDraft(
                "event-remote-tool-${sha256("$runId:$providerCallId").take(24)}", "ToolSucceeded",
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
                RuntimeRunStatus.EXECUTING.name,
                RuntimeRunStatus.OBSERVING.name,
                event.sequence,
                nowEpochMs,
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
}
