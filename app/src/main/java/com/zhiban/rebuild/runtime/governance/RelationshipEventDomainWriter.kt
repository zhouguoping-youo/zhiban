package com.zhiban.rebuild.runtime.governance

import androidx.room.withTransaction
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.agent.ToolAuditEntity
import com.zhiban.rebuild.data.autowrite.ChangeLogEntity
import com.zhiban.rebuild.data.contact.RelationshipEventEntity
import com.zhiban.rebuild.data.contact.RelationshipEventParticipantEntity
import com.zhiban.rebuild.data.store.RuntimeEventEntity
import com.zhiban.rebuild.data.store.RuntimeToolExecutionEntity
import com.zhiban.rebuild.foundation.changeIdFor
import com.zhiban.rebuild.foundation.sha256
import com.zhiban.rebuild.runtime.spi.RUNTIME_SCHEMA_VERSION
import com.zhiban.rebuild.runtime.spi.RuntimeRunStatus
import com.zhiban.rebuild.runtime.tool.ConfirmedToolExecutionContext
import com.zhiban.rebuild.runtime.tool.SafeToolResult
import com.zhiban.rebuild.runtime.tool.ToolConfirmation
import com.zhiban.rebuild.runtime.tool.auditIdFor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class RelationshipIntroductionCall(
    val providerCallId: String,
    val logicalStepId: String,
    val proposalId: String,
    val payloadRef: String,
    val revision: Long,
    val canonicalInputDigest: String,
    val idempotencyKey: String,
    val eventId: String,
    val subjectContactId: String,
    val introducerContactId: String,
    val subjectName: String,
    val introducerName: String,
    val title: String,
    val note: String?,
    val occurredAtEpochMs: Long?,
    val evidenceDigest: String,
)

/** Confirmation-gated writer for introduction events created from an Agent conversation. */
internal class RelationshipEventDomainWriter(private val database: AgentDatabase) {
    suspend fun execute(context: ConfirmedToolExecutionContext, call: RelationshipIntroductionCall, confirmation: ToolConfirmation): SafeToolResult =
        database.withTransaction {
            require(call.proposalId == confirmation.proposalId && call.payloadRef == confirmation.payloadRef)
            require(call.revision == confirmation.revision && call.canonicalInputDigest == confirmation.canonicalInputDigest)
            val run = requireNotNull(database.runtimeRunDao().find(context.runId))
            val attemptId = requireNotNull(run.activeAttemptId)
            check(run.status == RuntimeRunStatus.EXECUTING.name)
            val session = requireNotNull(database.runtimeSessionDao().find(run.sessionId))
            check(
                session.leaseOwnerId == context.ownerId &&
                    session.leaseEpoch == context.fencingEpoch &&
                    (session.leaseExpiresAtEpochMs ?: 0) > context.nowEpochMs,
            )
            database.runtimeToolExecutionDao().findByKey(call.idempotencyKey)?.let {
                return@withTransaction SafeToolResult(requireNotNull(it.resultRef), requireNotNull(it.safeResultJson))
            }
            val approval = requireNotNull(database.runtimeEventDao().latestByType(context.runId, "ApprovalRequested"))
            val approved = Json.parseToJsonElement(approval.payloadJson).jsonObject
            require(approved["toolName"]?.jsonPrimitive?.content == TOOL_NAME)
            require(approved["proposalId"]?.jsonPrimitive?.content == call.proposalId)
            require(approved["payloadRef"]?.jsonPrimitive?.content == call.payloadRef)
            val subject = requireNotNull(database.contactDao().findById(call.subjectContactId))
            val introducer = requireNotNull(database.contactDao().findById(call.introducerContactId))
            require(subject.contactId != introducer.contactId)
            database.relationshipEventDao().upsertEvent(
                RelationshipEventEntity(
                    eventId = call.eventId,
                    eventType = "INTRODUCTION",
                    title = call.title,
                    note = call.note,
                    occurredAtEpochMs = call.occurredAtEpochMs,
                    evidenceDigest = call.evidenceDigest,
                    evidenceRefsJson = "[\"runtime:${context.runId}\"]",
                    userConfirmed = true,
                    status = "ACTIVE",
                    createdAtEpochMs = context.nowEpochMs,
                    updatedAtEpochMs = context.nowEpochMs,
                ),
            )
            database.relationshipEventDao().deleteParticipants(call.eventId)
            database.relationshipEventDao().upsertParticipants(
                listOf(
                    RelationshipEventParticipantEntity(
                        participantId = "${call.eventId}-user",
                        eventId = call.eventId,
                        participantKind = "USER",
                        contactId = null,
                        participantRole = "SUBJECT",
                        displayNameSnapshot = "我",
                        createdAtEpochMs = context.nowEpochMs,
                    ),
                    RelationshipEventParticipantEntity(
                        participantId = "${call.eventId}-subject",
                        eventId = call.eventId,
                        participantKind = "CONTACT",
                        contactId = subject.contactId,
                        participantRole = "SUBJECT",
                        displayNameSnapshot = call.subjectName,
                        createdAtEpochMs = context.nowEpochMs,
                    ),
                    RelationshipEventParticipantEntity(
                        participantId = "${call.eventId}-introducer",
                        eventId = call.eventId,
                        participantKind = "CONTACT",
                        contactId = introducer.contactId,
                        participantRole = "INTRODUCER",
                        displayNameSnapshot = call.introducerName,
                        createdAtEpochMs = context.nowEpochMs,
                    ),
                ),
            )
            finish(context, call, attemptId, run.sessionId, session.nextSequence)
        }

    private suspend fun finish(
        context: ConfirmedToolExecutionContext,
        call: RelationshipIntroductionCall,
        attemptId: String,
        sessionId: String,
        nextSequence: Long,
    ): SafeToolResult {
        val changeId = changeIdFor(call.idempotencyKey)
        val afterDigest = sha256(
            "${call.eventId}|${call.title}|${call.note.orEmpty()}|${call.occurredAtEpochMs ?: 0}|${call.evidenceDigest}",
        )
        database.changeLogDao().insert(
            ChangeLogEntity(
                changeId,
                context.runId,
                TOOL_NAME,
                call.idempotencyKey,
                "RELATIONSHIP_EVENT",
                call.eventId,
                "CREATE",
                null,
                afterDigest,
                "{\"deleteEventId\":\"${call.eventId}\"}",
                "AVAILABLE",
                context.nowEpochMs,
                null,
            ),
        )
        val safe = buildJsonObject {
            put("eventId", call.eventId)
            put("eventType", "INTRODUCTION")
            put("title", call.title)
            put("subjectContactId", call.subjectContactId)
            put("introducerContactId", call.introducerContactId)
            put("changeId", changeId)
            put("undoAvailable", true)
        }.toString()
        val audit = ToolAuditEntity(
            id = auditIdFor(call.idempotencyKey),
            runId = null,
            subjectRunDigest = sha256(context.runId),
            toolCallId = call.providerCallId,
            toolName = TOOL_NAME,
            idempotencyKey = call.idempotencyKey,
            argumentsDigest = call.canonicalInputDigest,
            runtimeRunId = context.runId,
            runtimeAttemptId = attemptId,
            proposalId = call.proposalId,
            payloadRefDigest = sha256(call.payloadRef),
            approvalRevision = call.revision,
            status = "SUCCEEDED",
            resultJson = safe,
            expiresAtEpochMs = null,
            createdAtEpochMs = context.nowEpochMs,
            updatedAtEpochMs = context.nowEpochMs,
        )
        database.toolAuditDao().insert(audit)
        database.runtimeToolExecutionDao().insert(
            RuntimeToolExecutionEntity(
                "exec-${sha256(call.idempotencyKey).take(32)}",
                context.runId,
                call.logicalStepId,
                TOOL_NAME,
                1,
                call.canonicalInputDigest,
                call.idempotencyKey,
                call.providerCallId,
                call.proposalId,
                sha256(call.payloadRef),
                call.revision,
                attemptId,
                "SUCCEEDED",
                call.eventId,
                safe,
                context.fencingEpoch,
                context.nowEpochMs,
                context.nowEpochMs,
            ),
        )
        check(database.runtimeAttemptDao().finish(attemptId, "SUCCEEDED", context.nowEpochMs) == 1)
        check(database.runtimeSessionDao().advanceSequence(sessionId, nextSequence, nextSequence + 1, context.nowEpochMs) == 1)
        database.runtimeEventDao().insert(
            RuntimeEventEntity(
                "event-${sha256("RelationshipIntroductionCreated:${context.runId}:${call.providerCallId}").take(32)}",
                RUNTIME_SCHEMA_VERSION,
                "RelationshipIntroductionCreated",
                sessionId,
                context.runId,
                attemptId,
                nextSequence,
                call.providerCallId,
                context.runId,
                "relationship-event-domain-writer-v1",
                safe,
                context.nowEpochMs,
                context.fencingEpoch,
            ),
        )
        check(
            database.runtimeRunDao().transition(
                context.runId,
                RuntimeRunStatus.EXECUTING.name,
                RuntimeRunStatus.OBSERVING.name,
                nextSequence,
                context.nowEpochMs,
            ) == 1,
        )
        return SafeToolResult(call.eventId, safe)
    }

    internal companion object {
        const val TOOL_NAME = "relationship.event.createIntroduction"
    }
}
