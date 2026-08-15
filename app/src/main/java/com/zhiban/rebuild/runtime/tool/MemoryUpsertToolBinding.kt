package com.zhiban.rebuild.runtime.tool

import androidx.room.withTransaction
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.runtime.governance.AutoWriteAuditDraft
import com.zhiban.rebuild.runtime.governance.AutoWriteToolNames
import com.zhiban.rebuild.runtime.governance.ChangeLogEntity
import com.zhiban.rebuild.runtime.governance.ReversibleWriteReadiness
import com.zhiban.rebuild.runtime.governance.insertVisibleAutoWrite
import com.zhiban.rebuild.runtime.memory.MemoryAtomicStore
import com.zhiban.rebuild.runtime.memory.MemoryNamespaceEntity
import com.zhiban.rebuild.runtime.memory.MemoryUpsertRequest
import com.zhiban.rebuild.runtime.provider.OutboundPiiDetector
import com.zhiban.rebuild.runtime.provider.ProviderFailure
import com.zhiban.rebuild.runtime.store.ApprovedToolExecutionRequest
import com.zhiban.rebuild.runtime.store.RoomRuntimeStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class MemoryUpsertToolBinding(override val spec: RuntimeToolSpec, private val store: RoomRuntimeStore, private val writer: MemoryUpsertDomainWriter) :
    ReversibleAutoWriteBinding {
    init {
        require(spec.name == TOOL_NAME && spec.risk == RuntimeToolRisk.REVERSIBLE_AUTO_WRITE)
    }

    override suspend fun requestApproval(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): Boolean {
        val plan = buildPlan(request, context)
        return store.requestReversibleWriteApproval(
            plan.toString(),
            request.providerCallId,
            context.sessionId,
            context.runId,
            context.attemptId,
            context.ownerId,
            context.fencingEpoch,
            context.nowEpochMs,
        )
    }

    override suspend fun reversibleWriteReadiness(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): ReversibleWriteReadiness {
        val plan = buildPlan(request, context)
        val rejection = when {
            plan.requiredText("memoryType") !in AUTO_TYPES -> "auto_write:policy_rejected"
            plan.requiredText("subjectKey") != "user" -> "auto_write:policy_rejected"
            plan.requiredText("sensitivity") !in AUTO_SENSITIVITIES -> "auto_write:policy_rejected"
            plan.requiredText("confidence").toDouble() < MIN_AUTO_CONFIDENCE -> "auto_write:evidence_insufficient"
            OutboundPiiDetector.containsDirectIdentifier(plan.requiredText("content")) -> "auto_write:policy_rejected"
            else -> null
        }
        return ReversibleWriteReadiness(true, true, true, rejection)
    }

    override suspend fun executeReversibleAutoWrite(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): RoutedToolResult =
        writer.executeAuto(buildPlan(request, context), context)

    override suspend fun executeApproved(planJson: String, context: ConfirmedToolExecutionContext): RoutedToolResult =
        writer.executeApproved(parsePlan(planJson), context)

    private fun buildPlan(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): JsonObject {
        val raw = parseToolArgs(request.argumentsJson, ALLOWED) { ProviderFailure("INVALID_TOOL_ARGUMENTS", false) }
        val content = raw.requiredMemoryText("content", 500)
        val memoryType = raw.requiredMemoryText("memoryType", 32)
        val subjectKey = raw.requiredMemoryText("subjectKey", 128)
        val predicateKey = raw.requiredMemoryText("predicateKey", 64)
        val sensitivity = raw.requiredMemoryText("sensitivity", 16)
        val evidence = raw.requiredMemoryText("evidenceSummary", 500)
        val sourceRef = raw.requiredMemoryText("sourceRef", 500)
        val confidence = raw["confidence"]?.jsonPrimitive?.doubleOrNull
            ?.takeIf { it in 0.0..1.0 } ?: throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
        if (!predicateKey.matches(PREDICATE_PATTERN)) {
            throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
        }
        val logicalMemoryId = "logical-auto-${sha256("$subjectKey|$predicateKey").take(24)}"
        val data = buildJsonObject {
            put("logicalMemoryId", logicalMemoryId)
            put("content", content)
            put("memoryType", memoryType)
            put("subjectKey", subjectKey)
            put("predicateKey", predicateKey)
            put("sensitivity", sensitivity)
            put("evidenceSummary", evidence)
            put("sourceRef", sourceRef)
            put("confidence", confidence)
        }
        val digest = sha256(data.toString())
        val envelope = PlanEnvelopeFactory.create(request, context, TOOL_NAME, digest, payloadPrefix = "memory-upsert")
        return buildJsonObject {
            put("toolName", TOOL_NAME)
            put("providerCallId", request.providerCallId)
            put("logicalStepId", envelope.logicalStepId)
            put("proposalId", envelope.proposalId)
            put("payloadRef", envelope.payloadRef)
            put("revision", context.revision)
            put("canonicalInputDigest", digest)
            put("idempotencyKey", envelope.idempotencyKey)
            put("runId", context.runId)
            put("attemptId", context.attemptId)
            data.forEach { (key, value) -> put(key, value) }
            put("title", "更新长期记忆")
            put("message", "$content\n依据：$evidence")
        }
    }

    private fun parsePlan(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject.also { plan ->
        require(plan.requiredText("toolName") == TOOL_NAME)
        PLAN_REQUIRED.forEach(plan::requiredText)
    }

    companion object {
        const val TOOL_NAME = "memory.upsert"
        private const val MIN_AUTO_CONFIDENCE = 0.99
        private val AUTO_TYPES = setOf("PREFERENCE", "FACT")
        private val AUTO_SENSITIVITIES = setOf("PUBLIC", "PERSONAL")
        private val PREDICATE_PATTERN = Regex("[a-z][a-z0-9_.-]{1,63}")
        private val ALLOWED = setOf(
            "content",
            "memoryType",
            "subjectKey",
            "predicateKey",
            "sensitivity",
            "evidenceSummary",
            "confidence",
            "sourceRef",
        )
        private val PLAN_REQUIRED = ALLOWED + setOf(
            "providerCallId",
            "logicalStepId",
            "proposalId",
            "payloadRef",
            "canonicalInputDigest",
            "idempotencyKey",
            "runId",
            "logicalMemoryId",
        )
    }
}

internal class MemoryUpsertDomainWriter(private val database: AgentDatabase, private val store: RoomRuntimeStore) {
    suspend fun executeAuto(plan: JsonObject, context: RuntimeToolRouteContext): RoutedToolResult = database.withTransaction {
        val result = mutate(plan, context.nowEpochMs, auto = true, runtimeRunId = context.runId)
        store.completeReadOnlyTool(
            context.runId,
            plan.requiredText("providerCallId"),
            MemoryUpsertToolBinding.TOOL_NAME,
            1,
            plan.requiredText("canonicalInputDigest"),
            result.safeResultJson,
            context.ownerId,
            context.fencingEpoch,
            context.nowEpochMs,
        )
        result
    }

    suspend fun executeApproved(plan: JsonObject, context: ConfirmedToolExecutionContext): RoutedToolResult = database.withTransaction {
        val approval = database.runtimeEventDao().latestByType(context.runId, "ApprovalRequested")
            ?: throw ToolPolicyRejectedException("memory approval is missing")
        require(approval.payloadJson.contains(plan.requiredText("proposalId")))
        val result = mutate(plan, context.nowEpochMs, auto = false, runtimeRunId = context.runId)
        store.completeApprovedRemoteTool(
            ApprovedToolExecutionRequest(
                context.runId,
                plan.requiredText("providerCallId"),
                plan.requiredText("logicalStepId"),
                MemoryUpsertToolBinding.TOOL_NAME,
                1,
                plan.requiredText("canonicalInputDigest"),
                plan.requiredText("idempotencyKey"),
                result.safeResultJson,
                context.ownerId,
                context.fencingEpoch,
                context.nowEpochMs,
            ),
        )
        result
    }

    private suspend fun mutate(plan: JsonObject, now: Long, auto: Boolean, runtimeRunId: String): RoutedToolResult {
        val idempotencyKey = plan.requiredText("idempotencyKey")
        database.runtimeToolExecutionDao().findByKey(idempotencyKey)?.let { execution ->
            return RoutedToolResult(
                MemoryUpsertToolBinding.TOOL_NAME,
                plan.requiredText("providerCallId"),
                requireNotNull(execution.safeResultJson),
            )
        }
        val memory = MemoryAtomicStore(database) { now }
        memory.ensureNamespace(
            MemoryNamespaceEntity(
                RoomMemoryToolExecutor.GLOBAL_NAMESPACE,
                "local-user",
                "default",
                "GLOBAL",
                "",
                "ACTIVE",
                0,
                0,
                now,
            ),
        )
        val persisted = memory.upsertReversible(
            MemoryUpsertRequest(
                RoomMemoryToolExecutor.GLOBAL_NAMESPACE,
                plan.requiredText("logicalMemoryId"),
                plan.requiredText("memoryType"),
                plan.requiredText("subjectKey"),
                plan.requiredText("predicateKey"),
                plan.requiredText("content"),
                plan.requiredText("sensitivity"),
                plan.requiredText("confidence").toDouble(),
                plan.requiredText("sourceRef"),
            ),
        )
        val changeId = changeIdFor(idempotencyKey)
        if (persisted.changed) {
            recordChange(
                MemoryChangeWrite(
                    plan = plan,
                    persisted = persisted,
                    changeId = changeId,
                    idempotencyKey = idempotencyKey,
                    runtimeRunId = runtimeRunId,
                    nowEpochMs = now,
                    automatic = auto,
                ),
            )
        }
        val safe = buildJsonObject {
            put("status", if (persisted.changed) if (persisted.created) "remembered" else "updated" else "unchanged")
            put("logicalMemoryId", plan.requiredText("logicalMemoryId"))
            put("changeId", if (persisted.changed) changeId else "")
            put("undoAvailable", persisted.changed)
        }.toString()
        return RoutedToolResult(MemoryUpsertToolBinding.TOOL_NAME, plan.requiredText("providerCallId"), safe)
    }

    private suspend fun recordChange(write: MemoryChangeWrite) {
        val plan = write.plan
        val persisted = write.persisted
        if (write.automatic) {
            database.insertVisibleAutoWrite(
                AutoWriteAuditDraft(
                    write.changeId,
                    write.runtimeRunId,
                    AutoWriteToolNames.MEMORY_UPSERT,
                    write.idempotencyKey,
                    "MEMORY",
                    plan.requiredText("logicalMemoryId"),
                    if (persisted.created) "INSERT" else "UPDATE",
                    persisted.beforeDigest,
                    persisted.afterDigest,
                    persisted.inversePayloadJson,
                    "RUNTIME_TOOL",
                    null,
                    // This is a model-initiated auto write, not something the user typed; label it
                    // AGENT_AUTO (the CRM auto-write convention) rather than falsely USER_AUTHORED.
                    "AGENT_AUTO",
                    plan.requiredText("sourceRef"),
                    plan.requiredText("confidence").toDouble(),
                    "MEMORY",
                    "MEMORY_MANAGER",
                    write.nowEpochMs,
                ),
            )
        } else {
            database.changeLogDao().insert(
                ChangeLogEntity(
                    write.changeId,
                    write.runtimeRunId,
                    MemoryUpsertToolBinding.TOOL_NAME,
                    write.idempotencyKey,
                    "MEMORY",
                    plan.requiredText("logicalMemoryId"),
                    if (persisted.created) "INSERT" else "UPDATE",
                    persisted.beforeDigest,
                    persisted.afterDigest,
                    persisted.inversePayloadJson,
                    "AVAILABLE",
                    write.nowEpochMs,
                    null,
                ),
            )
        }
    }
}

private data class MemoryChangeWrite(
    val plan: JsonObject,
    val persisted: com.zhiban.rebuild.runtime.memory.MemoryUpsertResult,
    val changeId: String,
    val idempotencyKey: String,
    val runtimeRunId: String,
    val nowEpochMs: Long,
    val automatic: Boolean,
)

private fun JsonObject.requiredMemoryText(name: String, maxLength: Int): String =
    this[name]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() && it.length <= maxLength }
        ?: throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
