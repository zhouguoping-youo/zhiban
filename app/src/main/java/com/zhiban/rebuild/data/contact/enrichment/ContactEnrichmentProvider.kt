package com.zhiban.rebuild.data.contact.enrichment

/**
 * Boundary for commercial enterprise lookup, tenant-directory and geocoding providers.
 *
 * roadmap placeholder, not dead code: external enrichment boundary retained for future
 * contact intelligence roadmap.
 *
 * Implementations receive only the fields the user approved for that lookup. Provider credentials
 * belong in a server-side secret store; they must never be bundled into the Android APK.
 */
@Suppress("unused")
interface ContactEnrichmentProvider {
    val providerId: String
    val supportedFields: Set<ContactEnrichmentField>

    suspend fun suggest(request: ContactEnrichmentRequest): List<ContactEnrichmentSuggestion>
}

enum class ContactEnrichmentField {
    ORGANIZATION,
    EMPLOYMENT,
    ADDRESS,
    COMMUNICATION_METHOD,
}

data class ContactEnrichmentRequest(
    val contactId: String,
    val approvedFields: Set<ContactEnrichmentField>,
    val displayName: String?,
    val companyHint: String?,
    val addressHint: String?,
)

/** A suggestion is evidence, never an automatic overwrite of user-confirmed contact data. */
data class ContactEnrichmentSuggestion(
    val field: ContactEnrichmentField,
    val proposedValueJson: String,
    val sourceRef: String?,
    val confidence: Double,
    val observedAtEpochMs: Long,
    val expiresAtEpochMs: Long?,
)
