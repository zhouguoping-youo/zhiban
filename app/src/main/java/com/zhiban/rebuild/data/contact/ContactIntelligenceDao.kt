package com.zhiban.rebuild.data.contact

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactIntelligenceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPerson(value: PersonEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSourceIdentity(value: SourceIdentityEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertClaim(value: IdentityClaimEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEmployment(value: PersonEmploymentEpisodeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRelationship(value: RelationshipEpisodeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroup(value: GroupConversationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroupMembership(value: GroupMembershipEpisodeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAndroidRawContactLink(value: AndroidRawContactLinkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSyncSnapshot(value: ContactSyncSnapshotEntity)

    @Query("SELECT * FROM persons WHERE status = 'ACTIVE' ORDER BY normalizedName")
    fun observeActivePeople(): Flow<List<PersonEntity>>

    @Query("SELECT * FROM persons WHERE personId = :personId AND status = 'ACTIVE'")
    suspend fun findPerson(personId: String): PersonEntity?

    @Query("SELECT * FROM persons WHERE canonicalContactId = :contactId AND status = 'ACTIVE'")
    suspend fun findPersonByContactId(contactId: String): PersonEntity?

    @Query("SELECT * FROM source_identities WHERE personId IS NULL AND resolutionStatus IN ('UNRESOLVED', 'CANDIDATE') ORDER BY lastObservedAtEpochMs DESC")
    fun observeUnresolvedIdentities(): Flow<List<SourceIdentityEntity>>

    @Query("SELECT * FROM source_identities WHERE sourceIdentityId = :sourceIdentityId")
    suspend fun findSourceIdentity(sourceIdentityId: String): SourceIdentityEntity?

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

    @Query(
        """SELECT * FROM relationship_episodes
           WHERE status = 'ACTIVE' AND (fromPersonId = :personId OR toPersonId = :personId)
           ORDER BY updatedAtEpochMs DESC""",
    )
    fun observeRelationships(personId: String): Flow<List<RelationshipEpisodeEntity>>

    @Query("SELECT * FROM android_raw_contact_links WHERE personId = :personId ORDER BY lastObservedAtEpochMs DESC")
    suspend fun androidLinksForPerson(personId: String): List<AndroidRawContactLinkEntity>

    @Query("SELECT * FROM contact_sync_snapshots WHERE linkId = :linkId")
    suspend fun findSyncSnapshot(linkId: String): ContactSyncSnapshotEntity?
}
