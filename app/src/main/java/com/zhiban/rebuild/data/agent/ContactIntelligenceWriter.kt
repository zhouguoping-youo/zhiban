package com.zhiban.rebuild.data.agent

import com.zhiban.rebuild.data.contact.AndroidRawContactLinkEntity
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.ContactIntelligenceDao
import com.zhiban.rebuild.data.contact.ContactSyncSnapshotState
import com.zhiban.rebuild.data.contact.IdentityClaimEntity
import com.zhiban.rebuild.data.contact.OrganizationEntity
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
    val observation = SystemContactObservation(candidate, contact, sourceRef, nowEpochMs)
    dao.upsertPerson(contact.toPerson())
    val androidIdentity = candidate.toAndroidSourceIdentity(contact.contactId, sourceRef, nowEpochMs)
    dao.upsertAndroidIdentity(androidIdentity, nowEpochMs)
    candidate.observedClaims(contact.contactId, androidIdentity.sourceIdentityId, sourceRef, nowEpochMs)
        .forEach { dao.upsertClaim(it) }
    upsertObservedEmployment(observation)
    dao.upsertObservedPlatformIdentities(observation)
    dao.upsertAndroidSyncState(observation)
}

private suspend fun ContactIntelligenceDao.upsertAndroidIdentity(androidIdentity: SourceIdentityEntity, nowEpochMs: Long) {
    val existingIdentity = findSourceIdentity(androidIdentity.sourceIdentityId)
    upsertSourceIdentity(
        androidIdentity.copy(
            firstObservedAtEpochMs = existingIdentity?.firstObservedAtEpochMs ?: nowEpochMs,
        ),
    )
}

private suspend fun AgentDatabase.upsertObservedEmployment(observation: SystemContactObservation) {
    observation.candidate.company?.trim()?.takeIf(String::isNotEmpty)?.let { company ->
        val organizationId = stableContactKnowledgeId("organization", "NAME", company.lowercase())
        contactKnowledgeDao().upsertOrganization(
            OrganizationEntity(
                organizationId = organizationId,
                canonicalName = company,
                normalizedName = company.lowercase().filterNot(Char::isWhitespace),
                creditCode = null,
                status = null,
                registeredAddress = null,
                longitude = null,
                latitude = null,
                source = "SYSTEM_CONTACT",
                sourceRef = observation.sourceRef,
                userConfirmed = false,
                verifiedAtEpochMs = null,
                createdAtEpochMs = observation.nowEpochMs,
                updatedAtEpochMs = observation.nowEpochMs,
            ),
        )
        contactIntelligenceDao().upsertEmployment(
            PersonEmploymentEpisodeEntity(
                episodeId = stableContactKnowledgeId(
                    observation.contact.contactId,
                    "OBSERVED_EMPLOYMENT",
                    observation.sourceRef,
                ),
                personId = observation.contact.contactId,
                organizationId = organizationId,
                companyNameSnapshot = company,
                department = observation.candidate.department.cleanObservedValue(),
                title = observation.candidate.title.cleanObservedValue(),
                validFromEpochMs = null,
                validToEpochMs = null,
                temporalPrecision = "UNKNOWN",
                currentState = "UNKNOWN",
                sourceRef = observation.sourceRef,
                confidence = 0.6,
                verificationState = "OBSERVED",
                status = "ACTIVE",
                recordedAtEpochMs = observation.nowEpochMs,
                updatedAtEpochMs = observation.nowEpochMs,
            ),
        )
    }
}

private suspend fun ContactIntelligenceDao.upsertObservedPlatformIdentities(observation: SystemContactObservation) {
    observation.candidate.platformIdentities.forEach { identity ->
        val normalized = identity.handle.normalizeObservedHandle()
        if (normalized.isNotEmpty()) {
            upsertSourceIdentity(
                SourceIdentityEntity(
                    sourceIdentityId = stableContactKnowledgeId("source-identity", identity.platform, normalized),
                    personId = observation.contact.contactId,
                    sourceType = identity.platform,
                    accountScope = "ANDROID_CONTACT_CARD",
                    tenantId = null,
                    stableExternalId = null,
                    visibleHandle = identity.handle.trim(),
                    normalizedHandle = normalized,
                    conversationScopeId = null,
                    resolutionStatus = "RESOLVED",
                    confidence = 0.7,
                    sourceRef = observation.sourceRef,
                    firstObservedAtEpochMs = observation.nowEpochMs,
                    lastObservedAtEpochMs = observation.nowEpochMs,
                ),
            )
        }
    }
}

private suspend fun ContactIntelligenceDao.upsertAndroidSyncState(observation: SystemContactObservation) {
    observation.candidate.rawContacts.forEach { rawContact ->
        val linkId = stableContactKnowledgeId("android-raw-contact", rawContact.rawContactId.toString())
        upsertAndroidRawContactLink(
            AndroidRawContactLinkEntity(
                linkId = linkId,
                personId = observation.contact.contactId,
                aggregateContactId = rawContact.aggregateContactId,
                lookupKey = rawContact.lookupKey,
                rawContactId = rawContact.rawContactId,
                accountName = rawContact.accountName,
                accountType = rawContact.accountType,
                sourceId = rawContact.sourceId,
                version = rawContact.version,
                isReadOnly = rawContact.isReadOnly,
                lastObservedAtEpochMs = observation.nowEpochMs,
            ),
        )
        val observed = observation.candidate.androidProjection()
        upsertSyncSnapshot(ContactSyncSnapshotState.observe(findSyncSnapshot(linkId), linkId, observed, observation.nowEpochMs))
    }
}

private data class SystemContactObservation(val candidate: SystemContactCandidate, val contact: ContactEntity, val sourceRef: String, val nowEpochMs: Long)

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

private fun SystemContactCandidate.androidProjection() = com.zhiban.rebuild.data.contact.ContactSyncProjection(
    displayName = displayName,
    phones = phones,
    emails = emails,
    company = company,
    title = title,
    note = note,
)
