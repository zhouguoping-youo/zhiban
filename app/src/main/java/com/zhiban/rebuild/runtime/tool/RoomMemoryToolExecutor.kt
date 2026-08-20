package com.zhiban.rebuild.runtime.tool

import androidx.room.withTransaction
import com.zhiban.agent.memory.MemoryCommit
import com.zhiban.agent.memory.MemoryNamespace
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.memory.StagedMemoryCandidateEntity
import com.zhiban.rebuild.data.store.RuntimeEventEntity
import com.zhiban.rebuild.data.store.RuntimeToolExecutionEntity
import com.zhiban.rebuild.provider.ProviderFailure
import com.zhiban.rebuild.provider.SecretRedactor
import com.zhiban.rebuild.runtime.context.EmbeddingGateway
import com.zhiban.rebuild.runtime.context.attemptRetrieval
import com.zhiban.rebuild.runtime.input.asr.PrivacyConsent
import com.zhiban.rebuild.runtime.memory.RoomMemoryGate
import com.zhiban.rebuild.runtime.spi.RUNTIME_SCHEMA_VERSION
import com.zhiban.rebuild.runtime.spi.RuntimeRunStatus
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Approval-gated durable memory mutation. Model output alone can never reach commit(). */
internal class RoomMemoryToolExecutor(
    private val database: () -> com.zhiban.rebuild.data.agent.AgentDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    // Per派单 C+ (SOUL.md boundary 7): user data write requires explicit
    // privacy consent before any Room mutation. The gate is a lambda so
    // tests can drive it without a real consent store.
    private val privacyConsent: () -> PrivacyConsent = { PrivacyConsent.NotGranted },
    // Per派单 C+: approval payload is sanitized via the existing
    // SecretRedactor before parsing. The redaction is best-effort: known
    // secrets (bearer tokens, api keys) are masked before any inner
    // field is read.
    private val redactor: SecretRedactor = SecretRedactor(),
    private val embeddingGateway: EmbeddingGateway? = null,
) {
    private val memory by lazy { RoomMemoryGate(database(), clock, embeddingGateway) }

    suspend fun recallApproved(query: String, maxItems: Int = 8): ApprovedMemoryRecallResult {
        val attempt = attemptRetrieval("memory_approved") {
            memory.search(
                com.zhiban.agent.memory.MemoryQuery(
                    GLOBAL_NAMESPACE,
                    "local-user",
                    "default",
                    query,
                    maxItems,
                    APPROVED_RECALL_TOKEN_BUDGET,
                ),
            ).items.map { it.canonicalText }
        }
        return ApprovedMemoryRecallResult(
            items = attempt.value.orEmpty(),
            degradationReasons = listOfNotNull(attempt.degradation),
        )
    }

    suspend fun execute(context: ConfirmedToolExecutionContext, call: MemoryRememberToolCall, confirmation: ToolConfirmation): SafeToolResult {
        // Boundary gate (派单 C+ per SOUL.md boundary 7): refuse user
        // data write when the runtime has not recorded explicit privacy
        // consent. Throws rather than silently persisting.
        checkMemoryToolConsent(privacyConsent())
        require(
            call.proposalId == confirmation.proposalId && call.payloadRef == confirmation.payloadRef &&
                call.revision == confirmation.revision && call.canonicalInputDigest == confirmation.canonicalInputDigest,
        )
        require(canonicalMemoryDigest(call) == call.canonicalInputDigest)
        val run = requireNotNull(database().runtimeRunDao().find(context.runId))
        val attemptId = requireNotNull(run.activeAttemptId)
        require(call.idempotencyKey == canonicalMemoryIdempotencyKey(context.runId, attemptId, call))
        val approval = requireNotNull(database().runtimeEventDao().latestByType(context.runId, "ApprovalRequested"))
        // Boundary gate: redact approval payload before parsing. Any
        // bearer / api-key like content is masked so a leaked secret in
        // the approval event cannot flow into our redaction pipeline.
        val safeApprovalJson = redactor.redactJson(approval.payloadJson)
        val approved = Json.parseToJsonElement(safeApprovalJson).jsonObject
        require(approved["proposalId"]?.jsonPrimitive?.content == call.proposalId)
        require(approved["payloadRef"]?.jsonPrimitive?.content == call.payloadRef)

        val now = context.nowEpochMs
        val candidateId = call.candidateId
        val memoryId = "memory-${sha256(call.idempotencyKey).take(24)}"
        val logicalMemoryId = "logical-${sha256("${call.subjectKey}|${call.predicateKey}|${call.content}").take(24)}"
        val approvalRef = "approval-${sha256(call.proposalId).take(24)}"
        val source = "runtime:${context.runId}"
        val canonical = call.content.trim().replace(Regex("\\s+"), " ")
        return database().withTransaction {
            database().runtimeToolExecutionDao().findByKey(call.idempotencyKey)?.let { existing ->
                return@withTransaction SafeToolResult(
                    requireNotNull(existing.resultRef),
                    requireNotNull(existing.safeResultJson),
                )
            }
            val freshRun = requireNotNull(database().runtimeRunDao().find(context.runId))
            check(freshRun.status == RuntimeRunStatus.EXECUTING.name && freshRun.activeAttemptId == attemptId)
            val session = requireNotNull(database().runtimeSessionDao().find(freshRun.sessionId))
            check(
                session.leaseOwnerId == context.ownerId && session.leaseEpoch == context.fencingEpoch &&
                    (session.leaseExpiresAtEpochMs ?: 0) > now,
            )
            val persisted = requireNotNull(database().stagedMemoryCandidateDao().find(candidateId))
            if (persisted.state == "PENDING") {
                check(database().stagedMemoryCandidateDao().approve(candidateId, approvalRef, persisted.revision, now) == 1)
            }
            memory.ensureNamespace(MemoryNamespace(GLOBAL_NAMESPACE, "local-user", "default", "GLOBAL", "", now))
            val commit = memory.commit(
                MemoryCommit(
                    GLOBAL_NAMESPACE, candidateId, approvalRef, 1, memoryId, logicalMemoryId,
                    call.memoryType, call.subjectKey, call.predicateKey, canonical, sha256(canonical), sha256(source),
                ),
            )
            val safeResult = memorySafeResult(commit.memoryId)
            database().runtimeToolExecutionDao().insert(
                RuntimeToolExecutionEntity(
                    "exec-${sha256(call.idempotencyKey).take(32)}", context.runId, call.logicalStepId,
                    MemoryRememberPlanValidator.TOOL_NAME, 1, call.canonicalInputDigest, call.idempotencyKey,
                    call.providerCallId, call.proposalId, sha256(call.payloadRef), call.revision, attemptId,
                    "SUCCEEDED", commit.memoryId, safeResult, context.fencingEpoch, now, now,
                ),
            )
            check(database().runtimeAttemptDao().finish(attemptId, "SUCCEEDED", now) == 1)
            val sequence = session.nextSequence
            check(database().runtimeSessionDao().advanceSequence(freshRun.sessionId, sequence, sequence + 1, now) == 1)
            database().runtimeEventDao().insert(
                RuntimeEventEntity(
                    "event-${sha256("MemoryCommitted:${context.runId}:${call.providerCallId}").take(32)}",
                    RUNTIME_SCHEMA_VERSION, "MemoryCommitted", freshRun.sessionId, context.runId, attemptId,
                    sequence, call.providerCallId, context.runId, "runtime-memory-tool-v1", safeResult, now, context.fencingEpoch,
                ),
            )
            check(
                database().runtimeRunDao().transition(
                    context.runId,
                    RuntimeRunStatus.EXECUTING.name,
                    RuntimeRunStatus.OBSERVING.name,
                    sequence,
                    now,
                ) ==
                    1,
            )
            SafeToolResult(commit.memoryId, safeResult)
        }
    }

    companion object {
        const val GLOBAL_NAMESPACE = "runtime-global"
        const val APPROVED_RECALL_TOKEN_BUDGET = 600
    }
}

private fun memorySafeResult(memoryId: String) = buildJsonObject {
    put("memoryId", memoryId)
    put("status", "remembered")
}.toString()

internal data class ApprovedMemoryRecallResult(val items: List<String>, val degradationReasons: List<String> = emptyList())

/**
 * Shared boundary check for durable memory writes. It is deliberately outside the executors so
 * both the legacy confirmed path and the reversible upsert path enforce the same user consent.
 */
internal fun checkMemoryToolConsent(consent: PrivacyConsent) {
    if (consent != PrivacyConsent.Granted) {
        throw ProviderFailure("MEMORY_CONSENT_REQUIRED", retryable = false)
    }
}
