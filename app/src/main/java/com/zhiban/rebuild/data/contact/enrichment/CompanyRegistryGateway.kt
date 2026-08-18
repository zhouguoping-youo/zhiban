package com.zhiban.rebuild.data.contact.enrichment

data class CompanyRegistryMatch(
    val providerRecordId: String,
    val canonicalName: String,
    val creditCode: String?,
    val registrationStatus: String?,
    val registeredAddress: String?,
    val confidence: Double,
    val matchReasons: List<String>,
    /** Where the user can inspect this evidence. Null for sources without a per-record page. */
    val sourceUrl: String? = null,
)

interface CompanyRegistryGateway {
    val isConfigured: Boolean

    /** Stable provenance id stamped on every staged candidate so the source stays auditable. */
    val providerId: String

    /** Human-facing source label shown on the confirmation card. */
    val sourceLabel: String

    /** Sends only a company-name hint. Contact names, phones and emails are forbidden here. */
    suspend fun search(companyHint: String): List<CompanyRegistryMatch>
}
