package com.zhiban.rebuild.runtime.tool

import com.zhiban.rebuild.data.contact.ContactDao
import com.zhiban.rebuild.data.contact.RelationshipPersonIds
import com.zhiban.rebuild.foundation.RuntimeToolSpec
import com.zhiban.rebuild.runtime.governance.ContactProfileCandidateCall
import com.zhiban.rebuild.runtime.governance.ContactProfileDomainWriter
import com.zhiban.rebuild.runtime.store.RoomRuntimeStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Agent-proposed additive patch for an existing contact profile. */
internal class ContactProfileUpdateCandidateToolBinding(
    override val spec: RuntimeToolSpec,
    private val contacts: ContactDao,
    private val store: RoomRuntimeStore,
    private val writer: ContactProfileDomainWriter,
    private val ownerProfile: () -> ContactOwnerProfileSnapshot = { ContactOwnerProfileSnapshot() },
) : RuntimeToolBinding {
    override suspend fun requestApproval(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): Boolean {
        val args = parseToolArgs(request.argumentsJson, ALLOWED_KEYS) { throw IllegalArgumentException("INVALID_TOOL_ARGUMENTS") }
        if (args.keys.any { it !in ALLOWED_KEYS }) throw IllegalArgumentException("INVALID_TOOL_ARGUMENTS")
        fun required(name: String, max: Int) = args[name]?.jsonPrimitive?.content?.trim()
            ?.takeIf { it.isNotBlank() && it.length <= max } ?: throw IllegalArgumentException("INVALID_TOOL_ARGUMENTS")
        fun optional(name: String, max: Int) = args[name]?.jsonPrimitive?.content?.trim()?.takeIf(String::isNotBlank)
            ?.also { require(it.length <= max) { "INVALID_TOOL_ARGUMENTS" } }
        val contactId = required("contactId", 128)
        val isOwner = contactId == RelationshipPersonIds.SELF
        val contact = contactForTarget(contactId, isOwner)
        val confidence = args["confidence"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: DEFAULT_CONFIDENCE
        require(confidence in 0.0..1.0) { "INVALID_TOOL_ARGUMENTS" }
        val (factText, factType) = validatedFact(
            optional("factText", FACT_TEXT_MAX_LENGTH),
            optional("factType", FACT_TYPE_MAX_LENGTH),
        )
        val patchValues = ContactProfileDomainWriter.PROFILE_FIELDS.associateWith {
            optional(it, FIELD_LIMITS.getValue(it))
        }
        require(patchValues.values.any { it != null } || factText != null) { "INVALID_TOOL_ARGUMENTS" }
        validateOwnerPatch(isOwner, patchValues, factText)
        val evidenceSummary = required("evidenceSummary", 1_000)
        val staged = buildJsonObject {
            put("contactId", contactId)
            patchValues.forEach { (name, value) -> value?.let { put(name, it) } }
            factType?.let { put("factType", it) }
            factText?.let { put("factText", it) }
            put("evidenceDigest", sha256(evidenceSummary))
            put("confidence", confidence)
        }.toString()
        val digest = sha256(staged)
        val envelope = PlanEnvelopeFactory.create(
            request,
            context,
            ContactProfileDomainWriter.TOOL_NAME,
            digest,
            "contact-profile",
        )
        val call = ContactProfileCandidateCall(
            providerCallId = request.providerCallId,
            logicalStepId = "step-${request.providerCallId}",
            proposalId = envelope.proposalId,
            payloadRef = envelope.payloadRef,
            revision = context.revision,
            canonicalInputDigest = digest,
            idempotencyKey = envelope.idempotencyKey,
            candidateId = "profile-candidate-${sha256(digest).take(24)}",
            contactId = contactId,
            confidence = confidence,
        )
        return store.requestContactProfileApproval(
            call = call,
            stagedPayloadJson = staged,
            displayName = if (isOwner) ownerProfile().name.ifBlank { "我" } else requireNotNull(contact).displayName,
            sessionId = context.sessionId,
            runId = context.runId,
            attemptId = context.attemptId,
            ownerId = context.ownerId,
            fencingEpoch = context.fencingEpoch,
            nowEpochMs = context.nowEpochMs,
        )
    }

    private suspend fun contactForTarget(contactId: String, isOwner: Boolean) = if (isOwner) {
        null
    } else {
        contacts.findById(contactId) ?: throw IllegalArgumentException("CONTACT_NOT_FOUND")
    }

    private fun validateOwnerPatch(isOwner: Boolean, patchValues: Map<String, String?>, factText: String?) {
        if (!isOwner) return
        require(patchValues["company"] != null && factText == null) { "INVALID_OWNER_PROFILE_ARGUMENTS" }
        require(patchValues.filterKeys { it !in OWNER_EMPLOYMENT_FIELDS }.values.none { it != null }) {
            "INVALID_OWNER_PROFILE_ARGUMENTS"
        }
    }

    private fun validatedFact(factText: String?, factType: String?): Pair<String?, String?> {
        require((factText == null) == (factType == null)) { "INVALID_TOOL_ARGUMENTS" }
        factType?.let { require(it in ContactProfileDomainWriter.FACT_TYPES) { "INVALID_TOOL_ARGUMENTS" } }
        return factText to factType
    }

    override suspend fun executeApproved(planJson: String, context: ConfirmedToolExecutionContext): RoutedToolResult {
        val value = parseToolArgs(planJson, null) { IllegalArgumentException("INVALID_TOOL_CALL") }
        fun required(name: String) = value[name]?.jsonPrimitive?.content ?: error("INVALID_TOOL_CALL")
        val call = ContactProfileCandidateCall(
            required("providerCallId"),
            required("logicalStepId"),
            required("proposalId"),
            required("payloadRef"),
            required("revision").toLong(),
            required("canonicalInputDigest"),
            required("idempotencyKey"),
            required("candidateId"),
            required("contactId"),
            required("confidence").toDouble(),
        )
        val result = writer.execute(
            context,
            call,
            ToolConfirmation(call.proposalId, call.payloadRef, call.revision, call.canonicalInputDigest),
        )
        return RoutedToolResult(spec.name, call.providerCallId, result.safeResultJson)
    }

    private companion object {
        const val DEFAULT_CONFIDENCE = 0.75
        const val FACT_TEXT_MAX_LENGTH = 1_000
        const val FACT_TYPE_MAX_LENGTH = 40
        val ALLOWED_KEYS = ContactProfileDomainWriter.PROFILE_FIELDS.toSet() +
            setOf("contactId", "factType", "factText", "evidenceSummary", "confidence")
        val FIELD_LIMITS = mapOf(
            "phone" to 40,
            "email" to 200,
            "wechatId" to 100,
            "company" to 200,
            "title" to 100,
            "note" to 1_000,
        )
        val OWNER_EMPLOYMENT_FIELDS = setOf("company", "title")
    }
}
