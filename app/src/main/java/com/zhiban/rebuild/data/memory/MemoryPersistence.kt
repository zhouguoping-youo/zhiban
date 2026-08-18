package com.zhiban.rebuild.data.memory

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery

@Entity(
    tableName = "memory_namespaces",
    primaryKeys = ["namespaceId"],
    indices = [Index(value = ["ownerUserId", "profileId", "scopeType", "scopeId"], unique = true)],
)
data class MemoryNamespaceEntity(
    val namespaceId: String,
    val ownerUserId: String,
    val profileId: String,
    val scopeType: String,
    val scopeId: String,
    val state: String,
    val revision: Long,
    val invalidationGeneration: Long,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "memory_records",
    primaryKeys = ["namespaceId", "memoryId", "recordVersion"],
    foreignKeys = [
        ForeignKey(
            entity = MemoryNamespaceEntity::class,
            parentColumns = ["namespaceId"],
            childColumns = ["namespaceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("namespaceId"),
        Index(value = ["namespaceId", "logicalMemoryId"]),
        Index(value = ["namespaceId", "subjectKey", "predicateKey"]),
        Index(value = ["namespaceId", "expiresAtEpochMs"]),
    ],
)
data class MemoryRecordEntity(
    val namespaceId: String,
    val memoryId: String,
    val recordVersion: Long,
    val logicalMemoryId: String,
    val memoryType: String,
    val subjectKey: String,
    val predicateKey: String,
    val objectText: String,
    val canonicalText: String,
    val canonicalDigest: String,
    val sensitivity: String,
    val confidence: Double,
    val importance: Double,
    val status: String,
    val validFromEpochMs: Long?,
    val validToEpochMs: Long?,
    val observedAtEpochMs: Long,
    val txFromEpochMs: Long,
    val txToEpochMs: Long?,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long?,
    val schemaVersion: Int,
    val sourceSetDigest: String,
)

@Entity(
    tableName = "memory_current_versions",
    primaryKeys = ["namespaceId", "logicalMemoryId"],
    foreignKeys = [
        ForeignKey(
            entity = MemoryRecordEntity::class,
            parentColumns = ["namespaceId", "memoryId", "recordVersion"],
            childColumns = ["namespaceId", "memoryId", "recordVersion"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["namespaceId", "memoryId", "recordVersion"], unique = true)],
)
data class MemoryCurrentVersionEntity(val namespaceId: String, val logicalMemoryId: String, val recordVersion: Long, val memoryId: String, val revision: Long)

@Entity(
    tableName = "memory_evidence",
    primaryKeys = ["namespaceId", "memoryId", "recordVersion", "evidenceId"],
    foreignKeys = [
        ForeignKey(
            entity = MemoryRecordEntity::class,
            parentColumns = ["namespaceId", "memoryId", "recordVersion"],
            childColumns = ["namespaceId", "memoryId", "recordVersion"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["namespaceId", "memoryId", "recordVersion"])],
)
data class MemoryEvidenceEntity(
    val namespaceId: String,
    val memoryId: String,
    val recordVersion: Long,
    val evidenceId: String,
    val sourceType: String,
    val sourceRef: String,
    val sourceDigest: String,
    val observedAtEpochMs: Long,
    val excerptDigest: String,
    val trust: String,
    val sensitivity: String,
)

@Entity(
    tableName = "memory_relations",
    primaryKeys = ["namespaceId", "fromMemoryId", "fromRecordVersion", "toMemoryId", "toRecordVersion", "relationType"],
    foreignKeys = [
        ForeignKey(
            entity = MemoryRecordEntity::class,
            parentColumns = ["namespaceId", "memoryId", "recordVersion"],
            childColumns = ["namespaceId", "fromMemoryId", "fromRecordVersion"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MemoryRecordEntity::class,
            parentColumns = ["namespaceId", "memoryId", "recordVersion"],
            childColumns = ["namespaceId", "toMemoryId", "toRecordVersion"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["namespaceId", "fromMemoryId", "fromRecordVersion"]),
        Index(value = ["namespaceId", "toMemoryId", "toRecordVersion"]),
    ],
)
data class MemoryRelationEntity(
    val namespaceId: String,
    val fromMemoryId: String,
    val fromRecordVersion: Long,
    val toMemoryId: String,
    val toRecordVersion: Long,
    val relationType: String,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "memory_index_outbox",
    primaryKeys = ["jobId"],
    foreignKeys = [
        ForeignKey(
            entity = MemoryRecordEntity::class,
            parentColumns = ["namespaceId", "memoryId", "recordVersion"],
            childColumns = ["namespaceId", "memoryId", "recordVersion"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            value = ["namespaceId", "memoryId", "recordVersion"],
        ), Index(value = ["namespaceId", "memoryId", "recordVersion", "indexType"], unique = true), Index("status"),
    ],
)
data class MemoryIndexOutboxEntity(
    val jobId: String,
    val namespaceId: String,
    val memoryId: String,
    val recordVersion: Long,
    val indexType: String,
    val contentDigest: String,
    val status: String,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "memory_commit_receipts",
    primaryKeys = ["namespaceId", "candidateId"],
    foreignKeys = [
        ForeignKey(
            entity = MemoryRecordEntity::class,
            parentColumns = ["namespaceId", "memoryId", "recordVersion"],
            childColumns = ["namespaceId", "memoryId", "recordVersion"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["namespaceId", "memoryId", "recordVersion"]), Index("approvalRef", unique = true)],
)
data class MemoryCommitReceiptEntity(
    val namespaceId: String,
    val candidateId: String,
    val approvalRef: String,
    val canonicalDigest: String,
    val memoryId: String,
    val recordVersion: Long,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "memory_events",
    primaryKeys = ["eventId"],
    foreignKeys = [
        ForeignKey(
            entity = MemoryRecordEntity::class,
            parentColumns = ["namespaceId", "memoryId", "recordVersion"],
            childColumns = ["namespaceId", "memoryId", "recordVersion"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            value = ["namespaceId", "memoryId", "recordVersion"],
        ), Index(value = ["namespaceId", "candidateId", "eventType"], unique = true),
    ],
)
data class MemoryEventEntity(
    val eventId: String,
    val namespaceId: String,
    val candidateId: String,
    val memoryId: String,
    val recordVersion: Long,
    val eventType: String,
    val payloadDigest: String,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "memory_tombstones",
    primaryKeys = ["namespaceId", "logicalMemoryId"],
    foreignKeys = [
        ForeignKey(
            entity = MemoryNamespaceEntity::class,
            parentColumns = ["namespaceId"],
            childColumns = ["namespaceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("namespaceId")],
)
data class MemoryTombstoneEntity(
    val namespaceId: String,
    val logicalMemoryId: String,
    val memoryId: String,
    val highestRecordVersion: Long,
    val deleteCommandDigest: String,
    val deletionRevision: Long,
    val barrierState: String,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "memory_deletion_outbox",
    primaryKeys = ["jobId"],
    foreignKeys = [
        ForeignKey(
            entity = MemoryNamespaceEntity::class,
            parentColumns = ["namespaceId"],
            childColumns = ["namespaceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            "namespaceId",
        ), Index(value = ["namespaceId", "logicalMemoryId", "deletionRevision"], unique = true), Index("status"),
    ],
)
data class MemoryDeletionOutboxEntity(
    val jobId: String,
    val namespaceId: String,
    val logicalMemoryId: String,
    val deletionRevision: Long,
    val targetIndex: String,
    val status: String,
    val createdAtEpochMs: Long,
)

@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "memory_fts")
data class MemoryFtsEntity(val namespaceId: String, val memoryId: String, val recordVersion: Long, val canonicalText: String)

@Dao
internal interface MemoryPersistenceDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNamespace(value: MemoryNamespaceEntity): Long

    @Query("SELECT * FROM memory_namespaces WHERE namespaceId=:namespaceId")
    suspend fun namespace(namespaceId: String): MemoryNamespaceEntity?

    @Query(
        "UPDATE memory_namespaces SET revision=revision+1,invalidationGeneration=invalidationGeneration+1 WHERE namespaceId=:namespaceId AND invalidationGeneration=:expectedGeneration",
    )
    suspend fun bumpGeneration(namespaceId: String, expectedGeneration: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRecord(value: MemoryRecordEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCurrent(value: MemoryCurrentVersionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvidence(values: List<MemoryEvidenceEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReceipt(value: MemoryCommitReceiptEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvent(value: MemoryEventEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIndexJobs(values: List<MemoryIndexOutboxEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putTombstone(value: MemoryTombstoneEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDeletionJobs(values: List<MemoryDeletionOutboxEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFts(value: MemoryFtsEntity)

    @Query("SELECT * FROM memory_commit_receipts WHERE namespaceId=:namespaceId AND candidateId=:candidateId")
    suspend fun receipt(namespaceId: String, candidateId: String): MemoryCommitReceiptEntity?

    @Query("SELECT * FROM memory_current_versions WHERE namespaceId=:namespaceId AND logicalMemoryId=:logicalMemoryId")
    suspend fun current(namespaceId: String, logicalMemoryId: String): MemoryCurrentVersionEntity?

    @Query(
        "SELECT * FROM memory_records WHERE namespaceId=:namespaceId AND memoryId=:memoryId AND recordVersion=:recordVersion",
    )
    suspend fun record(namespaceId: String, memoryId: String, recordVersion: Long): MemoryRecordEntity?

    @Query(
        "DELETE FROM memory_records WHERE namespaceId=:namespaceId AND memoryId=:memoryId AND recordVersion=:recordVersion",
    )
    suspend fun deleteRecord(namespaceId: String, memoryId: String, recordVersion: Long): Int

    @Query(
        "DELETE FROM memory_current_versions WHERE namespaceId=:namespaceId AND logicalMemoryId=:logicalMemoryId AND memoryId=:memoryId AND recordVersion=:recordVersion",
    )
    suspend fun deleteCurrent(namespaceId: String, logicalMemoryId: String, memoryId: String, recordVersion: Long): Int

    @Query(
        "UPDATE memory_current_versions SET memoryId=:memoryId,recordVersion=:newVersion,revision=revision+1 WHERE namespaceId=:namespaceId AND logicalMemoryId=:logicalMemoryId AND memoryId=:memoryId AND recordVersion=:expectedVersion",
    )
    suspend fun moveCurrentVersion(namespaceId: String, logicalMemoryId: String, memoryId: String, expectedVersion: Long, newVersion: Long): Int

    @Query(
        "UPDATE memory_records SET status='SUPERSEDED',txToEpochMs=:nowEpochMs WHERE namespaceId=:namespaceId AND memoryId=:memoryId AND recordVersion=:recordVersion AND status='ACTIVE' AND txToEpochMs IS NULL",
    )
    suspend fun supersedeRecord(namespaceId: String, memoryId: String, recordVersion: Long, nowEpochMs: Long): Int

    @Query(
        "UPDATE memory_records SET status='ACTIVE',txToEpochMs=NULL WHERE namespaceId=:namespaceId AND memoryId=:memoryId AND recordVersion=:recordVersion AND status='SUPERSEDED'",
    )
    suspend fun reactivateRecord(namespaceId: String, memoryId: String, recordVersion: Long): Int

    @Query(
        "DELETE FROM memory_fts WHERE namespaceId=:namespaceId AND memoryId=:memoryId AND recordVersion=:recordVersion",
    )
    suspend fun deleteFts(namespaceId: String, memoryId: String, recordVersion: Long)

    @Query("SELECT * FROM memory_tombstones WHERE namespaceId=:namespaceId AND logicalMemoryId=:logicalMemoryId")
    suspend fun tombstone(namespaceId: String, logicalMemoryId: String): MemoryTombstoneEntity?

    @Query(
        "SELECT * FROM memory_deletion_outbox WHERE namespaceId=:namespaceId AND logicalMemoryId=:logicalMemoryId AND deletionRevision=:generation",
    )
    suspend fun deletionJob(namespaceId: String, logicalMemoryId: String, generation: Long): MemoryDeletionOutboxEntity?

    @Query(
        "UPDATE memory_deletion_outbox SET targetIndex=:newTargets,status=:newStatus WHERE jobId=:jobId AND targetIndex=:expectedTargets AND status='PENDING'",
    )
    suspend fun updateDeletionProgress(jobId: String, expectedTargets: String, newTargets: String, newStatus: String): Int

    @Query(
        "UPDATE memory_tombstones SET barrierState='ACKED' WHERE namespaceId=:namespaceId AND logicalMemoryId=:logicalMemoryId AND deletionRevision=:generation AND barrierState='PENDING'",
    )
    suspend fun finishBarrier(namespaceId: String, logicalMemoryId: String, generation: Long): Int

    @Query(
        """
        SELECT r.* FROM memory_current_versions c
        JOIN memory_records r ON r.namespaceId=c.namespaceId AND r.memoryId=c.memoryId AND r.recordVersion=c.recordVersion
        WHERE c.namespaceId=:namespaceId AND r.canonicalText=:canonicalText AND r.status='ACTIVE'
          AND (r.expiresAtEpochMs IS NULL OR r.expiresAtEpochMs>:trustedNow)
          AND NOT EXISTS (SELECT 1 FROM memory_tombstones t WHERE t.namespaceId=c.namespaceId AND t.logicalMemoryId=c.logicalMemoryId)
        ORDER BY r.memoryId LIMIT :limit
    """,
    )
    suspend fun exactCandidates(namespaceId: String, canonicalText: String, trustedNow: Long, limit: Int): List<MemoryRecordEntity>

    @Query(
        """
        SELECT r.* FROM memory_fts f
        JOIN memory_records r ON r.namespaceId=f.namespaceId AND r.memoryId=f.memoryId AND r.recordVersion=f.recordVersion
        JOIN memory_current_versions c ON c.namespaceId=r.namespaceId AND c.memoryId=r.memoryId AND c.recordVersion=r.recordVersion
        WHERE memory_fts MATCH :ftsQuery AND r.namespaceId=:namespaceId AND r.status='ACTIVE'
          AND (r.expiresAtEpochMs IS NULL OR r.expiresAtEpochMs>:trustedNow)
          AND NOT EXISTS (SELECT 1 FROM memory_tombstones t WHERE t.namespaceId=c.namespaceId AND t.logicalMemoryId=c.logicalMemoryId)
        ORDER BY r.memoryId LIMIT :limit
    """,
    )
    suspend fun ftsCandidates(namespaceId: String, ftsQuery: String, trustedNow: Long, limit: Int): List<MemoryRecordEntity>

    // 子串检索曾每 term 一次全表 instr 扫描(最多 16 次);改为一条 SQL、全部片段 OR 拼接,
    // 一次扫描(P1-性能5)。SQL 含动态片段数,走 @RawQuery(Room 不校验其 SQL)。
    @RawQuery
    suspend fun substringCandidatesRaw(query: SupportSQLiteQuery): List<MemoryRecordEntity>

    suspend fun substringCandidates(namespaceId: String, fragments: List<String>, trustedNow: Long, limit: Int): List<MemoryRecordEntity> {
        if (fragments.isEmpty()) return emptyList()
        val ors = fragments.joinToString(" OR ") { "instr(lower(r.canonicalText), lower(?)) > 0" }
        val sql = SUBSTRING_CANDIDATES_SQL_TEMPLATE.replace(":FRAGMENT_ORS", ors)
        val args = arrayOf(namespaceId, *fragments.toTypedArray(), trustedNow, limit)
        return substringCandidatesRaw(SimpleSQLiteQuery(sql, args))
    }

    @Query(
        "SELECT * FROM memory_evidence WHERE namespaceId=:namespaceId AND memoryId=:memoryId AND recordVersion=:recordVersion ORDER BY evidenceId LIMIT :limit",
    )
    suspend fun evidence(namespaceId: String, memoryId: String, recordVersion: Long, limit: Int): List<MemoryEvidenceEntity>

    @Query(
        "UPDATE memory_records SET status='DORMANT' WHERE status='ACTIVE' AND observedAtEpochMs<=:dormantBeforeEpochMs",
    )
    suspend fun markDormant(dormantBeforeEpochMs: Long): Int

    @Query(
        """
        SELECT r.* FROM memory_current_versions c
        JOIN memory_records r ON r.namespaceId=c.namespaceId AND r.memoryId=c.memoryId AND r.recordVersion=c.recordVersion
        WHERE c.namespaceId=:namespaceId AND r.status='ACTIVE' AND r.txToEpochMs IS NULL
          AND (r.expiresAtEpochMs IS NULL OR r.expiresAtEpochMs>:trustedNow)
          AND NOT EXISTS (SELECT 1 FROM memory_tombstones t WHERE t.namespaceId=c.namespaceId AND t.logicalMemoryId=c.logicalMemoryId)
        ORDER BY r.confidence DESC, r.observedAtEpochMs DESC, r.logicalMemoryId
    """,
    )
    suspend fun recall(namespaceId: String, trustedNow: Long): List<MemoryRecordEntity>
}

private const val SUBSTRING_CANDIDATES_SQL_TEMPLATE = """
    SELECT r.* FROM memory_current_versions c
    JOIN memory_records r ON r.namespaceId=c.namespaceId AND r.memoryId=c.memoryId AND r.recordVersion=c.recordVersion
    WHERE c.namespaceId=? AND (:FRAGMENT_ORS) AND r.status='ACTIVE'
      AND (r.expiresAtEpochMs IS NULL OR r.expiresAtEpochMs>?)
      AND NOT EXISTS (SELECT 1 FROM memory_tombstones t WHERE t.namespaceId=c.namespaceId AND t.logicalMemoryId=c.logicalMemoryId)
    ORDER BY r.confidence DESC, r.observedAtEpochMs DESC LIMIT ?
"""
