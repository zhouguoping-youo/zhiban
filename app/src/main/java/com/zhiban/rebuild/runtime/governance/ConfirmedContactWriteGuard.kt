package com.zhiban.rebuild.runtime.governance

import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.runtime.spi.RuntimeRunStatus
import com.zhiban.rebuild.runtime.tool.ConfirmedToolExecutionContext
import com.zhiban.rebuild.runtime.tool.SafeToolResult
import com.zhiban.rebuild.runtime.tool.ToolConfirmation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class ConfirmedContactWriteStart(val attemptId: String, val sessionId: String, val nextSequence: Long, val replay: SafeToolResult?)

internal suspend fun AgentDatabase.validateConfirmedContactWrite(
    context: ConfirmedToolExecutionContext,
    proposalId: String,
    payloadRef: String,
    revision: Long,
    canonicalInputDigest: String,
    idempotencyKey: String,
    confirmation: ToolConfirmation,
): ConfirmedContactWriteStart {
    require(proposalId == confirmation.proposalId && payloadRef == confirmation.payloadRef)
    require(revision == confirmation.revision && canonicalInputDigest == confirmation.canonicalInputDigest)
    val run = requireNotNull(runtimeRunDao().find(context.runId))
    val attemptId = requireNotNull(run.activeAttemptId)
    check(run.status == RuntimeRunStatus.EXECUTING.name)
    val session = requireNotNull(runtimeSessionDao().find(run.sessionId))
    check(
        session.leaseOwnerId == context.ownerId &&
            session.leaseEpoch == context.fencingEpoch &&
            (session.leaseExpiresAtEpochMs ?: 0) > context.nowEpochMs,
    )
    val replay = runtimeToolExecutionDao().findByKey(idempotencyKey)?.let {
        SafeToolResult(requireNotNull(it.resultRef), requireNotNull(it.safeResultJson))
    }
    val approval = requireNotNull(runtimeEventDao().latestByType(context.runId, "ApprovalRequested"))
    val approvalJson = Json.parseToJsonElement(approval.payloadJson).jsonObject
    require(approvalJson["proposalId"]?.jsonPrimitive?.content == proposalId)
    require(approvalJson["payloadRef"]?.jsonPrimitive?.content == payloadRef)
    return ConfirmedContactWriteStart(attemptId, run.sessionId, session.nextSequence, replay)
}
