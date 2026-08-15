package com.zhiban.rebuild.runtime.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
internal interface RuntimeSessionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: RuntimeSessionEntity): Long

    @Query(
        "UPDATE runtime_sessions SET nextSequence = :nextSequence, updatedAtEpochMs = :nowEpochMs WHERE sessionId = :sessionId AND nextSequence = :expected",
    )
    suspend fun advanceSequence(sessionId: String, expected: Long, nextSequence: Long, nowEpochMs: Long): Int

    @Query("SELECT * FROM runtime_sessions WHERE sessionId = :sessionId")
    suspend fun find(sessionId: String): RuntimeSessionEntity?

    @Query(
        "UPDATE runtime_sessions SET leaseExpiresAtEpochMs = :expiresAtEpochMs, updatedAtEpochMs = :nowEpochMs WHERE sessionId = :sessionId AND leaseOwnerId = :ownerId AND leaseExpiresAtEpochMs > :nowEpochMs",
    )
    suspend fun renew(sessionId: String, ownerId: String, nowEpochMs: Long, expiresAtEpochMs: Long): Int

    @Query(
        "UPDATE runtime_sessions SET leaseOwnerId = :ownerId, leaseEpoch = leaseEpoch + 1, leaseExpiresAtEpochMs = :expiresAtEpochMs, updatedAtEpochMs = :nowEpochMs WHERE sessionId = :sessionId AND (leaseOwnerId IS NULL OR leaseExpiresAtEpochMs IS NULL OR leaseExpiresAtEpochMs <= :nowEpochMs)",
    )
    suspend fun acquireExpired(sessionId: String, ownerId: String, nowEpochMs: Long, expiresAtEpochMs: Long): Int

    @Query(
        "SELECT DISTINCT s.sessionId FROM runtime_sessions s JOIN runtime_runs r ON r.sessionId = s.sessionId WHERE r.status NOT IN ('SUCCEEDED','CANCELLED','FAILED_FINAL') AND (s.leaseExpiresAtEpochMs IS NULL OR s.leaseExpiresAtEpochMs <= :nowEpochMs)",
    )
    suspend fun findRecoverableSessionIds(nowEpochMs: Long): List<String>

    @Query(
        """SELECT MIN(s.leaseExpiresAtEpochMs) FROM runtime_sessions s
        JOIN runtime_runs r ON r.sessionId = s.sessionId
        WHERE r.status IN ('ASSEMBLING_CONTEXT','INFERENCING','EXECUTING','OBSERVING')
        AND s.leaseExpiresAtEpochMs IS NOT NULL AND s.leaseExpiresAtEpochMs > :nowEpochMs""",
    )
    suspend fun nextRecoverableLeaseExpiry(nowEpochMs: Long): Long?

    @Query(
        """SELECT s.sessionId AS sessionId,
        COALESCE((SELECT t.content FROM runtime_conversation_turns t WHERE t.sessionId = s.sessionId AND t.role = 'user' ORDER BY t.createdAtEpochMs DESC LIMIT 1), '新对话') AS preview,
        s.updatedAtEpochMs AS updatedAtEpochMs
        FROM runtime_sessions s
        WHERE EXISTS (SELECT 1 FROM runtime_conversation_turns t2 WHERE t2.sessionId = s.sessionId)
        ORDER BY s.updatedAtEpochMs DESC LIMIT :limit""",
    )
    suspend fun conversationSummaries(limit: Int): List<ConversationSummary>

    @Query("DELETE FROM runtime_sessions WHERE sessionId = :sessionId")
    suspend fun deleteById(sessionId: String): Int
}

@Dao
internal interface RuntimeRunDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: RuntimeRunEntity): Long

    @Query("SELECT * FROM runtime_runs WHERE runId = :runId")
    suspend fun find(runId: String): RuntimeRunEntity?

    @Query(
        "UPDATE runtime_runs SET activeAttemptId = :attemptId, status = 'INFERENCING', recoveryCursor = :recoveryCursor, updatedAtEpochMs = :nowEpochMs WHERE runId = :runId AND status IN ('RECEIVED','ASSEMBLING_CONTEXT','INFERENCING','FAILED_RETRYABLE')",
    )
    suspend fun startAttempt(runId: String, attemptId: String, recoveryCursor: Long, nowEpochMs: Long): Int

    @Query(
        "UPDATE runtime_runs SET activeAttemptId = :attemptId, recoveryCursor = :recoveryCursor, updatedAtEpochMs = :nowEpochMs WHERE runId = :runId AND status = 'OBSERVING'",
    )
    suspend fun startObservationAttempt(runId: String, attemptId: String, recoveryCursor: Long, nowEpochMs: Long): Int

    @Query(
        "UPDATE runtime_runs SET status = :targetStatus, recoveryCursor = :recoveryCursor, updatedAtEpochMs = :nowEpochMs WHERE runId = :runId AND status = :expectedStatus",
    )
    suspend fun transition(runId: String, expectedStatus: String, targetStatus: String, recoveryCursor: Long, nowEpochMs: Long): Int

    @Query(
        "SELECT * FROM runtime_runs WHERE sessionId = :sessionId AND status NOT IN ('SUCCEEDED','CANCELLED','FAILED_FINAL') ORDER BY createdAtEpochMs",
    )
    suspend fun listNonTerminalBySession(sessionId: String): List<RuntimeRunEntity>

    @Query("SELECT * FROM runtime_runs ORDER BY createdAtEpochMs DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<RuntimeRunEntity>

    @Query("SELECT runId FROM runtime_runs WHERE sessionId = :sessionId")
    suspend fun idsBySession(sessionId: String): List<String>
}

@Dao
internal interface RuntimeAttemptDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: RuntimeAttemptEntity)

    @Query("SELECT * FROM runtime_attempts WHERE runId = :runId ORDER BY ordinal")
    suspend fun listByRunId(runId: String): List<RuntimeAttemptEntity>

    @Query(
        "UPDATE runtime_attempts SET status = :status, updatedAtEpochMs = :nowEpochMs WHERE attemptId = :attemptId AND status = 'ACTIVE'",
    )
    suspend fun finish(attemptId: String, status: String, nowEpochMs: Long): Int
}

@Dao
internal interface RuntimeCommandInboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: RuntimeCommandInboxEntity): Long

    @Query("SELECT * FROM runtime_command_inbox WHERE commandId = :commandId")
    suspend fun find(commandId: String): RuntimeCommandInboxEntity?

    @Query("SELECT COUNT(*) FROM runtime_command_inbox WHERE sessionId = :sessionId")
    suspend fun countBySession(sessionId: String): Int

    @Query(
        """SELECT c.* FROM runtime_command_inbox c JOIN runtime_sessions s ON s.sessionId = c.sessionId
        WHERE (s.leaseOwnerId IS NULL OR s.leaseOwnerId = :ownerId OR s.leaseExpiresAtEpochMs IS NULL OR s.leaseExpiresAtEpochMs <= :nowEpochMs)
        AND (c.status = 'PENDING' OR (c.status = 'CLAIMED' AND (s.leaseExpiresAtEpochMs IS NULL OR s.leaseExpiresAtEpochMs <= :nowEpochMs)))
        ORDER BY CASE WHEN c.status = 'PENDING' THEN 0 ELSE 1 END, c.createdAtEpochMs, c.commandId LIMIT 1""",
    )
    suspend fun nextProcessable(nowEpochMs: Long, ownerId: String): RuntimeCommandInboxEntity?

    @Query("SELECT COUNT(*) FROM runtime_command_inbox WHERE status IN ('PENDING','CLAIMED')")
    fun observeWorkCount(): Flow<Int>

    @Query(
        """SELECT MIN(s.leaseExpiresAtEpochMs) FROM runtime_command_inbox c JOIN runtime_sessions s ON s.sessionId = c.sessionId
        WHERE c.status IN ('PENDING','CLAIMED') AND s.leaseOwnerId IS NOT NULL AND s.leaseOwnerId != :ownerId
        AND s.leaseExpiresAtEpochMs IS NOT NULL AND s.leaseExpiresAtEpochMs > :nowEpochMs""",
    )
    suspend fun nextForeignLeaseExpiry(ownerId: String, nowEpochMs: Long): Long?

    @Query(
        "UPDATE runtime_command_inbox SET receiptJson = :receiptJson, updatedAtEpochMs = :nowEpochMs WHERE commandId = :commandId AND receiptJson IS NULL",
    )
    suspend fun persistReceipt(commandId: String, receiptJson: String, nowEpochMs: Long): Int

    @Query(
        "UPDATE runtime_command_inbox SET status = 'CLAIMED', claimedBy = :ownerId, claimedLeaseEpoch = :leaseEpoch, updatedAtEpochMs = :nowEpochMs WHERE commandId = :commandId AND (status = 'PENDING' OR (status = 'CLAIMED' AND claimedLeaseEpoch < :leaseEpoch))",
    )
    suspend fun claim(commandId: String, ownerId: String, leaseEpoch: Long, nowEpochMs: Long): Int

    @Query(
        "UPDATE runtime_command_inbox SET status = 'COMPLETED', resultJson = :resultJson, updatedAtEpochMs = :nowEpochMs WHERE commandId = :commandId AND status IN ('PENDING','CLAIMED')",
    )
    suspend fun complete(commandId: String, resultJson: String, nowEpochMs: Long): Int

    @Query(
        "UPDATE runtime_command_inbox SET status = 'FAILED', resultJson = :resultJson, updatedAtEpochMs = :nowEpochMs WHERE commandId = :commandId AND status IN ('PENDING','CLAIMED')",
    )
    suspend fun fail(commandId: String, resultJson: String, nowEpochMs: Long): Int
}

@Dao
internal interface RuntimeEventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: RuntimeEventEntity)

    @Query("SELECT * FROM runtime_events WHERE sessionId = :sessionId AND sequence > :afterSequence ORDER BY sequence")
    suspend fun listAfter(sessionId: String, afterSequence: Long): List<RuntimeEventEntity>

    @Query("SELECT * FROM runtime_events WHERE sessionId = :sessionId AND sequence > :afterSequence ORDER BY sequence")
    fun observeAfter(sessionId: String, afterSequence: Long): Flow<List<RuntimeEventEntity>>

    @Query("SELECT * FROM runtime_events WHERE runId = :runId ORDER BY sequence")
    suspend fun listByRunId(runId: String): List<RuntimeEventEntity>

    @Query(
        "SELECT * FROM runtime_events WHERE runId = :runId AND eventType = :eventType ORDER BY sequence DESC LIMIT 1",
    )
    suspend fun latestByType(runId: String, eventType: String): RuntimeEventEntity?

    @Query("SELECT * FROM runtime_events WHERE eventId = :eventId")
    suspend fun find(eventId: String): RuntimeEventEntity?
}

@Dao
internal interface RuntimeConversationTurnDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: RuntimeConversationTurnEntity): Long

    @Query(
        "SELECT * FROM (SELECT * FROM runtime_conversation_turns WHERE sessionId=:sessionId AND runId!=:excludeRunId ORDER BY createdAtEpochMs DESC LIMIT :limit) ORDER BY createdAtEpochMs",
    )
    suspend fun recent(sessionId: String, excludeRunId: String, limit: Int): List<RuntimeConversationTurnEntity>

    @Query("SELECT * FROM runtime_conversation_turns WHERE sessionId=:sessionId ORDER BY createdAtEpochMs LIMIT :limit")
    suspend fun listBySession(sessionId: String, limit: Int): List<RuntimeConversationTurnEntity>

    @Query(
        "SELECT content FROM runtime_conversation_turns WHERE sessionId=:sessionId AND runId=:runId AND role='assistant' LIMIT 1",
    )
    suspend fun assistantTurnContent(sessionId: String, runId: String): String?

    @Query("SELECT * FROM runtime_conversation_turns WHERE sessionId=:sessionId ORDER BY createdAtEpochMs LIMIT :limit")
    fun observeBySession(sessionId: String, limit: Int): Flow<List<RuntimeConversationTurnEntity>>

    @Query("SELECT * FROM runtime_conversation_turns ORDER BY turnId LIMIT :limit OFFSET :offset")
    suspend fun listPageForExport(limit: Int, offset: Int): List<RuntimeConversationTurnEntity>
}

@Dao
internal interface RuntimeToolExecutionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: RuntimeToolExecutionEntity)

    @Query("SELECT * FROM runtime_tool_executions WHERE idempotencyKey = :key")
    suspend fun findByKey(key: String): RuntimeToolExecutionEntity?

    @Query("SELECT * FROM runtime_tool_executions WHERE runId = :runId ORDER BY createdAtEpochMs")
    suspend fun listByRunId(runId: String): List<RuntimeToolExecutionEntity>

    @Query("SELECT COUNT(*) FROM runtime_tool_executions WHERE runId = :runId AND toolName = :toolName")
    suspend fun countByRunAndTool(runId: String, toolName: String): Int

    @Query("SELECT COUNT(*) FROM runtime_tool_executions WHERE runId = :runId")
    suspend fun countByRun(runId: String): Int

    @Query(
        "UPDATE runtime_tool_executions SET status = 'SUCCEEDED', resultRef = :resultRef, safeResultJson = :safeResultJson, fencingEpoch = :fencingEpoch, updatedAtEpochMs = :nowEpochMs WHERE executionId = :executionId AND status = 'IN_PROGRESS'",
    )
    suspend fun completeReserved(executionId: String, resultRef: String, safeResultJson: String, fencingEpoch: Long, nowEpochMs: Long): Int

    @Query(
        "DELETE FROM runtime_tool_executions WHERE executionId = :executionId AND status = 'IN_PROGRESS' AND fencingEpoch = :fencingEpoch",
    )
    suspend fun deleteReservation(executionId: String, fencingEpoch: Long): Int
}

@Dao
internal interface RuntimeProjectionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: RuntimeProjectionEntity): Long

    @Query(
        "UPDATE runtime_projections SET consumedSequence = :consumedSequence, snapshotJson = :snapshotJson, updatedAtEpochMs = :nowEpochMs WHERE projectionName = :projectionName AND sessionId = :sessionId AND consumedSequence < :consumedSequence",
    )
    suspend fun advance(projectionName: String, sessionId: String, consumedSequence: Long, snapshotJson: String, nowEpochMs: Long): Int

    @Query("SELECT * FROM runtime_projections WHERE projectionName = :projectionName AND sessionId = :sessionId")
    suspend fun find(projectionName: String, sessionId: String): RuntimeProjectionEntity?
}

@Dao
internal interface RuntimeInputStagingDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: RuntimeInputStagingEntity)

    @Query("SELECT * FROM runtime_input_staging WHERE inputRef = :inputRef")
    suspend fun find(inputRef: String): RuntimeInputStagingEntity?

    @Query("DELETE FROM runtime_input_staging WHERE inputRef = :inputRef")
    suspend fun delete(inputRef: String): Int

    @Query("DELETE FROM runtime_input_staging WHERE expiresAtEpochMs <= :nowEpochMs")
    suspend fun deleteExpired(nowEpochMs: Long): Int

    @Query("SELECT COUNT(*) FROM runtime_input_staging")
    suspend fun count(): Int
}

@Dao
internal interface RuntimeApprovalStagingDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: RuntimeApprovalStagingEntity)

    @Query("SELECT * FROM runtime_approval_staging WHERE stagedRef = :stagedRef")
    suspend fun find(stagedRef: String): RuntimeApprovalStagingEntity?

    @Query("SELECT * FROM runtime_approval_staging WHERE runId = :runId")
    suspend fun findByRunId(runId: String): RuntimeApprovalStagingEntity?

    @Query("DELETE FROM runtime_approval_staging WHERE stagedRef = :stagedRef")
    suspend fun delete(stagedRef: String): Int

    @Query("DELETE FROM runtime_approval_staging WHERE runId = :runId")
    suspend fun deleteByRunId(runId: String): Int

    @Query("DELETE FROM runtime_approval_staging WHERE expiresAtEpochMs <= :nowEpochMs")
    suspend fun deleteExpired(nowEpochMs: Long): Int

    @Query("SELECT COUNT(*) FROM runtime_approval_staging")
    suspend fun count(): Int
}

@Dao
internal interface RuntimeRunInputDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: RuntimeRunInputEntity)

    @Query("SELECT * FROM runtime_run_inputs WHERE runId = :runId")
    suspend fun findByRunId(runId: String): RuntimeRunInputEntity?

    @Query("DELETE FROM runtime_run_inputs WHERE runId = :runId")
    suspend fun deleteByRunId(runId: String): Int

    @Query("DELETE FROM runtime_run_inputs WHERE expiresAtEpochMs <= :nowEpochMs")
    suspend fun deleteExpired(nowEpochMs: Long): Int
}

@Dao
internal interface RuntimeSessionWorkspaceDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: RuntimeSessionWorkspaceEntity): Long

    @Query("SELECT * FROM runtime_session_workspaces WHERE sessionId = :sessionId")
    suspend fun find(sessionId: String): RuntimeSessionWorkspaceEntity?

    @Query(
        """UPDATE runtime_session_workspaces
           SET summaryText = :summaryText,
               summaryThroughTurnAtEpochMs = :throughEpochMs,
               updatedAtEpochMs = :nowEpochMs
           WHERE sessionId = :sessionId""",
    )
    suspend fun updateSummary(sessionId: String, summaryText: String, throughEpochMs: Long, nowEpochMs: Long): Int

    @Query(
        """UPDATE runtime_session_workspaces
           SET totalArtifactBytes = totalArtifactBytes + :byteDelta,
               updatedAtEpochMs = :nowEpochMs
           WHERE sessionId = :sessionId
             AND totalArtifactBytes + :byteDelta >= 0""",
    )
    suspend fun adjustArtifactBytes(sessionId: String, byteDelta: Long, nowEpochMs: Long): Int
}

@Dao
internal interface RuntimeArtifactDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: RuntimeArtifactEntity)

    @Query(
        """SELECT * FROM runtime_artifacts
           WHERE sessionId = :sessionId AND status = 'READY'
           ORDER BY createdAtEpochMs DESC LIMIT :limit""",
    )
    suspend fun listReadyBySession(sessionId: String, limit: Int): List<RuntimeArtifactEntity>

    @Query(
        """SELECT * FROM runtime_artifacts
           WHERE sessionId = :sessionId AND status = 'READY'
           ORDER BY createdAtEpochMs DESC LIMIT :limit""",
    )
    fun observeReadyBySession(sessionId: String, limit: Int): Flow<List<RuntimeArtifactEntity>>

    @Query("SELECT * FROM runtime_artifacts WHERE artifactId = :artifactId")
    suspend fun find(artifactId: String): RuntimeArtifactEntity?

    @Query(
        """UPDATE runtime_artifacts
           SET status = :status, updatedAtEpochMs = :nowEpochMs
           WHERE artifactId = :artifactId AND status = :expectedStatus""",
    )
    suspend fun transition(artifactId: String, expectedStatus: String, status: String, nowEpochMs: Long): Int
}
