package com.zhiban.rebuild.runtime.tool

import com.zhiban.rebuild.data.contact.ContactDao
import com.zhiban.rebuild.data.contact.ContactIntelligenceDao
import com.zhiban.rebuild.runtime.governance.ContactIdentityResolutionCall
import com.zhiban.rebuild.runtime.governance.ContactIdentityResolutionDomainWriter
import com.zhiban.rebuild.runtime.store.RoomRuntimeStore
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Confirmation-gated binding for associating one observed platform identity with one person. */
internal class ContactIdentityResolutionToolBinding(
    override val spec: RuntimeToolSpec,
    private val contacts: ContactDao,
    private val intelligence: ContactIntelligenceDao,
    private val store: RoomRuntimeStore,
    private val writer: ContactIdentityResolutionDomainWriter,
) : RuntimeToolBinding {
    override suspend fun requestApproval(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): Boolean {
        val args = parseToolArgs(
            request.argumentsJson,
            setOf("sourceIdentityId", "contactId", "evidenceSummary", "confidence"),
        ) { throw IllegalArgumentException("INVALID_TOOL_ARGUMENTS") }
        fun required(name: String, maxLength: Int) = args[name]?.jsonPrimitive?.content?.trim()
            ?.takeIf { it.isNotBlank() && it.length <= maxLength }
            ?: throw IllegalArgumentException("INVALID_TOOL_ARGUMENTS")
        val sourceIdentityId = required("sourceIdentityId", 256)
        val contactId = required("contactId", 128)
        val evidenceSummary = required("evidenceSummary", 1_000)
        val confidence = args["confidence"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.7
        require(confidence in 0.0..1.0) { "INVALID_TOOL_ARGUMENTS" }
        val identity = intelligence.findSourceIdentity(sourceIdentityId)
            ?.takeIf { it.personId == null && it.resolutionStatus in setOf("UNRESOLVED", "CANDIDATE") }
            ?: throw IllegalArgumentException("SOURCE_IDENTITY_NOT_UNRESOLVED")
        val contact = contacts.findById(contactId) ?: throw IllegalArgumentException("CONTACT_NOT_FOUND")
        val digest = sha256(
            buildJsonObject {
                put("sourceIdentityId", sourceIdentityId)
                put("contactId", contactId)
                put("evidenceDigest", sha256(evidenceSummary))
                put("confidence", confidence)
            }.toString(),
        )
        val envelope = PlanEnvelopeFactory.create(request, context, spec.name, digest, "identity-resolution")
        val call = ContactIdentityResolutionCall(
            providerCallId = request.providerCallId,
            logicalStepId = envelope.logicalStepId,
            proposalId = envelope.proposalId,
            payloadRef = envelope.payloadRef,
            revision = context.revision,
            canonicalInputDigest = digest,
            idempotencyKey = envelope.idempotencyKey,
            sourceIdentityId = sourceIdentityId,
            contactId = contactId,
            evidenceDigest = sha256(evidenceSummary),
            confidence = 1.0,
            previousStatus = identity.resolutionStatus,
            previousConfidence = identity.confidence,
        )
        return store.requestContactIdentityResolutionApproval(
            call = call,
            visibleHandle = identity.visibleHandle,
            platform = identity.sourceType,
            contactName = contact.displayName,
            sessionId = context.sessionId,
            runId = context.runId,
            attemptId = context.attemptId,
            ownerId = context.ownerId,
            fencingEpoch = context.fencingEpoch,
            nowEpochMs = context.nowEpochMs,
        )
    }

    override suspend fun executeApproved(planJson: String, context: ConfirmedToolExecutionContext): RoutedToolResult {
        val value = parseToolArgs(planJson, null) { IllegalArgumentException("INVALID_TOOL_CALL") }
        fun required(name: String) = value[name]?.jsonPrimitive?.content ?: error("INVALID_TOOL_CALL")
        val call = ContactIdentityResolutionCall(
            providerCallId = required("providerCallId"),
            logicalStepId = required("logicalStepId"),
            proposalId = required("proposalId"),
            payloadRef = required("payloadRef"),
            revision = required("revision").toLong(),
            canonicalInputDigest = required("canonicalInputDigest"),
            idempotencyKey = required("idempotencyKey"),
            sourceIdentityId = required("sourceIdentityId"),
            contactId = required("contactId"),
            evidenceDigest = required("evidenceDigest"),
            confidence = required("confidence").toDouble(),
            previousStatus = required("previousStatus"),
            previousConfidence = required("previousConfidence").toDouble(),
        )
        val result = writer.execute(
            context,
            call,
            ToolConfirmation(call.proposalId, call.payloadRef, call.revision, call.canonicalInputDigest),
        )
        return RoutedToolResult(spec.name, call.providerCallId, result.safeResultJson)
    }
}
