package com.zhiban.rebuild.runtime.governance

import androidx.room.withTransaction
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.agent.ToolAuditEntity
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.ContactRoleEntity
import com.zhiban.rebuild.runtime.context.FactEntity
import com.zhiban.rebuild.runtime.context.FactIndex
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

data class ContactCreateCandidateCall(
    val providerCallId: String,
    val logicalStepId: String,
    val proposalId: String,
    val payloadRef: String,
    val revision: Long,
    val canonicalInputDigest: String,
    val idempotencyKey: String,
    val candidateId: String,
    val contactId: String,
)

/** The only production writer for Agent-created contact candidates. */
internal class ContactDomainWriter(private val database: AgentDatabase) {
    suspend fun execute(context: ConfirmedToolExecutionContext, call: ContactCreateCandidateCall, confirmation: ToolConfirmation): SafeToolResult =
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
            val staged = requireNotNull(database.stagedContactCandidateDao().find(call.candidateId))
            require(staged.state in setOf("PENDING", "APPROVED") && staged.expiresAtEpochMs > context.nowEpochMs)
            require(staged.payloadDigest == call.canonicalInputDigest)
            val payload = Json.parseToJsonElement(staged.payloadJson).jsonObject
            fun optional(name: String) = payload[name]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            val displayName = requireNotNull(optional("displayName"))
            database.contactDao().insert(
                ContactEntity(
                    call.contactId, displayName, payload.getValue("normalizedName").jsonPrimitive.content,
                    optional("phone"), optional("email"), optional("wechatId"), optional("company"), optional("title"),
                    "[]", "[]", optional("note"), null, "AGENT_CANDIDATE", null, context.nowEpochMs, context.nowEpochMs,
                ),
            )
            optional("roleType")?.let { role ->
                database.contactDao().upsertRole(
                    ContactRoleEntity(
                        call.contactId,
                        "default",
                        role,
                        1.0,
                        true,
                        null,
                        context.nowEpochMs,
                        context.nowEpochMs,
                    ),
                )
            }
            FactIndex(database).upsert(
                FactEntity(
                    factId = "contact:${call.contactId}", factType = "CONTACT",
                    textContent = buildString {
                        append(displayName)
                        optional("company")?.let { append("，公司=").append(it) }
                        optional("title")?.let { append("，职位=").append(it) }
                        optional("phone")?.let { append("，电话=").append(it) }
                        optional("email")?.let { append("，邮箱=").append(it) }
                        optional("note")?.let { append("，备注=").append(it) }
                    },
                    structuredDataJson = staged.payloadJson, sourceType = "AGENT_DOMAIN_WRITE",
                    sourceRef = context.runId, contactId = call.contactId,
                    skillId = optional("roleType")?.let {
                        "default"
                    },
                    confidence = 1.0,
                    sensitivity = if (optional("phone") != null ||
                        optional("email") != null
                    ) {
                        "SENSITIVE"
                    } else {
                        "PERSONAL"
                    },
                    status = "ACTIVE", ttlDays = 0, expiresAtEpochMs = null,
                    createdAtEpochMs = context.nowEpochMs, updatedAtEpochMs = context.nowEpochMs,
                ),
            )
            database.stagedContactCandidateDao().approve(call.candidateId, context.nowEpochMs)
            finalizeConfirmedContactWrite(start, context, call, displayName, staged.payloadDigest)
        }

    suspend fun undo(changeId: String, now: Long): Boolean = database.withTransaction {
        val change = database.changeLogDao().find(changeId) ?: return@withTransaction false
        if (change.undoState != "AVAILABLE" ||
            change.toolName != "contact.createCandidate"
        ) {
            return@withTransaction false
        }
        if (database.contactDao().deleteAgentCandidate(change.targetId) != 1) return@withTransaction false
        FactIndex(database).delete("contact:${change.targetId}")
        database.changeLogDao().markUndone(changeId, now) == 1
    }

    private suspend fun finalizeConfirmedContactWrite(
        start: ConfirmedContactWriteStart,
        context: ConfirmedToolExecutionContext,
        call: ContactCreateCandidateCall,
        displayName: String,
        payloadDigest: String,
    ): SafeToolResult {
        val attemptId = start.attemptId
        val changeId = changeIdFor(call.idempotencyKey)
        database.changeLogDao().insert(
            ChangeLogEntity(
                changeId, context.runId, "contact.createCandidate", call.idempotencyKey, "CONTACT", call.contactId,
                "CREATE", null, payloadDigest, "{\"deleteContactId\":\"${call.contactId}\"}",
                "AVAILABLE", context.nowEpochMs, null,
            ),
        )
        val safe = buildJsonObject {
            put("contactId", call.contactId)
            put("displayName", displayName)
            put("status", "candidate_created")
            put("changeId", changeId)
            put("undoAvailable", true)
        }.toString()
        database.toolAuditDao().insert(
            ToolAuditEntity(
                auditIdFor(call.idempotencyKey), null, sha256(context.runId), call.providerCallId,
                "contact.createCandidate", call.idempotencyKey, call.canonicalInputDigest, context.runId, attemptId,
                call.proposalId, sha256(call.payloadRef), call.revision, status = "SUCCEEDED", resultJson = safe,
                expiresAtEpochMs = null, createdAtEpochMs = context.nowEpochMs, updatedAtEpochMs = context.nowEpochMs,
            ),
        )
        database.runtimeToolExecutionDao().insert(
            RuntimeToolExecutionEntity(
                "exec-${sha256(
                    call.idempotencyKey,
                ).take(32)}",
                context.runId, call.logicalStepId, "contact.createCandidate", 1,
                call.canonicalInputDigest, call.idempotencyKey, call.providerCallId, call.proposalId,
                sha256(
                    call.payloadRef,
                ),
                call.revision, attemptId, "SUCCEEDED", call.contactId, safe, context.fencingEpoch, context.nowEpochMs, context.nowEpochMs,
            ),
        )
        database.stagedContactCandidateDao().consumeAndScrub(call.candidateId, context.nowEpochMs)
        check(database.runtimeAttemptDao().finish(attemptId, "SUCCEEDED", context.nowEpochMs) == 1)
        val sequence = start.nextSequence
        check(
            database.runtimeSessionDao().advanceSequence(
                start.sessionId,
                sequence,
                sequence + 1,
                context.nowEpochMs,
            ) == 1,
        )
        database.runtimeEventDao().insert(
            RuntimeEventEntity(
                "event-${sha256(
                    "ContactCandidateCreated:${context.runId}:${call.providerCallId}",
                ).take(32)}",
                RUNTIME_SCHEMA_VERSION,
                "ContactCandidateCreated", start.sessionId, context.runId, attemptId, sequence, call.providerCallId,
                context.runId, "contact-domain-writer-v1", safe, context.nowEpochMs, context.fencingEpoch,
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
        return SafeToolResult(call.contactId, safe)
    }
}
