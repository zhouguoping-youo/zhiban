package com.zhiban.rebuild.data.contact

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A real-world person. Contacts and platform accounts are projections of this stable identity. */
@Entity(
    tableName = "persons",
    foreignKeys = [
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["contactId"],
            childColumns = ["canonicalContactId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("canonicalContactId", unique = true), Index("kind"), Index("status")],
)
data class PersonEntity(
    @PrimaryKey val personId: String,
    val canonicalContactId: String?,
    val displayName: String,
    val normalizedName: String,
    val kind: String,
    val status: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

/** An identity observed in one account, platform, conversation or device source. */
@Entity(
    tableName = "source_identities",
    foreignKeys = [
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["personId"],
            childColumns = ["personId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("personId"),
        Index(value = ["sourceType", "accountScope", "stableExternalId"]),
        Index(value = ["sourceType", "conversationScopeId", "normalizedHandle"]),
        Index("resolutionStatus"),
        Index("lastObservedAtEpochMs"),
    ],
)
data class SourceIdentityEntity(
    @PrimaryKey val sourceIdentityId: String,
    val personId: String?,
    val sourceType: String,
    val accountScope: String,
    val tenantId: String?,
    val stableExternalId: String?,
    val visibleHandle: String,
    val normalizedHandle: String,
    val conversationScopeId: String?,
    val resolutionStatus: String,
    val confidence: Double,
    val sourceRef: String?,
    val firstObservedAtEpochMs: Long,
    val lastObservedAtEpochMs: Long,
)

/** Field-level assertion with valid time, record time and provenance. */
@Entity(
    tableName = "identity_claims",
    foreignKeys = [
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["personId"],
            childColumns = ["personId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SourceIdentityEntity::class,
            parentColumns = ["sourceIdentityId"],
            childColumns = ["sourceIdentityId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("personId"),
        Index("sourceIdentityId"),
        Index(value = ["personId", "fieldType", "status"]),
        Index(value = ["fieldType", "normalizedValue"]),
        Index("verificationState"),
        Index("validToEpochMs"),
    ],
)
data class IdentityClaimEntity(
    @PrimaryKey val claimId: String,
    val personId: String,
    val fieldType: String,
    val displayValue: String,
    val normalizedValue: String,
    val validFromEpochMs: Long?,
    val validToEpochMs: Long?,
    val temporalPrecision: String,
    val recordedAtEpochMs: Long,
    val sourceIdentityId: String?,
    val sourceRef: String?,
    val confidence: Double,
    val verificationState: String,
    val supersedesClaimId: String?,
    val status: String,
)

/** Time-bounded employment; unknown end time is not equivalent to confirmed current employment. */
@Entity(
    tableName = "person_employment_episodes",
    foreignKeys = [
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["personId"],
            childColumns = ["personId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = OrganizationEntity::class,
            parentColumns = ["organizationId"],
            childColumns = ["organizationId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("personId"), Index("organizationId"), Index("validFromEpochMs"), Index("validToEpochMs"), Index("status")],
)
data class PersonEmploymentEpisodeEntity(
    @PrimaryKey val episodeId: String,
    val personId: String,
    val organizationId: String?,
    val companyNameSnapshot: String,
    val department: String?,
    val title: String?,
    val validFromEpochMs: Long?,
    val validToEpochMs: Long?,
    val temporalPrecision: String,
    val currentState: String,
    val sourceRef: String?,
    val confidence: Double,
    val verificationState: String,
    val status: String,
    val recordedAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

/** A relationship can change over time and multiple relationship types may coexist. */
@Entity(
    tableName = "relationship_episodes",
    foreignKeys = [
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["personId"],
            childColumns = ["fromPersonId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["personId"],
            childColumns = ["toPersonId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("fromPersonId"),
        Index("toPersonId"),
        Index("relationshipType"),
        Index("validFromEpochMs"),
        Index("validToEpochMs"),
        Index("status"),
    ],
)
data class RelationshipEpisodeEntity(
    @PrimaryKey val episodeId: String,
    val fromPersonId: String,
    val toPersonId: String,
    val relationshipType: String,
    val direction: String,
    val validFromEpochMs: Long?,
    val validToEpochMs: Long?,
    val temporalPrecision: String,
    val evidenceRefsJson: String,
    val confidence: Double,
    val verificationState: String,
    val status: String,
    val recordedAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "group_conversations",
    indices = [Index(value = ["platform", "accountScope", "stableGroupId"]), Index("lastObservedAtEpochMs")],
)
data class GroupConversationEntity(
    @PrimaryKey val groupId: String,
    val platform: String,
    val accountScope: String,
    val stableGroupId: String?,
    val displayName: String,
    val sourceRef: String?,
    val firstObservedAtEpochMs: Long,
    val lastObservedAtEpochMs: Long,
)

@Entity(
    tableName = "group_membership_episodes",
    foreignKeys = [
        ForeignKey(
            entity = GroupConversationEntity::class,
            parentColumns = ["groupId"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SourceIdentityEntity::class,
            parentColumns = ["sourceIdentityId"],
            childColumns = ["sourceIdentityId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("groupId"), Index("sourceIdentityId"), Index("validToEpochMs"), Index("status")],
)
data class GroupMembershipEpisodeEntity(
    @PrimaryKey val membershipId: String,
    val groupId: String,
    val sourceIdentityId: String,
    val groupAlias: String?,
    val validFromEpochMs: Long?,
    val validToEpochMs: Long?,
    val status: String,
    val confidence: Double,
    val sourceRef: String?,
    val recordedAtEpochMs: Long,
)

/** Raw Android row ownership and version information required for safe three-way sync. */
@Entity(
    tableName = "android_raw_contact_links",
    foreignKeys = [
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["personId"],
            childColumns = ["personId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("personId"),
        Index("aggregateContactId"),
        Index("lookupKey"),
        Index("rawContactId", unique = true),
        Index(value = ["accountType", "accountName"]),
    ],
)
data class AndroidRawContactLinkEntity(
    @PrimaryKey val linkId: String,
    val personId: String,
    val aggregateContactId: Long,
    val lookupKey: String,
    val rawContactId: Long,
    val accountName: String?,
    val accountType: String?,
    val sourceId: String?,
    val version: Long,
    val isReadOnly: Boolean,
    val lastObservedAtEpochMs: Long,
)

@Entity(
    tableName = "contact_sync_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = AndroidRawContactLinkEntity::class,
            parentColumns = ["linkId"],
            childColumns = ["linkId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("linkId", unique = true), Index("syncState"), Index("updatedAtEpochMs")],
)
data class ContactSyncSnapshotEntity(
    @PrimaryKey val snapshotId: String,
    val linkId: String,
    val baseProjectionJson: String,
    val baseDigest: String,
    val desiredProjectionJson: String?,
    val desiredDigest: String?,
    val syncState: String,
    val lastVerifiedAtEpochMs: Long?,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "contact_sync_operations",
    foreignKeys = [
        ForeignKey(
            entity = AndroidRawContactLinkEntity::class,
            parentColumns = ["linkId"],
            childColumns = ["linkId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("linkId"), Index("contactId"), Index("state"), Index("createdAtEpochMs")],
)
data class ContactSyncOperationEntity(
    @PrimaryKey val operationId: String,
    val linkId: String?,
    val contactId: String,
    /** Stored only inside the SQLCipher database; change_log keeps just operationId. */
    val beforeProjectionJson: String,
    val afterProjectionJson: String,
    val insertedDataRowIdsJson: String,
    val rawContactVersionBefore: Long?,
    val rawContactVersionAfter: Long?,
    val state: String,
    val createdAtEpochMs: Long,
    val undoneAtEpochMs: Long?,
)
