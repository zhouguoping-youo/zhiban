package com.zhiban.rebuild.data.interaction

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.zhiban.rebuild.data.calllog.CallRecordEntity
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.notification.NotificationCandidateEntity

object InteractionSourceType {
    const val FACT = "FACT"
    const val CALL = "CALL"
    const val NOTIFICATION = "NOTIFICATION"
}

object InteractionDirection {
    const val UNKNOWN = "UNKNOWN"
    const val INCOMING = "INCOMING"
    const val OUTGOING = "OUTGOING"
}

@Entity(
    tableName = "contact_interactions",
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
        Index("occurredAtEpochMs"),
        Index(value = ["contactId", "occurredAtEpochMs"]),
        Index(value = ["sourceType", "sourceId"], unique = true),
    ],
)
data class ContactInteractionEntity(
    @PrimaryKey val interactionId: String,
    val contactId: String,
    val occurredAtEpochMs: Long,
    val channel: String,
    val direction: String,
    val sourceType: String,
    val sourceId: String,
    val createdAtEpochMs: Long,
)

data class ContactInteractionRecency(val contactId: String, val lastInteractionAtEpochMs: Long?, val silenceDays: Long?)

data class UnobservedReplyFollowUp(val contactId: String, val displayName: String, val outgoingSourceId: String, val outgoingAtEpochMs: Long)

@Dao
interface ContactInteractionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(value: ContactInteractionEntity): Long

    @Query("DELETE FROM contact_interactions WHERE sourceType = :sourceType AND sourceId = :sourceId")
    suspend fun deleteBySource(sourceType: String, sourceId: String): Int

    @Query(
        """SELECT MAX(occurredAtEpochMs) FROM contact_interactions
        WHERE COALESCE(
            (SELECT canonicalContactId FROM contact_merge_links
             WHERE sourceContactId = contact_interactions.contactId AND undoneAtEpochMs IS NULL),
            contactId
        ) = COALESCE(
            (SELECT canonicalContactId FROM contact_merge_links
             WHERE sourceContactId = :contactId AND undoneAtEpochMs IS NULL),
            :contactId
        )""",
    )
    suspend fun latestForContact(contactId: String): Long?

    @Query(
        """SELECT contact.contactId AS contactId,
          MAX(interaction.occurredAtEpochMs) AS lastInteractionAtEpochMs,
          CASE WHEN MAX(interaction.occurredAtEpochMs) IS NULL THEN NULL
               ELSE (:nowEpochMs - MAX(interaction.occurredAtEpochMs)) / 86400000 END AS silenceDays
        FROM contacts contact
        LEFT JOIN contact_interactions interaction ON COALESCE(
            (SELECT canonicalContactId FROM contact_merge_links
             WHERE sourceContactId = interaction.contactId AND undoneAtEpochMs IS NULL),
            interaction.contactId
        ) = contact.contactId
        WHERE contact.deletedAtEpochMs IS NULL
          AND contact.contactId != 'user:self'
          AND contact.contactId NOT IN (
            SELECT sourceContactId FROM contact_merge_links WHERE undoneAtEpochMs IS NULL
          )
        GROUP BY contact.contactId
        ORDER BY COALESCE(MAX(interaction.occurredAtEpochMs), 0), contact.contactId
        LIMIT :limit OFFSET :offset""",
    )
    suspend fun contactRecencyPage(nowEpochMs: Long, limit: Int, offset: Int): List<ContactInteractionRecency>

    @Query(
        """SELECT contact.contactId AS contactId, contact.displayName AS displayName,
          outgoing.sourceId AS outgoingSourceId, outgoing.occurredAtEpochMs AS outgoingAtEpochMs
        FROM contacts contact
        INNER JOIN contact_interactions outgoing ON COALESCE(
            (SELECT canonicalContactId FROM contact_merge_links
             WHERE sourceContactId = outgoing.contactId AND undoneAtEpochMs IS NULL),
            outgoing.contactId
        ) = contact.contactId
        WHERE contact.deletedAtEpochMs IS NULL
          AND contact.contactId NOT IN (
            SELECT sourceContactId FROM contact_merge_links WHERE undoneAtEpochMs IS NULL
          )
          AND outgoing.direction = 'OUTGOING'
          AND outgoing.occurredAtEpochMs <= :cutoffEpochMs
          AND outgoing.occurredAtEpochMs = (
            SELECT MAX(latest.occurredAtEpochMs) FROM contact_interactions latest
            WHERE latest.direction = 'OUTGOING' AND COALESCE(
                (SELECT canonicalContactId FROM contact_merge_links
                 WHERE sourceContactId = latest.contactId AND undoneAtEpochMs IS NULL),
                latest.contactId
            ) = contact.contactId
          )
          AND NOT EXISTS (
            SELECT 1 FROM contact_interactions incoming
            WHERE incoming.direction = 'INCOMING'
              AND incoming.occurredAtEpochMs > outgoing.occurredAtEpochMs
              AND COALESCE(
                (SELECT canonicalContactId FROM contact_merge_links
                 WHERE sourceContactId = incoming.contactId AND undoneAtEpochMs IS NULL),
                incoming.contactId
              ) = contact.contactId
          )
        ORDER BY outgoing.occurredAtEpochMs LIMIT :limit""",
    )
    suspend fun unobservedReplyFollowUps(cutoffEpochMs: Long, limit: Int): List<UnobservedReplyFollowUp>
}

internal fun factInteraction(factId: String, contactId: String, occurredAtEpochMs: Long, createdAtEpochMs: Long): ContactInteractionEntity =
    ContactInteractionEntity(
        interactionId = "fact:$factId",
        contactId = contactId,
        occurredAtEpochMs = occurredAtEpochMs,
        channel = "FACT",
        direction = InteractionDirection.UNKNOWN,
        sourceType = InteractionSourceType.FACT,
        sourceId = factId,
        createdAtEpochMs = createdAtEpochMs,
    )

internal fun callInteraction(value: CallRecordEntity): ContactInteractionEntity? {
    val contactId = value.linkedContactId ?: return null
    if (value.sourceStatus != "ACTIVE" || value.durationSeconds <= 0) return null
    return ContactInteractionEntity(
        interactionId = "call:${value.callRecordId}",
        contactId = contactId,
        occurredAtEpochMs = value.startedAtEpochMs,
        channel = "PHONE",
        direction = value.direction,
        sourceType = InteractionSourceType.CALL,
        sourceId = value.callRecordId,
        createdAtEpochMs = value.createdAtEpochMs,
    )
}

internal fun notificationInteraction(value: NotificationCandidateEntity): ContactInteractionEntity? {
    val contactId = value.linkedContactId ?: return null
    return ContactInteractionEntity(
        interactionId = "notification:${value.candidateId}",
        contactId = contactId,
        occurredAtEpochMs = value.postedAtEpochMs,
        channel = value.platform,
        direction = value.direction,
        sourceType = InteractionSourceType.NOTIFICATION,
        sourceId = value.candidateId,
        createdAtEpochMs = value.createdAtEpochMs,
    )
}
