package com.zhiban.rebuild.data.contact

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactIntelligenceDao {
    @Upsert
    suspend fun upsertPerson(value: PersonEntity)

    @Upsert
    suspend fun upsertSourceIdentity(value: SourceIdentityEntity)

    @Upsert
    suspend fun upsertClaim(value: IdentityClaimEntity)

    @Upsert
    suspend fun upsertEmployment(value: PersonEmploymentEpisodeEntity)

    @Query("SELECT * FROM person_employment_episodes WHERE episodeId = :episodeId")
    suspend fun findEmploymentEpisode(episodeId: String): PersonEmploymentEpisodeEntity?

    @Query("DELETE FROM person_employment_episodes WHERE episodeId = :episodeId")
    suspend fun deleteEmploymentEpisode(episodeId: String): Int

    @Upsert
    suspend fun upsertRelationship(value: RelationshipEpisodeEntity)

    @Upsert
    suspend fun upsertGroup(value: GroupConversationEntity)

    @Query("SELECT * FROM group_conversations WHERE groupId = :groupId")
    suspend fun findGroup(groupId: String): GroupConversationEntity?

    @Query("SELECT * FROM group_membership_episodes WHERE groupId = :groupId AND status = 'ACTIVE'")
    suspend fun membershipsForGroup(groupId: String): List<GroupMembershipEpisodeEntity>

    @Query(
        """SELECT membership.groupId AS groupId, person.canonicalContactId AS contactId
           FROM group_membership_episodes membership
           INNER JOIN source_identities identity ON identity.sourceIdentityId = membership.sourceIdentityId
           INNER JOIN persons person ON person.personId = identity.personId
           INNER JOIN contacts contact ON contact.contactId = person.canonicalContactId
           WHERE membership.status = 'ACTIVE' AND identity.resolutionStatus = 'RESOLVED'
             AND person.status = 'ACTIVE' AND contact.deletedAtEpochMs IS NULL
           ORDER BY membership.groupId, person.canonicalContactId
           LIMIT :limit""",
    )
    suspend fun resolvedGroupMemberships(limit: Int): List<ResolvedGroupMembershipProjection>

    @Upsert
    suspend fun upsertGroupMembership(value: GroupMembershipEpisodeEntity)

    @Upsert
    suspend fun upsertAndroidRawContactLink(value: AndroidRawContactLinkEntity)

    @Upsert
    suspend fun upsertSyncSnapshot(value: ContactSyncSnapshotEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSyncOperation(value: ContactSyncOperationEntity)

    @Query("SELECT * FROM persons WHERE status = 'ACTIVE' ORDER BY normalizedName")
    fun observeActivePeople(): Flow<List<PersonEntity>>

    @Query("SELECT * FROM persons WHERE personId = :personId AND status = 'ACTIVE'")
    suspend fun findPerson(personId: String): PersonEntity?

    @Query("SELECT * FROM persons WHERE canonicalContactId = :contactId AND status = 'ACTIVE'")
    suspend fun findPersonByContactId(contactId: String): PersonEntity?

    @Query("SELECT * FROM source_identities WHERE personId IS NULL AND resolutionStatus IN ('UNRESOLVED', 'CANDIDATE') ORDER BY lastObservedAtEpochMs DESC")
    fun observeUnresolvedIdentities(): Flow<List<SourceIdentityEntity>>

    @Query(
        """SELECT * FROM source_identities
           WHERE personId IS NULL AND resolutionStatus IN ('UNRESOLVED', 'CANDIDATE')
           ORDER BY lastObservedAtEpochMs DESC LIMIT :limit""",
    )
    suspend fun listUnresolvedIdentities(limit: Int): List<SourceIdentityEntity>

    @Query(
        """SELECT COUNT(*) FROM source_identities
           WHERE personId IS NULL AND resolutionStatus IN ('UNRESOLVED', 'CANDIDATE')""",
    )
    suspend fun countUnresolvedIdentities(): Int

    @Query("SELECT * FROM source_identities WHERE sourceIdentityId = :sourceIdentityId")
    suspend fun findSourceIdentity(sourceIdentityId: String): SourceIdentityEntity?

    @Query(
        """UPDATE source_identities
           SET personId = :personId, resolutionStatus = 'RESOLVED', confidence = :confidence,
               lastObservedAtEpochMs = :nowEpochMs
           WHERE sourceIdentityId = :sourceIdentityId AND personId IS NULL
             AND resolutionStatus IN ('UNRESOLVED', 'CANDIDATE')""",
    )
    suspend fun resolveSourceIdentity(sourceIdentityId: String, personId: String, confidence: Double, nowEpochMs: Long): Int

    @Query(
        """UPDATE source_identities
           SET personId = NULL, resolutionStatus = :previousStatus, confidence = :previousConfidence,
               lastObservedAtEpochMs = :nowEpochMs
           WHERE sourceIdentityId = :sourceIdentityId AND personId = :expectedPersonId
             AND resolutionStatus = 'RESOLVED'""",
    )
    suspend fun restoreSourceIdentityResolution(
        sourceIdentityId: String,
        expectedPersonId: String,
        previousStatus: String,
        previousConfidence: Double,
        nowEpochMs: Long,
    ): Int

    @Query("SELECT * FROM source_identities WHERE personId = :personId ORDER BY lastObservedAtEpochMs DESC")
    suspend fun identitiesForPerson(personId: String): List<SourceIdentityEntity>

    @Query("SELECT * FROM identity_claims WHERE personId = :personId AND status = 'ACTIVE' ORDER BY recordedAtEpochMs DESC")
    fun observeClaims(personId: String): Flow<List<IdentityClaimEntity>>

    @Query("SELECT * FROM identity_claims WHERE fieldType = :fieldType AND normalizedValue = :normalizedValue AND status = 'ACTIVE'")
    suspend fun matchingClaims(fieldType: String, normalizedValue: String): List<IdentityClaimEntity>

    @Query(
        """SELECT * FROM person_employment_episodes
           WHERE personId = :personId AND status = 'ACTIVE'
           ORDER BY validToEpochMs IS NULL DESC, validFromEpochMs DESC""",
    )
    fun observeEmployments(personId: String): Flow<List<PersonEmploymentEpisodeEntity>>

    @Query("SELECT * FROM person_employment_episodes WHERE status = 'ACTIVE' ORDER BY updatedAtEpochMs DESC")
    fun observeAllEmployments(): Flow<List<PersonEmploymentEpisodeEntity>>

    @Query("SELECT * FROM person_employment_episodes WHERE status = 'ACTIVE' ORDER BY updatedAtEpochMs DESC")
    suspend fun listAllEmployments(): List<PersonEmploymentEpisodeEntity>

    @Query(
        """SELECT * FROM person_employment_episodes
           WHERE (personId = :selfPersonId OR personId IN (
               SELECT contactId FROM owner_contact_links
               WHERE userConfirmed = 1 AND undoneAtEpochMs IS NULL
           )) AND status = 'ACTIVE' AND verificationState = 'USER_CONFIRMED'
           ORDER BY CASE WHEN currentState = 'CURRENT' THEN 0 ELSE 1 END, updatedAtEpochMs DESC""",
    )
    suspend fun listConfirmedOwnerEmployments(selfPersonId: String): List<PersonEmploymentEpisodeEntity>

    @Query(
        """SELECT * FROM person_employment_episodes
           WHERE personId = :personId AND status = 'ACTIVE' AND currentState = 'CURRENT'
             AND verificationState = 'USER_CONFIRMED'
           ORDER BY updatedAtEpochMs DESC LIMIT 1""",
    )
    suspend fun findCurrentUserEmployment(personId: String): PersonEmploymentEpisodeEntity?

    @Query("SELECT * FROM person_employment_episodes WHERE personId = :personId ORDER BY updatedAtEpochMs DESC")
    suspend fun listEmploymentEpisodes(personId: String): List<PersonEmploymentEpisodeEntity>

    @Query(
        """UPDATE person_employment_episodes
           SET validToEpochMs = :nowEpochMs, currentState = 'PAST', updatedAtEpochMs = :nowEpochMs
           WHERE personId = :personId AND status = 'ACTIVE' AND currentState = 'CURRENT'
             AND verificationState = 'USER_CONFIRMED'""",
    )
    suspend fun endCurrentUserEmployments(personId: String, nowEpochMs: Long): Int

    @Query(
        """UPDATE identity_claims SET status = 'SUPERSEDED', validToEpochMs = :nowEpochMs
           WHERE personId = :personId AND status = 'ACTIVE' AND verificationState = 'USER_CONFIRMED'""",
    )
    suspend fun supersedeUserClaims(personId: String, nowEpochMs: Long): Int

    @Query(
        """UPDATE source_identities SET resolutionStatus = 'SUPERSEDED', lastObservedAtEpochMs = :nowEpochMs
           WHERE personId = :personId AND sourceRef = 'USER_PROFILE' AND resolutionStatus = 'RESOLVED'""",
    )
    suspend fun supersedeUserSourceIdentities(personId: String, nowEpochMs: Long): Int

    @Query(
        """SELECT * FROM relationship_episodes
           WHERE status = 'ACTIVE' AND (fromPersonId = :personId OR toPersonId = :personId)
           ORDER BY updatedAtEpochMs DESC""",
    )
    fun observeRelationships(personId: String): Flow<List<RelationshipEpisodeEntity>>

    @Query(
        """SELECT * FROM relationship_episodes
           WHERE status = 'ACTIVE'
           ORDER BY COALESCE(validToEpochMs, updatedAtEpochMs) DESC""",
    )
    fun observeAllRelationships(): Flow<List<RelationshipEpisodeEntity>>

    @Query(
        """SELECT * FROM relationship_episodes
           WHERE status = 'ACTIVE' AND (fromPersonId = :personId OR toPersonId = :personId)
           ORDER BY COALESCE(validToEpochMs, updatedAtEpochMs) DESC LIMIT :limit""",
    )
    suspend fun listRelationships(personId: String, limit: Int): List<RelationshipEpisodeEntity>

    @Query(
        """UPDATE relationship_episodes SET validToEpochMs = :nowEpochMs, updatedAtEpochMs = :nowEpochMs
           WHERE status = 'ACTIVE' AND validToEpochMs IS NULL AND verificationState = 'USER_CONFIRMED'
             AND relationshipType = :relationshipType
             AND ((fromPersonId = :fromPersonId AND toPersonId = :toPersonId)
               OR (fromPersonId = :toPersonId AND toPersonId = :fromPersonId))""",
    )
    suspend fun closeOpenUserRelationships(fromPersonId: String, toPersonId: String, relationshipType: String, nowEpochMs: Long): Int

    @Query("SELECT * FROM android_raw_contact_links WHERE personId = :personId ORDER BY lastObservedAtEpochMs DESC")
    suspend fun androidLinksForPerson(personId: String): List<AndroidRawContactLinkEntity>

    @Query("SELECT * FROM contact_sync_snapshots WHERE linkId = :linkId")
    suspend fun findSyncSnapshot(linkId: String): ContactSyncSnapshotEntity?

    @Query("SELECT * FROM contact_sync_operations WHERE operationId = :operationId")
    suspend fun findSyncOperation(operationId: String): ContactSyncOperationEntity?

    @Query("UPDATE contact_sync_operations SET state = :state, undoneAtEpochMs = :undoneAt WHERE operationId = :operationId")
    suspend fun updateSyncOperationState(operationId: String, state: String, undoneAt: Long?): Int
}

data class ResolvedGroupMembershipProjection(val groupId: String, val contactId: String)
