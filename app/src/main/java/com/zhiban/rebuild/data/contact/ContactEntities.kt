package com.zhiban.rebuild.data.contact

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/**
 * Stable identity used when the signed-in user participates in the relationship graph.
 *
 * The owner is deliberately not persisted as a ContactEntity: their canonical data lives in the
 * encrypted UserProfileStore and must never be deleted, merged, or re-imported as another person.
 */
object RelationshipPersonIds {
    const val SELF = "user:self"
}

@Entity(
    tableName = "contacts",
    indices = [
        Index(value = ["normalizedName"]),
        Index(value = ["phone"]),
        Index(value = ["email"]),
        Index(value = ["company"]),
        Index(value = ["source"]),
        // observeActive/search 的 deletedAtEpochMs IS NULL 过滤 + ORDER BY updatedAtEpochMs(P1-性能/索引)。
        Index(value = ["normalizedName", "deletedAtEpochMs"]),
    ],
)
data class ContactEntity(
    @PrimaryKey val contactId: String,
    val displayName: String,
    val normalizedName: String,
    val phone: String?,
    val email: String?,
    val wechatId: String?,
    val company: String?,
    val title: String?,
    val aliasesJson: String,
    val tagsJson: String,
    val note: String?,
    val avatarUri: String?,
    val source: String,
    val deletedAtEpochMs: Long?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    // "负责什么"（业务范围/职责）。联系人级、可编辑的一等字段；区别于 contact_employments.jobDescription
    // （绑任职记录、且只有系统导入会写）。带默认值以免破坏既有的位置化构造调用。
    val responsibilities: String? = null,
)

@Entity(
    tableName = "contact_roles",
    primaryKeys = ["contactId", "skillId", "roleType"],
    foreignKeys = [
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["contactId"],
            childColumns = ["contactId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("contactId"), Index("skillId"), Index("roleType")],
)
data class ContactRoleEntity(
    val contactId: String,
    val skillId: String,
    val roleType: String,
    val confidence: Double,
    val userConfirmed: Boolean,
    val profileJson: String?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

/**
 * A name the user or an observed source uses for the same person.
 *
 * Aliases are separate rows rather than an opaque JSON field so identity
 * resolution can be exact, explainable and independently removable.
 */
@Entity(
    tableName = "contact_aliases",
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
        Index(value = ["normalizedAlias"]),
        Index(value = ["contactId", "normalizedAlias"], unique = true),
    ],
)
data class ContactAliasEntity(
    @PrimaryKey val aliasId: String,
    val contactId: String,
    val alias: String,
    val normalizedAlias: String,
    val aliasType: String,
    val source: String,
    val userConfirmed: Boolean,
    val createdAtEpochMs: Long,
)

/**
 * A stable identity belonging to a contact on a communication platform.
 *
 * The platform user id is optional because users often only know a visible
 * handle. Duplicate detection never treats display names as a unique key.
 */
@Entity(
    tableName = "contact_platform_identities",
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
        Index(value = ["platform", "normalizedHandle"]),
        Index(value = ["contactId", "platform", "normalizedHandle"], unique = true),
    ],
)
data class ContactPlatformIdentityEntity(
    @PrimaryKey val identityId: String,
    val contactId: String,
    val platform: String,
    val handle: String,
    val normalizedHandle: String,
    val platformUserId: String?,
    val source: String,
    val userConfirmed: Boolean,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

/**
 * Non-destructive, reversible canonical-person mapping.
 *
 * The source contact remains intact. Readers hide it while the link is active,
 * so undo only needs to close this mapping rather than reconstruct deleted
 * relationship or memory rows.
 */
@Entity(
    tableName = "contact_merge_links",
    foreignKeys = [
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["contactId"],
            childColumns = ["sourceContactId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["contactId"],
            childColumns = ["canonicalContactId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("canonicalContactId"), Index("undoneAtEpochMs")],
)
data class ContactMergeLinkEntity(
    @PrimaryKey val sourceContactId: String,
    val canonicalContactId: String,
    val reason: String,
    val userConfirmed: Boolean,
    val createdAtEpochMs: Long,
    val undoneAtEpochMs: Long?,
)

data class ContactSearchProjection(
    val contactId: String,
    val displayName: String,
    val phone: String?,
    val email: String?,
    val wechatId: String?,
    val company: String?,
    val title: String?,
    val note: String?,
)

@Entity(
    tableName = "relationship_edges",
    indices = [Index("fromContactId"), Index("toContactId"), Index("skillId"), Index("status")],
)
data class RelationshipEdgeEntity(
    @PrimaryKey val edgeId: String,
    val fromContactId: String,
    val toContactId: String,
    val relationType: String,
    val evidenceDigest: String,
    val evidenceRefsJson: String,
    val confidence: Double,
    val userConfirmed: Boolean,
    val skillId: String?,
    val status: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "relationship_events",
    indices = [Index("eventType"), Index("status"), Index("occurredAtEpochMs")],
)
data class RelationshipEventEntity(
    @PrimaryKey val eventId: String,
    val eventType: String,
    val title: String,
    val note: String?,
    val occurredAtEpochMs: Long?,
    val evidenceDigest: String,
    val evidenceRefsJson: String,
    val userConfirmed: Boolean,
    val status: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "relationship_event_participants",
    foreignKeys = [
        ForeignKey(
            entity = RelationshipEventEntity::class,
            parentColumns = ["eventId"],
            childColumns = ["eventId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("eventId"), Index("contactId"), Index("participantRole")],
)
data class RelationshipEventParticipantEntity(
    @PrimaryKey val participantId: String,
    val eventId: String,
    val participantKind: String,
    val contactId: String?,
    val participantRole: String,
    val displayNameSnapshot: String,
    val createdAtEpochMs: Long,
)

data class RelationshipEventWithParticipants(
    @Embedded val event: RelationshipEventEntity,
    @Relation(
        parentColumn = "eventId",
        entityColumn = "eventId",
    )
    val participants: List<RelationshipEventParticipantEntity>,
)

@Entity(tableName = "staged_contact_candidates", indices = [Index("state"), Index("expiresAtEpochMs")])
data class StagedContactCandidateEntity(
    @PrimaryKey val candidateId: String,
    val payloadJson: String,
    val payloadDigest: String,
    val state: String,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(tableName = "contact_search_fts")
@androidx.room.Fts4
data class ContactFtsEntity(val contactId: String, val content: String)
