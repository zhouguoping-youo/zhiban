package com.zhiban.rebuild.data.autowrite

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.zhiban.rebuild.data.contact.ContactEntity
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "change_log",
    indices = [
        Index(
            "runtimeRunId",
        ), Index("targetDomain", "targetId"), Index("undoState"), Index(value = ["idempotencyKey"], unique = true),
    ],
)
data class ChangeLogEntity(
    @PrimaryKey val changeId: String,
    val runtimeRunId: String?,
    val toolName: String,
    val idempotencyKey: String,
    val targetDomain: String,
    val targetId: String,
    val operation: String,
    val beforeDigest: String?,
    val afterDigest: String?,
    /** Minimal inverse command only; never stores contact PII in plaintext. */
    val inversePayloadJson: String,
    val undoState: String,
    val createdAtEpochMs: Long,
    val undoneAtEpochMs: Long?,
    val originType: String = "RUNTIME_TOOL",
)

@Entity(
    tableName = "auto_write_receipts",
    foreignKeys = [
        ForeignKey(
            entity = ChangeLogEntity::class,
            parentColumns = ["changeId"],
            childColumns = ["changeId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["contactId"],
            childColumns = ["subjectContactId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("subjectContactId"), Index("reviewState"), Index("createdAtEpochMs")],
)
data class AutoWriteReceiptEntity(
    @PrimaryKey val changeId: String,
    val subjectContactId: String?,
    val sourceType: String,
    val sourceRefDigest: String,
    val confidence: Double?,
    val presentationType: String,
    val correctionRoute: String,
    val reviewState: String,
    val createdAtEpochMs: Long,
)

data class AutoWriteReceiptRow(
    val changeId: String,
    val toolName: String,
    val targetDomain: String,
    val targetId: String,
    val operation: String,
    val undoState: String,
    val subjectContactId: String?,
    val contactName: String?,
    val sourceType: String,
    val confidence: Double?,
    val presentationType: String,
    val correctionRoute: String,
    val reviewState: String,
    val createdAtEpochMs: Long,
    // 收据卡的内容预览:FACT 域取事实正文(如互动摘要文本),SCHEDULE 域取日程标题,
    // 其余类型暂不投影。让用户在关系页就能判断"系统到底整理了啥"。
    val contentPreview: String? = null,
)

@Dao
interface ChangeLogDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(change: ChangeLogEntity)

    @Query("SELECT * FROM change_log WHERE changeId = :changeId")
    suspend fun find(changeId: String): ChangeLogEntity?

    @Query("SELECT * FROM change_log WHERE idempotencyKey = :key")
    suspend fun findByIdempotencyKey(key: String): ChangeLogEntity?

    @Query(
        "UPDATE change_log SET undoState = 'UNDONE', undoneAtEpochMs = :now WHERE changeId = :changeId AND undoState = 'AVAILABLE'",
    )
    suspend fun markUndone(changeId: String, now: Long): Int

    @Query("UPDATE change_log SET undoState = 'UNAVAILABLE' WHERE changeId = :changeId AND undoState = 'AVAILABLE'")
    suspend fun markUnavailable(changeId: String): Int

    @Query("SELECT * FROM change_log WHERE runtimeRunId = :runId ORDER BY createdAtEpochMs")
    suspend fun listByRun(runId: String): List<ChangeLogEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAutoWriteReceipt(receipt: AutoWriteReceiptEntity)

    @Query("SELECT * FROM auto_write_receipts WHERE changeId = :changeId")
    suspend fun findAutoWriteReceipt(changeId: String): AutoWriteReceiptEntity?

    @Query(
        "SELECT change_log.* FROM change_log INNER JOIN auto_write_receipts ON auto_write_receipts.changeId = change_log.changeId WHERE change_log.targetDomain = :targetDomain AND change_log.targetId = :targetId AND change_log.undoState = 'AVAILABLE' ORDER BY change_log.createdAtEpochMs DESC LIMIT 1",
    )
    suspend fun findAvailableAutoChangeForTarget(targetDomain: String, targetId: String): ChangeLogEntity?

    @Query(
        "SELECT * FROM change_log WHERE targetDomain = :targetDomain AND targetId = :targetId " +
            "AND undoState = 'AVAILABLE' ORDER BY createdAtEpochMs DESC LIMIT 1",
    )
    suspend fun findAvailableChangeForTarget(targetDomain: String, targetId: String): ChangeLogEntity?

    @Query(
        """SELECT change_log.changeId AS changeId, change_log.toolName AS toolName,
            change_log.targetDomain AS targetDomain, change_log.targetId AS targetId,
            change_log.operation AS operation, change_log.undoState AS undoState,
            auto_write_receipts.subjectContactId AS subjectContactId,
            contacts.displayName AS contactName,
            auto_write_receipts.sourceType AS sourceType,
            auto_write_receipts.confidence AS confidence,
            auto_write_receipts.presentationType AS presentationType,
            auto_write_receipts.correctionRoute AS correctionRoute,
            auto_write_receipts.reviewState AS reviewState,
            auto_write_receipts.createdAtEpochMs AS createdAtEpochMs,
            CASE WHEN change_log.targetDomain = 'FACT' THEN facts.textContent
                 WHEN change_log.targetDomain = 'SCHEDULE' THEN schedules.title
                 ELSE NULL END AS contentPreview
            FROM auto_write_receipts
            INNER JOIN change_log ON change_log.changeId = auto_write_receipts.changeId
            LEFT JOIN contacts ON contacts.contactId = auto_write_receipts.subjectContactId
            LEFT JOIN facts ON facts.factId = change_log.targetId AND change_log.targetDomain = 'FACT'
            LEFT JOIN schedules ON schedules.id = change_log.targetId AND change_log.targetDomain = 'SCHEDULE'
            ORDER BY auto_write_receipts.createdAtEpochMs DESC
            LIMIT :limit""",
    )
    fun observeAutoWriteReceipts(limit: Int = 100): Flow<List<AutoWriteReceiptRow>>

    @Query(
        "UPDATE auto_write_receipts SET reviewState = 'SEEN' WHERE changeId = :changeId AND reviewState = 'UNREVIEWED'",
    )
    suspend fun markAutoWriteSeen(changeId: String): Int

    @Query("UPDATE auto_write_receipts SET reviewState = 'CORRECTED' WHERE changeId = :changeId")
    suspend fun markAutoWriteCorrected(changeId: String): Int

    @Query(
        "UPDATE change_log SET undoState = 'EXPIRED', inversePayloadJson = '{}' " +
            "WHERE runtimeRunId IN (:runIds) AND undoState = 'AVAILABLE'",
    )
    suspend fun expireAndScrubByRuntimeRunIds(runIds: List<String>): Int

    @Query(
        "UPDATE change_log SET undoState = 'EXPIRED', inversePayloadJson = '{}' " +
            "WHERE changeId IN (SELECT changeId FROM change_log WHERE undoState = 'AVAILABLE' " +
            "AND createdAtEpochMs < :cutoffEpochMs ORDER BY createdAtEpochMs LIMIT :limit)",
    )
    suspend fun expireUndoBefore(cutoffEpochMs: Long, limit: Int): Int

    @Query(
        "DELETE FROM change_log WHERE changeId IN (SELECT changeId FROM change_log " +
            "WHERE undoState IN ('EXPIRED', 'UNDONE', 'UNAVAILABLE') " +
            "AND createdAtEpochMs < :cutoffEpochMs ORDER BY createdAtEpochMs LIMIT :limit)",
    )
    suspend fun deleteTerminalBefore(cutoffEpochMs: Long, limit: Int): Int
}
