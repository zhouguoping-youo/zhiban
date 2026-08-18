package com.zhiban.rebuild.data.contact.enrichment

import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.contact.ContactEnrichmentCandidateEntity
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.foundation.runSuspendCatching
import com.zhiban.rebuild.foundation.sha256
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class CompanyEnrichmentRefreshResult(val queriedCompanies: Int, val stagedCandidates: Int, val degradationCodes: Set<String>)

interface CompanyEnrichmentRefresher {
    suspend fun refresh(): CompanyEnrichmentRefreshResult
}

/**
 * Queries public company records using company-name hints only. Results remain PENDING evidence;
 * this coordinator never updates a contact profile or asserts that a person works at a company.
 */
internal class CompanyEnrichmentCoordinator(private val database: AgentDatabase, private val gateway: CompanyRegistryGateway) : CompanyEnrichmentRefresher {
    override suspend fun refresh(): CompanyEnrichmentRefreshResult = refresh(System.currentTimeMillis())

    internal suspend fun refresh(nowEpochMs: Long): CompanyEnrichmentRefreshResult {
        if (!gateway.isConfigured) return CompanyEnrichmentRefreshResult(0, 0, emptySet())
        val eligible = eligibleContacts(nowEpochMs)
        var queriedCompanies = 0
        var stagedCandidates = 0
        val degradationCodes = mutableSetOf<String>()
        eligible.groupBy { normalizeCompanyHint(requireNotNull(it.company)) }.values
            .take(MAX_QUERIES_PER_REFRESH)
            .forEach { contacts ->
                val companyHint = contacts.first().company.orEmpty().trim()
                queriedCompanies += 1
                runSuspendCatching { gateway.search(companyHint) }
                    .onSuccess { matches ->
                        stagedCandidates += stageMatches(
                            contacts = contacts,
                            companyHint = companyHint,
                            matches = matches,
                            nowEpochMs = nowEpochMs,
                        )
                    }
                    .onFailure { degradationCodes += "company_registry:failure" }
            }
        return CompanyEnrichmentRefreshResult(
            queriedCompanies = queriedCompanies,
            stagedCandidates = stagedCandidates,
            degradationCodes = degradationCodes,
        )
    }

    private suspend fun eligibleContacts(nowEpochMs: Long): List<ContactEntity> {
        val knowledge = database.contactKnowledgeDao()
        return database.contactDao().listActiveForIntelligence().filter { contact ->
            val company = contact.company?.trim()
            company != null && company.length >= MIN_QUERY_CHARS &&
                CompanyShortNameDetector.classify(company) == CompanyShortNameDetector.Classification.SUSPECTED_SHORT &&
                knowledge.countActiveEnrichmentCandidates(
                    contactId = contact.contactId,
                    providerId = gateway.providerId,
                    fieldKind = FIELD_KIND,
                    nowEpochMs = nowEpochMs,
                ) == 0
        }
    }

    private suspend fun stageMatches(contacts: List<ContactEntity>, companyHint: String, matches: List<CompanyRegistryMatch>, nowEpochMs: Long): Int {
        var staged = 0
        matches.asSequence()
            .filter { it.confidence >= MIN_STAGED_CONFIDENCE }
            .sortedByDescending(CompanyRegistryMatch::confidence)
            .take(MAX_STAGED_MATCHES)
            .forEach { match ->
                contacts.forEach { contact ->
                    if (stageMatch(contact, companyHint, match, nowEpochMs)) staged += 1
                }
            }
        return staged
    }

    private suspend fun stageMatch(contact: ContactEntity, companyHint: String, match: CompanyRegistryMatch, nowEpochMs: Long): Boolean {
        val evidenceKey = "${contact.contactId}|${match.providerRecordId}|$companyHint"
        val candidate = ContactEnrichmentCandidateEntity(
            candidateId = "registry-org-${sha256(evidenceKey).take(24)}",
            contactId = contact.contactId,
            providerId = gateway.providerId,
            fieldKind = FIELD_KIND,
            proposedValueJson = proposedValue(companyHint, match),
            sourceRef = gateway.sourceLabel,
            confidence = match.confidence,
            status = "PENDING",
            observedAtEpochMs = nowEpochMs,
            expiresAtEpochMs = nowEpochMs + CANDIDATE_TTL_MS,
            createdAtEpochMs = nowEpochMs,
            updatedAtEpochMs = nowEpochMs,
        )
        return database.contactKnowledgeDao().insertEnrichmentCandidateIfAbsent(candidate) != -1L
    }

    private fun proposedValue(companyHint: String, match: CompanyRegistryMatch): String = buildJsonObject {
        val canonicalName = match.canonicalName.trim()
        put("company", canonicalName)
        put("canonicalName", canonicalName)
        put("matchedCompanyHint", companyHint)
        put("providerRecordId", match.providerRecordId)
        match.creditCode?.let { put("creditCode", it) }
        match.registrationStatus?.let { put("registrationStatus", it) }
        match.registeredAddress?.let { put("registeredAddress", it) }
        match.sourceUrl?.let { put("sourceUrl", it) }
        put(
            "matchReasons",
            buildJsonArray { match.matchReasons.forEach { add(JsonPrimitive(it)) } },
        )
    }.toString()

    private fun normalizeCompanyHint(value: String): String = value
        .trim()
        .lowercase()
        .filterNot(Char::isWhitespace)

    private companion object {
        const val FIELD_KIND = "ORGANIZATION"
        const val MIN_QUERY_CHARS = 2
        const val MIN_STAGED_CONFIDENCE = 0.65
        const val MAX_STAGED_MATCHES = 3
        const val MAX_QUERIES_PER_REFRESH = 20
        const val CANDIDATE_TTL_MS = 30L * 24 * 60 * 60 * 1_000
    }
}
