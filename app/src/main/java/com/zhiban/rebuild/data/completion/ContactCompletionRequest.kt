package com.zhiban.rebuild.data.completion

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.zhiban.rebuild.data.contact.ContactEntity
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow

/**
 * One proactive "请补全资料" outreach to a contact. The agent drafts a short WeChat message asking for the
 * contact's missing profile fields; the user reviews the draft, then is handed off to WeChat where they pick
 * the contact and press send themselves — 知伴 never sends on the user's behalf. External and irreversible
 * actions require final user confirmation. The row then tracks the lifecycle: AWAITING_REPLY until the
 * contact's next 1:1 incoming
 * message is parsed into enrichment candidates (RESPONSE_RECEIVED), then COMPLETED once those candidates are
 * resolved, or EXPIRED/CANCELLED. At most one active (DRAFTED/AWAITING_REPLY/RESPONSE_RECEIVED) request per
 * contact is allowed so overlapping asks can't confuse response attribution.
 */
@Entity(
    tableName = "contact_completion_requests",
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
        Index("status"),
        Index("expiresAtEpochMs"),
    ],
)
data class ContactCompletionRequestEntity(
    @PrimaryKey val requestId: String,
    val contactId: String,
    /** JSON array of ContactProfileField names being asked for (already capped). Extraction parses only these. */
    val requestedFieldsJson: String,
    val draftText: String,
    val status: String = ContactCompletionStatus.DRAFTED,
    /** WECHAT conversation key, reserved for a future "did the user actually send" reconciliation. */
    val threadKey: String? = null,
    /** The incoming candidate that answered the ask (traceability). */
    val responseCandidateId: String? = null,
    val sentAtEpochMs: Long? = null,
    val respondedAtEpochMs: Long? = null,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

object ContactCompletionStatus {
    const val DRAFTED = "DRAFTED"
    const val AWAITING_REPLY = "AWAITING_REPLY"
    const val RESPONSE_RECEIVED = "RESPONSE_RECEIVED"
    const val COMPLETED = "COMPLETED"
    const val EXPIRED = "EXPIRED"
    const val CANCELLED = "CANCELLED"
}

/** Deterministic id so re-drafting the same field set for a contact upserts instead of duplicating. */
fun contactCompletionRequestId(contactId: String, requestedFieldKey: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$contactId|$requestedFieldKey".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return "ccr-" + digest.take(24)
}

@Dao
interface ContactCompletionRequestDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(value: ContactCompletionRequestEntity)

    @Query("SELECT * FROM contact_completion_requests WHERE requestId = :requestId")
    suspend fun findById(requestId: String): ContactCompletionRequestEntity?

    /** The live request awaiting this contact's reply (unexpired), if any. */
    @Query(
        "SELECT * FROM contact_completion_requests WHERE contactId = :contactId " +
            "AND status = 'AWAITING_REPLY' AND expiresAtEpochMs > :nowEpochMs " +
            "ORDER BY sentAtEpochMs DESC LIMIT 1",
    )
    suspend fun findAwaitingForContact(contactId: String, nowEpochMs: Long): ContactCompletionRequestEntity?

    /**
     * Active (DRAFTED/AWAITING_REPLY/RESPONSE_RECEIVED) unexpired requests — enforces the one-active-request-
     * per-contact cap. RESPONSE_RECEIVED counts too: its candidates are still unresolved, and re-outreach would
     * reuse the deterministic requestId and REPLACE this row, orphaning the response attribution (P1-1).
     */
    @Query(
        "SELECT COUNT(*) FROM contact_completion_requests WHERE contactId = :contactId " +
            "AND status IN ('DRAFTED','AWAITING_REPLY','RESPONSE_RECEIVED') AND expiresAtEpochMs > :nowEpochMs",
    )
    suspend fun countActiveForContact(contactId: String, nowEpochMs: Long): Int

    /** Requests whose reply arrived but whose staged candidates are not all resolved yet — reconciliation input. */
    @Query("SELECT * FROM contact_completion_requests WHERE status = 'RESPONSE_RECEIVED'")
    suspend fun responseReceivedRequests(): List<ContactCompletionRequestEntity>

    @Query(
        "UPDATE contact_completion_requests SET status = 'AWAITING_REPLY', draftText = :draftText, sentAtEpochMs = :sentAtEpochMs, " +
            "expiresAtEpochMs = :expiresAtEpochMs, updatedAtEpochMs = :nowEpochMs " +
            "WHERE requestId = :requestId AND status = 'DRAFTED'",
    )
    suspend fun markAwaiting(requestId: String, draftText: String, sentAtEpochMs: Long, expiresAtEpochMs: Long, nowEpochMs: Long): Int

    @Query(
        "UPDATE contact_completion_requests SET status = 'RESPONSE_RECEIVED', responseCandidateId = :candidateId, " +
            "respondedAtEpochMs = :nowEpochMs, updatedAtEpochMs = :nowEpochMs " +
            "WHERE requestId = :requestId AND status = 'AWAITING_REPLY'",
    )
    suspend fun markResponseReceived(requestId: String, candidateId: String, nowEpochMs: Long): Int

    @Query(
        "UPDATE contact_completion_requests SET status = :status, updatedAtEpochMs = :nowEpochMs " +
            "WHERE requestId = :requestId",
    )
    suspend fun markStatus(requestId: String, status: String, nowEpochMs: Long): Int

    @Query(
        "UPDATE contact_completion_requests SET status = 'EXPIRED', updatedAtEpochMs = :nowEpochMs " +
            "WHERE status IN ('DRAFTED','AWAITING_REPLY') AND expiresAtEpochMs <= :nowEpochMs",
    )
    suspend fun expireAwaitingBefore(nowEpochMs: Long): Int

    /** Cancel a contact's in-flight (DRAFTED/AWAITING_REPLY) requests — used by "不再打扰". */
    @Query(
        "UPDATE contact_completion_requests SET status = 'CANCELLED', updatedAtEpochMs = :nowEpochMs " +
            "WHERE contactId = :contactId AND status IN ('DRAFTED','AWAITING_REPLY')",
    )
    suspend fun cancelActiveForContact(contactId: String, nowEpochMs: Long): Int

    /** Live requests for a contact, for a possible UI badge. */
    @Query(
        "SELECT * FROM contact_completion_requests WHERE contactId = :contactId " +
            "AND status IN ('DRAFTED','AWAITING_REPLY','RESPONSE_RECEIVED') AND expiresAtEpochMs > :nowEpochMs " +
            "ORDER BY createdAtEpochMs DESC",
    )
    fun observeActionableForContact(contactId: String, nowEpochMs: Long): Flow<List<ContactCompletionRequestEntity>>
}
