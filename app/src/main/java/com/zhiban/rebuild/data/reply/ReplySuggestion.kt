package com.zhiban.rebuild.data.reply

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow

/**
 * One AI-drafted reply candidate for an incoming message the user has not answered yet. A single
 * incoming message yields a GROUP of 2–3 rows (same candidateId, draftIndex 0..2); the card shows the
 * group and forward/dismiss act on the whole group. Status is deliberately per-row but transitioned
 * together. The user always presses send in WeChat themselves — a draft never becomes a sent message
 * without that human act; external and irreversible actions always require final user confirmation.
 */
@Entity(
    tableName = "reply_suggestions",
    indices = [
        Index("candidateId"),
        Index("threadKey"),
        Index("status"),
        Index("createdAtEpochMs"),
    ],
)
data class ReplySuggestionEntity(
    @PrimaryKey val suggestionId: String,
    val candidateId: String,
    val threadKey: String,
    val contactId: String?,
    val draft: String,
    val draftIndex: Int,
    val status: String = ReplySuggestionStatus.PENDING,
    val createdAtEpochMs: Long,
    val forwardedAtEpochMs: Long? = null,
    val confirmedAtEpochMs: Long? = null,
    // Denormalized display fields captured at creation so the card stays correct even after the source
    // candidate is confirmed/dismissed (the inbox only observes PENDING candidates). Not in the plan's
    // minimal schema (§9) but required by the card (§7.2 联系人名 + 待回复消息摘要) without a fragile join.
    val contactName: String? = null,
    @ColumnInfo(defaultValue = "") val incomingExcerpt: String = "",
)

object ReplySuggestionStatus {
    const val PENDING = "PENDING"
    const val FORWARDED = "FORWARDED"
    const val SENT_CONFIRMED = "SENT_CONFIRMED"
    const val DISMISSED = "DISMISSED"
    const val EXPIRED = "EXPIRED"
}

/** Stable conversation key shared by the incoming candidate and its outgoing replies. */
fun replyThreadKey(platform: String, conversationTitle: String?): String = "$platform|${conversationTitle.orEmpty().trim()}"

/** Deterministic id so regenerating the same group upserts instead of duplicating. */
fun replySuggestionId(threadKey: String, candidateId: String, draftIndex: Int): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$threadKey|$candidateId|$draftIndex".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return "rs-" + digest.take(24)
}

@Dao
interface ReplySuggestionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(values: List<ReplySuggestionEntity>)

    @Query("SELECT * FROM reply_suggestions WHERE candidateId = :candidateId")
    suspend fun findByCandidateId(candidateId: String): List<ReplySuggestionEntity>

    /** A live PENDING group for this thread suppresses regenerating on every new message. */
    @Query("SELECT COUNT(*) FROM reply_suggestions WHERE threadKey = :threadKey AND status = 'PENDING'")
    suspend fun countPendingForThread(threadKey: String): Int

    @Query(
        "SELECT * FROM reply_suggestions WHERE status = 'PENDING' AND createdAtEpochMs > :sinceEpochMs " +
            "ORDER BY createdAtEpochMs DESC",
    )
    fun observePending(sinceEpochMs: Long): Flow<List<ReplySuggestionEntity>>

    @Query("SELECT * FROM reply_suggestions WHERE status IN ('FORWARDED','PENDING') AND threadKey = :threadKey ORDER BY createdAtEpochMs DESC")
    suspend fun recentActionableForThread(threadKey: String): List<ReplySuggestionEntity>

    @Query("UPDATE reply_suggestions SET status = :status, forwardedAtEpochMs = :atEpochMs WHERE candidateId = :candidateId AND status = 'PENDING'")
    suspend fun markGroupForwarded(candidateId: String, status: String, atEpochMs: Long): Int

    @Query("UPDATE reply_suggestions SET status = :status, confirmedAtEpochMs = :atEpochMs WHERE threadKey = :threadKey AND status = 'FORWARDED'")
    suspend fun markThreadSentConfirmed(threadKey: String, status: String, atEpochMs: Long): Int

    /**
     * 转发后迟迟没有 OUTGOING(用户取消了微信分享或没发出去):整组回退 PENDING 并清 forwardedAt,
     * 卡片重生、可再次转发,否则 FORWARDED 不可逆、卡片永久消失(P1-7)。
     */
    @Query("UPDATE reply_suggestions SET status = 'PENDING', forwardedAtEpochMs = NULL WHERE threadKey = :threadKey AND status = 'FORWARDED'")
    suspend fun revertThreadToPending(threadKey: String): Int

    @Query("UPDATE reply_suggestions SET status = :status WHERE candidateId = :candidateId AND status = 'PENDING'")
    suspend fun markGroupDismissed(candidateId: String, status: String): Int

    @Query("UPDATE reply_suggestions SET status = 'EXPIRED' WHERE status = 'PENDING' AND createdAtEpochMs < :cutoffEpochMs")
    suspend fun expirePendingBefore(cutoffEpochMs: Long): Int

    @Query("SELECT * FROM reply_suggestions WHERE status = 'FORWARDED'")
    suspend fun forwardedGroups(): List<ReplySuggestionEntity>

    @Query("UPDATE reply_suggestions SET status = 'DISMISSED' WHERE contactId = :contactId AND status = 'PENDING'")
    suspend fun dismissPendingForContact(contactId: String): Int

    @Query("SELECT COUNT(*) FROM reply_suggestions WHERE status = 'PENDING'")
    fun observePendingCount(): Flow<Int>
}
