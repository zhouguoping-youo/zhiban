package com.zhiban.rebuild.data.memory

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.withTransaction
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.foundation.MemoryCandidateState
import com.zhiban.rebuild.foundation.MemoryScope
import com.zhiban.rebuild.foundation.Sensitivity
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(
    tableName = "staged_memory_candidates",
    indices = [Index("state"), Index("expiresAtEpochMs"), Index(value = ["scope", "scopeId"])],
)
data class StagedMemoryCandidateEntity(
    @PrimaryKey val id: String,
    val scope: String,
    val scopeId: String?,
    val content: String?,
    val contentDigest: String,
    val utf8Length: Int,
    val sourceIdsJson: String,
    val sensitivity: String,
    val state: String,
    val approvalRef: String? = null,
    val revision: Long = 0,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Dao internal interface StagedMemoryCandidateDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(value: StagedMemoryCandidateEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(value: StagedMemoryCandidateEntity): Long

    @Query("SELECT * FROM staged_memory_candidates WHERE id=:id")
    suspend fun find(id: String): StagedMemoryCandidateEntity?

    @Query(
        "SELECT * FROM staged_memory_candidates WHERE state='PENDING' AND scope=:scope AND ((scopeId IS NULL AND :scopeId IS NULL) OR scopeId=:scopeId) AND expiresAtEpochMs>:now ORDER BY createdAtEpochMs,id",
    )
    suspend fun listPending(scope: String, scopeId: String?, now: Long): List<StagedMemoryCandidateEntity>

    @Query(
        "UPDATE staged_memory_candidates SET state='APPROVED',approvalRef=:approvalRef,revision=revision+1,updatedAtEpochMs=:now WHERE id=:id AND state='PENDING' AND revision=:expectedRevision AND expiresAtEpochMs>:now",
    )
    suspend fun approve(id: String, approvalRef: String, expectedRevision: Long, now: Long): Int

    @Query(
        "UPDATE staged_memory_candidates SET content=NULL,state=:target,revision=revision+1,updatedAtEpochMs=:now WHERE id=:id AND state='PENDING'",
    )
    suspend fun clearPending(id: String, target: String, now: Long): Int

    @Query(
        "UPDATE staged_memory_candidates SET content=NULL,state='DELETED',revision=revision+1,updatedAtEpochMs=:now WHERE id=:id AND state IN ('PENDING','APPROVED')",
    )
    suspend fun delete(id: String, now: Long): Int

    @Query(
        "UPDATE staged_memory_candidates SET content=NULL,state='EXPIRED',revision=revision+1,updatedAtEpochMs=:now WHERE state='PENDING' AND expiresAtEpochMs<=:now",
    )
    suspend fun purgeExpired(now: Long): Int

    @Query(
        "UPDATE staged_memory_candidates SET content=NULL,state='CONSUMED',revision=revision+1,updatedAtEpochMs=:now WHERE id=:id AND state='APPROVED' AND approvalRef=:approvalRef AND revision=:expectedRevision AND expiresAtEpochMs>:now",
    )
    suspend fun consume(id: String, approvalRef: String, expectedRevision: Long, now: Long): Int
}

data class StagedMemoryCandidateView(
    val id: String,
    val scope: MemoryScope,
    val scopeId: String?,
    val content: String?,
    val contentDigest: String,
    val sourceIds: List<String>,
    val sensitivity: Sensitivity,
    val state: String,
    val approvalRef: String?,
    val revision: Long,
    val expiresAtEpochMs: Long,
)
enum class ApprovalWriteResult { APPROVED, DUPLICATE, CONFLICT }

internal class RoomStagedMemoryCandidateStore(private val database: AgentDatabase, private val random: SecureRandom = SecureRandom()) {
    suspend fun stage(
        scope: MemoryScope,
        scopeId: String?,
        content: String,
        sourceIds: List<String>,
        sensitivity: Sensitivity,
        nowEpochMs: Long,
        ttlMs: Long,
    ): StagedMemoryCandidateView = database.withTransaction {
        require((scope == MemoryScope.GLOBAL) == (scopeId == null)) { "scope identity mismatch" }
        require(scopeId == null || scopeId.toByteArray().size <= 256)
        val distinctSources = sourceIds.distinct()
        require(distinctSources.isNotEmpty() && distinctSources.size <= 64)
        require(distinctSources.all { it.isNotBlank() && it.toByteArray().size <= 256 })
        val sourceJson = Json.encodeToString(distinctSources)
        require(sourceJson.toByteArray().size <= 8 * 1024)
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= 64 * 1024)
        require(ttlMs in 1..24 * 60 * 60 * 1000L)
        require(bytes.size + sourceJson.toByteArray().size + (scopeId?.toByteArray()?.size ?: 0) <= 72 * 1024)
        val idBytes = ByteArray(16).also(random::nextBytes)
        val id = idBytes.joinToString("") { "%02x".format(it) }
        val entity =
            StagedMemoryCandidateEntity(
                id, scope.name, scopeId, content,
                sha256(
                    bytes,
                ),
                bytes.size, sourceJson, sensitivity.name, MemoryCandidateState.PENDING.name, null, 0, nowEpochMs,
                Math.addExact(
                    nowEpochMs,
                    ttlMs,
                ),
                nowEpochMs,
            )
        database.stagedMemoryCandidateDao().insert(entity)
        entity.view()
    }
    suspend fun find(id: String, nowEpochMs: Long) = database.withTransaction {
        database.stagedMemoryCandidateDao().purgeExpired(nowEpochMs)
        database.stagedMemoryCandidateDao().find(id)?.view()
    }
    suspend fun listPending(scope: MemoryScope, scopeId: String?, nowEpochMs: Long) = database.withTransaction {
        database.stagedMemoryCandidateDao().purgeExpired(nowEpochMs)
        database.stagedMemoryCandidateDao().listPending(scope.name, scopeId, nowEpochMs).map { it.view() }
    }
    suspend fun approve(id: String, approvalRef: String, expectedRevision: Long, nowEpochMs: Long): ApprovalWriteResult = database.withTransaction {
        require(approvalRef.isNotBlank() && approvalRef.toByteArray().size <= 256)
        if (database.stagedMemoryCandidateDao().approve(id, approvalRef, expectedRevision, nowEpochMs) ==
            1
        ) {
            return@withTransaction ApprovalWriteResult.APPROVED
        }
        database.stagedMemoryCandidateDao().purgeExpired(nowEpochMs)
        val current = database.stagedMemoryCandidateDao().find(
            id,
        ) ?: return@withTransaction ApprovalWriteResult.CONFLICT
        if (current.state == MemoryCandidateState.APPROVED.name && current.approvalRef == approvalRef &&
            current.revision == expectedRevision + 1
        ) {
            ApprovalWriteResult.DUPLICATE
        } else {
            ApprovalWriteResult.CONFLICT
        }
    }
    suspend fun reject(id: String, nowEpochMs: Long) = database.stagedMemoryCandidateDao().clearPending(id, MemoryCandidateState.REJECTED.name, nowEpochMs) == 1
    suspend fun delete(id: String, nowEpochMs: Long) = database.stagedMemoryCandidateDao().delete(id, nowEpochMs) == 1
    suspend fun purgeExpired(nowEpochMs: Long) = database.stagedMemoryCandidateDao().purgeExpired(nowEpochMs)
    private fun StagedMemoryCandidateEntity.view() = StagedMemoryCandidateView(
        id,
        MemoryScope.valueOf(
            scope,
        ),
        scopeId, content, contentDigest,
        Json.decodeFromString(
            sourceIdsJson,
        ),
        Sensitivity.valueOf(sensitivity), state, approvalRef, revision, expiresAtEpochMs,
    )
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
