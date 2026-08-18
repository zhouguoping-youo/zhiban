package com.zhiban.rebuild.runtime.tool

import com.zhiban.rebuild.foundation.RuntimeToolSpec
import com.zhiban.rebuild.runtime.governance.ContactCreateCandidateCall
import com.zhiban.rebuild.runtime.governance.ContactDomainWriter
import com.zhiban.rebuild.runtime.store.RoomRuntimeStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class ContactCreateCandidateToolBinding(
    override val spec: RuntimeToolSpec,
    private val store: RoomRuntimeStore,
    private val writer: ContactDomainWriter,
) : RuntimeToolBinding {
    override suspend fun requestApproval(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): Boolean {
        val allowed = setOf(
            "displayName",
            "roleType",
            "phone",
            "email",
            "wechatId",
            "company",
            "title",
            "note",
        )
        val args = parseToolArgs(request.argumentsJson, allowed) { throw IllegalArgumentException("INVALID_TOOL_ARGUMENTS") }
        fun optional(name: String, max: Int) = args[name]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() }
            ?.also { require(it.length <= max) { "INVALID_TOOL_ARGUMENTS" } }
        val displayName = requireNotNull(optional("displayName", 100)) { "INVALID_TOOL_ARGUMENTS" }
        val role = optional("roleType", 40)?.also { require(it in ALLOWED_ROLES) { "INVALID_TOOL_ARGUMENTS" } }
        val staged = buildJsonObject {
            put("displayName", displayName)
            put("normalizedName", normalizeContactQuery(displayName))
            optional("phone", 40)?.let { put("phone", it) }
            optional("email", 200)?.let { put("email", it) }
            optional("wechatId", 100)?.let { put("wechatId", it) }
            optional("company", 200)?.let { put("company", it) }
            optional("title", 100)?.let { put("title", it) }
            optional("note", 1000)?.let { put("note", it) }
            role?.let { put("roleType", it) }
        }.toString()
        val digest = sha256(staged)
        val contactId = "contact-${sha256("${context.runId}:${request.providerCallId}").take(24)}"
        val envelope = PlanEnvelopeFactory.create(
            request,
            context,
            spec.name,
            digest,
            "contact-stage",
        )
        val call = ContactCreateCandidateCall(
            request.providerCallId,
            envelope.logicalStepId,
            envelope.proposalId,
            envelope.payloadRef,
            context.revision,
            digest,
            envelope.idempotencyKey,
            "candidate-${sha256("${context.runId}:${request.providerCallId}:$digest").take(24)}",
            contactId,
        )
        return store.requestContactApproval(
            call, staged, displayName, context.sessionId, context.runId, context.attemptId,
            context.ownerId, context.fencingEpoch, context.nowEpochMs,
        )
    }

    override suspend fun executeApproved(planJson: String, context: ConfirmedToolExecutionContext): RoutedToolResult {
        val value = parseToolArgs(planJson, null) { IllegalArgumentException("INVALID_TOOL_CALL") }
        fun required(name: String) = value[name]?.jsonPrimitive?.content ?: error("INVALID_TOOL_CALL")
        val call = ContactCreateCandidateCall(
            required("providerCallId"), required("logicalStepId"), required("proposalId"), required("payloadRef"),
            required("revision").toLong(), required("canonicalInputDigest"), required("idempotencyKey"),
            required("candidateId"), required("contactId"),
        )
        val result = writer.execute(
            context,
            call,
            ToolConfirmation(call.proposalId, call.payloadRef, call.revision, call.canonicalInputDigest),
        )
        return RoutedToolResult(spec.name, call.providerCallId, result.safeResultJson)
    }

    private companion object {
        val ALLOWED_ROLES = setOf("CUSTOMER", "FRIEND", "FAMILY", "TEACHER", "PROJECT_PARTNER")
    }
}
