package com.zhiban.rebuild.data.contact

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class ContactImportantDateProjection(
    val dateId: String,
    val contactId: String,
    val displayName: String,
    val kind: String,
    val year: Int?,
    val month: Int,
    val day: Int,
    val source: String,
    val evidenceRef: String?,
    val userConfirmed: Boolean,
    val updatedAtEpochMs: Long,
)

/** 联系人库公司地址投影：公司名 + 该联系人关联地址（用于拜访地点检索）。 */
data class CompanyContactAddressRow(val company: String, val formattedAddress: String, val longitude: Double?, val latitude: Double?)

@Dao
interface ContactKnowledgeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMethods(values: List<ContactMethodEntity>)

    @Upsert
    suspend fun upsertOrganization(value: OrganizationEntity)

    @Upsert
    suspend fun upsertEmployment(value: ContactEmploymentEntity)

    @Query("SELECT * FROM organizations WHERE organizationId = :organizationId")
    suspend fun findOrganization(organizationId: String): OrganizationEntity?

    @Query("SELECT * FROM contact_employments WHERE employmentId = :employmentId")
    suspend fun findEmployment(employmentId: String): ContactEmploymentEntity?

    @Query("DELETE FROM contact_employments WHERE employmentId = :employmentId")
    suspend fun deleteEmployment(employmentId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAddresses(values: List<ContactAddressEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertImportantDate(value: ContactImportantDateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFacet(value: ContactFacetEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEnrichmentCandidateIfAbsent(value: ContactEnrichmentCandidateEntity): Long

    /**
     * 补全闭环专用 REPLACE 落候选:同一请求+字段的确定性 candidateId 再次解析到新值(请求过期后重新触达、
     * 对方二次回复)时覆盖旧 PENDING 候选,新值不因 PK 冲突丢失;重扫同一回复 REPLACE 同值,幂等。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEnrichmentCandidate(value: ContactEnrichmentCandidateEntity)

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

    /** 同步版地址查询（唤醒协调器/行程估算用，避免 Flow 订阅开销）。 */
    @Query(
        """SELECT * FROM contact_addresses WHERE contactId = :contactId OR contactId IN (
        SELECT sourceContactId FROM contact_merge_links WHERE canonicalContactId = :contactId AND undoneAtEpochMs IS NULL
    ) ORDER BY userConfirmed DESC, updatedAtEpochMs DESC""",
    )
    suspend fun listAddresses(contactId: String): List<ContactAddressEntity>

    /** 公司注册地址注册表（来源 REGISTRY，供拜访地点检索）。 */
    @Query(
        "SELECT * FROM organizations WHERE registeredAddress IS NOT NULL AND trim(registeredAddress) != ''",
    )
    suspend fun listOrganizationsWithAddress(): List<OrganizationEntity>

    /** 联系人库公司地址（来源 CONTACT）：联系人公司名 + 其关联地址，供拜访地点检索。 */
    @Query(
        """SELECT c.company AS company, a.formattedAddress AS formattedAddress,
           a.longitude AS longitude, a.latitude AS latitude
           FROM contact_addresses a
           INNER JOIN contacts c ON c.contactId = a.contactId
           WHERE c.deletedAtEpochMs IS NULL
             AND c.company IS NOT NULL AND trim(c.company) != ''
             AND a.formattedAddress IS NOT NULL AND trim(a.formattedAddress) != ''""",
    )
    suspend fun listCompanyContactAddresses(): List<CompanyContactAddressRow>

    @Query(
        """SELECT * FROM contact_important_dates WHERE contactId = :contactId OR contactId IN (
        SELECT sourceContactId FROM contact_merge_links WHERE canonicalContactId = :contactId AND undoneAtEpochMs IS NULL
    ) ORDER BY kind""",
    )
    fun observeImportantDates(contactId: String): Flow<List<ContactImportantDateEntity>>

    @Query(
        """SELECT date.dateId, canonical.contactId, canonical.displayName, date.kind, date.year,
           date.month, date.day, date.source, date.evidenceRef, date.userConfirmed, date.updatedAtEpochMs
           FROM contact_important_dates date
           INNER JOIN contacts source ON source.contactId = date.contactId
           LEFT JOIN contact_merge_links link
             ON link.sourceContactId = source.contactId AND link.undoneAtEpochMs IS NULL
           INNER JOIN contacts canonical ON canonical.contactId = COALESCE(link.canonicalContactId, source.contactId)
           WHERE canonical.deletedAtEpochMs IS NULL
           ORDER BY date.userConfirmed DESC, date.updatedAtEpochMs DESC""",
    )
    fun observeAllImportantDates(): Flow<List<ContactImportantDateProjection>>

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

    @Query(
        """SELECT * FROM contact_enrichment_candidates
        WHERE status = 'PENDING' AND (expiresAtEpochMs IS NULL OR expiresAtEpochMs > :nowEpochMs)
        ORDER BY confidence DESC, updatedAtEpochMs DESC""",
    )
    fun observeAllPendingEnrichment(nowEpochMs: Long): Flow<List<ContactEnrichmentCandidateEntity>>

    @Query(
        """SELECT COUNT(*) FROM contact_enrichment_candidates
        WHERE status = 'PENDING' AND (expiresAtEpochMs IS NULL OR expiresAtEpochMs > :nowEpochMs)""",
    )
    suspend fun countAllPendingEnrichment(nowEpochMs: Long): Int

    @Query("SELECT * FROM contact_enrichment_candidates WHERE candidateId = :candidateId")
    suspend fun findEnrichmentCandidate(candidateId: String): ContactEnrichmentCandidateEntity?

    @Query(
        """SELECT COUNT(*) FROM contact_enrichment_candidates
        WHERE contactId = :contactId AND providerId = :providerId AND fieldKind = :fieldKind
        AND (expiresAtEpochMs IS NULL OR expiresAtEpochMs > :nowEpochMs)""",
    )
    suspend fun countActiveEnrichmentCandidates(contactId: String, providerId: String, fieldKind: String, nowEpochMs: Long): Int

    /** 某补全请求(sourceRef 前缀 completion:{requestId})下仍可操作(PENDING 且未过期)的候选数。 */
    @Query(
        """SELECT COUNT(*) FROM contact_enrichment_candidates
        WHERE sourceRef LIKE :prefix || '%' AND status = 'PENDING'
        AND (expiresAtEpochMs IS NULL OR expiresAtEpochMs > :nowEpochMs)""",
    )
    suspend fun countPendingBySourceRefPrefix(prefix: String, nowEpochMs: Long): Int

    /** 某补全请求下已采纳(APPROVED)的候选数——对账判据:有采纳即视为回复兑现成资料。 */
    @Query(
        "SELECT COUNT(*) FROM contact_enrichment_candidates WHERE sourceRef LIKE :prefix || '%' AND status = 'APPROVED'",
    )
    suspend fun countApprovedBySourceRefPrefix(prefix: String): Int

    @Query(
        "UPDATE contact_enrichment_candidates SET status = :status, updatedAtEpochMs = :nowEpochMs WHERE candidateId = :candidateId AND status = 'PENDING'",
    )
    suspend fun resolveEnrichmentCandidate(candidateId: String, status: String, nowEpochMs: Long): Int

    @Query(
        """UPDATE contact_enrichment_candidates SET status = 'SUPERSEDED', updatedAtEpochMs = :nowEpochMs
        WHERE contactId = :contactId AND providerId = :providerId AND fieldKind = :fieldKind
        AND status = 'PENDING' AND candidateId != :replacementCandidateId""",
    )
    suspend fun supersedePendingEnrichment(contactId: String, providerId: String, fieldKind: String, replacementCandidateId: String, nowEpochMs: Long): Int

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
        "SELECT * FROM owner_contact_links WHERE undoneAtEpochMs IS NULL AND userConfirmed = 1 ORDER BY createdAtEpochMs DESC",
    )
    suspend fun listActiveOwnerContactLinks(): List<OwnerContactLinkEntity>

    @Query(
        "UPDATE owner_contact_links SET undoneAtEpochMs = :nowEpochMs WHERE contactId = :contactId AND undoneAtEpochMs IS NULL AND userConfirmed = 1",
    )
    suspend fun undoOwnerContactLink(contactId: String, nowEpochMs: Long): Int
}
