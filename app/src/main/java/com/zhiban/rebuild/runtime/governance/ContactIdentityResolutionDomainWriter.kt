package com.zhiban.rebuild.runtime.governance

import androidx.room.withTransaction
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.agent.ToolAuditEntity
import com.zhiban.rebuild.data.agent.stableContactKnowledgeId
import com.zhiban.rebuild.data.contact.ContactPlatformIdentityEntity
import com.zhiban.rebuild.data.contact.PersonEntity
import com.zhiban.rebuild.data.contact.SourceIdentityEntity
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class ContactIdentityResolutionCall(
    val providerCallId: String,
    val logicalStepId: String,
    val proposalId: String,
    val payloadRef: String,
    val revision: Long,
    val canonicalInputDigest: String,
    val idempotencyKey: String,
    val sourceIdentityId: String,
    val contactId: String,
    val evidenceDigest: String,
    val confidence: Double,
    val previousStatus: String,
    val previousConfidence: Double,
)

/** The confirmation-gated writer that closes an unresolved social identity loop. */
internal class ContactIdentityResolutionDomainWriter(private val database: AgentDatabase) {
    suspend fun execute(context: ConfirmedToolExecutionContext, call: ContactIdentityResolutionCall, confirmation: ToolConfirmation): SafeToolResult =
        database.withTransaction {
            val start = database.validateConfirmedContactWrite(
                context,
                call.proposalId,
                call.payloadRef,
                call.revision,
                call.canonicalInputDigest,
                call.idempotencyKey,
                confirmation,
            )
            start.replay?.let { return@withTransaction it }
            val identity = requireNotNull(database.contactIntelligenceDao().findSourceIdentity(call.sourceIdentityId))
            require(identity.personId == null && identity.resolutionStatus == call.previousStatus)
            require(identity.confidence == call.previousConfidence)
            val contact = requireNotNull(database.contactDao().findById(call.contactId))
            ensurePerson(contact.contactId, contact.displayName, contact.normalizedName, contact.createdAtEpochMs, context.nowEpochMs)
            check(
                database.contactIntelligenceDao().resolveSourceIdentity(
                    call.sourceIdentityId,
                    call.contactId,
                    call.confidence,
                    context.nowEpochMs,
                ) == 1,
            )
            val projectionId = projectResolvedIdentity(identity, call.contactId, context.nowEpochMs)
            finish(context, call, start, identity, contact.displayName, projectionId)
        }

    private suspend fun ensurePerson(contactId: String, displayName: String, normalizedName: String, createdAtEpochMs: Long, nowEpochMs: Long) {
        val dao = database.contactIntelligenceDao()
        if (dao.findPerson(contactId) != null) return
        dao.upsertPerson(
            PersonEntity(
                personId = contactId,
                canonicalContactId = contactId,
                displayName = displayName,
                normalizedName = normalizedName,
                kind = "CONTACT",
                status = "ACTIVE",
                createdAtEpochMs = createdAtEpochMs,
                updatedAtEpochMs = nowEpochMs,
            ),
        )
    }

    private suspend fun projectResolvedIdentity(identity: SourceIdentityEntity, contactId: String, nowEpochMs: Long): String? {
        if (identity.sourceType in NON_SOCIAL_TYPES || identity.visibleHandle.isBlank()) return null
        val projectionId = stableContactKnowledgeId("resolved-source-identity", identity.sourceIdentityId)
        database.contactIdentityDao().upsertPlatformIdentity(
            ContactPlatformIdentityEntity(
                identityId = projectionId,
                contactId = contactId,
                platform = identity.sourceType,
                handle = identity.visibleHandle,
                normalizedHandle = identity.normalizedHandle,
                platformUserId = identity.stableExternalId,
                source = "AGENT_CONFIRMED_IDENTITY",
                userConfirmed = true,
                createdAtEpochMs = nowEpochMs,
                updatedAtEpochMs = nowEpochMs,
            ),
        )
        return projectionId
    }

    private suspend fun finish(
        context: ConfirmedToolExecutionContext,
        call: ContactIdentityResolutionCall,
        start: ConfirmedContactWriteStart,
        previous: SourceIdentityEntity,
        contactName: String,
        projectionId: String?,
    ): SafeToolResult {
        val changeId = changeIdFor(call.idempotencyKey)
        val inverse = buildJsonObject {
            put("sourceIdentityId", call.sourceIdentityId)
            put("contactId", call.contactId)
            put("previousStatus", previous.resolutionStatus)
            put("previousConfidence", previous.confidence)
            projectionId?.let { put("deletePlatformIdentityId", it) }
        }.toString()
        val afterDigest = sourceIdentityResolutionDigest(call.sourceIdentityId, call.contactId, call.confidence)
        database.changeLogDao().insert(
            ChangeLogEntity(
                changeId,
                context.runId,
                TOOL_NAME,
                call.idempotencyKey,
                "SOURCE_IDENTITY",
                call.sourceIdentityId,
                "RESOLVE",
                sourceIdentityResolutionDigest(call.sourceIdentityId, null, previous.confidence),
                afterDigest,
                inverse,
                "AVAILABLE",
                context.nowEpochMs,
                null,
            ),
        )
        val safe = buildJsonObject {
            put("sourceIdentityId", call.sourceIdentityId)
            put("contactId", call.contactId)
            put("contactName", contactName)
            put("status", "identity_resolved")
            put("confidence", call.confidence)
            put("changeId", changeId)
            put("undoAvailable", true)
        }.toString()
        writeRuntimeRecords(context, call, start, safe)
        return SafeToolResult(call.sourceIdentityId, safe)
    }

    private suspend fun writeRuntimeRecords(
        context: ConfirmedToolExecutionContext,
        call: ContactIdentityResolutionCall,
        start: ConfirmedContactWriteStart,
        safe: String,
    ) {
        database.toolAuditDao().insert(
            ToolAuditEntity(
                id = auditIdFor(call.idempotencyKey),
                runId = null,
                subjectRunDigest = sha256(context.runId),
                toolCallId = call.providerCallId,
                toolName = TOOL_NAME,
                idempotencyKey = call.idempotencyKey,
                argumentsDigest = call.canonicalInputDigest,
                runtimeRunId = context.runId,
                runtimeAttemptId = start.attemptId,
                proposalId = call.proposalId,
                payloadRefDigest = sha256(call.payloadRef),
                approvalRevision = call.revision,
                status = "SUCCEEDED",
                resultJson = safe,
                expiresAtEpochMs = null,
                createdAtEpochMs = context.nowEpochMs,
                updatedAtEpochMs = context.nowEpochMs,
            ),
        )
        database.runtimeToolExecutionDao().insert(
            RuntimeToolExecutionEntity(
                "exec-${sha256(call.idempotencyKey).take(32)}", context.runId, call.logicalStepId,
                TOOL_NAME, 1, call.canonicalInputDigest, call.idempotencyKey, call.providerCallId,
                call.proposalId, sha256(call.payloadRef), call.revision, start.attemptId, "SUCCEEDED",
                call.sourceIdentityId, safe, context.fencingEpoch, context.nowEpochMs, context.nowEpochMs,
            ),
        )
        check(database.runtimeAttemptDao().finish(start.attemptId, "SUCCEEDED", context.nowEpochMs) == 1)
        val sequence = start.nextSequence
        check(database.runtimeSessionDao().advanceSequence(start.sessionId, sequence, sequence + 1, context.nowEpochMs) == 1)
        database.runtimeEventDao().insert(
            RuntimeEventEntity(
                "event-${sha256("ContactIdentityResolved:${context.runId}:${call.providerCallId}").take(32)}",
                RUNTIME_SCHEMA_VERSION, "ContactIdentityResolved", start.sessionId, context.runId,
                start.attemptId, sequence, call.providerCallId, context.runId,
                "contact-identity-resolution-writer-v1", safe, context.nowEpochMs, context.fencingEpoch,
            ),
        )
        check(
            database.runtimeRunDao().transition(
                context.runId,
                RuntimeRunStatus.EXECUTING.name,
                RuntimeRunStatus.OBSERVING.name,
                sequence,
                context.nowEpochMs,
            ) == 1,
        )
    }

    companion object {
        const val TOOL_NAME = "contact.identity.resolve"
        private val NON_SOCIAL_TYPES = setOf("PHONE", "EMAIL", "ANDROID_CONTACT")
    }
}

internal fun sourceIdentityResolutionDigest(sourceIdentityId: String, personId: String?, confidence: Double): String =
    sha256("$sourceIdentityId|${personId.orEmpty()}|$confidence")
