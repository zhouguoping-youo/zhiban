package com.zhiban.rebuild.data.agent

import androidx.room.withTransaction
import com.zhiban.rebuild.data.contact.ContactEmploymentEntity
import com.zhiban.rebuild.data.contact.ContactEnrichmentCandidateEntity
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.OrganizationEntity
import com.zhiban.rebuild.data.contact.PersonEmploymentEpisodeEntity
import com.zhiban.rebuild.data.contact.PersonEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Applies user-confirmed enrichment evidence inside one transaction. */
internal class ContactEnrichmentDomainWriter(private val database: AgentDatabase) {
    suspend fun apply(candidateId: String, nowEpochMs: Long): Boolean = database.withTransaction {
        val candidate = database.contactKnowledgeDao().findEnrichmentCandidate(candidateId)
            ?: return@withTransaction false
        if (!candidate.canApply(nowEpochMs)) return@withTransaction false
        val contactId = candidate.contactId ?: return@withTransaction false
        val contact = database.contactDao().findRawById(contactId) ?: return@withTransaction false
        val value = parseValue(candidate.proposedValueJson) ?: return@withTransaction false
        val applied = when (candidate.fieldKind) {
            "ORGANIZATION" -> applyOrganization(contact, candidate, value, nowEpochMs)

            "EMPLOYMENT", "COMMUNICATION_METHOD", "RESPONSIBILITIES" -> applyScalarPatch(
                contact,
                candidate,
                value,
                nowEpochMs,
            )

            else -> false
        }
        check(
            database.contactKnowledgeDao().resolveEnrichmentCandidate(
                candidateId = candidate.candidateId,
                status = "APPROVED",
                nowEpochMs = nowEpochMs,
            ) == 1,
        )
        applied
    }

    private suspend fun applyOrganization(contact: ContactEntity, candidate: ContactEnrichmentCandidateEntity, value: JsonObject, nowEpochMs: Long): Boolean {
        val canonicalName = normalizeOrganizationFullName(value.text("canonicalName") ?: value.text("company") ?: return false)
        val matchedHint = value.text("matchedCompanyHint")
        require(
            matchedHint == null || contact.company?.trim() == matchedHint,
        ) { "CONTACT_COMPANY_CHANGED" }
        val creditCode = value.text("creditCode")
        val organizationId = stableContactKnowledgeId(
            "organization",
            creditCode?.let { "CREDIT:$it" } ?: "NAME:${canonicalName.lowercase()}",
        )
        upsertOrganization(organizationId, canonicalName, creditCode, candidate, value, nowEpochMs)
        upsertEmployment(contact, organizationId, canonicalName, candidate, nowEpochMs)
        val shouldUpdateProfile = contact.company.isNullOrBlank() || matchedHint != null
        if (shouldUpdateProfile && contact.company != canonicalName) {
            check(
                database.contactDao().update(
                    contact.copy(company = canonicalName, updatedAtEpochMs = nowEpochMs),
                ) == 1,
            )
        }
        return true
    }

    private suspend fun upsertOrganization(
        organizationId: String,
        canonicalName: String,
        creditCode: String?,
        candidate: ContactEnrichmentCandidateEntity,
        value: JsonObject,
        nowEpochMs: Long,
    ) {
        val knowledge = database.contactKnowledgeDao()
        val existing = knowledge.findOrganization(organizationId)
        knowledge.upsertOrganization(
            OrganizationEntity(
                organizationId = organizationId,
                canonicalName = canonicalName,
                normalizedName = canonicalName.lowercase().filterNot(Char::isWhitespace),
                creditCode = creditCode ?: existing?.creditCode,
                status = value.text("registrationStatus") ?: existing?.status,
                registeredAddress = value.text("registeredAddress") ?: existing?.registeredAddress,
                longitude = existing?.longitude,
                latitude = existing?.latitude,
                source = candidate.providerId,
                sourceRef = candidate.sourceRef,
                userConfirmed = true,
                verifiedAtEpochMs = nowEpochMs,
                createdAtEpochMs = existing?.createdAtEpochMs ?: nowEpochMs,
                updatedAtEpochMs = nowEpochMs,
            ),
        )
    }

    private suspend fun upsertEmployment(
        contact: ContactEntity,
        organizationId: String,
        canonicalName: String,
        candidate: ContactEnrichmentCandidateEntity,
        nowEpochMs: Long,
    ) {
        val knowledge = database.contactKnowledgeDao()
        val employmentId = stableContactKnowledgeId(contact.contactId, "EMPLOYMENT", organizationId)
        val existing = knowledge.findEmployment(employmentId)
        knowledge.upsertEmployment(
            ContactEmploymentEntity(
                employmentId = employmentId,
                contactId = contact.contactId,
                organizationId = organizationId,
                companyNameSnapshot = canonicalName,
                department = existing?.department,
                title = existing?.title ?: contact.title,
                jobDescription = existing?.jobDescription,
                officeLocation = existing?.officeLocation,
                isCurrent = existing?.isCurrent ?: false,
                source = candidate.providerId,
                evidenceRef = candidate.sourceRef,
                confidence = candidate.confidence,
                userConfirmed = true,
                createdAtEpochMs = existing?.createdAtEpochMs ?: nowEpochMs,
                updatedAtEpochMs = nowEpochMs,
            ),
        )
        upsertTemporalEmployment(contact, organizationId, canonicalName, candidate, nowEpochMs)
    }

    private suspend fun upsertTemporalEmployment(
        contact: ContactEntity,
        organizationId: String,
        canonicalName: String,
        candidate: ContactEnrichmentCandidateEntity,
        nowEpochMs: Long,
    ) {
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
                    nowEpochMs,
                ),
            )
        }
        val episodeId = stableContactKnowledgeId(contact.contactId, "CONFIRMED_EMPLOYMENT", organizationId)
        val existing = intelligence.findEmploymentEpisode(episodeId)
        intelligence.upsertEmployment(
            PersonEmploymentEpisodeEntity(
                episodeId = episodeId,
                personId = contact.contactId,
                organizationId = organizationId,
                companyNameSnapshot = canonicalName,
                department = existing?.department,
                title = existing?.title ?: contact.title,
                validFromEpochMs = existing?.validFromEpochMs,
                validToEpochMs = existing?.validToEpochMs,
                temporalPrecision = existing?.temporalPrecision ?: "UNKNOWN",
                currentState = existing?.currentState ?: "UNKNOWN",
                sourceRef = candidate.sourceRef,
                confidence = candidate.confidence,
                verificationState = "USER_CONFIRMED",
                status = "ACTIVE",
                recordedAtEpochMs = existing?.recordedAtEpochMs ?: nowEpochMs,
                updatedAtEpochMs = nowEpochMs,
            ),
        )
    }

    private suspend fun applyScalarPatch(contact: ContactEntity, candidate: ContactEnrichmentCandidateEntity, value: JsonObject, nowEpochMs: Long): Boolean {
        val patch = scalarPatch(candidate.fieldKind, value).mapValues { (field, proposed) ->
            if (field == "company") normalizeOrganizationFullName(proposed) else proposed
        }
        var updated = contact
        var applied = false
        patch.forEach { (field, proposed) ->
            if (updated.field(field).isNullOrBlank()) {
                updated = updated.withField(field, proposed)
                applied = true
            }
        }
        if (applied) {
            check(database.contactDao().update(updated.copy(updatedAtEpochMs = nowEpochMs)) == 1)
            if (contact.company.isNullOrBlank() && !updated.company.isNullOrBlank()) {
                val company = requireNotNull(updated.company)
                val organization = database.upsertUserConfirmedOrganization(company, candidate.sourceRef ?: candidate.providerId, nowEpochMs)
                upsertEmployment(updated, organization.organizationId, company, candidate, nowEpochMs)
            }
        }
        return applied
    }

    private fun scalarPatch(fieldKind: String, value: JsonObject): Map<String, String> = when (fieldKind) {
        "EMPLOYMENT" -> listOfNotNull(
            value.text("title")?.let { "title" to it },
            value.text("company")?.let { "company" to it },
        ).toMap()

        "COMMUNICATION_METHOD" -> listOfNotNull(
            value.text("phone")?.let { "phone" to it },
            value.text("email")?.let { "email" to it },
            value.text("wechatId")?.let { "wechatId" to it },
        ).toMap()

        "RESPONSIBILITIES" -> listOfNotNull(
            value.text("responsibilities")?.let { "responsibilities" to it },
        ).toMap()

        else -> emptyMap()
    }

    private fun ContactEntity.field(name: String): String? = when (name) {
        "phone" -> phone
        "email" -> email
        "wechatId" -> wechatId
        "company" -> company
        "title" -> title
        "responsibilities" -> responsibilities
        else -> null
    }

    private fun ContactEntity.withField(name: String, value: String): ContactEntity = when (name) {
        "phone" -> copy(phone = value)
        "email" -> copy(email = value)
        "wechatId" -> copy(wechatId = value)
        "company" -> copy(company = value)
        "title" -> copy(title = value)
        "responsibilities" -> copy(responsibilities = value)
        else -> this
    }

    private fun ContactEnrichmentCandidateEntity.canApply(nowEpochMs: Long): Boolean = status == "PENDING" && expiresAtEpochMs?.let { it > nowEpochMs } != false

    private fun parseValue(raw: String): JsonObject? = runCatching {
        JSON.parseToJsonElement(raw).jsonObject
    }.getOrNull()

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= MAX_FIELD_CHARS }

    private companion object {
        const val MAX_FIELD_CHARS = 500
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
