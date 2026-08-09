package com.zhiban.rebuild.data.contact

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(contact: ContactEntity)

    @Update
    suspend fun update(contact: ContactEntity): Int

    @Query(
        """SELECT * FROM contacts WHERE deletedAtEpochMs IS NULL
        AND contactId NOT IN (SELECT sourceContactId FROM contact_merge_links WHERE undoneAtEpochMs IS NULL)
        ORDER BY normalizedName, updatedAtEpochMs DESC""",
    )
    fun observeActive(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE deletedAtEpochMs IS NULL ORDER BY normalizedName, updatedAtEpochMs DESC")
    fun observeAllActive(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE deletedAtEpochMs IS NULL ORDER BY contactId LIMIT :limit OFFSET :offset")
    suspend fun listActivePageForExport(limit: Int, offset: Int): List<ContactEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRole(role: ContactRoleEntity)

    @Query(
        """SELECT canonical.contactId, canonical.displayName, canonical.normalizedName,
        COALESCE(canonical.phone, (SELECT source.phone FROM contact_merge_links link INNER JOIN contacts source ON source.contactId = link.sourceContactId WHERE link.canonicalContactId = canonical.contactId AND link.undoneAtEpochMs IS NULL AND source.phone IS NOT NULL LIMIT 1)) AS phone,
        COALESCE(canonical.email, (SELECT source.email FROM contact_merge_links link INNER JOIN contacts source ON source.contactId = link.sourceContactId WHERE link.canonicalContactId = canonical.contactId AND link.undoneAtEpochMs IS NULL AND source.email IS NOT NULL LIMIT 1)) AS email,
        COALESCE(canonical.wechatId, (SELECT source.wechatId FROM contact_merge_links link INNER JOIN contacts source ON source.contactId = link.sourceContactId WHERE link.canonicalContactId = canonical.contactId AND link.undoneAtEpochMs IS NULL AND source.wechatId IS NOT NULL LIMIT 1)) AS wechatId,
        COALESCE(canonical.company, (SELECT source.company FROM contact_merge_links link INNER JOIN contacts source ON source.contactId = link.sourceContactId WHERE link.canonicalContactId = canonical.contactId AND link.undoneAtEpochMs IS NULL AND source.company IS NOT NULL LIMIT 1)) AS company,
        COALESCE(canonical.title, (SELECT source.title FROM contact_merge_links link INNER JOIN contacts source ON source.contactId = link.sourceContactId WHERE link.canonicalContactId = canonical.contactId AND link.undoneAtEpochMs IS NULL AND source.title IS NOT NULL LIMIT 1)) AS title,
        canonical.aliasesJson, canonical.tagsJson,
        COALESCE(canonical.note, (SELECT source.note FROM contact_merge_links link INNER JOIN contacts source ON source.contactId = link.sourceContactId WHERE link.canonicalContactId = canonical.contactId AND link.undoneAtEpochMs IS NULL AND source.note IS NOT NULL LIMIT 1)) AS note,
        COALESCE(canonical.avatarUri, (SELECT source.avatarUri FROM contact_merge_links link INNER JOIN contacts source ON source.contactId = link.sourceContactId WHERE link.canonicalContactId = canonical.contactId AND link.undoneAtEpochMs IS NULL AND source.avatarUri IS NOT NULL LIMIT 1)) AS avatarUri,
        canonical.source, canonical.deletedAtEpochMs, canonical.createdAtEpochMs, canonical.updatedAtEpochMs
        FROM contacts canonical
        WHERE canonical.contactId = COALESCE(
            (SELECT canonicalContactId FROM contact_merge_links WHERE sourceContactId = :contactId AND undoneAtEpochMs IS NULL),
            :contactId
        ) AND canonical.deletedAtEpochMs IS NULL""",
    )
    suspend fun findById(contactId: String): ContactEntity?

    @Query("SELECT * FROM contacts WHERE contactId = :contactId AND deletedAtEpochMs IS NULL")
    suspend fun findRawById(contactId: String): ContactEntity?

    @Query("SELECT * FROM contacts WHERE source = :source AND deletedAtEpochMs IS NULL LIMIT 1")
    suspend fun findBySource(source: String): ContactEntity?

    @Query("SELECT * FROM contacts WHERE phone = :phone AND deletedAtEpochMs IS NULL LIMIT 1")
    suspend fun findByPhone(phone: String): ContactEntity?

    @Query(
        "SELECT * FROM contacts WHERE normalizedName = :normalizedName AND deletedAtEpochMs IS NULL ORDER BY updatedAtEpochMs DESC LIMIT 1",
    )
    suspend fun findByNormalizedName(normalizedName: String): ContactEntity?

    @Query(
        """SELECT * FROM contact_roles WHERE contactId = COALESCE(
            (SELECT canonicalContactId FROM contact_merge_links WHERE sourceContactId = :contactId AND undoneAtEpochMs IS NULL),
            :contactId
        ) OR contactId IN (
            SELECT sourceContactId FROM contact_merge_links
            WHERE canonicalContactId = COALESCE(
                (SELECT canonicalContactId FROM contact_merge_links WHERE sourceContactId = :contactId AND undoneAtEpochMs IS NULL),
                :contactId
            ) AND undoneAtEpochMs IS NULL
        ) ORDER BY userConfirmed DESC, confidence DESC""",
    )
    suspend fun roles(contactId: String): List<ContactRoleEntity>

    @Query(
        """SELECT COALESCE(m.canonicalContactId, r.contactId) AS contactId,
        r.skillId, r.roleType, r.confidence, r.userConfirmed, r.profileJson,
        r.createdAtEpochMs, r.updatedAtEpochMs
        FROM contact_roles r
        LEFT JOIN contact_merge_links m ON m.sourceContactId = r.contactId AND m.undoneAtEpochMs IS NULL
        ORDER BY r.userConfirmed DESC, r.confidence DESC, r.updatedAtEpochMs DESC""",
    )
    fun observeRoles(): Flow<List<ContactRoleEntity>>

    @Query("DELETE FROM contact_roles WHERE contactId = :contactId AND skillId = :skillId AND roleType = :roleType")
    suspend fun deleteRole(contactId: String, skillId: String, roleType: String): Int

    @Query(
        """SELECT canonical.contactId, canonical.displayName,
           COALESCE(canonical.phone, (SELECT source.phone FROM contact_merge_links link INNER JOIN contacts source ON source.contactId = link.sourceContactId WHERE link.canonicalContactId = canonical.contactId AND link.undoneAtEpochMs IS NULL AND source.phone IS NOT NULL LIMIT 1)) AS phone,
           COALESCE(canonical.email, (SELECT source.email FROM contact_merge_links link INNER JOIN contacts source ON source.contactId = link.sourceContactId WHERE link.canonicalContactId = canonical.contactId AND link.undoneAtEpochMs IS NULL AND source.email IS NOT NULL LIMIT 1)) AS email,
           COALESCE(canonical.wechatId, (SELECT source.wechatId FROM contact_merge_links link INNER JOIN contacts source ON source.contactId = link.sourceContactId WHERE link.canonicalContactId = canonical.contactId AND link.undoneAtEpochMs IS NULL AND source.wechatId IS NOT NULL LIMIT 1)) AS wechatId,
           COALESCE(canonical.company, (SELECT source.company FROM contact_merge_links link INNER JOIN contacts source ON source.contactId = link.sourceContactId WHERE link.canonicalContactId = canonical.contactId AND link.undoneAtEpochMs IS NULL AND source.company IS NOT NULL LIMIT 1)) AS company,
           COALESCE(canonical.title, (SELECT source.title FROM contact_merge_links link INNER JOIN contacts source ON source.contactId = link.sourceContactId WHERE link.canonicalContactId = canonical.contactId AND link.undoneAtEpochMs IS NULL AND source.title IS NOT NULL LIMIT 1)) AS title,
           COALESCE(canonical.note, (SELECT source.note FROM contact_merge_links link INNER JOIN contacts source ON source.contactId = link.sourceContactId WHERE link.canonicalContactId = canonical.contactId AND link.undoneAtEpochMs IS NULL AND source.note IS NOT NULL LIMIT 1)) AS note
           FROM contacts canonical
           WHERE canonical.deletedAtEpochMs IS NULL
             AND canonical.contactId NOT IN (SELECT sourceContactId FROM contact_merge_links WHERE undoneAtEpochMs IS NULL)
             AND canonical.contactId IN (
               SELECT COALESCE(
                 (SELECT m.canonicalContactId FROM contact_merge_links m
                  WHERE m.sourceContactId = f.contactId AND m.undoneAtEpochMs IS NULL),
                 f.contactId
               )
               FROM contact_search_fts f WHERE f.content MATCH :query
             )
           ORDER BY CASE WHEN canonical.normalizedName = :normalizedQuery THEN 0 ELSE 1 END, canonical.updatedAtEpochMs DESC
           LIMIT :limit""",
    )
    suspend fun search(query: String, normalizedQuery: String, limit: Int): List<ContactSearchProjection>

    @Query(
        """SELECT * FROM contacts canonical
           WHERE canonical.deletedAtEpochMs IS NULL
             AND canonical.contactId NOT IN (SELECT sourceContactId FROM contact_merge_links WHERE undoneAtEpochMs IS NULL)
             AND canonical.displayName != ''
             AND (
               instr(:input, canonical.displayName) > 0 OR instr(:input, canonical.normalizedName) > 0 OR
               EXISTS (
                 SELECT 1 FROM contact_merge_links link
                 INNER JOIN contacts source ON source.contactId = link.sourceContactId
                 WHERE link.canonicalContactId = canonical.contactId
                   AND link.undoneAtEpochMs IS NULL
                   AND (instr(:input, source.displayName) > 0 OR instr(:input, source.normalizedName) > 0)
               )
             )
           ORDER BY length(canonical.displayName) DESC, canonical.updatedAtEpochMs DESC
           LIMIT :limit""",
    )
    suspend fun findMentionedIn(input: String, limit: Int = 10): List<ContactEntity>

    @Query(
        """SELECT COUNT(*) FROM contacts WHERE deletedAtEpochMs IS NULL
        AND contactId NOT IN (SELECT sourceContactId FROM contact_merge_links WHERE undoneAtEpochMs IS NULL)""",
    )
    suspend fun countActive(): Int

    @Query(
        "UPDATE contacts SET deletedAtEpochMs = :nowEpochMs, updatedAtEpochMs = :nowEpochMs WHERE contactId = :contactId AND deletedAtEpochMs IS NULL",
    )
    suspend fun softDelete(contactId: String, nowEpochMs: Long): Int

    @Query(
        """WITH canonical(id) AS (
               SELECT COALESCE(
                   (SELECT canonicalContactId FROM contact_merge_links
                    WHERE sourceContactId = :contactId AND undoneAtEpochMs IS NULL),
                   :contactId
               )
           )
           SELECT contactId FROM contacts
           WHERE contactId = (SELECT id FROM canonical)
              OR contactId IN (
                  SELECT sourceContactId FROM contact_merge_links
                  WHERE canonicalContactId = (SELECT id FROM canonical) AND undoneAtEpochMs IS NULL
              )""",
    )
    suspend fun activeIdentityClusterIds(contactId: String): List<String>

    @Query(
        "UPDATE contacts SET deletedAtEpochMs = :nowEpochMs, updatedAtEpochMs = :nowEpochMs WHERE contactId IN (:contactIds) AND deletedAtEpochMs IS NULL",
    )
    suspend fun softDeleteAll(contactIds: List<String>, nowEpochMs: Long): Int

    @Query("DELETE FROM contacts WHERE contactId = :contactId AND source = 'AGENT_CANDIDATE'")
    suspend fun deleteAgentCandidate(contactId: String): Int

    @Query("SELECT COUNT(*) FROM contacts WHERE source = 'CRM_DEMO'")
    suspend fun countLegacyCrmDemo(): Int

    @Query("DELETE FROM contacts WHERE source = 'CRM_DEMO'")
    suspend fun deleteLegacyCrmDemo(): Int
}

@Dao
interface ContactIdentityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAlias(value: ContactAliasEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlatformIdentity(value: ContactPlatformIdentityEntity)

    @Query(
        """SELECT a.aliasId, COALESCE(m.canonicalContactId, a.contactId) AS contactId,
        a.alias, a.normalizedAlias, a.aliasType, a.source, a.userConfirmed, a.createdAtEpochMs
        FROM contact_aliases a
        LEFT JOIN contact_merge_links m ON m.sourceContactId = a.contactId AND m.undoneAtEpochMs IS NULL
        INNER JOIN contacts canonical ON canonical.contactId = COALESCE(m.canonicalContactId, a.contactId)
        WHERE canonical.deletedAtEpochMs IS NULL
        ORDER BY a.createdAtEpochMs DESC""",
    )
    fun observeAliases(): Flow<List<ContactAliasEntity>>

    @Query(
        """SELECT i.identityId, COALESCE(m.canonicalContactId, i.contactId) AS contactId,
        i.platform, i.handle, i.normalizedHandle, i.platformUserId, i.source,
        i.userConfirmed, i.createdAtEpochMs, i.updatedAtEpochMs
        FROM contact_platform_identities i
        LEFT JOIN contact_merge_links m ON m.sourceContactId = i.contactId AND m.undoneAtEpochMs IS NULL
        INNER JOIN contacts canonical ON canonical.contactId = COALESCE(m.canonicalContactId, i.contactId)
        WHERE canonical.deletedAtEpochMs IS NULL
        ORDER BY i.updatedAtEpochMs DESC""",
    )
    fun observePlatformIdentities(): Flow<List<ContactPlatformIdentityEntity>>

    @Query(
        """SELECT * FROM contact_aliases WHERE contactId = :contactId OR contactId IN (
        SELECT sourceContactId FROM contact_merge_links WHERE canonicalContactId = :contactId AND undoneAtEpochMs IS NULL
    ) ORDER BY createdAtEpochMs DESC""",
    )
    suspend fun aliases(contactId: String): List<ContactAliasEntity>

    @Query(
        """SELECT * FROM contact_platform_identities WHERE contactId = :contactId OR contactId IN (
        SELECT sourceContactId FROM contact_merge_links WHERE canonicalContactId = :contactId AND undoneAtEpochMs IS NULL
    ) ORDER BY updatedAtEpochMs DESC""",
    )
    suspend fun platformIdentities(contactId: String): List<ContactPlatformIdentityEntity>

    @Query(
        """SELECT canonical.* FROM contact_platform_identities
           INNER JOIN contacts source ON source.contactId = contact_platform_identities.contactId
           LEFT JOIN contact_merge_links link
             ON link.sourceContactId = source.contactId AND link.undoneAtEpochMs IS NULL
           INNER JOIN contacts canonical ON canonical.contactId = COALESCE(link.canonicalContactId, source.contactId)
           WHERE contact_platform_identities.platform = :platform
             AND contact_platform_identities.normalizedHandle = :normalizedHandle
             AND canonical.deletedAtEpochMs IS NULL
           ORDER BY contact_platform_identities.userConfirmed DESC, contact_platform_identities.updatedAtEpochMs DESC
           LIMIT 1""",
    )
    suspend fun findContactByPlatformHandle(platform: String, normalizedHandle: String): ContactEntity?

    @Query(
        """SELECT canonical.* FROM contact_aliases
           INNER JOIN contacts source ON source.contactId = contact_aliases.contactId
           LEFT JOIN contact_merge_links link
             ON link.sourceContactId = source.contactId AND link.undoneAtEpochMs IS NULL
           INNER JOIN contacts canonical ON canonical.contactId = COALESCE(link.canonicalContactId, source.contactId)
           WHERE contact_aliases.normalizedAlias = :normalizedAlias
             AND canonical.deletedAtEpochMs IS NULL
           ORDER BY contact_aliases.userConfirmed DESC, contact_aliases.createdAtEpochMs DESC
           LIMIT 1""",
    )
    suspend fun findContactByAlias(normalizedAlias: String): ContactEntity?

    @Query("DELETE FROM contact_aliases WHERE aliasId = :aliasId AND userConfirmed = 1")
    suspend fun deleteConfirmedAlias(aliasId: String): Int

    @Query("DELETE FROM contact_platform_identities WHERE identityId = :identityId AND userConfirmed = 1")
    suspend fun deleteConfirmedPlatformIdentity(identityId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMergeLink(value: ContactMergeLinkEntity)

    @Query("SELECT * FROM contact_merge_links WHERE undoneAtEpochMs IS NULL ORDER BY createdAtEpochMs DESC")
    fun observeActiveMergeLinks(): Flow<List<ContactMergeLinkEntity>>

    @Query("SELECT * FROM contact_merge_links WHERE sourceContactId = :sourceContactId AND undoneAtEpochMs IS NULL")
    suspend fun activeMergeLink(sourceContactId: String): ContactMergeLinkEntity?

    @Query("SELECT COUNT(*) FROM contact_merge_links WHERE canonicalContactId = :contactId AND undoneAtEpochMs IS NULL")
    suspend fun countActiveSources(contactId: String): Int

    /** True when [contactId] is the canonical of at least one active merge link. */
    suspend fun hasActiveSources(contactId: String): Boolean = countActiveSources(contactId) > 0

    @Query(
        """UPDATE contact_merge_links SET undoneAtEpochMs = :nowEpochMs
           WHERE sourceContactId = :sourceContactId AND undoneAtEpochMs IS NULL AND userConfirmed = 1
             AND EXISTS (SELECT 1 FROM contacts source WHERE source.contactId = contact_merge_links.sourceContactId AND source.deletedAtEpochMs IS NULL)
             AND EXISTS (SELECT 1 FROM contacts canonical WHERE canonical.contactId = contact_merge_links.canonicalContactId AND canonical.deletedAtEpochMs IS NULL)""",
    )
    suspend fun undoConfirmedMerge(sourceContactId: String, nowEpochMs: Long): Int
}

@Dao
interface StagedContactCandidateDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(candidate: StagedContactCandidateEntity): Long

    @Query("SELECT * FROM staged_contact_candidates WHERE candidateId = :id")
    suspend fun find(id: String): StagedContactCandidateEntity?

    @Query(
        "UPDATE staged_contact_candidates SET state = 'APPROVED', updatedAtEpochMs = :now WHERE candidateId = :id AND state = 'PENDING'",
    )
    suspend fun approve(id: String, now: Long): Int

    @Query(
        "UPDATE staged_contact_candidates SET state = 'CONSUMED', payloadJson = '{}', updatedAtEpochMs = :now WHERE candidateId = :id AND state IN ('PENDING','APPROVED')",
    )
    suspend fun consumeAndScrub(id: String, now: Long): Int

    @Query("DELETE FROM staged_contact_candidates WHERE expiresAtEpochMs <= :now")
    suspend fun purgeExpired(now: Long): Int
}

@Dao
interface RelationshipEdgeDao {
    companion object {
        /** Upper bound on merge-link chain length when resolving the ultimate canonical. */
        private const val MAX_MERGE_HOPS = 16
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(edge: RelationshipEdgeEntity)

    @Query("SELECT canonicalContactId FROM contact_merge_links WHERE sourceContactId = :contactId AND undoneAtEpochMs IS NULL")
    suspend fun directCanonicalContactId(contactId: String): String?

    /**
     * Resolves [contactId] to its ultimate canonical, following the merge-link chain (A→B→C
     * resolves A to C, not the hidden intermediate B). Bounded so a malformed cycle cannot loop.
     */
    suspend fun resolveCanonicalContactId(contactId: String): String {
        var current = contactId
        repeat(MAX_MERGE_HOPS) {
            val next = directCanonicalContactId(current) ?: return current
            current = next
        }
        return current
    }

    @Query(
        """SELECT edgeId,
        COALESCE((SELECT canonicalContactId FROM contact_merge_links WHERE sourceContactId = fromContactId AND undoneAtEpochMs IS NULL), fromContactId) AS fromContactId,
        COALESCE((SELECT canonicalContactId FROM contact_merge_links WHERE sourceContactId = toContactId AND undoneAtEpochMs IS NULL), toContactId) AS toContactId,
        relationType, evidenceDigest, evidenceRefsJson, confidence, userConfirmed, skillId,
        status, createdAtEpochMs, updatedAtEpochMs
        FROM relationship_edges WHERE status='ACTIVE'
        AND (fromContactId = 'user:self' OR EXISTS (SELECT 1 FROM contacts active_from WHERE active_from.contactId = COALESCE((SELECT canonicalContactId FROM contact_merge_links WHERE sourceContactId = fromContactId AND undoneAtEpochMs IS NULL), fromContactId) AND active_from.deletedAtEpochMs IS NULL))
        AND (toContactId = 'user:self' OR EXISTS (SELECT 1 FROM contacts active_to WHERE active_to.contactId = COALESCE((SELECT canonicalContactId FROM contact_merge_links WHERE sourceContactId = toContactId AND undoneAtEpochMs IS NULL), toContactId) AND active_to.deletedAtEpochMs IS NULL))
        AND COALESCE((SELECT canonicalContactId FROM contact_merge_links WHERE sourceContactId = fromContactId AND undoneAtEpochMs IS NULL), fromContactId)
            != COALESCE((SELECT canonicalContactId FROM contact_merge_links WHERE sourceContactId = toContactId AND undoneAtEpochMs IS NULL), toContactId)
        ORDER BY userConfirmed DESC, updatedAtEpochMs DESC""",
    )
    fun observeActive(): Flow<List<RelationshipEdgeEntity>>

    @Query(
        """SELECT edgeId,
        COALESCE((SELECT canonicalContactId FROM contact_merge_links WHERE sourceContactId = fromContactId AND undoneAtEpochMs IS NULL), fromContactId) AS fromContactId,
        COALESCE((SELECT canonicalContactId FROM contact_merge_links WHERE sourceContactId = toContactId AND undoneAtEpochMs IS NULL), toContactId) AS toContactId,
        relationType, evidenceDigest, evidenceRefsJson, confidence, userConfirmed, skillId,
        status, createdAtEpochMs, updatedAtEpochMs
        FROM relationship_edges WHERE status='ACTIVE'
        AND (fromContactId = 'user:self' OR EXISTS (SELECT 1 FROM contacts active_from WHERE active_from.contactId = COALESCE((SELECT canonicalContactId FROM contact_merge_links WHERE sourceContactId = fromContactId AND undoneAtEpochMs IS NULL), fromContactId) AND active_from.deletedAtEpochMs IS NULL))
        AND (toContactId = 'user:self' OR EXISTS (SELECT 1 FROM contacts active_to WHERE active_to.contactId = COALESCE((SELECT canonicalContactId FROM contact_merge_links WHERE sourceContactId = toContactId AND undoneAtEpochMs IS NULL), toContactId) AND active_to.deletedAtEpochMs IS NULL))
        AND (
          COALESCE((SELECT canonicalContactId FROM contact_merge_links WHERE sourceContactId = fromContactId AND undoneAtEpochMs IS NULL), fromContactId) IN (:contactIds)
          OR COALESCE((SELECT canonicalContactId FROM contact_merge_links WHERE sourceContactId = toContactId AND undoneAtEpochMs IS NULL), toContactId) IN (:contactIds)
        )
        AND COALESCE((SELECT canonicalContactId FROM contact_merge_links WHERE sourceContactId = fromContactId AND undoneAtEpochMs IS NULL), fromContactId)
            != COALESCE((SELECT canonicalContactId FROM contact_merge_links WHERE sourceContactId = toContactId AND undoneAtEpochMs IS NULL), toContactId)
        ORDER BY userConfirmed DESC, confidence DESC, updatedAtEpochMs DESC LIMIT :limit""",
    )
    suspend fun touching(contactIds: List<String>, limit: Int): List<RelationshipEdgeEntity>

    @Query(
        """SELECT contactId, displayName, phone, email, wechatId, company, title, note FROM contacts
        WHERE deletedAtEpochMs IS NULL AND contactId IN (:contactIds)
        AND contactId NOT IN (SELECT sourceContactId FROM contact_merge_links WHERE undoneAtEpochMs IS NULL)""",
    )
    suspend fun contactSummaries(contactIds: List<String>): List<ContactSearchProjection>

    @Query("SELECT * FROM relationship_edges WHERE edgeId = :edgeId")
    suspend fun find(edgeId: String): RelationshipEdgeEntity?

    @Query("DELETE FROM relationship_edges WHERE edgeId = :edgeId AND userConfirmed = 1")
    suspend fun deleteConfirmed(edgeId: String): Int

    @Query(
        "UPDATE relationship_edges SET status = 'DELETED', updatedAtEpochMs = :nowEpochMs WHERE status = 'ACTIVE' AND (fromContactId IN (:contactIds) OR toContactId IN (:contactIds))",
    )
    suspend fun deactivateForContacts(contactIds: List<String>, nowEpochMs: Long): Int
}

@Dao
interface RelationshipEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEvent(event: RelationshipEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertParticipants(participants: List<RelationshipEventParticipantEntity>)

    @Transaction
    @Query(
        """SELECT * FROM relationship_events WHERE status = 'ACTIVE'
        AND NOT EXISTS (
            SELECT 1 FROM relationship_event_participants participant
            WHERE participant.eventId = relationship_events.eventId AND participant.contactId IS NOT NULL
              AND NOT EXISTS (
                  SELECT 1 FROM contacts active_contact
                  WHERE active_contact.contactId = COALESCE(
                      (SELECT canonicalContactId FROM contact_merge_links
                       WHERE sourceContactId = participant.contactId AND undoneAtEpochMs IS NULL),
                      participant.contactId
                  ) AND active_contact.deletedAtEpochMs IS NULL
              )
        )
        ORDER BY occurredAtEpochMs DESC, updatedAtEpochMs DESC""",
    )
    fun observeActive(): Flow<List<RelationshipEventWithParticipants>>

    @Transaction
    @Query(
        """SELECT DISTINCT relationship_events.* FROM relationship_events
           INNER JOIN relationship_event_participants
             ON relationship_events.eventId = relationship_event_participants.eventId
           WHERE relationship_events.status = 'ACTIVE'
             AND (
               relationship_event_participants.contactId = :contactId OR
               relationship_event_participants.contactId IN (
                 SELECT sourceContactId FROM contact_merge_links WHERE canonicalContactId = :contactId AND undoneAtEpochMs IS NULL
               )
             )
           ORDER BY relationship_events.occurredAtEpochMs DESC, relationship_events.updatedAtEpochMs DESC""",
    )
    fun observeForContact(contactId: String): Flow<List<RelationshipEventWithParticipants>>

    @Transaction
    @Query(
        """SELECT DISTINCT relationship_events.* FROM relationship_events
           INNER JOIN relationship_event_participants
             ON relationship_events.eventId = relationship_event_participants.eventId
           WHERE relationship_events.status = 'ACTIVE'
             AND (
               relationship_event_participants.contactId = :contactId OR
               relationship_event_participants.contactId IN (
                 SELECT sourceContactId FROM contact_merge_links WHERE canonicalContactId = :contactId AND undoneAtEpochMs IS NULL
               )
             )
           ORDER BY relationship_events.occurredAtEpochMs DESC, relationship_events.updatedAtEpochMs DESC
           LIMIT :limit""",
    )
    suspend fun listForContact(contactId: String, limit: Int): List<RelationshipEventWithParticipants>

    @Query("SELECT * FROM relationship_events WHERE eventId = :eventId")
    suspend fun findEvent(eventId: String): RelationshipEventEntity?

    @Query("DELETE FROM relationship_event_participants WHERE eventId = :eventId")
    suspend fun deleteParticipants(eventId: String): Int

    @Query("DELETE FROM relationship_events WHERE eventId = :eventId AND userConfirmed = 1")
    suspend fun deleteConfirmed(eventId: String): Int

    @Query(
        """UPDATE relationship_events SET status = 'DELETED', updatedAtEpochMs = :nowEpochMs
           WHERE status = 'ACTIVE' AND eventId IN (
               SELECT eventId FROM relationship_event_participants WHERE contactId IN (:contactIds)
           )""",
    )
    suspend fun deactivateForContacts(contactIds: List<String>, nowEpochMs: Long): Int
}
