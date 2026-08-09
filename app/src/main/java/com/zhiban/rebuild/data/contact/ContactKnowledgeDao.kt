package com.zhiban.rebuild.data.contact

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactKnowledgeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMethods(values: List<ContactMethodEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOrganization(value: OrganizationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEmployment(value: ContactEmploymentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAddresses(values: List<ContactAddressEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertImportantDate(value: ContactImportantDateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFacet(value: ContactFacetEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEnrichmentCandidateIfAbsent(value: ContactEnrichmentCandidateEntity): Long

    @Query(
        """SELECT canonical.* FROM contact_methods method
           INNER JOIN contacts source ON source.contactId = method.contactId
           LEFT JOIN contact_merge_links link
             ON link.sourceContactId = source.contactId AND link.undoneAtEpochMs IS NULL
           INNER JOIN contacts canonical ON canonical.contactId = COALESCE(link.canonicalContactId, source.contactId)
           WHERE method.kind = :kind AND method.normalizedValue = :normalizedValue
             AND canonical.deletedAtEpochMs IS NULL
           ORDER BY method.userConfirmed DESC, method.updatedAtEpochMs DESC
           LIMIT 1""",
    )
    suspend fun findContactByMethod(kind: String, normalizedValue: String): ContactEntity?

    @Query(
        """SELECT DISTINCT canonical.* FROM contact_methods method
           INNER JOIN contacts source ON source.contactId = method.contactId
           LEFT JOIN contact_merge_links link
             ON link.sourceContactId = source.contactId AND link.undoneAtEpochMs IS NULL
           INNER JOIN contacts canonical ON canonical.contactId = COALESCE(link.canonicalContactId, source.contactId)
           WHERE method.kind = :kind AND method.normalizedValue = :normalizedValue
             AND canonical.deletedAtEpochMs IS NULL
           ORDER BY canonical.updatedAtEpochMs DESC""",
    )
    suspend fun findContactsByMethod(kind: String, normalizedValue: String): List<ContactEntity>

    @Query("DELETE FROM contact_methods WHERE contactId = :contactId AND kind = :kind AND source = 'USER'")
    suspend fun deleteUserMethods(contactId: String, kind: String): Int

    @Query(
        "UPDATE contact_employments SET isCurrent = 0, updatedAtEpochMs = :nowEpochMs WHERE contactId = :contactId AND source = 'USER' AND isCurrent = 1",
    )
    suspend fun endCurrentUserEmployments(contactId: String, nowEpochMs: Long): Int

    @Query("DELETE FROM contact_facets WHERE contactId = :contactId AND dimension = :dimension AND source = 'USER'")
    suspend fun deleteUserFacets(contactId: String, dimension: String): Int

    @Query(
        """SELECT * FROM contact_methods WHERE contactId = :contactId OR contactId IN (
        SELECT sourceContactId FROM contact_merge_links WHERE canonicalContactId = :contactId AND undoneAtEpochMs IS NULL
    ) ORDER BY isPrimary DESC, kind, updatedAtEpochMs DESC""",
    )
    fun observeMethods(contactId: String): Flow<List<ContactMethodEntity>>

    @Query(
        """SELECT * FROM contact_employments WHERE contactId = :contactId OR contactId IN (
        SELECT sourceContactId FROM contact_merge_links WHERE canonicalContactId = :contactId AND undoneAtEpochMs IS NULL
    ) ORDER BY isCurrent DESC, updatedAtEpochMs DESC""",
    )
    fun observeEmployments(contactId: String): Flow<List<ContactEmploymentEntity>>

    @Query(
        """SELECT * FROM contact_addresses WHERE contactId = :contactId OR contactId IN (
        SELECT sourceContactId FROM contact_merge_links WHERE canonicalContactId = :contactId AND undoneAtEpochMs IS NULL
    ) ORDER BY userConfirmed DESC, updatedAtEpochMs DESC""",
    )
    fun observeAddresses(contactId: String): Flow<List<ContactAddressEntity>>

    @Query(
        """SELECT * FROM contact_important_dates WHERE contactId = :contactId OR contactId IN (
        SELECT sourceContactId FROM contact_merge_links WHERE canonicalContactId = :contactId AND undoneAtEpochMs IS NULL
    ) ORDER BY kind""",
    )
    fun observeImportantDates(contactId: String): Flow<List<ContactImportantDateEntity>>

    @Query(
        """SELECT * FROM contact_facets WHERE contactId = :contactId OR contactId IN (
        SELECT sourceContactId FROM contact_merge_links WHERE canonicalContactId = :contactId AND undoneAtEpochMs IS NULL
    ) ORDER BY dimension, userConfirmed DESC, value""",
    )
    fun observeFacets(contactId: String): Flow<List<ContactFacetEntity>>

    @Query(
        """SELECT * FROM contact_enrichment_candidates WHERE (
        contactId = :contactId OR contactId IN (
            SELECT sourceContactId FROM contact_merge_links WHERE canonicalContactId = :contactId AND undoneAtEpochMs IS NULL
        )
    ) AND status = 'PENDING' ORDER BY confidence DESC, updatedAtEpochMs DESC""",
    )
    fun observePendingEnrichment(contactId: String): Flow<List<ContactEnrichmentCandidateEntity>>

    @Query("SELECT * FROM contact_enrichment_candidates WHERE candidateId = :candidateId")
    suspend fun findEnrichmentCandidate(candidateId: String): ContactEnrichmentCandidateEntity?

    @Query(
        "UPDATE contact_enrichment_candidates SET status = :status, updatedAtEpochMs = :nowEpochMs WHERE candidateId = :candidateId AND status = 'PENDING'",
    )
    suspend fun resolveEnrichmentCandidate(candidateId: String, status: String, nowEpochMs: Long): Int

    @Query(
        "DELETE FROM contact_enrichment_candidates WHERE expiresAtEpochMs IS NOT NULL AND expiresAtEpochMs <= :nowEpochMs",
    )
    suspend fun purgeExpiredEnrichment(nowEpochMs: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOwnerContactLink(value: OwnerContactLinkEntity)

    @Query(
        "SELECT * FROM owner_contact_links WHERE undoneAtEpochMs IS NULL AND userConfirmed = 1 ORDER BY createdAtEpochMs DESC",
    )
    fun observeActiveOwnerContactLinks(): Flow<List<OwnerContactLinkEntity>>

    @Query(
        "UPDATE owner_contact_links SET undoneAtEpochMs = :nowEpochMs WHERE contactId = :contactId AND undoneAtEpochMs IS NULL AND userConfirmed = 1",
    )
    suspend fun undoOwnerContactLink(contactId: String, nowEpochMs: Long): Int
}
