package com.zhiban.rebuild.data.contact

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Independently verifiable ways to reach a person. */
@Entity(
    tableName = "contact_methods",
    foreignKeys = [
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["contactId"],
            childColumns = ["contactId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("contactId"),
        Index(value = ["kind", "normalizedValue"]),
        Index(value = ["contactId", "kind", "normalizedValue"], unique = true),
    ],
)
data class ContactMethodEntity(
    @PrimaryKey val methodId: String,
    val contactId: String,
    val kind: String,
    val value: String,
    val normalizedValue: String,
    val label: String?,
    val isPrimary: Boolean,
    val source: String,
    val evidenceRef: String?,
    val confidence: Double,
    val userConfirmed: Boolean,
    val verifiedAtEpochMs: Long?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

/** Canonical organisation record. Provider results remain candidates until confirmed. */
@Entity(
    tableName = "organizations",
    indices = [Index("normalizedName"), Index("creditCode", unique = true)],
)
data class OrganizationEntity(
    @PrimaryKey val organizationId: String,
    val canonicalName: String,
    val normalizedName: String,
    val creditCode: String?,
    val status: String?,
    val registeredAddress: String?,
    val longitude: Double?,
    val latitude: Double?,
    val source: String,
    val sourceRef: String?,
    val userConfirmed: Boolean,
    val verifiedAtEpochMs: Long?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "contact_employments",
    foreignKeys = [
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["contactId"],
            childColumns = ["contactId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = OrganizationEntity::class,
            parentColumns = ["organizationId"],
            childColumns = ["organizationId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("contactId"), Index("organizationId"), Index("isCurrent")],
)
data class ContactEmploymentEntity(
    @PrimaryKey val employmentId: String,
    val contactId: String,
    val organizationId: String?,
    val companyNameSnapshot: String,
    val department: String?,
    val title: String?,
    val jobDescription: String?,
    val officeLocation: String?,
    val isCurrent: Boolean,
    val source: String,
    val evidenceRef: String?,
    val confidence: Double,
    val userConfirmed: Boolean,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "contact_addresses",
    foreignKeys = [
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["contactId"],
            childColumns = ["contactId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("contactId"), Index(value = ["contactId", "kind", "formattedAddress"], unique = true)],
)
data class ContactAddressEntity(
    @PrimaryKey val addressId: String,
    val contactId: String,
    val kind: String,
    val formattedAddress: String,
    val longitude: Double?,
    val latitude: Double?,
    val precision: String?,
    val source: String,
    val evidenceRef: String?,
    val userConfirmed: Boolean,
    val verifiedAtEpochMs: Long?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "contact_important_dates",
    foreignKeys = [
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["contactId"],
            childColumns = ["contactId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("contactId"), Index(value = ["contactId", "kind"], unique = true)],
)
data class ContactImportantDateEntity(
    @PrimaryKey val dateId: String,
    val contactId: String,
    val kind: String,
    val year: Int?,
    val month: Int,
    val day: Int,
    val source: String,
    val evidenceRef: String?,
    val userConfirmed: Boolean,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

/** Multi-dimensional facets avoid forcing one person into one exclusive bucket. */
@Entity(
    tableName = "contact_facets",
    foreignKeys = [
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["contactId"],
            childColumns = ["contactId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            "contactId",
        ), Index("dimension"), Index(value = ["contactId", "dimension", "value"], unique = true),
    ],
)
data class ContactFacetEntity(
    @PrimaryKey val facetId: String,
    val contactId: String,
    val dimension: String,
    val value: String,
    val source: String,
    val evidenceRef: String?,
    val confidence: Double,
    val userConfirmed: Boolean,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

/** Untrusted external/agent suggestions. Applying one always requires an explicit approval. */
@Entity(
    tableName = "contact_enrichment_candidates",
    indices = [Index("contactId"), Index("providerId"), Index("status"), Index("expiresAtEpochMs")],
)
data class ContactEnrichmentCandidateEntity(
    @PrimaryKey val candidateId: String,
    val contactId: String?,
    val providerId: String,
    val fieldKind: String,
    val proposedValueJson: String,
    val sourceRef: String?,
    val confidence: Double,
    val status: String,
    val observedAtEpochMs: Long,
    val expiresAtEpochMs: Long?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

/** User-confirmed mapping from an imported contact card to the signed-in owner. */
@Entity(
    tableName = "owner_contact_links",
    foreignKeys = [
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["contactId"],
            childColumns = ["contactId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("undoneAtEpochMs")],
)
data class OwnerContactLinkEntity(
    @PrimaryKey val contactId: String,
    val reason: String,
    val userConfirmed: Boolean,
    val createdAtEpochMs: Long,
    val undoneAtEpochMs: Long?,
)
