package com.zhiban.rebuild.data.agent

import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.IdentityClaimEntity
import com.zhiban.rebuild.data.contact.PersonEmploymentEpisodeEntity
import com.zhiban.rebuild.data.contact.PersonEntity
import com.zhiban.rebuild.data.contact.SourceIdentityEntity
import com.zhiban.rebuild.data.contact.SystemContactCandidate
import com.zhiban.rebuild.data.contact.normalizeContactPhone

/** Persists observations without promoting an imported card to user-confirmed truth. */
internal suspend fun AgentDatabase.upsertObservedSystemContactIntelligence(
    candidate: SystemContactCandidate,
    contact: ContactEntity,
    sourceRef: String,
    nowEpochMs: Long,
) {
    val dao = contactIntelligenceDao()
    dao.upsertPerson(contact.toPerson())
    val androidIdentity = candidate.toAndroidSourceIdentity(contact.contactId, sourceRef, nowEpochMs)
    dao.upsertSourceIdentity(androidIdentity)
    candidate.observedClaims(contact.contactId, androidIdentity.sourceIdentityId, sourceRef, nowEpochMs)
        .forEach { dao.upsertClaim(it) }
    candidate.company?.trim()?.takeIf(String::isNotEmpty)?.let { company ->
        dao.upsertEmployment(
            PersonEmploymentEpisodeEntity(
                episodeId = stableContactKnowledgeId(contact.contactId, "OBSERVED_EMPLOYMENT", sourceRef),
                personId = contact.contactId,
                organizationId = stableContactKnowledgeId("organization", "NAME", company.lowercase()),
                companyNameSnapshot = company,
                department = candidate.department.cleanObservedValue(),
                title = candidate.title.cleanObservedValue(),
                validFromEpochMs = null,
                validToEpochMs = null,
                temporalPrecision = "UNKNOWN",
                currentState = "UNKNOWN",
                sourceRef = sourceRef,
                confidence = 0.6,
                verificationState = "OBSERVED",
                status = "ACTIVE",
                recordedAtEpochMs = nowEpochMs,
                updatedAtEpochMs = nowEpochMs,
            ),
        )
    }
    candidate.platformIdentities.forEach { identity ->
        val normalized = identity.handle.normalizeObservedHandle()
        if (normalized.isNotEmpty()) {
            dao.upsertSourceIdentity(
                SourceIdentityEntity(
                    sourceIdentityId = stableContactKnowledgeId("source-identity", identity.platform, normalized),
                    personId = contact.contactId,
                    sourceType = identity.platform,
                    accountScope = "ANDROID_CONTACT_CARD",
                    tenantId = null,
                    stableExternalId = null,
                    visibleHandle = identity.handle.trim(),
                    normalizedHandle = normalized,
                    conversationScopeId = null,
                    resolutionStatus = "RESOLVED",
                    confidence = 0.7,
                    sourceRef = sourceRef,
                    firstObservedAtEpochMs = nowEpochMs,
                    lastObservedAtEpochMs = nowEpochMs,
                ),
            )
        }
    }
}

private fun ContactEntity.toPerson() = PersonEntity(
    personId = contactId,
    canonicalContactId = contactId,
    displayName = displayName,
    normalizedName = normalizedName,
    kind = "CONTACT",
    status = if (deletedAtEpochMs == null) "ACTIVE" else "DELETED",
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
)

private fun SystemContactCandidate.toAndroidSourceIdentity(personId: String, sourceRef: String, nowEpochMs: Long) = SourceIdentityEntity(
    sourceIdentityId = stableContactKnowledgeId("source-identity", sourceRef),
    personId = personId,
    sourceType = "ANDROID_CONTACT",
    accountScope = "DEVICE",
    tenantId = null,
    stableExternalId = sourceId,
    visibleHandle = displayName,
    normalizedHandle = displayName.lowercase().filterNot(Char::isWhitespace),
    conversationScopeId = null,
    resolutionStatus = "RESOLVED",
    confidence = 0.7,
    sourceRef = sourceRef,
    firstObservedAtEpochMs = nowEpochMs,
    lastObservedAtEpochMs = nowEpochMs,
)

private fun SystemContactCandidate.observedClaims(personId: String, sourceIdentityId: String, sourceRef: String, nowEpochMs: Long): List<IdentityClaimEntity> =
    buildList {
        val context = ClaimContext(personId, sourceIdentityId, sourceRef, nowEpochMs)
        addObservedClaim(context, ObservedClaimDraft("NAME", displayName, displayName))
        phones.mapNotNull(::normalizeContactPhone).distinct().forEach { phone ->
            addObservedClaim(context, ObservedClaimDraft("PHONE", phone, phone))
        }
        emails.map(String::trim).filter { '@' in it }.map(String::lowercase).distinct().forEach { email ->
            addObservedClaim(context, ObservedClaimDraft("EMAIL", email, email))
        }
        company.cleanObservedValue()?.let { company ->
            addObservedClaim(context, ObservedClaimDraft("COMPANY", company, company.lowercase()))
        }
        title.cleanObservedValue()?.let { title ->
            addObservedClaim(context, ObservedClaimDraft("TITLE", title, title.lowercase()))
        }
    }

private fun MutableList<IdentityClaimEntity>.addObservedClaim(context: ClaimContext, draft: ObservedClaimDraft) {
    val cleanDisplay = draft.displayValue.trim()
    val cleanNormalized = draft.normalizedValue.trim()
    if (cleanDisplay.isEmpty() || cleanNormalized.isEmpty()) return
    add(
        IdentityClaimEntity(
            claimId = stableContactKnowledgeId(
                context.personId,
                "CLAIM",
                draft.fieldType,
                context.sourceRef,
                cleanNormalized,
            ),
            personId = context.personId,
            fieldType = draft.fieldType,
            displayValue = cleanDisplay,
            normalizedValue = cleanNormalized,
            validFromEpochMs = null,
            validToEpochMs = null,
            temporalPrecision = "UNKNOWN",
            recordedAtEpochMs = context.nowEpochMs,
            sourceIdentityId = context.sourceIdentityId,
            sourceRef = context.sourceRef,
            confidence = 0.6,
            verificationState = "OBSERVED",
            supersedesClaimId = null,
            status = "ACTIVE",
        ),
    )
}

private data class ClaimContext(val personId: String, val sourceIdentityId: String, val sourceRef: String, val nowEpochMs: Long)

private data class ObservedClaimDraft(val fieldType: String, val displayValue: String, val normalizedValue: String)

private fun String?.cleanObservedValue(): String? = this?.trim()?.takeIf(String::isNotEmpty)

private fun String.normalizeObservedHandle(): String = trim().trimStart('@').lowercase().filterNot(Char::isWhitespace)
