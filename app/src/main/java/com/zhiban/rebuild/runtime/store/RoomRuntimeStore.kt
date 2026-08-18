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

data class RuntimeCommandReceipt(val commandId: String, val runId: String, val acceptedSequence: Long)
data class AcceptedRuntimeCommand(val inserted: Boolean, val receipt: RuntimeCommandReceipt)
data class SessionClaim(val claimed: Boolean, val leaseEpoch: Long, val leaseExpiresAtEpochMs: Long?)
data class RuntimeRecoverySnapshot(
    val run: RuntimeRunEntity,
    val attempts: List<RuntimeAttemptEntity>,
    val events: List<RuntimeEventEntity>,
    val projection: RuntimeProjectionEntity?,
)
data class RuntimeRecoveryHandle(
    val sessionId: String,
    val ownerId: String,
    val leaseEpoch: Long,
    val leaseExpiresAtEpochMs: Long,
    val snapshot: RuntimeRecoverySnapshot,
)
data class ProjectionSnapshotRecord(
    val sessionId: String,
    val projectionName: String,
    val lastAppliedSequence: Long,
    val currentRevision: Long,
    val snapshotSchemaVersion: Int,
    val snapshotProducerVersion: String,
    val snapshotJson: String?,
)

data class SessionConversationContext(val summary: String?, val recentTurns: List<RuntimeConversationTurnEntity>)

data class RuntimeEventDraft(
    val eventId: String,
    val eventType: String,
    val sessionId: String,
    val runId: String,
    val attemptId: String?,
    val causationId: String?,
    val correlationId: String,
    val payloadJson: String,
    val createdAtEpochMs: Long,
)

data class AttemptStartRequest(val attemptId: String, val runId: String, val ordinal: Int, val ownerId: String, val fencingEpoch: Long, val nowEpochMs: Long)

class FencingRejectedException(message: String) : IllegalStateException(message)
class CommandConflictException(message: String) : IllegalStateException(message)

internal class RoomRuntimeStore(internal val database: AgentDatabase, internal val producerVersion: String) {
    private val scheduleProjection = RoomScheduleProjectionWriter(database)
    internal fun estimateTurnTokens(value: String): Int = (value.toByteArray().size / 4 + 1).coerceAtLeast(1)
    suspend fun nextProcessableCommand(nowEpochMs: Long, ownerId: String): RuntimeCommandInboxEntity? =
        database.runtimeCommandInboxDao().nextProcessable(nowEpochMs, ownerId)

    fun observeWorkCount() = database.runtimeCommandInboxDao().observeWorkCount()
    suspend fun nextForeignLeaseExpiry(ownerId: String, nowEpochMs: Long) = database.runtimeCommandInboxDao().nextForeignLeaseExpiry(ownerId, nowEpochMs)

    suspend fun nextRecoverableLeaseExpiry(nowEpochMs: Long) = database.runtimeSessionDao().nextRecoverableLeaseExpiry(nowEpochMs)

    suspend fun readRunInput(runId: String, nowEpochMs: Long): String? = database.withTransaction {
        database.runtimeRunInputDao().deleteExpired(nowEpochMs)
        database.runtimeRunInputDao().findByRunId(runId)?.rawText
    }

    internal val approvals = RoomApprovalStore(
        database = database,
        producerVersion = producerVersion,
        requireActiveLease = ::requireActiveLease,
        appendEventInTransaction = ::appendEventInTransaction,
        scheduleJson = scheduleProjection::scheduleJson,
        putScheduleFact = scheduleProjection::putScheduleFact,
        completeApprovedRemoteTool = ::completeApprovedRemoteTool,
    )

    suspend fun requestScheduleApproval(
        call: ScheduleCreateToolCall,
        sessionId: String,
        runId: String,
        attemptId: String,
        ownerId: String,
        fencingEpoch: Long,
        nowEpochMs: Long,
    ): Boolean = approvals.requestScheduleApproval(call, sessionId, runId, attemptId, ownerId, fencingEpoch, nowEpochMs)

    suspend fun requestMemoryApproval(
        call: MemoryRememberToolCall,
        sessionId: String,
        runId: String,
        attemptId: String,
        ownerId: String,
        fencingEpoch: Long,
        nowEpochMs: Long,
    ): Boolean = approvals.requestMemoryApproval(call, sessionId, runId, attemptId, ownerId, fencingEpoch, nowEpochMs)

    suspend fun requestMemoryDeleteApproval(
        payloadJson: String,
        providerCallId: String,
        sessionId: String,
        runId: String,
        attemptId: String,
        ownerId: String,
        fencingEpoch: Long,
        nowEpochMs: Long,
    ): Boolean = approvals.requestMemoryDeleteApproval(
        payloadJson,
        providerCallId,
        sessionId,
        runId,
        attemptId,
        ownerId,
        fencingEpoch,
        nowEpochMs,
    )

    suspend fun completeApprovedMemoryDelete(plan: JsonObject, ownerId: String, fencingEpoch: Long, nowEpochMs: Long): RuntimeToolExecutionEntity =
        approvals.completeApprovedMemoryDelete(plan, ownerId, fencingEpoch, nowEpochMs)

    suspend fun requestContactApproval(
        call: ContactCreateCandidateCall,
        stagedPayloadJson: String,
        displayName: String,
        sessionId: String,
        runId: String,
        attemptId: String,
        ownerId: String,
        fencingEpoch: Long,
        nowEpochMs: Long,
    ): Boolean = approvals.requestContactApproval(call, stagedPayloadJson, displayName, sessionId, runId, attemptId, ownerId, fencingEpoch, nowEpochMs)

    suspend fun requestContactProfileApproval(
        call: com.zhiban.rebuild.runtime.governance.ContactProfileCandidateCall,
        stagedPayloadJson: String,
        displayName: String,
        sessionId: String,
        runId: String,
        attemptId: String,
        ownerId: String,
        fencingEpoch: Long,
        nowEpochMs: Long,
    ): Boolean = approvals.requestContactProfileApproval(call, stagedPayloadJson, displayName, sessionId, runId, attemptId, ownerId, fencingEpoch, nowEpochMs)

    suspend fun requestContactIdentityResolutionApproval(
        call: com.zhiban.rebuild.runtime.governance.ContactIdentityResolutionCall,
        visibleHandle: String,
        platform: String,
        contactName: String,
        sessionId: String,
        runId: String,
        attemptId: String,
        ownerId: String,
        fencingEpoch: Long,
        nowEpochMs: Long,
    ): Boolean = approvals.requestContactIdentityResolutionApproval(
        call,
        visibleHandle,
        platform,
        contactName,
        sessionId,
        runId,
        attemptId,
        ownerId,
        fencingEpoch,
        nowEpochMs,
    )

    suspend fun requestRelationshipApproval(
        call: RelationshipCandidateCall,
        sessionId: String,
        runId: String,
        attemptId: String,
        ownerId: String,
        fencingEpoch: Long,
        nowEpochMs: Long,
    ): Boolean = approvals.requestRelationshipApproval(call, sessionId, runId, attemptId, ownerId, fencingEpoch, nowEpochMs)

    suspend fun requestRemoteMcpApproval(
        payloadJson: String,
        providerCallId: String,
        sessionId: String,
        runId: String,
        attemptId: String,
        ownerId: String,
        fencingEpoch: Long,
        nowEpochMs: Long,
    ): Boolean = approvals.requestRemoteMcpApproval(
        payloadJson,
        providerCallId,
        sessionId,
        runId,
        attemptId,
        ownerId,
        fencingEpoch,
        nowEpochMs,
    )

    suspend fun requestCommunicationApproval(
        payloadJson: String,
        providerCallId: String,
        sessionId: String,
        runId: String,
        attemptId: String,
        ownerId: String,
        fencingEpoch: Long,
        nowEpochMs: Long,
    ): Boolean = approvals.requestCommunicationApproval(
        payloadJson,
        providerCallId,
        sessionId,
        runId,
        attemptId,
        ownerId,
        fencingEpoch,
        nowEpochMs,
    )

    suspend fun requestCalendarMutationApproval(
        payloadJson: String,
        providerCallId: String,
        sessionId: String,
        runId: String,
        attemptId: String,
        ownerId: String,
        fencingEpoch: Long,
        nowEpochMs: Long,
    ): Boolean = approvals.requestCalendarMutationApproval(
        payloadJson,
        providerCallId,
        sessionId,
        runId,
        attemptId,
        ownerId,
        fencingEpoch,
        nowEpochMs,
    )

    suspend fun requestCrmMutationApproval(
        payloadJson: String,
        providerCallId: String,
        sessionId: String,
        runId: String,
        attemptId: String,
        ownerId: String,
        fencingEpoch: Long,
        nowEpochMs: Long,
    ): Boolean = approvals.requestCrmMutationApproval(
        payloadJson,
        providerCallId,
        sessionId,
        runId,
        attemptId,
        ownerId,
        fencingEpoch,
        nowEpochMs,
    )

    suspend fun requestReversibleWriteApproval(
        payloadJson: String,
        providerCallId: String,
        sessionId: String,
        runId: String,
        attemptId: String,
        ownerId: String,
        fencingEpoch: Long,
        nowEpochMs: Long,
    ): Boolean = approvals.requestReversibleWriteApproval(
        payloadJson,
        providerCallId,
        sessionId,
        runId,
        attemptId,
        ownerId,
        fencingEpoch,
        nowEpochMs,
    )

    suspend fun completeApprovedCalendarMutation(plan: JsonObject, ownerId: String, fencingEpoch: Long, nowEpochMs: Long): RuntimeToolExecutionEntity =
        approvals.completeApprovedCalendarMutation(plan, ownerId, fencingEpoch, nowEpochMs)

    suspend fun pendingToolPlan(runId: String, nowEpochMs: Long = System.currentTimeMillis()): String? = approvals.pendingToolPlan(runId, nowEpochMs)

    suspend fun stagedMemoryContent(candidateId: String, nowEpochMs: Long): String? = approvals.stagedMemoryContent(candidateId, nowEpochMs)

    suspend fun stagedApprovalContent(stagedRef: String, nowEpochMs: Long = System.currentTimeMillis()): StagedApprovalContent? =
        approvals.stagedApprovalContent(stagedRef, nowEpochMs)

    suspend fun latestToolExecution(runId: String): RuntimeToolExecutionEntity? = approvals.latestToolExecution(runId)

    suspend fun completedToolNames(runId: String): Set<String> = approvals.completedToolNames(runId)

    suspend fun completedToolResults(runId: String): List<Pair<String, String>> = approvals.completedToolResults(runId)

    suspend fun toolProposalCount(runId: String, toolName: String): Int = approvals.toolProposalCount(runId, toolName)

    suspend fun totalToolInvocationCount(runId: String): Int = approvals.totalToolInvocationCount(runId)

    suspend fun recentFeedback(sessionId: String, limit: Int = 8): List<String> = approvals.recentFeedback(sessionId, limit)

    suspend fun recentConversation(sessionId: String, excludeRunId: String, limit: Int = 12): List<RuntimeConversationTurnEntity> =
        approvals.recentConversation(sessionId, excludeRunId, limit)

    suspend fun conversationContext(sessionId: String, excludeRunId: String, recentLimit: Int = 12, scanLimit: Int = 80): SessionConversationContext =
        approvals.conversationContext(sessionId, excludeRunId, recentLimit, scanLimit)

    suspend fun saveAssistantTurn(sessionId: String, runId: String, content: String, nowEpochMs: Long) =
        approvals.saveAssistantTurn(sessionId, runId, content, nowEpochMs)

    internal val commands = RoomCommandStore(
        database = database,
        producerVersion = producerVersion,
        requireActiveLease = ::requireActiveLease,
        allocateSequence = ::allocateSequence,
        currentRevision = ::currentRevision,
        appendEventInTransaction = ::appendEventInTransaction,
    )

    suspend fun consumeRunInput(runId: String): Boolean = commands.consumeRunInput(runId)

    suspend fun clearStagedInputs(nowEpochMs: Long) = commands.clearStagedInputs(nowEpochMs)

    suspend fun acceptExternalCommand(command: RuntimeUiCommand, nowEpochMs: Long): CommandReceipt = commands.acceptExternalCommand(command, nowEpochMs)

    suspend fun projectionSnapshot(sessionId: String, projectionName: String): ProjectionSnapshotRecord = commands.projectionSnapshot(sessionId, projectionName)

    fun observeEventsAfter(sessionId: String, afterSequenceExclusive: Long) = commands.observeEventsAfter(sessionId, afterSequenceExclusive)

    suspend fun assistantTurnText(sessionId: String, runId: String): String? = database.runtimeConversationTurnDao().assistantTurnContent(sessionId, runId)

    suspend fun stagedCandidateContent(candidateId: String): String? = database.stagedMemoryCandidateDao().find(candidateId)?.content

    suspend fun acceptStart(commandId: String, sessionId: String, runId: String, payloadJson: String, nowEpochMs: Long): AcceptedRuntimeCommand =
        database.withTransaction {
            database.runtimeCommandInboxDao().find(commandId)?.let { existing ->
                if (existing.commandType != "Start" || existing.sessionId != sessionId || existing.runId != runId ||
                    existing.payloadJson != payloadJson
                ) {
                    throw CommandConflictException("commandId is already bound to different input")
                }
                return@withTransaction AcceptedRuntimeCommand(false, decodeReceipt(existing.receiptJson))
            }
            database.runtimeSessionDao().insert(RuntimeSessionEntity(sessionId = sessionId, updatedAtEpochMs = nowEpochMs))
            database.runtimeRunDao().insert(
                RuntimeRunEntity(
                    runId = runId,
                    sessionId = sessionId,
                    schemaVersion = RUNTIME_SCHEMA_VERSION,
                    status = RuntimeRunStatus.RECEIVED.name,
                    budgetJson = "{}",
                    createdAtEpochMs = nowEpochMs,
                    updatedAtEpochMs = nowEpochMs,
                ),
            )
            val sequence = allocateSequence(sessionId, nowEpochMs)
            val receipt = RuntimeCommandReceipt(commandId, runId, sequence)
            check(
                database.runtimeCommandInboxDao().insert(
                    RuntimeCommandInboxEntity(
                        commandId = commandId,
                        schemaVersion = RUNTIME_SCHEMA_VERSION,
                        commandType = "Start",
                        sessionId = sessionId,
                        runId = runId,
                        correlationId = commandId,
                        payloadJson = payloadJson,
                        status = RuntimeCommandStatus.PENDING.name,
                        receiptJson = encodeReceipt(receipt),
                        createdAtEpochMs = nowEpochMs,
                        updatedAtEpochMs = nowEpochMs,
                    ),
                ) != -1L,
            )
            database.runtimeEventDao().insert(
                RuntimeEventEntity(
                    eventId = "event-$commandId",
                    schemaVersion = RUNTIME_SCHEMA_VERSION,
                    eventType = "RunReceived",
                    sessionId = sessionId,
                    runId = runId,
                    attemptId = null,
                    sequence = sequence,
                    causationId = commandId,
                    correlationId = commandId,
                    producerVersion = producerVersion,
                    payloadJson = "{}",
                    createdAtEpochMs = nowEpochMs,
                    fencingEpoch = 0,
                ),
            )
            AcceptedRuntimeCommand(true, receipt)
        }

    suspend fun appendEvent(draft: RuntimeEventDraft, ownerId: String?, fencingEpoch: Long, nowEpochMs: Long): RuntimeEventEntity = database.withTransaction {
        val session = requireNotNull(database.runtimeSessionDao().find(draft.sessionId))
        val bootstrap = session.leaseOwnerId == null && ownerId == null && fencingEpoch == 0L
        val activeOwner =
            session.leaseOwnerId == ownerId && session.leaseEpoch == fencingEpoch &&
                (session.leaseExpiresAtEpochMs ?: Long.MIN_VALUE) > nowEpochMs
        if (!bootstrap && !activeOwner) {
            throw FencingRejectedException("writer does not hold a current session lease")
        }
        appendEventInTransaction(draft, fencingEpoch)
    }

    suspend fun appendProviderEventOnce(draft: RuntimeEventDraft, ownerId: String, fencingEpoch: Long, nowEpochMs: Long): RuntimeEventEntity =
        database.withTransaction {
            requireActiveLease(draft.sessionId, ownerId, fencingEpoch, nowEpochMs)
            val run = requireNotNull(database.runtimeRunDao().find(draft.runId))
            check(run.status == RuntimeRunStatus.INFERENCING.name && run.activeAttemptId == draft.attemptId) {
                "STALE_PROVIDER_EVENT"
            }
            database.runtimeEventDao().find(draft.eventId)?.also { existing ->
                check(
                    existing.eventType == draft.eventType && existing.runId == draft.runId &&
                        existing.attemptId == draft.attemptId && existing.payloadJson == draft.payloadJson,
                ) {
                    "PROVIDER_REPLAY_CONFLICT"
                }
                return@withTransaction existing
            }
            appendEventInTransaction(draft, fencingEpoch)
        }

    suspend fun appendRuntimeEventOnce(draft: RuntimeEventDraft, ownerId: String, fencingEpoch: Long, nowEpochMs: Long): RuntimeEventEntity =
        database.withTransaction {
            requireActiveLease(draft.sessionId, ownerId, fencingEpoch, nowEpochMs)
            database.runtimeEventDao().find(draft.eventId)?.also { existing ->
                check(
                    existing.eventType == draft.eventType && existing.runId == draft.runId &&
                        existing.attemptId == draft.attemptId && existing.payloadJson == draft.payloadJson,
                ) {
                    "RUNTIME_REPLAY_CONFLICT"
                }
                return@withTransaction existing
            }
            appendEventInTransaction(draft, fencingEpoch)
        }

    suspend fun finishProviderRun(
        runId: String,
        targetStatus: String,
        eventType: String,
        safePayloadJson: String,
        attemptStatus: String,
        ownerId: String,
        fencingEpoch: Long,
        nowEpochMs: Long,
        deleteInput: Boolean,
    ) = database.withTransaction {
        finishProviderRunInTransaction(
            runId, targetStatus, eventType, safePayloadJson, attemptStatus,
            ownerId, fencingEpoch, nowEpochMs, deleteInput,
        )
    }

    suspend fun completeProviderRunWithAssistantTurn(runId: String, content: String, ownerId: String, fencingEpoch: Long, nowEpochMs: Long) =
        database.withTransaction {
            val run = requireNotNull(database.runtimeRunDao().find(runId))
            approvals.saveAssistantTurn(run.sessionId, runId, content, nowEpochMs)
            finishProviderRunInTransaction(
                runId, RuntimeRunStatus.SUCCEEDED.name, "RunCompleted", "{}", "SUCCEEDED",
                ownerId, fencingEpoch, nowEpochMs, deleteInput = true,
            )
        }

    private suspend fun finishProviderRunInTransaction(
        runId: String,
        targetStatus: String,
        eventType: String,
        safePayloadJson: String,
        attemptStatus: String,
        ownerId: String,
        fencingEpoch: Long,
        nowEpochMs: Long,
        deleteInput: Boolean,
    ) {
        val run = requireNotNull(database.runtimeRunDao().find(runId))
        requireActiveLease(run.sessionId, ownerId, fencingEpoch, nowEpochMs)
        if (run.status == targetStatus) return
        check(run.status == RuntimeRunStatus.INFERENCING.name) { "PROVIDER_RUN_NOT_INFERENCING" }
        val attemptId = requireNotNull(run.activeAttemptId)
        val eventId = "event-provider-$attemptId-terminal-$targetStatus"
        val existing = database.runtimeEventDao().find(eventId)
        val event = existing ?: appendEventInTransaction(
            RuntimeEventDraft(eventId, eventType, run.sessionId, runId, attemptId, attemptId, runId, safePayloadJson, nowEpochMs),
            fencingEpoch,
        )
        check(
            database.runtimeRunDao().transition(
                runId,
                RuntimeRunStatus.INFERENCING.name,
                targetStatus,
                event.sequence,
                nowEpochMs,
            ) ==
                1,
        )
        check(database.runtimeAttemptDao().finish(attemptId, attemptStatus, nowEpochMs) == 1)
        if (deleteInput) database.runtimeRunInputDao().deleteByRunId(runId)
    }

    suspend fun cancelProviderRun(runId: String, ownerId: String, fencingEpoch: Long, nowEpochMs: Long) = database.withTransaction {
        val run = requireNotNull(database.runtimeRunDao().find(runId))
        requireActiveLease(run.sessionId, ownerId, fencingEpoch, nowEpochMs)
        if (run.status == RuntimeRunStatus.CANCELLED.name) return@withTransaction
        check(run.status == RuntimeRunStatus.CANCEL_REQUESTED.name)
        val attemptId = run.activeAttemptId
        val cancellationIdentity = attemptId ?: runId
        val event = appendEventInTransaction(
            RuntimeEventDraft(
                "event-provider-$cancellationIdentity-cancelled",
                "RunCancelled",
                run.sessionId,
                runId,
                attemptId,
                attemptId,
                runId,
                "{}",
                nowEpochMs,
            ),
            fencingEpoch,
        )
        check(
            database.runtimeRunDao().transition(
                runId,
                RuntimeRunStatus.CANCEL_REQUESTED.name,
                RuntimeRunStatus.CANCELLED.name,
                event.sequence,
                nowEpochMs,
            ) ==
                1,
        )
        attemptId?.let { database.runtimeAttemptDao().finish(it, "CANCELLED", nowEpochMs) }
        database.runtimeRunInputDao().deleteByRunId(runId)
    }

    suspend fun supersedeAttemptAndStart(
        oldAttemptId: String,
        newAttemptId: String,
        runId: String,
        ordinal: Int,
        ownerId: String,
        fencingEpoch: Long,
        nowEpochMs: Long,
    ) = database.withTransaction {
        val run = requireNotNull(database.runtimeRunDao().find(runId))
        requireActiveLease(run.sessionId, ownerId, fencingEpoch, nowEpochMs)
        check(run.status == RuntimeRunStatus.INFERENCING.name && run.activeAttemptId == oldAttemptId)
        check(database.runtimeAttemptDao().finish(oldAttemptId, "SUPERSEDED", nowEpochMs) == 1)
        val superseded = appendEventInTransaction(
            RuntimeEventDraft(
                "event-provider-$oldAttemptId-superseded", "ProviderAttemptSuperseded", run.sessionId, runId,
                oldAttemptId, oldAttemptId, runId, "{}", nowEpochMs,
            ),
            fencingEpoch,
        )
        database.runtimeAttemptDao().insert(
            RuntimeAttemptEntity(newAttemptId, runId, ordinal, "ACTIVE", nowEpochMs, nowEpochMs),
        )
        val started = appendEventInTransaction(
            RuntimeEventDraft(
                "event-attempt-$newAttemptId", "ProviderAttemptStarted", run.sessionId, runId,
                newAttemptId, newAttemptId, runId, "{}", nowEpochMs,
            ),
            fencingEpoch,
        )
        check(database.runtimeRunDao().startAttempt(runId, newAttemptId, started.sequence, nowEpochMs) == 1)
        check(started.sequence > superseded.sequence)
    }

    suspend fun tryClaimSession(sessionId: String, ownerId: String, nowEpochMs: Long, leaseDurationMs: Long): SessionClaim = database.withTransaction {
        val expiresAt = Math.addExact(nowEpochMs, leaseDurationMs)
        val renewed = database.runtimeSessionDao().renew(sessionId, ownerId, nowEpochMs, expiresAt) == 1
        val acquired = if (renewed) {
            false
        } else {
            database.runtimeSessionDao().acquireExpired(sessionId, ownerId, nowEpochMs, expiresAt) ==
                1
        }
        val current = requireNotNull(database.runtimeSessionDao().find(sessionId))
        SessionClaim(renewed || acquired, current.leaseEpoch, current.leaseExpiresAtEpochMs)
    }

    suspend fun claimSession(sessionId: String, ownerId: String, nowEpochMs: Long, leaseDurationMs: Long): RuntimeSessionEntity {
        val claim = tryClaimSession(sessionId, ownerId, nowEpochMs, leaseDurationMs)
        check(claim.claimed) { "session lease is held by another owner" }
        return requireNotNull(database.runtimeSessionDao().find(sessionId))
    }

    suspend fun claimCommand(commandId: String, ownerId: String, leaseEpoch: Long, nowEpochMs: Long): Boolean = database.withTransaction {
        val command = requireNotNull(database.runtimeCommandInboxDao().find(commandId))
        val session = requireNotNull(database.runtimeSessionDao().find(command.sessionId))
        if (session.leaseOwnerId != ownerId || session.leaseEpoch != leaseEpoch ||
            (session.leaseExpiresAtEpochMs ?: Long.MIN_VALUE) <= nowEpochMs
        ) {
            throw FencingRejectedException("command owner does not hold the active lease")
        }
        database.runtimeCommandInboxDao().claim(commandId, ownerId, leaseEpoch, nowEpochMs) == 1
    }

    suspend fun appendObservationEventOnce(draft: RuntimeEventDraft, ownerId: String, fencingEpoch: Long, nowEpochMs: Long): RuntimeEventEntity =
        database.withTransaction {
            requireActiveLease(draft.sessionId, ownerId, fencingEpoch, nowEpochMs)
            val run = requireNotNull(database.runtimeRunDao().find(draft.runId))
            check(run.status == RuntimeRunStatus.OBSERVING.name && run.activeAttemptId == draft.attemptId) {
                "STALE_OBSERVATION_EVENT"
            }
            database.runtimeEventDao().find(draft.eventId)?.also { existing ->
                check(
                    existing.eventType == draft.eventType && existing.runId == draft.runId &&
                        existing.attemptId == draft.attemptId && existing.payloadJson == draft.payloadJson,
                ) {
                    "OBSERVATION_REPLAY_CONFLICT"
                }
                return@withTransaction existing
            }
            appendEventInTransaction(draft, fencingEpoch)
        }

    suspend fun finishObservationRun(
        runId: String,
        targetStatus: RuntimeRunStatus,
        eventType: String,
        safePayloadJson: String,
        ownerId: String,
        fencingEpoch: Long,
        nowEpochMs: Long,
    ) = database.withTransaction {
        finishObservationRunInTransaction(
            runId,
            targetStatus,
            eventType,
            safePayloadJson,
            ownerId,
            fencingEpoch,
            nowEpochMs,
        )
    }

    suspend fun failBrokenObservationRecovery(runId: String, ownerId: String, fencingEpoch: Long, nowEpochMs: Long) = database.withTransaction {
        val run = requireNotNull(database.runtimeRunDao().find(runId))
        requireActiveLease(run.sessionId, ownerId, fencingEpoch, nowEpochMs)
        check(run.status == RuntimeRunStatus.OBSERVING.name)
        val payload = "{\"errorCode\":\"RUNTIME_INTERRUPTED\"}"
        val eventId = "event-observation-$runId-recovery-missing-execution"
        val event = database.runtimeEventDao().find(eventId) ?: appendEventInTransaction(
            RuntimeEventDraft(
                eventId,
                "RunFailedRetryable",
                run.sessionId,
                runId,
                run.activeAttemptId,
                run.activeAttemptId,
                runId,
                payload,
                nowEpochMs,
            ),
            fencingEpoch,
        )
        check(
            database.runtimeRunDao().transition(
                runId,
                RuntimeRunStatus.OBSERVING.name,
                RuntimeRunStatus.FAILED_RETRYABLE.name,
                event.sequence,
                nowEpochMs,
            ) == 1,
        )
        run.activeAttemptId?.let { attemptId ->
            database.runtimeAttemptDao().listByRunId(runId).firstOrNull {
                it.attemptId == attemptId && it.status == "ACTIVE"
            }?.let { check(database.runtimeAttemptDao().finish(attemptId, "FAILED", nowEpochMs) == 1) }
        }
    }

    suspend fun completeObservationWithAssistantTurn(
        runId: String,
        content: String,
        safePayloadJson: String,
        ownerId: String,
        fencingEpoch: Long,
        nowEpochMs: Long,
    ) = database.withTransaction {
        val run = requireNotNull(database.runtimeRunDao().find(runId))
        approvals.saveAssistantTurn(run.sessionId, runId, content, nowEpochMs)
        finishObservationRunInTransaction(
            runId,
            RuntimeRunStatus.SUCCEEDED,
            "RunCompleted",
            safePayloadJson,
            ownerId,
            fencingEpoch,
            nowEpochMs,
        )
    }

    private suspend fun finishObservationRunInTransaction(
        runId: String,
        targetStatus: RuntimeRunStatus,
        eventType: String,
        safePayloadJson: String,
        ownerId: String,
        fencingEpoch: Long,
        nowEpochMs: Long,
    ) {
        val run = requireNotNull(database.runtimeRunDao().find(runId))
        requireActiveLease(run.sessionId, ownerId, fencingEpoch, nowEpochMs)
        check(run.status == RuntimeRunStatus.OBSERVING.name)
        val attemptId = requireNotNull(run.activeAttemptId)
        val event = appendEventInTransaction(
            RuntimeEventDraft(
                "event-observation-$attemptId-terminal", eventType, run.sessionId, runId,
                attemptId, attemptId, runId, safePayloadJson, nowEpochMs,
            ),
            fencingEpoch,
        )
        check(
            database.runtimeRunDao().transition(
                runId,
                RuntimeRunStatus.OBSERVING.name,
                targetStatus.name,
                event.sequence,
                nowEpochMs,
            ) ==
                1,
        )
        check(
            database.runtimeAttemptDao().finish(
                attemptId,
                if (targetStatus ==
                    RuntimeRunStatus.SUCCEEDED
                ) {
                    "SUCCEEDED"
                } else {
                    "FAILED"
                },
                nowEpochMs,
            ) ==
                1,
        )
        database.planDao().transitionRunStatus(runId, PLAN_STATUS_ACTIVE, PLAN_STATUS_TERMINAL, nowEpochMs)
        database.runtimeRunInputDao().deleteByRunId(runId)
        database.runtimeApprovalStagingDao().deleteByRunId(runId)
    }

    suspend fun finishExecutingRunFailure(
        runId: String,
        safeFailureCode: String,
        ownerId: String,
        fencingEpoch: Long,
        nowEpochMs: Long,
        retryable: Boolean = false,
    ) = database.withTransaction {
        val run = requireNotNull(database.runtimeRunDao().find(runId))
        requireActiveLease(run.sessionId, ownerId, fencingEpoch, nowEpochMs)
        check(run.status == RuntimeRunStatus.EXECUTING.name)
        val attemptId = requireNotNull(run.activeAttemptId)
        val payload = buildJsonObject { put("errorCode", safeFailureCode) }.toString()
        val event = appendEventInTransaction(
            RuntimeEventDraft(
                "event-execution-$attemptId-terminal",
                if (retryable) "RunFailedRetryable" else "RunFailedFinal",
                run.sessionId,
                runId,
                attemptId, attemptId, runId, payload, nowEpochMs,
            ),
            fencingEpoch,
        )
        check(
            database.runtimeRunDao().transition(
                runId,
                RuntimeRunStatus.EXECUTING.name,
                if (retryable) RuntimeRunStatus.FAILED_RETRYABLE.name else RuntimeRunStatus.FAILED_FINAL.name,
                event.sequence,
                nowEpochMs,
            ) ==
                1,
        )
        database.runtimeAttemptDao().listByRunId(runId).firstOrNull {
            it.attemptId == attemptId && it.status == "ACTIVE"
        }
            ?.let { check(database.runtimeAttemptDao().finish(attemptId, "FAILED", nowEpochMs) == 1) }
        database.planDao().transitionRunStatus(runId, PLAN_STATUS_ACTIVE, PLAN_STATUS_TERMINAL, nowEpochMs)
        if (!retryable) database.runtimeRunInputDao().deleteByRunId(runId)
        database.runtimeApprovalStagingDao().deleteByRunId(runId)
    }

    suspend fun transitionRun(
        runId: String,
        expectedStatus: String,
        targetStatus: String,
        eventType: String,
        attemptTerminalStatus: String?,
        ownerId: String,
        fencingEpoch: Long,
        nowEpochMs: Long,
    ) = database.withTransaction {
        val run = requireNotNull(database.runtimeRunDao().find(runId))
        requireActiveLease(run.sessionId, ownerId, fencingEpoch, nowEpochMs)
        val event = appendEventInTransaction(
            RuntimeEventDraft(
                "event-$runId-$eventType-$nowEpochMs", eventType, run.sessionId, runId,
                run.activeAttemptId, run.activeAttemptId, runId, "{}", nowEpochMs,
            ),
            fencingEpoch,
        )
        check(
            database.runtimeRunDao().transition(runId, expectedStatus, targetStatus, event.sequence, nowEpochMs) == 1,
        ) {
            "illegal or stale run transition"
        }
        if (attemptTerminalStatus != null) {
            val attemptId = requireNotNull(run.activeAttemptId)
            check(database.runtimeAttemptDao().finish(attemptId, attemptTerminalStatus, nowEpochMs) == 1) {
                "attempt is not active"
            }
        }
        event
    }

    suspend fun claimRecoverable(ownerId: String, nowEpochMs: Long, leaseDurationMs: Long, projectionName: String): List<RuntimeRecoveryHandle> =
        database.withTransaction {
            val expiresAt = Math.addExact(nowEpochMs, leaseDurationMs)
            database.runtimeSessionDao().findRecoverableSessionIds(nowEpochMs).flatMap { sessionId ->
                if (database.runtimeSessionDao().acquireExpired(sessionId, ownerId, nowEpochMs, expiresAt) !=
                    1
                ) {
                    return@flatMap emptyList()
                }
                val session = requireNotNull(database.runtimeSessionDao().find(sessionId))
                database.runtimeRunDao().listNonTerminalBySession(sessionId).map { run ->
                    RuntimeRecoveryHandle(
                        sessionId = sessionId,
                        ownerId = ownerId,
                        leaseEpoch = session.leaseEpoch,
                        leaseExpiresAtEpochMs = requireNotNull(session.leaseExpiresAtEpochMs),
                        snapshot = RuntimeRecoverySnapshot(
                            run = run,
                            attempts = database.runtimeAttemptDao().listByRunId(run.runId),
                            events = database.runtimeEventDao().listByRunId(run.runId),
                            projection = database.runtimeProjectionDao().find(
                                projectionName,
                                sessionId,
                            )?.decodedForRuntime(),
                        ),
                    )
                }
            }
        }

    suspend fun saveProjection(
        projectionName: String,
        sessionId: String,
        consumedSequence: Long,
        snapshotJson: String,
        ownerId: String,
        fencingEpoch: Long,
        nowEpochMs: Long,
    ): Boolean = database.withTransaction {
        requireActiveLease(sessionId, ownerId, fencingEpoch, nowEpochMs)
        val envelopeJson = RuntimeProjectionEnvelopeCodec.encode(snapshotJson, producerVersion)
        val inserted =
            database.runtimeProjectionDao().insert(
                RuntimeProjectionEntity(projectionName, sessionId, consumedSequence, envelopeJson, nowEpochMs),
            ) !=
                -1L
        inserted ||
            database.runtimeProjectionDao().advance(
                projectionName,
                sessionId,
                consumedSequence,
                envelopeJson,
                nowEpochMs,
            ) ==
            1
    }

    suspend fun runById(runId: String): RuntimeRunEntity? = database.runtimeRunDao().find(runId)

    suspend fun recoverySnapshot(runId: String, projectionName: String): RuntimeRecoverySnapshot = database.withTransaction {
        val run = requireNotNull(database.runtimeRunDao().find(runId))
        RuntimeRecoverySnapshot(
            run = run,
            attempts = database.runtimeAttemptDao().listByRunId(runId),
            events = database.runtimeEventDao().listByRunId(runId),
            projection = database.runtimeProjectionDao().find(projectionName, run.sessionId)?.decodedForRuntime(),
        )
    }

    suspend fun completeCommand(commandId: String, resultJson: String, ownerId: String, fencingEpoch: Long, nowEpochMs: Long) = database.withTransaction {
        val command = requireNotNull(database.runtimeCommandInboxDao().find(commandId))
        requireActiveLease(command.sessionId, ownerId, fencingEpoch, nowEpochMs)
        check(database.runtimeCommandInboxDao().complete(commandId, resultJson, nowEpochMs) == 1) {
            "command is not completable"
        }
    }

    suspend fun failCommand(commandId: String, resultJson: String, ownerId: String, fencingEpoch: Long, nowEpochMs: Long) = database.withTransaction {
        val command = requireNotNull(database.runtimeCommandInboxDao().find(commandId))
        requireActiveLease(command.sessionId, ownerId, fencingEpoch, nowEpochMs)
        check(database.runtimeCommandInboxDao().fail(commandId, resultJson, nowEpochMs) == 1) {
            "command is not fail-able"
        }
    }

    suspend fun commandResult(commandId: String): String? = database.runtimeCommandInboxDao().find(commandId)?.resultJson

    internal val tools = RoomToolExecutionStore(
        database = database,
        requireActiveLease = ::requireActiveLease,
        appendEventInTransaction = ::appendEventInTransaction,
    )

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
    ) = tools.recordToolSuccess(
        executionId, runId, logicalStepId, toolName, toolSpecVersion, canonicalInputDigest,
        idempotencyKey, resultRef, safeResultJson, ownerId, fencingEpoch, nowEpochMs,
    )

    suspend fun toolResult(idempotencyKey: String): RuntimeToolExecutionEntity? = tools.toolResult(idempotencyKey)

    suspend fun completeApprovedRemoteTool(request: ApprovedToolExecutionRequest): RuntimeToolExecutionEntity = tools.completeApprovedRemoteTool(request)

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
    ): RuntimeToolExecutionEntity =
        tools.completeReadOnlyTool(runId, providerCallId, toolName, toolSpecVersion, argumentsDigest, safeResultJson, ownerId, fencingEpoch, nowEpochMs)

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
    ): RuntimeToolExecutionEntity = tools.recordInvalidToolArguments(
        runId,
        providerCallId,
        toolName,
        argumentsDigest,
        safeResultJson,
        terminal,
        ownerId,
        fencingEpoch,
        nowEpochMs,
    )

    suspend fun recoverableSessionIds(nowEpochMs: Long): List<String> = database.runtimeSessionDao().findRecoverableSessionIds(nowEpochMs)

    suspend fun eventsAfter(sessionId: String, sequence: Long): List<RuntimeEventEntity> = database.runtimeEventDao().listAfter(sessionId, sequence)

    internal suspend fun requireActiveLease(sessionId: String, ownerId: String, fencingEpoch: Long, nowEpochMs: Long) {
        val session = requireNotNull(database.runtimeSessionDao().find(sessionId))
        if (session.leaseOwnerId != ownerId || session.leaseEpoch != fencingEpoch ||
            (session.leaseExpiresAtEpochMs ?: Long.MIN_VALUE) <= nowEpochMs
        ) {
            throw FencingRejectedException("writer does not hold a current session lease")
        }
    }

    internal suspend fun appendEventInTransaction(draft: RuntimeEventDraft, fencingEpoch: Long): RuntimeEventEntity {
        val sequence = allocateSequence(draft.sessionId, draft.createdAtEpochMs)
        return RuntimeEventEntity(
            draft.eventId, RUNTIME_SCHEMA_VERSION, draft.eventType, draft.sessionId, draft.runId,
            draft.attemptId, sequence, draft.causationId, draft.correlationId, producerVersion,
            draft.payloadJson, draft.createdAtEpochMs, fencingEpoch,
        ).also { database.runtimeEventDao().insert(it) }
    }

    private suspend fun allocateSequence(sessionId: String, nowEpochMs: Long): Long {
        val session = requireNotNull(database.runtimeSessionDao().find(sessionId))
        check(
            database.runtimeSessionDao().advanceSequence(
                sessionId,
                session.nextSequence,
                session.nextSequence + 1,
                nowEpochMs,
            ) ==
                1,
        )
        return session.nextSequence
    }

    private suspend fun currentRevision(sessionId: String): Long = database.runtimeSessionDao().find(sessionId)?.nextSequence?.minus(1) ?: 0

    private fun encodeReceipt(receipt: RuntimeCommandReceipt): String =
        listOf(receipt.commandId, receipt.runId, receipt.acceptedSequence.toString()).joinToString("|")

    private fun decodeReceipt(value: String?): RuntimeCommandReceipt {
        val parts = requireNotNull(value).split('|')
        return RuntimeCommandReceipt(parts[0], parts[1], parts[2].toLong())
    }
}
