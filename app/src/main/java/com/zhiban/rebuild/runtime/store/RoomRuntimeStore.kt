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

internal class RoomRuntimeStore(private val database: AgentDatabase, private val producerVersion: String) {
    private val scheduleProjection = RoomScheduleProjectionWriter(database)
    private fun estimateTurnTokens(value: String): Int = (value.toByteArray().size / 4 + 1).coerceAtLeast(1)
    suspend fun nextProcessableCommand(nowEpochMs: Long, ownerId: String): RuntimeCommandInboxEntity? =
        database.runtimeCommandInboxDao().nextProcessable(nowEpochMs, ownerId)

    fun observeWorkCount() = database.runtimeCommandInboxDao().observeWorkCount()
    suspend fun nextForeignLeaseExpiry(ownerId: String, nowEpochMs: Long) = database.runtimeCommandInboxDao().nextForeignLeaseExpiry(ownerId, nowEpochMs)

    suspend fun nextRecoverableLeaseExpiry(nowEpochMs: Long) = database.runtimeSessionDao().nextRecoverableLeaseExpiry(nowEpochMs)

    suspend fun processClaimedCommand(commandId: String, ownerId: String, fencingEpoch: Long, nowEpochMs: Long): Boolean = database.withTransaction {
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

    private data class ClaimedStartFailureCtx(
        val command: RuntimeCommandInboxEntity,
        val commandId: String,
        val staged: RuntimeInputStagingEntity?,
        val inputRef: String,
        val fencingEpoch: Long,
        val nowEpochMs: Long,
    )

    private data class ClaimedStartCommitCtx(
        val command: RuntimeCommandInboxEntity,
        val commandId: String,
        val staged: RuntimeInputStagingEntity,
        val inputRef: String,
        val fencingEpoch: Long,
        val nowEpochMs: Long,
    )

    private suspend fun failExpiredOrMissingInput(ctx: ClaimedStartFailureCtx): Boolean {
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

    private suspend fun commitStagedInputAndComplete(ctx: ClaimedStartCommitCtx): Boolean {
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

    private suspend fun processRunAction(command: RuntimeCommandInboxEntity, fencingEpoch: Long, nowEpochMs: Long): Boolean {
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

    private suspend fun processUndoAction(
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

    private suspend fun processFeedbackAction(
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

    private suspend fun processResumeAction(
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

    private suspend fun processSignalDispatch(
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

    private suspend fun appendRunStatusUiProjection(
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

    private suspend fun approvalMatches(command: RuntimeCommandInboxEntity, runId: String): Boolean {
        val requested = database.runtimeEventDao().latestByType(runId, "ApprovalRequested") ?: return false
        val expected =
            runSuspendCatching { Json.parseToJsonElement(requested.payloadJson).jsonObject }.getOrNull() ?: return false
        val actual = runSuspendCatching { Json.parseToJsonElement(command.payloadJson).jsonObject }.getOrNull() ?: return false
        val expectedProposal = expected["proposalId"]?.jsonPrimitive?.content ?: return false
        val expectedPayload = expected["payloadRef"]?.jsonPrimitive?.content ?: return false
        return actual["proposalId"]?.jsonPrimitive?.content == expectedProposal &&
            actual["payloadRef"]?.jsonPrimitive?.content == expectedPayload
    }

    private fun approvalAuditPayload(command: RuntimeCommandInboxEntity): String {
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

    suspend fun readRunInput(runId: String, nowEpochMs: Long): String? = database.withTransaction {
        database.runtimeRunInputDao().deleteExpired(nowEpochMs)
        database.runtimeRunInputDao().findByRunId(runId)?.rawText
    }

    private val approvals = RoomApprovalStore(
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

    suspend fun requestContactTagApproval(
        payloadJson: String,
        providerCallId: String,
        sessionId: String,
        runId: String,
        attemptId: String,
        ownerId: String,
        fencingEpoch: Long,
        nowEpochMs: Long,
    ): Boolean = approvals.requestContactTagApproval(
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

    private val commands = RoomCommandStore(
        database = database,
        producerVersion = producerVersion,
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

    suspend fun startAttempt(request: AttemptStartRequest) = database.withTransaction {
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

    suspend fun startObservationAttempt(request: AttemptStartRequest) = database.withTransaction {
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

    private val tools = RoomToolExecutionStore(
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

    suspend fun recoverableSessionIds(nowEpochMs: Long): List<String> = database.runtimeSessionDao().findRecoverableSessionIds(nowEpochMs)

    suspend fun eventsAfter(sessionId: String, sequence: Long): List<RuntimeEventEntity> = database.runtimeEventDao().listAfter(sessionId, sequence)

    private suspend fun requireActiveLease(sessionId: String, ownerId: String, fencingEpoch: Long, nowEpochMs: Long) {
        val session = requireNotNull(database.runtimeSessionDao().find(sessionId))
        if (session.leaseOwnerId != ownerId || session.leaseEpoch != fencingEpoch ||
            (session.leaseExpiresAtEpochMs ?: Long.MIN_VALUE) <= nowEpochMs
        ) {
            throw FencingRejectedException("writer does not hold a current session lease")
        }
    }

    private suspend fun appendEventInTransaction(draft: RuntimeEventDraft, fencingEpoch: Long): RuntimeEventEntity {
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
