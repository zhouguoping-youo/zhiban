package com.zhiban.rebuild.runtime.governance

import androidx.room.withTransaction
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.agent.ToolAuditEntity
import com.zhiban.rebuild.data.agent.normalizeOrganizationFullName
import com.zhiban.rebuild.data.agent.stableContactKnowledgeId
import com.zhiban.rebuild.data.agent.upsertUserConfirmedOrganization
import com.zhiban.rebuild.data.contact.ContactEmploymentEntity
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.PersonEmploymentEpisodeEntity
import com.zhiban.rebuild.data.contact.PersonEntity
import com.zhiban.rebuild.data.contact.RelationshipPersonIds
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class ContactProfileCandidateCall(
    val providerCallId: String,
    val logicalStepId: String,
    val proposalId: String,
    val payloadRef: String,
    val revision: Long,
    val canonicalInputDigest: String,
    val idempotencyKey: String,
    val candidateId: String,
    val contactId: String,
    val confidence: Double,
)

/** Applies an approved, additive-only profile patch. Existing non-blank fields are never replaced. */
internal class ContactProfileDomainWriter(private val database: AgentDatabase) {
    suspend fun execute(context: ConfirmedToolExecutionContext, call: ContactProfileCandidateCall, confirmation: ToolConfirmation): SafeToolResult =
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
            val applied = validateAndApplyProfile(context, call)
            val changeId = approveAndWriteChangeLog(applied, call, context)
            val safe = writeAuditRecords(call, context, start, applied, changeId)
            completeProfileRun(call, context, start, safe)
        }

    private suspend fun validateAndApplyProfile(context: ConfirmedToolExecutionContext, call: ContactProfileCandidateCall): AppliedProfile {
        val staged = requireNotNull(database.stagedContactCandidateDao().find(call.candidateId))
        require(staged.state in setOf("PENDING", "APPROVED") && staged.expiresAtEpochMs > context.nowEpochMs)
        require(staged.payloadDigest == call.canonicalInputDigest)
        val payload = Json.parseToJsonElement(staged.payloadJson).jsonObject
        return if (call.contactId == RelationshipPersonIds.SELF) {
            applyOwnerEmployment(payload, context)
        } else {
            applyContactProfile(payload, context, call)
        }
    }

    private suspend fun applyContactProfile(
        payload: kotlinx.serialization.json.JsonObject,
        context: ConfirmedToolExecutionContext,
        call: ContactProfileCandidateCall,
    ): AppliedProfile {
        val contact = requireNotNull(database.contactDao().findById(call.contactId))
        fun proposed(name: String) = payload[name]?.jsonPrimitive?.content?.trim()?.takeIf(String::isNotBlank)?.let { value ->
            if (name == "company") normalizeOrganizationFullName(value) else value
        }
        fun additive(current: String?, name: String): String? {
            val value = proposed(name) ?: return current
            require(current.isNullOrBlank() || current == value) { "CONTACT_FIELD_CONFLICT:$name" }
            return current ?: value
        }

        val updated = contact.copy(
            phone = additive(contact.phone, "phone"),
            email = additive(contact.email, "email"),
            wechatId = additive(contact.wechatId, "wechatId"),
            company = additive(contact.company, "company"),
            title = additive(contact.title, "title"),
            note = additive(contact.note, "note"),
            updatedAtEpochMs = context.nowEpochMs,
        )
        val changedFields = PROFILE_FIELDS.filter { name ->
            proposed(name) != null &&
                fieldValue(contact, name).isNullOrBlank()
        }
        val factText = proposed("factText")
        val factType = proposed("factType")
        require(changedFields.isNotEmpty() || factText != null) { "CONTACT_PROFILE_NO_CHANGE" }
        if (changedFields.isNotEmpty()) check(database.contactDao().update(updated) == 1)
        val employmentWrite = writeConfirmedContactEmployment(updated, changedFields, context)

        val factId = factText?.let { text ->
            val type = requireNotNull(factType).also { require(it in FACT_TYPES) }
            val id = "contact-profile:${call.contactId}:${sha256("$type:$text").take(24)}"
            FactIndex(database).upsert(
                FactEntity(
                    factId = id,
                    factType = type,
                    textContent = text.take(1_000),
                    structuredDataJson = null,
                    sourceType = "AGENT_DOMAIN_WRITE",
                    sourceRef = context.runId,
                    contactId = call.contactId,
                    skillId = "contact_relationship",
                    confidence = call.confidence,
                    sensitivity = if (type == "IMPORTANT_DATE") "SENSITIVE" else "NORMAL",
                    status = "ACTIVE",
                    ttlDays = 0,
                    expiresAtEpochMs = null,
                    createdAtEpochMs = context.nowEpochMs,
                    updatedAtEpochMs = context.nowEpochMs,
                ),
            )
            id
        }
        return AppliedProfile(updated, changedFields, factId, null, employmentWrite)
    }

    private suspend fun writeConfirmedContactEmployment(
        contact: ContactEntity,
        changedFields: List<String>,
        context: ConfirmedToolExecutionContext,
    ): ContactEmploymentWrite? {
        if ("company" !in changedFields) return null
        val company = normalizeOrganizationFullName(requireNotNull(contact.company))
        val organization = database.upsertUserConfirmedOrganization(
            company,
            "runtime:${context.runId}",
            context.nowEpochMs,
        )
        val knowledge = database.contactKnowledgeDao()
        val contactEmployment = ContactEmploymentEntity(
            employmentId = stableContactKnowledgeId(contact.contactId, "EMPLOYMENT", organization.organizationId),
            contactId = contact.contactId,
            organizationId = organization.organizationId,
            companyNameSnapshot = company,
            department = null,
            title = contact.title,
            jobDescription = null,
            officeLocation = null,
            // A confirmed legal company name is not evidence that the role is current.
            isCurrent = false,
            source = "USER",
            evidenceRef = "runtime:${context.runId}",
            confidence = 1.0,
            userConfirmed = true,
            createdAtEpochMs = context.nowEpochMs,
            updatedAtEpochMs = context.nowEpochMs,
        )
        knowledge.upsertEmployment(contactEmployment)

        val intelligence = database.contactIntelligenceDao()
        if (intelligence.findPerson(contact.contactId) == null) {
            intelligence.upsertPerson(
                PersonEntity(
                    contact.contactId,
                    contact.contactId,
                    contact.displayName,
                    contact.normalizedName,
                    "CONTACT",
                    "ACTIVE",
                    contact.createdAtEpochMs,
                    context.nowEpochMs,
                ),
            )
        }
        val temporalEmployment = PersonEmploymentEpisodeEntity(
            episodeId = stableContactKnowledgeId(
                contact.contactId,
                "USER_EMPLOYMENT",
                "${organization.organizationId}:${context.nowEpochMs}",
            ),
            personId = contact.contactId,
            organizationId = organization.organizationId,
            companyNameSnapshot = company,
            department = null,
            title = contact.title,
            validFromEpochMs = null,
            validToEpochMs = null,
            temporalPrecision = "UNKNOWN",
            currentState = "UNKNOWN",
            sourceRef = "runtime:${context.runId}",
            confidence = 1.0,
            verificationState = "USER_CONFIRMED",
            status = "ACTIVE",
            recordedAtEpochMs = context.nowEpochMs,
            updatedAtEpochMs = context.nowEpochMs,
        )
        intelligence.upsertEmployment(temporalEmployment)
        return ContactEmploymentWrite(contactEmployment, temporalEmployment)
    }

    private suspend fun applyOwnerEmployment(payload: kotlinx.serialization.json.JsonObject, context: ConfirmedToolExecutionContext): AppliedProfile {
        val company = normalizeOrganizationFullName(
            requireNotNull(payload["company"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)),
        )
        val title = payload["title"]?.jsonPrimitive?.content?.trim()?.takeIf(String::isNotBlank)
        val intelligence = database.contactIntelligenceDao()
        require(intelligence.findCurrentUserEmployment(RelationshipPersonIds.SELF) == null) {
            "OWNER_CURRENT_EMPLOYMENT_ALREADY_CONFIRMED"
        }
        if (intelligence.findPerson(RelationshipPersonIds.SELF) == null) {
            intelligence.upsertPerson(
                PersonEntity(
                    personId = RelationshipPersonIds.SELF,
                    canonicalContactId = null,
                    displayName = "我",
                    normalizedName = "我",
                    kind = "SELF",
                    status = "ACTIVE",
                    createdAtEpochMs = context.nowEpochMs,
                    updatedAtEpochMs = context.nowEpochMs,
                ),
            )
        }
        val organization = database.upsertUserConfirmedOrganization(
            fullName = company,
            sourceRef = "runtime:${context.runId}",
            nowEpochMs = context.nowEpochMs,
        )
        val employment = PersonEmploymentEpisodeEntity(
            episodeId = "owner-employment-${sha256(company.lowercase()).take(24)}",
            personId = RelationshipPersonIds.SELF,
            organizationId = organization.organizationId,
            companyNameSnapshot = company,
            department = null,
            title = title,
            validFromEpochMs = null,
            validToEpochMs = null,
            temporalPrecision = "UNKNOWN",
            currentState = "CURRENT",
            sourceRef = "runtime:${context.runId}",
            confidence = 1.0,
            verificationState = "USER_CONFIRMED",
            status = "ACTIVE",
            recordedAtEpochMs = context.nowEpochMs,
            updatedAtEpochMs = context.nowEpochMs,
        )
        intelligence.upsertEmployment(employment)
        val changedFields = buildList {
            add("company")
            if (title != null) add("title")
        }
        return AppliedProfile(null, changedFields, null, employment, null)
    }

    private suspend fun approveAndWriteChangeLog(applied: AppliedProfile, call: ContactProfileCandidateCall, context: ConfirmedToolExecutionContext): String {
        database.stagedContactCandidateDao().approve(call.candidateId, context.nowEpochMs)

        val appliedDigest = applied.employment?.let(::ownerEmploymentDigest)
            ?: applied.contactEmploymentWrite?.let { write ->
                contactProfileWriteDigest(requireNotNull(applied.updatedContact), applied.changedFields, write)
            }
            ?: contactProfileFieldsDigest(requireNotNull(applied.updatedContact), applied.changedFields)
        val inverse = buildJsonObject {
            if (applied.updatedContact != null) {
                put("clearFields", buildJsonArray { applied.changedFields.forEach { add(JsonPrimitive(it)) } })
            }
            applied.factId?.let { put("deleteFactId", it) }
            applied.employment?.let { put("deleteEmploymentEpisodeId", it.episodeId) }
            applied.contactEmploymentWrite?.let { write ->
                put("deleteContactEmploymentId", write.contactEmployment.employmentId)
                put("deleteEmploymentEpisodeId", write.temporalEmployment.episodeId)
            }
        }.toString()
        val changeId = changeIdFor(call.idempotencyKey)
        database.changeLogDao().insert(
            ChangeLogEntity(
                changeId,
                context.runId,
                TOOL_NAME,
                call.idempotencyKey,
                if (applied.employment == null) "CONTACT" else "PERSON_EMPLOYMENT",
                call.contactId,
                "UPDATE",
                null,
                appliedDigest,
                inverse,
                "AVAILABLE",
                context.nowEpochMs,
                null,
            ),
        )
        return changeId
    }

    private suspend fun writeAuditRecords(
        call: ContactProfileCandidateCall,
        context: ConfirmedToolExecutionContext,
        start: ConfirmedContactWriteStart,
        applied: AppliedProfile,
        changeId: String,
    ): String {
        val attemptId = start.attemptId
        val safe = buildJsonObject {
            put("contactId", call.contactId)
            put("updatedFieldCount", applied.changedFields.size)
            put("factAdded", applied.factId != null)
            put("employmentAdded", applied.employment != null)
            put("confidence", call.confidence)
            put("status", "profile_enriched")
            put("changeId", changeId)
            put("undoAvailable", true)
        }.toString()
        database.toolAuditDao().insert(
            ToolAuditEntity(
                auditIdFor(call.idempotencyKey),
                null,
                sha256(context.runId),
                call.providerCallId,
                TOOL_NAME,
                call.idempotencyKey,
                call.canonicalInputDigest,
                context.runId,
                attemptId,
                call.proposalId,
                sha256(call.payloadRef),
                call.revision,
                status = "SUCCEEDED",
                resultJson = safe,
                expiresAtEpochMs = null,
                createdAtEpochMs = context.nowEpochMs,
                updatedAtEpochMs = context.nowEpochMs,
            ),
        )
        database.runtimeToolExecutionDao().insert(
            RuntimeToolExecutionEntity(
                "exec-${sha256(call.idempotencyKey).take(32)}",
                context.runId,
                call.logicalStepId,
                TOOL_NAME,
                1,
                call.canonicalInputDigest,
                call.idempotencyKey,
                call.providerCallId,
                call.proposalId,
                sha256(call.payloadRef),
                call.revision,
                attemptId,
                "SUCCEEDED",
                call.contactId,
                safe,
                context.fencingEpoch,
                context.nowEpochMs,
                context.nowEpochMs,
            ),
        )
        return safe
    }

    private suspend fun completeProfileRun(
        call: ContactProfileCandidateCall,
        context: ConfirmedToolExecutionContext,
        start: ConfirmedContactWriteStart,
        safe: String,
    ): SafeToolResult {
        val attemptId = start.attemptId
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
                "event-${sha256("ContactProfileEnriched:${context.runId}:${call.providerCallId}").take(32)}",
                RUNTIME_SCHEMA_VERSION,
                "ContactProfileEnriched",
                start.sessionId,
                context.runId,
                attemptId,
                sequence,
                call.providerCallId,
                context.runId,
                "contact-profile-domain-writer-v1",
                safe,
                context.nowEpochMs,
                context.fencingEpoch,
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
        return SafeToolResult(call.contactId, safe)
    }

    private data class AppliedProfile(
        val updatedContact: ContactEntity?,
        val changedFields: List<String>,
        val factId: String?,
        val employment: PersonEmploymentEpisodeEntity?,
        val contactEmploymentWrite: ContactEmploymentWrite?,
    )

    internal data class ContactEmploymentWrite(val contactEmployment: ContactEmploymentEntity, val temporalEmployment: PersonEmploymentEpisodeEntity)

    companion object {
        const val TOOL_NAME = "contact.profile.proposeUpdate"
        val PROFILE_FIELDS = listOf("phone", "email", "wechatId", "company", "title", "note")
        val FACT_TYPES = setOf("CONTACT_MEMORY", "IMPORTANT_DATE", "COMMUNICATION_PREFERENCE", "CURRENT_MATTER")
    }
}

internal fun contactProfileFieldsDigest(contact: com.zhiban.rebuild.data.contact.ContactEntity, fields: List<String>): String = sha256(
    buildJsonObject {
        fields.sorted().forEach { name -> fieldValue(contact, name)?.let { put(name, it) } }
    }.toString(),
)

internal fun contactProfileWriteDigest(contact: ContactEntity, fields: List<String>, employment: ContactProfileDomainWriter.ContactEmploymentWrite?): String =
    sha256(
        buildJsonObject {
            put("profile", contactProfileFieldsDigest(contact, fields))
            employment?.let { write ->
                put("contactEmploymentId", write.contactEmployment.employmentId)
                put("temporalEmploymentId", write.temporalEmployment.episodeId)
                put("organizationId", requireNotNull(write.contactEmployment.organizationId))
                put("company", write.contactEmployment.companyNameSnapshot)
                write.contactEmployment.title?.let { put("title", it) }
            }
        }.toString(),
    )

internal fun ownerEmploymentDigest(value: PersonEmploymentEpisodeEntity): String = sha256(
    buildJsonObject {
        put("episodeId", value.episodeId)
        put("personId", value.personId)
        put("company", value.companyNameSnapshot)
        value.title?.let { put("title", it) }
        put("currentState", value.currentState)
        put("verificationState", value.verificationState)
        put("status", value.status)
    }.toString(),
)

internal fun fieldValue(contact: com.zhiban.rebuild.data.contact.ContactEntity, name: String): String? = when (name) {
    "phone" -> contact.phone
    "email" -> contact.email
    "wechatId" -> contact.wechatId
    "company" -> contact.company
    "title" -> contact.title
    "note" -> contact.note
    else -> null
}
