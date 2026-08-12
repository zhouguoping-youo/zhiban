package com.zhiban.rebuild.runtime.governance

import androidx.room.withTransaction
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.agent.TemporalRelationshipWriter
import com.zhiban.rebuild.data.agent.ToolAuditEntity
import com.zhiban.rebuild.data.contact.RelationshipEdgeEntity
import com.zhiban.rebuild.data.contact.RelationshipPersonIds
import com.zhiban.rebuild.relationship.RelationshipTaxonomy
import com.zhiban.rebuild.runtime.spi.RUNTIME_SCHEMA_VERSION
import com.zhiban.rebuild.runtime.spi.RuntimeRunStatus
import com.zhiban.rebuild.runtime.store.RuntimeEventEntity
import com.zhiban.rebuild.runtime.store.RuntimeToolExecutionEntity
import com.zhiban.rebuild.runtime.tool.ConfirmedToolExecutionContext
import com.zhiban.rebuild.runtime.tool.SafeToolResult
import com.zhiban.rebuild.runtime.tool.ToolConfirmation
import com.zhiban.rebuild.runtime.tool.auditIdFor
import com.zhiban.rebuild.runtime.tool.changeIdFor
import com.zhiban.rebuild.runtime.tool.sha256
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class RelationshipCandidateCall(
    val providerCallId: String,
    val logicalStepId: String,
    val proposalId: String,
    val payloadRef: String,
    val revision: Long,
    val canonicalInputDigest: String,
    val idempotencyKey: String,
    val edgeId: String,
    val fromContactId: String,
    val toContactId: String,
    val relationType: String,
    val evidenceBasis: String,
    val evidenceDigest: String,
    val confidence: Double,
    val skillId: String?,
    val temporalState: String,
)

/** Sole writer for Agent-proposed relationship edges. Raw evidence never enters runtime events. */
internal class RelationshipDomainWriter(private val database: AgentDatabase) {
    private val temporalRelationships = TemporalRelationshipWriter(database)

    suspend fun execute(context: ConfirmedToolExecutionContext, call: RelationshipCandidateCall, confirmation: ToolConfirmation): SafeToolResult =
        database.withTransaction {
            require(call.proposalId == confirmation.proposalId && call.payloadRef == confirmation.payloadRef)
            require(
                call.revision == confirmation.revision && call.canonicalInputDigest == confirmation.canonicalInputDigest,
            )
            val run = requireNotNull(database.runtimeRunDao().find(context.runId))
            val attemptId = requireNotNull(run.activeAttemptId)
            check(run.status == RuntimeRunStatus.EXECUTING.name)
            val session = requireNotNull(database.runtimeSessionDao().find(run.sessionId))
            check(
                session.leaseOwnerId == context.ownerId && session.leaseEpoch == context.fencingEpoch &&
                    (session.leaseExpiresAtEpochMs ?: 0) > context.nowEpochMs,
            )
            database.runtimeToolExecutionDao().findByKey(call.idempotencyKey)?.let {
                return@withTransaction SafeToolResult(requireNotNull(it.resultRef), requireNotNull(it.safeResultJson))
            }
            val approval = requireNotNull(database.runtimeEventDao().latestByType(context.runId, "ApprovalRequested"))
            val approved = Json.parseToJsonElement(approval.payloadJson).jsonObject
            require(approved["proposalId"]?.jsonPrimitive?.content == call.proposalId)
            require(approved["payloadRef"]?.jsonPrimitive?.content == call.payloadRef)
            suspend fun endpointExists(id: String): Boolean = id == RelationshipPersonIds.SELF || database.contactDao().findById(id) != null
            require(endpointExists(call.fromContactId) && endpointExists(call.toContactId))
            RelationshipTaxonomy.requireAllowedTemporalState(call.relationType, call.temporalState)
            require(call.evidenceBasis == RelationshipTaxonomy.evidencePolicy(call.relationType).basis.name)

            database.relationshipEdgeDao().upsert(
                RelationshipEdgeEntity(
                    call.edgeId, call.fromContactId, call.toContactId, call.relationType,
                    call.evidenceDigest, "[\"runtime:${context.runId}\"]", call.confidence,
                    true, call.skillId, if (call.temporalState == "PAST") "HISTORICAL" else "ACTIVE",
                    context.nowEpochMs, context.nowEpochMs,
                ),
            )
            temporalRelationships.replaceEpisode(
                episodeKey = call.edgeId,
                fromPersonId = call.fromContactId,
                toPersonId = call.toContactId,
                relationshipType = call.relationType,
                temporalState = call.temporalState,
                evidenceRefsJson = "[\"runtime:${context.runId}\"]",
                confidence = call.confidence,
                verificationState = "USER_CONFIRMED",
                nowEpochMs = context.nowEpochMs,
            )
            finalizeRelationshipWrite(context, call, attemptId, run.sessionId, session.nextSequence)
        }

    private suspend fun finalizeRelationshipWrite(
        context: ConfirmedToolExecutionContext,
        call: RelationshipCandidateCall,
        attemptId: String,
        sessionId: String,
        nextSequence: Long,
    ): SafeToolResult {
        val changeId = changeIdFor(call.idempotencyKey)
        database.changeLogDao().insert(
            ChangeLogEntity(
                changeId, context.runId, "relationship.createCandidate", call.idempotencyKey,
                "RELATIONSHIP", call.edgeId, "CREATE", null, call.canonicalInputDigest,
                "{\"deleteEdgeId\":\"${call.edgeId}\"}", "AVAILABLE", context.nowEpochMs, null,
            ),
        )
        val safe = buildJsonObject {
            put("edgeId", call.edgeId)
            put("fromContactId", call.fromContactId)
            put("toContactId", call.toContactId)
            put("relationType", call.relationType)
            put("evidenceBasis", call.evidenceBasis)
            put("evidenceDigest", call.evidenceDigest)
            put("confidence", call.confidence)
            put("temporalState", call.temporalState)
            put("userConfirmed", true)
            put("changeId", changeId)
            put("undoAvailable", true)
        }.toString()
        database.toolAuditDao().insert(
            ToolAuditEntity(
                id = auditIdFor(call.idempotencyKey), runId = null,
                subjectRunDigest = sha256(context.runId), toolCallId = call.providerCallId,
                toolName = "relationship.createCandidate", idempotencyKey = call.idempotencyKey,
                argumentsDigest = call.canonicalInputDigest, runtimeRunId = context.runId,
                runtimeAttemptId = attemptId, proposalId = call.proposalId,
                payloadRefDigest = sha256(call.payloadRef), approvalRevision = call.revision,
                status = "SUCCEEDED", resultJson = safe, expiresAtEpochMs = null,
                createdAtEpochMs = context.nowEpochMs, updatedAtEpochMs = context.nowEpochMs,
            ),
        )
        database.runtimeToolExecutionDao().insert(
            RuntimeToolExecutionEntity(
                "exec-${sha256(call.idempotencyKey).take(32)}", context.runId, call.logicalStepId,
                "relationship.createCandidate", 1, call.canonicalInputDigest, call.idempotencyKey,
                call.providerCallId, call.proposalId, sha256(call.payloadRef), call.revision, attemptId,
                "SUCCEEDED", call.edgeId, safe, context.fencingEpoch, context.nowEpochMs, context.nowEpochMs,
            ),
        )
        check(database.runtimeAttemptDao().finish(attemptId, "SUCCEEDED", context.nowEpochMs) == 1)
        val sequence = nextSequence
        check(
            database.runtimeSessionDao().advanceSequence(
                sessionId,
                sequence,
                sequence + 1,
                context.nowEpochMs,
            ) == 1,
        )
        database.runtimeEventDao().insert(
            RuntimeEventEntity(
                "event-${sha256("RelationshipCandidateCreated:${context.runId}:${call.providerCallId}").take(32)}",
                RUNTIME_SCHEMA_VERSION, "RelationshipCandidateCreated", sessionId, context.runId, attemptId,
                sequence, call.providerCallId, context.runId, "relationship-domain-writer-v1", safe,
                context.nowEpochMs, context.fencingEpoch,
            ),
        )
        check(
            database.runtimeRunDao().transition(
                context.runId,
                RuntimeRunStatus.EXECUTING.name,
                RuntimeRunStatus.OBSERVING.name,
                sequence,
                context.nowEpochMs,
            ) ==
                1,
        )
        return SafeToolResult(call.edgeId, safe)
    }
}
