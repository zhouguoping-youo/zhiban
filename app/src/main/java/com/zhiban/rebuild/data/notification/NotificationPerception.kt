package com.zhiban.rebuild.data.notification

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "notification_candidates",
    indices = [
        Index(value = ["sourceKey"], unique = true),
        Index("status"),
        Index("postedAtEpochMs"),
        Index("platform"),
        Index("suggestedContactId"),
    ],
)
data class NotificationCandidateEntity(
    @PrimaryKey val candidateId: String,
    val sourceKey: String,
    val packageName: String,
    val appLabel: String,
    val title: String?,
    val body: String?,
    val postedAtEpochMs: Long,
    val status: String = "PENDING",
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val sourceType: String = "NOTIFICATION",
    val platform: String = "OTHER",
    val conversationTitle: String? = null,
    val senderName: String? = null,
    val direction: String = "INCOMING",
    val isGroupChat: Boolean = false,
    val messageKind: String = "MESSAGE",
    val insightJson: String? = null,
    val suggestedContactId: String? = null,
    val suggestedContactConfidence: Double = 0.0,
    val linkedContactId: String? = null,
    val createdScheduleId: String? = null,
)

fun sharedTextCandidate(
    sourcePackage: String,
    sourceLabel: String,
    subject: String?,
    body: String?,
    nowEpochMs: Long = System.currentTimeMillis(),
): NotificationCandidateEntity? {
    val cleanSubject = subject.cleanSharedText(200)
    val cleanBody = body.cleanSharedText(500)
    if (cleanSubject == null && cleanBody == null) return null
    val normalizedPackage = sourcePackage.trim().take(200).ifBlank { "manual-share" }
    val sourceKey = sha256("shared-text|$normalizedPackage|$cleanSubject|$cleanBody")
    return NotificationCandidateEntity(
        candidateId = "shared-${sourceKey.take(32)}",
        sourceKey = sourceKey,
        packageName = normalizedPackage,
        appLabel = sourceLabel.trim().take(80).ifBlank { "手动分享" },
        title = cleanSubject,
        body = cleanBody,
        postedAtEpochMs = nowEpochMs,
        createdAtEpochMs = nowEpochMs,
        sourceType = "USER_SHARE",
        platform = SocialAppCatalog.platformForPackage(normalizedPackage)?.code ?: "OTHER",
        conversationTitle = cleanSubject,
        messageKind = "SHARED_TEXT",
        insightJson = NotificationInsightAnalyzer.analyze(
            text = listOfNotNull(cleanSubject, cleanBody).joinToString(" "),
            senderName = null,
            conversationTitle = cleanSubject,
            postedAtEpochMs = nowEpochMs,
        ).toJsonOrNull(),
    )
}

private fun String?.cleanSharedText(limit: Int): String? = this?.replace(Regex("\\s+"), " ")?.trim()?.take(limit)?.takeIf(String::isNotBlank)

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

@Dao
interface NotificationCandidateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(value: NotificationCandidateEntity)

    @Query("SELECT * FROM notification_candidates WHERE status = 'PENDING' ORDER BY postedAtEpochMs DESC LIMIT :limit")
    fun observePending(limit: Int = 100): Flow<List<NotificationCandidateEntity>>

    @Query("SELECT * FROM notification_candidates WHERE candidateId = :candidateId")
    suspend fun find(candidateId: String): NotificationCandidateEntity?

    @Query("SELECT * FROM notification_candidates WHERE sourceKey = :sourceKey LIMIT 1")
    suspend fun findBySourceKey(sourceKey: String): NotificationCandidateEntity?

    @Query(
        "UPDATE notification_candidates SET status = 'DISMISSED' WHERE candidateId = :candidateId AND status = 'PENDING'",
    )
    suspend fun dismiss(candidateId: String): Int

    @Query(
        "UPDATE notification_candidates SET status = 'CONFIRMED' WHERE candidateId = :candidateId AND status = 'PENDING'",
    )
    suspend fun confirm(candidateId: String): Int

    @Query(
        "UPDATE notification_candidates SET status = 'PENDING', linkedContactId = NULL WHERE candidateId = :candidateId AND status = 'CONFIRMED'",
    )
    suspend fun reopen(candidateId: String): Int

    @Query("DELETE FROM notification_candidates WHERE postedAtEpochMs < :cutoffEpochMs OR status = 'DISMISSED'")
    suspend fun clearExpiredOrDismissed(cutoffEpochMs: Long): Int

    @Query(
        """DELETE FROM notification_candidates
           WHERE status = 'PENDING' AND (
             platform = 'SMS'
             OR packageName IN (
               'com.android.mms','com.android.messaging','com.google.android.apps.messaging',
               'com.samsung.android.messaging','com.samsung.android.messagingui',
               'com.samsung.android.app.messaging','com.coloros.mms','com.oplus.mms',
               'com.vivo.message','com.huawei.android.messaging','com.huawei.mms'
             )
           ) AND (
             lower(replace(coalesce(senderName, ''), ' ', '')) IN
               ('信息','新信息','短信','新短信','message','messages','newmessage')
             OR lower(replace(coalesce(title, ''), ' ', '')) IN
               ('信息','新信息','短信','新短信','message','messages','newmessage')
             OR senderName LIKE '106%'
             OR title LIKE '106%'
             OR body LIKE '【%】%'
             OR body LIKE '[%]%'
             OR replace(coalesce(body, ''), ' ', '') IN
               ('查看信息','查看短信','新信息','新短信','你有一条新信息')
           )""",
    )
    suspend fun deleteNonPersonalSmsCandidates(): Int

    @Query(
        """DELETE FROM notification_candidates
           WHERE status = 'PENDING'
             AND sourceType = 'NOTIFICATION'
             AND platform = 'OTHER'""",
    )
    suspend fun deleteUnsupportedLegacyNotificationCandidates(): Int

    @Query(
        """SELECT EXISTS(
            SELECT 1 FROM notification_candidates
            WHERE platform = :platform
              AND direction = 'OUTGOING'
              AND linkedContactId = :linkedContactId
              AND postedAtEpochMs >= :afterEpochMs
            LIMIT 1
        )""",
    )
    suspend fun hasRecentOutgoingByContact(platform: String, linkedContactId: String, afterEpochMs: Long): Boolean

    @Query(
        """SELECT EXISTS(
            SELECT 1 FROM notification_candidates
            WHERE platform = :platform
              AND direction = 'OUTGOING'
              AND conversationTitle = :conversationTitle
              AND postedAtEpochMs >= :afterEpochMs
            LIMIT 1
        )""",
    )
    suspend fun hasRecentOutgoingByConversation(platform: String, conversationTitle: String, afterEpochMs: Long): Boolean
}
