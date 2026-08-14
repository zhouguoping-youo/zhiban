package com.zhiban.rebuild.runtime.tool
import androidx.room.withTransaction
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.agent.ScheduleEntity
import com.zhiban.rebuild.data.agent.ToolAuditEntity
import com.zhiban.rebuild.data.calendar.ExternalCalendarConflictSource
import com.zhiban.rebuild.data.crm.CrmActionStatus
import com.zhiban.rebuild.runtime.context.FactEntity
import com.zhiban.rebuild.runtime.context.FactIndex
import com.zhiban.rebuild.runtime.governance.ChangeLogEntity
import com.zhiban.rebuild.runtime.kernel.RuntimeSignal
import com.zhiban.rebuild.runtime.kernel.RuntimeStateMachine
import com.zhiban.rebuild.runtime.runSuspendCatching
import com.zhiban.rebuild.runtime.spi.RUNTIME_SCHEMA_VERSION
import com.zhiban.rebuild.runtime.spi.RuntimeRunStatus
import com.zhiban.rebuild.runtime.store.RuntimeEventEntity
import com.zhiban.rebuild.runtime.store.RuntimeToolExecutionEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class ToolIdempotencyConflictException(message: String) : IllegalStateException(message)

internal class RoomScheduleToolExecutor(
    private val database: AgentDatabase,
    private val enabled: () -> Boolean = { true },
    private val externalConflicts: ExternalCalendarConflictSource? = null,
    private val onScheduleSaved: (ScheduleEntity) -> Unit = {},
) {
    suspend fun execute(context: ConfirmedToolExecutionContext, call: ScheduleCreateToolCall, confirmation: ToolConfirmation): SafeToolResult {
        // Validate the persisted approval before inspecting the user's device calendar. The same
        // validation runs again in the write transaction so a stale approval cannot win a race.
        val replay = database.withTransaction {
            val resolved = validateToolCall(context, call, confirmation)
            handleIdempotentReplay(context, call, resolved.attemptId)
        }
        if (replay != null) {
            database.scheduleDao().findById(replay.scheduleId)?.let(onScheduleSaved)
            return replay
        }
        val endAt = Math.addExact(call.startAtEpochMs, call.durationMinutes * 60_000L)
        if (externalConflicts?.findConflicts(call.startAtEpochMs, endAt, call.scheduleId, 1).orEmpty().isNotEmpty()) {
            throw CalendarScheduleConflictException("CALENDAR_SCHEDULE_CONFLICT")
        }
        val result = database.withTransaction {
            val resolved = validateToolCall(context, call, confirmation)
            handleIdempotentReplay(context, call, resolved.attemptId)?.let { return@withTransaction it }
            checkSchedulePreconditions(resolved.attemptStatus, resolved.runStatus, call, context.nowEpochMs)
            val safeResult = persistScheduleAndSideEffects(context, call, resolved.attemptId)
            finalizeRun(context, call, resolved.sessionId, resolved.attemptId, safeResult)
        }
        database.scheduleDao().findById(result.scheduleId)?.let(onScheduleSaved)
        return result
    }

    private data class ResolvedExecution(val sessionId: String, val attemptId: String, val attemptStatus: String, val runStatus: String)

    private fun requireCallBoundToConfirmation(call: ScheduleCreateToolCall, confirmation: ToolConfirmation) {
        require(
            call.proposalId == confirmation.proposalId && call.payloadRef == confirmation.payloadRef &&
                call.revision == confirmation.revision &&
                call.canonicalInputDigest == confirmation.canonicalInputDigest,
        ) {
            "confirmation does not bind the tool call"
        }
        require(canonicalScheduleDigest(call) == call.canonicalInputDigest) {
            "tool arguments do not match the approved digest"
        }
    }

    private suspend fun validateToolCall(
        context: ConfirmedToolExecutionContext,
        call: ScheduleCreateToolCall,
        confirmation: ToolConfirmation,
    ): ResolvedExecution {
        check(enabled()) { "runtime tool feature is disabled" }
        requireCallBoundToConfirmation(call, confirmation)
        val run = requireNotNull(database.runtimeRunDao().find(context.runId))
        val attemptId = requireNotNull(run.activeAttemptId) { "tool execution requires an active attempt" }
        val attempt =
            database.runtimeAttemptDao().listByRunId(context.runId).singleOrNull { it.attemptId == attemptId }
                ?: error("tool execution attempt is missing")
        val expectedIdempotencyKey = canonicalToolIdempotencyKey(context.runId, attemptId, call)
        require(call.idempotencyKey == expectedIdempotencyKey) { "idempotency key is not canonical" }
        val session = requireNotNull(database.runtimeSessionDao().find(run.sessionId))
        check(
            session.leaseOwnerId == context.ownerId && session.leaseEpoch == context.fencingEpoch &&
                (session.leaseExpiresAtEpochMs ?: Long.MIN_VALUE) > context.nowEpochMs,
        ) { "stale tool writer" }
        val requested = database.runtimeEventDao().latestByType(context.runId, "ApprovalRequested")
            ?: throw ToolPolicyRejectedException("approval is missing")
        val approved = runSuspendCatching { Json.parseToJsonElement(requested.payloadJson).jsonObject }.getOrNull()
            ?: throw ToolPolicyRejectedException("approval is malformed")
        fun approvedString(name: String) = approved[name]?.jsonPrimitive?.content
        if (approvedString("proposalId") != confirmation.proposalId ||
            approvedString("payloadRef") != confirmation.payloadRef ||
            approvedString("revision")?.toLongOrNull() != confirmation.revision ||
            approvedString("canonicalInputDigest") != confirmation.canonicalInputDigest
        ) {
            throw ToolPolicyRejectedException("persisted approval does not bind the tool call")
        }
        return ResolvedExecution(run.sessionId, attemptId, attempt.status, run.status)
    }

    private suspend fun handleIdempotentReplay(context: ConfirmedToolExecutionContext, call: ScheduleCreateToolCall, attemptId: String): SafeToolResult? {
        val existing = database.runtimeToolExecutionDao().findByKey(call.idempotencyKey)
        if (existing != null) {
            if (existing.runId != context.runId || existing.attemptId != attemptId ||
                existing.toolName != SchedulePlanValidator.TOOL_NAME || existing.toolSpecVersion != 1 ||
                existing.providerCallId != call.providerCallId || existing.logicalStepId != call.logicalStepId ||
                existing.proposalId != call.proposalId || existing.payloadRefDigest != sha256(call.payloadRef) ||
                existing.approvalRevision != call.revision ||
                existing.canonicalInputDigest != call.canonicalInputDigest ||
                existing.resultRef != call.scheduleId
            ) {
                throw ToolIdempotencyConflictException("idempotency key conflicts with another tool call")
            }
            return SafeToolResult(
                requireNotNull(existing.resultRef),
                requireNotNull(existing.safeResultJson),
            )
        }
        return null
    }

    private suspend fun checkSchedulePreconditions(attemptStatus: String, runStatus: String, call: ScheduleCreateToolCall, nowEpochMs: Long) {
        check(attemptStatus == "ACTIVE") { "tool execution requires the run's active attempt" }
        check(runStatus == RuntimeRunStatus.EXECUTING.name) { "run is not executing" }
        require(call.startAtEpochMs >= nowEpochMs - PAST_SCHEDULE_GRACE_MS) { "CALENDAR_SCHEDULE_IN_PAST" }
        val endAt = Math.addExact(call.startAtEpochMs, call.durationMinutes * 60_000L)
        if (database.scheduleDao().findConflicts(call.startAtEpochMs, endAt).isNotEmpty()) {
            throw CalendarScheduleConflictException("CALENDAR_SCHEDULE_CONFLICT")
        }
        call.crmActionId?.let { actionId ->
            val action = requireNotNull(database.crmDao().findAction(actionId)) { "CRM_ACTION_NOT_FOUND" }
            check(action.status == CrmActionStatus.PENDING) { "CRM_ACTION_NOT_PENDING" }
            check(action.scheduleId == null || action.scheduleId == call.scheduleId) { "CRM_ACTION_ALREADY_SCHEDULED" }
        }
    }

    private companion object {
        const val PAST_SCHEDULE_GRACE_MS = 5 * 60_000L
    }

    private suspend fun persistScheduleAndSideEffects(context: ConfirmedToolExecutionContext, call: ScheduleCreateToolCall, attemptId: String): String {
        val changeId = changeIdFor(call.idempotencyKey)
        val safeResult = buildJsonObject {
            put("scheduleId", call.scheduleId)
            put("status", "created")
            put("changeId", changeId)
            put("undoAvailable", true)
            // Echo the verified wall-clock so the post-confirmation summary can state the real
            // date/time instead of a generic "已创建" that cannot correct a hallucinated model reply.
            put("title", call.title)
            put("startAtEpochMs", call.startAtEpochMs)
            put("durationMinutes", call.durationMinutes)
            call.crmActionId?.let { put("crmActionId", it) }
        }.toString()
        val schedule = ScheduleEntity(
            call.scheduleId, call.title, call.startAtEpochMs, call.durationMinutes, call.note,
            createdByRunId = null, createdByRuntimeRunId = context.runId, createdByRuntimeAttemptId = attemptId,
            createdAtEpochMs = context.nowEpochMs, updatedAtEpochMs = context.nowEpochMs,
            reminderMinutesBefore = call.reminderMinutesBefore,
        )
        database.scheduleDao().insert(schedule)
        call.crmActionId?.let { actionId ->
            val action = requireNotNull(database.crmDao().findAction(actionId)) { "CRM_ACTION_NOT_FOUND" }
            check(
                database.crmDao().updateAction(
                    action.copy(
                        dueAtEpochMs = call.startAtEpochMs,
                        scheduleId = call.scheduleId,
                        updatedAtEpochMs = context.nowEpochMs,
                    ),
                ) == 1,
            )
        }
        FactIndex(database).upsert(
            FactEntity(
                factId = "schedule:${call.scheduleId}", factType = "CALENDAR_EVENT",
                textContent = buildString {
                    append(call.title).append("，开始时间=").append(call.startAtEpochMs)
                    append("，时长=").append(call.durationMinutes).append("分钟")
                    call.note?.takeIf(String::isNotBlank)?.let { append("，备注=").append(it) }
                },
                structuredDataJson = buildJsonObject {
                    put("startAtEpochMs", call.startAtEpochMs)
                    put("durationMinutes", call.durationMinutes)
                }.toString(),
                sourceType = "AGENT_DOMAIN_WRITE", sourceRef = context.runId, contactId = null, skillId = null,
                confidence = 1.0, sensitivity = "NORMAL", status = "ACTIVE", ttlDays = 0, expiresAtEpochMs = null,
                createdAtEpochMs = context.nowEpochMs, updatedAtEpochMs = context.nowEpochMs,
            ),
        )
        database.changeLogDao().insert(
            ChangeLogEntity(
                changeId, context.runId, SchedulePlanValidator.TOOL_NAME, call.idempotencyKey,
                "CALENDAR", call.scheduleId, "CREATE", null, call.canonicalInputDigest,
                "{\"deleteScheduleId\":\"${call.scheduleId}\"}", "AVAILABLE", context.nowEpochMs, null,
            ),
        )
        database.toolAuditDao().insert(
            ToolAuditEntity(
                id = auditIdFor(call.idempotencyKey), runId = null,
                subjectRunDigest = sha256(context.runId), toolCallId = call.providerCallId,
                toolName = SchedulePlanValidator.TOOL_NAME, idempotencyKey = call.idempotencyKey,
                argumentsDigest = call.canonicalInputDigest, runtimeRunId = context.runId, runtimeAttemptId = attemptId,
                proposalId = call.proposalId,
                payloadRefDigest = sha256(
                    call.payloadRef,
                ),
                approvalRevision = call.revision,
                status = "SUCCEEDED", resultJson = safeResult,
                expiresAtEpochMs = null, createdAtEpochMs = context.nowEpochMs, updatedAtEpochMs = context.nowEpochMs,
            ),
        )
        database.runtimeToolExecutionDao().insert(
            RuntimeToolExecutionEntity(
                executionId = "exec-${sha256(call.idempotencyKey).take(32)}", runId = context.runId,
                logicalStepId = call.logicalStepId, toolName = SchedulePlanValidator.TOOL_NAME,
                toolSpecVersion = 1, canonicalInputDigest = call.canonicalInputDigest,
                idempotencyKey = call.idempotencyKey, providerCallId = call.providerCallId,
                proposalId = call.proposalId,
                payloadRefDigest = sha256(
                    call.payloadRef,
                ),
                approvalRevision = call.revision,
                attemptId = attemptId, status = "SUCCEEDED", resultRef = call.scheduleId,
                safeResultJson = safeResult, fencingEpoch = context.fencingEpoch,
                createdAtEpochMs = context.nowEpochMs, updatedAtEpochMs = context.nowEpochMs,
            ),
        )
        return safeResult
    }

    private suspend fun finalizeRun(
        context: ConfirmedToolExecutionContext,
        call: ScheduleCreateToolCall,
        sessionId: String,
        attemptId: String,
        safeResult: String,
    ): SafeToolResult {
        check(database.runtimeAttemptDao().finish(attemptId, "SUCCEEDED", context.nowEpochMs) == 1) {
            "active attempt cannot be completed"
        }
        val observing = RuntimeStateMachine.reduce(RuntimeRunStatus.EXECUTING, RuntimeSignal.ToolCompleted)
        val toolEventSequence =
            appendEvent(sessionId, context.runId, "ToolSucceeded", call.providerCallId, safeResult, context)
        check(
            database.runtimeRunDao().transition(
                context.runId,
                RuntimeRunStatus.EXECUTING.name,
                observing.name,
                toolEventSequence,
                context.nowEpochMs,
            ) ==
                1,
        )
        return SafeToolResult(call.scheduleId, safeResult)
    }

    private suspend fun appendEvent(
        sessionId: String,
        runId: String,
        eventType: String,
        causationId: String,
        payloadJson: String,
        context: ConfirmedToolExecutionContext,
    ): Long {
        val session = requireNotNull(database.runtimeSessionDao().find(sessionId))
        val sequence = session.nextSequence
        check(database.runtimeSessionDao().advanceSequence(sessionId, sequence, sequence + 1, context.nowEpochMs) == 1)
        database.runtimeEventDao().insert(
            RuntimeEventEntity(
                eventId = "event-${sha256("$eventType:${context.runId}:$causationId").take(32)}",
                schemaVersion = RUNTIME_SCHEMA_VERSION,
                eventType = eventType,
                sessionId = sessionId,
                runId = runId,
                attemptId = requireNotNull(database.runtimeRunDao().find(runId)?.activeAttemptId),
                sequence = sequence,
                causationId = causationId,
                correlationId = runId,
                producerVersion = "runtime-tool-v1",
                payloadJson = payloadJson,
                createdAtEpochMs = context.nowEpochMs,
                fencingEpoch = context.fencingEpoch,
            ),
        )
        return sequence
    }
}

internal class CalendarScheduleConflictException(message: String) : IllegalStateException(message)
