package com.zhiban.rebuild.runtime.tool

import com.zhiban.rebuild.data.contact.ContactDao
import com.zhiban.rebuild.data.contact.ContactEnrichmentCandidateEntity
import com.zhiban.rebuild.data.contact.ContactKnowledgeDao
import com.zhiban.rebuild.data.contact.enrichment.ContactEnrichmentField
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Stages an agent-proposed contact-field enrichment as a PENDING candidate. Never writes the
 * contact profile here — the user confirms the candidate in the contact-detail UI, which applies
 * the field and resolves the candidate. This tool only produces evidence, so there is no
 * approved-execution path.
 */
internal class ContactEnrichmentSuggestToolBinding(
    override val spec: RuntimeToolSpec,
    private val contacts: ContactDao,
    private val knowledge: ContactKnowledgeDao,
) : RuntimeToolBinding {

    override suspend fun requestApproval(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): Boolean {
        val args = parseToolArgs(request.argumentsJson, ALLOWED_KEYS) { throw IllegalArgumentException("INVALID_TOOL_ARGUMENTS") }
        val contactId = args["contactId"]?.jsonPrimitive?.content?.trim()
            ?.takeIf { it.isNotBlank() && it.length <= 128 } ?: throw IllegalArgumentException("INVALID_TOOL_ARGUMENTS")
        val contact = contacts.findById(contactId) ?: throw IllegalArgumentException("CONTACT_NOT_FOUND")

        val fieldName = args["field"]?.jsonPrimitive?.content?.trim()
            ?: throw IllegalArgumentException("INVALID_TOOL_ARGUMENTS")
        val field = runCatching { ContactEnrichmentField.valueOf(fieldName) }
            .getOrElse { throw IllegalArgumentException("INVALID_TOOL_ARGUMENTS") }

        val proposedValueJson = args["proposedValueJson"]?.jsonPrimitive?.content?.trim()
            ?.takeIf { it.isNotBlank() && it.length <= MAX_VALUE_CHARS }
            ?: throw IllegalArgumentException("INVALID_TOOL_ARGUMENTS")
        runCatching { kotlinx.serialization.json.Json.parseToJsonElement(proposedValueJson).jsonObject }
            .getOrElse { throw IllegalArgumentException("INVALID_TOOL_ARGUMENTS") }

        val confidence = args["confidence"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.75
        require(confidence in 0.0..1.0) { "INVALID_TOOL_ARGUMENTS" }
        val sourceRef = args["sourceRef"]?.jsonPrimitive?.content?.trim()
            ?.takeIf { it.isNotBlank() && it.length <= 200 }

        val now = context.nowEpochMs
        val candidate = ContactEnrichmentCandidateEntity(
            candidateId = "enrich-candidate-${sha256("${context.runId}|${request.providerCallId}").take(24)}",
            contactId = contact.contactId,
            providerId = spec.name,
            fieldKind = field.name,
            proposedValueJson = proposedValueJson,
            sourceRef = sourceRef,
            confidence = confidence,
            status = "PENDING",
            observedAtEpochMs = now,
            expiresAtEpochMs = now + SUGGESTION_TTL_MS,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        )
        knowledge.insertEnrichmentCandidateIfAbsent(candidate)
        return true
    }

    private companion object {
        val ALLOWED_KEYS = setOf("contactId", "field", "proposedValueJson", "confidence", "sourceRef")
        const val MAX_VALUE_CHARS = 1_000
        const val SUGGESTION_TTL_MS = 7L * 24 * 60 * 60 * 1_000
    }
}
