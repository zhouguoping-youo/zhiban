package com.zhiban.rebuild.runtime.tool
import androidx.room.withTransaction
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.runtime.governance.AutoWriteAuditDraft
import com.zhiban.rebuild.runtime.governance.AutoWriteToolNames
import com.zhiban.rebuild.runtime.governance.ChangeLogEntity
import com.zhiban.rebuild.runtime.governance.ReversibleWriteReadiness
import com.zhiban.rebuild.runtime.governance.insertVisibleAutoWrite
import com.zhiban.rebuild.runtime.provider.ProviderFailure
import com.zhiban.rebuild.runtime.runSuspendCatching
import com.zhiban.rebuild.runtime.store.ApprovedToolExecutionRequest
import com.zhiban.rebuild.runtime.store.RoomRuntimeStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class ContactTagToolBinding(override val spec: RuntimeToolSpec, private val store: RoomRuntimeStore, private val writer: ContactTagDomainWriter) :
    ReversibleAutoWriteBinding {
    init {
        require(spec.name == TOOL_NAME)
        require(spec.risk == RuntimeToolRisk.REVERSIBLE_AUTO_WRITE)
    }

    override suspend fun requestApproval(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): Boolean {
        val plan = buildPlan(request, context)
        return store.requestContactTagApproval(
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
        val confidence = plan["confidence"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val tag = plan.requiredText("tag")
        val rejected = when {
            confidence < 0.99 -> "auto_write:evidence_insufficient"

            plan.optionalText(
                "sourceRef",
            ) == null || plan.optionalText("evidenceSummary") == null -> "auto_write:evidence_insufficient"

            tag.contains('@') || tag.count(Char::isDigit) >= 7 -> "auto_write:policy_rejected"

            else -> null
        }
        return ReversibleWriteReadiness(true, true, true, rejected)
    }

    override suspend fun executeReversibleAutoWrite(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): RoutedToolResult =
        writer.executeAuto(buildPlan(request, context), context)

    override suspend fun executeApproved(planJson: String, context: ConfirmedToolExecutionContext): RoutedToolResult =
        writer.executeApproved(parsePlan(planJson), context)

    private suspend fun buildPlan(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): JsonObject {
        val raw = parseToolArgs(request.argumentsJson, ALLOWED) { ProviderFailure("INVALID_TOOL_ARGUMENTS", false) }
        REQUIRED.forEach {
            val value = raw[it]?.jsonPrimitive?.content?.trim()
            if (value.isNullOrEmpty()) {
                throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
            }
        }
        val contactId = raw.requiredTrimmed("contactId", 128)
        val contact = writer.requireContact(contactId)
        val tag = raw.requiredTrimmed("tag", 24)
        val evidence = raw.requiredTrimmed("evidenceSummary", 1_000)
        val sourceRef = raw.requiredTrimmed("sourceRef", 500)
        val confidence = raw["confidence"]?.jsonPrimitive?.doubleOrNull
            ?.takeIf { it in 0.0..1.0 } ?: throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
        val data = buildJsonObject {
            put("contactId", contact.contactId)
            put("tag", tag)
            put("evidenceSummary", evidence)
            put("sourceRef", sourceRef)
            put("confidence", confidence)
        }
        val digest = sha256(data.toString())
        val envelope = PlanEnvelopeFactory.create(request, context, TOOL_NAME, digest, payloadPrefix = "contact-tag")
        return buildJsonObject {
            put("toolName", TOOL_NAME)
            put("providerCallId", request.providerCallId)
            put("logicalStepId", "step-${request.providerCallId}")
            put("proposalId", envelope.proposalId)
            put("payloadRef", envelope.payloadRef)
            put("revision", context.revision)
            put("canonicalInputDigest", digest)
            put("idempotencyKey", envelope.idempotencyKey)
            put("runId", context.runId)
            put("attemptId", context.attemptId)
            data.forEach { (key, value) -> put(key, value) }
            put("title", "为${contact.displayName}增加标签")
            put("message", "标签：$tag\n依据：$evidence")
        }
    }

    private fun parsePlan(value: String): JsonObject {
        val plan = Json.parseToJsonElement(value).jsonObject
        require(plan.requiredText("toolName") == TOOL_NAME)
        listOf(
            "providerCallId",
            "logicalStepId",
            "idempotencyKey",
            "canonicalInputDigest",
            "runId",
            "contactId",
            "tag",
        ).forEach(plan::requiredText)
        return plan
    }

    companion object {
        const val TOOL_NAME = "contact.tag.add"
        private val REQUIRED = setOf("contactId", "tag", "evidenceSummary", "confidence", "sourceRef")
        private val ALLOWED = REQUIRED
    }
}

internal class ContactTagDomainWriter(private val database: AgentDatabase, private val store: RoomRuntimeStore) {
    suspend fun requireContact(contactId: String) = database.contactDao().findById(contactId)
        ?.takeUnless { it.source == "CRM_DEMO" || it.contactId.startsWith("crm-demo-") }
        ?: throw ProviderFailure("CONTACT_NOT_FOUND", false)

    suspend fun executeAuto(plan: JsonObject, context: RuntimeToolRouteContext): RoutedToolResult = database.withTransaction {
        val executionKey = sha256(
            "${context.runId}|${plan.requiredText(
                "providerCallId",
            )}|${ContactTagToolBinding.TOOL_NAME}|${plan.requiredText("canonicalInputDigest")}",
        )
        database.runtimeToolExecutionDao().findByKey(executionKey)?.let { execution ->
            return@withTransaction RoutedToolResult(
                ContactTagToolBinding.TOOL_NAME,
                plan.requiredText("providerCallId"),
                requireNotNull(execution.safeResultJson),
            )
        }
        val result = mutate(plan, context.nowEpochMs, auto = true, runtimeRunId = context.runId)
        store.completeReadOnlyTool(
            context.runId, plan.requiredText("providerCallId"), ContactTagToolBinding.TOOL_NAME, 1,
            plan.requiredText("canonicalInputDigest"), result.safeResultJson,
            context.ownerId, context.fencingEpoch, context.nowEpochMs,
        )
        result
    }

    suspend fun executeApproved(plan: JsonObject, context: ConfirmedToolExecutionContext): RoutedToolResult = database.withTransaction {
        val approval = database.runtimeEventDao().latestByType(context.runId, "ApprovalRequested")
            ?: throw ToolPolicyRejectedException("contact tag approval is missing")
        require(approval.payloadJson.contains(plan.requiredText("proposalId")))
        val result = mutate(plan, context.nowEpochMs, auto = false, runtimeRunId = context.runId)
        store.completeApprovedRemoteTool(
            ApprovedToolExecutionRequest(
                context.runId,
                plan.requiredText("providerCallId"),
                plan.requiredText("logicalStepId"),
                ContactTagToolBinding.TOOL_NAME,
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
        database.runtimeToolExecutionDao().findByKey(idempotencyKey)?.let {
            return RoutedToolResult(
                ContactTagToolBinding.TOOL_NAME,
                plan.requiredText("providerCallId"),
                requireNotNull(it.safeResultJson),
            )
        }
        val canonical = requireContact(plan.requiredText("contactId"))
        val contact = requireNotNull(database.contactDao().findRawById(canonical.contactId))
        val tag = plan.requiredText("tag")
        val tags = runSuspendCatching { Json.parseToJsonElement(contact.tagsJson).jsonArray.map { it.jsonPrimitive.content } }
            .getOrDefault(emptyList())
        if (tag in tags) {
            return RoutedToolResult(
                ContactTagToolBinding.TOOL_NAME,
                plan.requiredText("providerCallId"),
                "{\"status\":\"unchanged\",\"contactId\":\"${contact.contactId}\"}",
            )
        }
        val updated = contact.copy(
            tagsJson = buildJsonArray { (tags + tag).distinct().forEach { add(JsonPrimitive(it)) } }.toString(),
            updatedAtEpochMs = now,
        )
        check(database.contactDao().update(updated) == 1)
        val changeId = changeIdFor(idempotencyKey)
        val afterDigest = sha256(updated.tagsJson)
        if (auto) {
            database.insertVisibleAutoWrite(
                AutoWriteAuditDraft(
                    changeId, runtimeRunId, AutoWriteToolNames.CONTACT_TAG_ADD, idempotencyKey,
                    "CONTACT", contact.contactId, "UPDATE", sha256(contact.tagsJson), afterDigest,
                    "{\"removeTag\":${JsonPrimitive(tag)}}", "RUNTIME_TOOL", contact.contactId,
                    "AGENT_INFERENCE", plan.requiredText("sourceRef"), plan.requiredText("confidence").toDouble(),
                    "CONTACT_TAG", "CONTACT_TAG_EDITOR", now,
                ),
            )
        } else {
            database.changeLogDao().insert(
                ChangeLogEntity(
                    changeId, runtimeRunId, ContactTagToolBinding.TOOL_NAME, idempotencyKey,
                    "CONTACT", contact.contactId, "UPDATE", sha256(contact.tagsJson), afterDigest,
                    "{\"removeTag\":${JsonPrimitive(tag)}}", "AVAILABLE", now, null,
                ),
            )
        }
        val safe = "{\"status\":\"tag_added\",\"contactId\":\"${contact.contactId}\",\"changeId\":\"$changeId\",\"undoAvailable\":true}"
        return RoutedToolResult(ContactTagToolBinding.TOOL_NAME, plan.requiredText("providerCallId"), safe)
    }
}

private fun JsonObject.requiredTrimmed(name: String, maxLength: Int): String =
    this[name]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() && it.length <= maxLength }
        ?: throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
