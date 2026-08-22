package com.zhiban.rebuild.data.agent

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduleDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ScheduleEntity)

    @Update
    suspend fun update(entity: ScheduleEntity): Int

    @Query("SELECT * FROM schedules WHERE createdByRunId = :runId ORDER BY startAtEpochMs")
    suspend fun findByRunId(runId: String): List<ScheduleEntity>

    @Query(
        """SELECT id, title, startAtEpochMs, durationMinutes, note, reminderMinutesBefore, status, outcomeNote, completedAtEpochMs, createdByRuntimeRunId FROM schedules
           WHERE startAtEpochMs <= :toEpochMs
             AND (startAtEpochMs + durationMinutes * 60000) > :fromEpochMs
           ORDER BY startAtEpochMs""",
    )
    fun observeRange(fromEpochMs: Long, toEpochMs: Long): Flow<List<ScheduleProjection>>

    @Query(
        """SELECT id, title, startAtEpochMs, durationMinutes, note, reminderMinutesBefore, status, outcomeNote, completedAtEpochMs, createdByRuntimeRunId FROM schedules
           WHERE status = 'PENDING'
             AND (startAtEpochMs + durationMinutes * 60000) < :beforeEpochMs
             AND (startAtEpochMs + durationMinutes * 60000) >= :oldestEpochMs
           ORDER BY startAtEpochMs DESC LIMIT :limit""",
    )
    fun observePendingFeedback(beforeEpochMs: Long, oldestEpochMs: Long, limit: Int): Flow<List<ScheduleProjection>>

    @Query(
        """SELECT id, title, startAtEpochMs, durationMinutes, note, reminderMinutesBefore, status, outcomeNote, completedAtEpochMs, createdByRuntimeRunId FROM schedules
           WHERE startAtEpochMs <= :toEpochMs
             AND (startAtEpochMs + durationMinutes * 60000) > :fromEpochMs
           ORDER BY startAtEpochMs LIMIT :limit""",
    )
    suspend fun listRange(fromEpochMs: Long, toEpochMs: Long, limit: Int): List<ScheduleProjection>

    @Query(
        """SELECT id, title, startAtEpochMs, durationMinutes, note, reminderMinutesBefore, status, outcomeNote, completedAtEpochMs, createdByRuntimeRunId FROM schedules
           WHERE startAtEpochMs <= :toEpochMs
             AND (startAtEpochMs + durationMinutes * 60000) > :fromEpochMs
             AND (:query = '' OR title LIKE '%' || :query || '%' OR note LIKE '%' || :query || '%')
           ORDER BY startAtEpochMs LIMIT :limit""",
    )
    suspend fun searchRange(query: String, fromEpochMs: Long, toEpochMs: Long, limit: Int): List<ScheduleProjection>

    @Query(
        """SELECT id, title, startAtEpochMs, durationMinutes, note, reminderMinutesBefore, status, outcomeNote, completedAtEpochMs, createdByRuntimeRunId FROM schedules
        WHERE startAtEpochMs < :endEpochMs
          AND (startAtEpochMs + durationMinutes * 60000) > :startEpochMs
          AND (:excludeId IS NULL OR id != :excludeId)
        ORDER BY startAtEpochMs LIMIT :limit""",
    )
    suspend fun findConflicts(startEpochMs: Long, endEpochMs: Long, excludeId: String? = null, limit: Int = 20): List<ScheduleProjection>

    @Query("SELECT * FROM schedules WHERE id = :id")
    suspend fun findById(id: String): ScheduleEntity?

    @Query(
        """UPDATE schedules SET status = :status, outcomeNote = :outcomeNote,
           completedAtEpochMs = :completedAtEpochMs, updatedAtEpochMs = :nowEpochMs
           WHERE id = :id""",
    )
    suspend fun updateCompletion(id: String, status: String, outcomeNote: String?, completedAtEpochMs: Long?, nowEpochMs: Long): Int

    @Query("SELECT COUNT(*) FROM schedules")
    suspend fun count(): Int

    @Query("SELECT * FROM schedules ORDER BY id LIMIT :limit OFFSET :offset")
    suspend fun listPageForExport(limit: Int, offset: Int): List<ScheduleEntity>

    @Query("DELETE FROM schedules WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("SELECT COUNT(*) FROM schedules WHERE id LIKE 'crm-demo-%'")
    suspend fun countLegacyCrmDemo(): Int

    @Query("DELETE FROM schedules WHERE id LIKE 'crm-demo-%'")
    suspend fun deleteLegacyCrmDemo(): Int

    @Query(
        "UPDATE schedules SET title = REPLACE(title, '销售 CRM', '个人 CRM'), note = REPLACE(note, '销售 CRM', '个人 CRM') WHERE title LIKE '%销售 CRM%' OR note LIKE '%销售 CRM%'",
    )
    suspend fun migrateCrmLabel(): Int
}

@Dao
interface AgentRunDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: AgentRunEntity)

    @Update
    suspend fun update(entity: AgentRunEntity)

    @Query("SELECT * FROM agent_runs WHERE id = :id")
    suspend fun findById(id: String): AgentRunEntity?

    @Query(
        "UPDATE agent_runs SET status = :newStatus, errorCode = :errorCode, updatedAtEpochMs = :nowEpochMs WHERE id = :id AND status = :expectedStatus",
    )
    suspend fun transition(id: String, expectedStatus: String, newStatus: String, errorCode: String?, nowEpochMs: Long): Int

    @Query(
        "UPDATE agent_runs SET status = 'AWAITING_CONFIRMATION', pendingToolCallJson = :pendingToolCallJson, updatedAtEpochMs = :nowEpochMs WHERE id = :id AND status = 'PLANNING'",
    )
    suspend fun markAwaitingConfirmation(id: String, pendingToolCallJson: String, nowEpochMs: Long): Int

    @Query("DELETE FROM agent_runs WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("SELECT id FROM agent_runs WHERE expiresAtEpochMs IS NOT NULL AND expiresAtEpochMs <= :nowEpochMs")
    suspend fun findExpiredIds(nowEpochMs: Long): List<String>

    @Query(
        "DELETE FROM agent_runs WHERE id IN (SELECT id FROM agent_runs " +
            "WHERE updatedAtEpochMs < :cutoffEpochMs AND (expiresAtEpochMs IS NULL OR expiresAtEpochMs <= :nowEpochMs) " +
            "ORDER BY updatedAtEpochMs LIMIT :limit)",
    )
    suspend fun deleteRetiredBefore(cutoffEpochMs: Long, nowEpochMs: Long, limit: Int): Int
}

@Dao
interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: MemoryEntity)

    @Query("SELECT * FROM memories WHERE sourceRunId = :runId")
    suspend fun findByRunId(runId: String): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun findById(id: String): MemoryEntity?

    @Query("SELECT * FROM memories WHERE kind = 'USER_PREFERENCE' ORDER BY createdAtEpochMs")
    fun observeUserPreferences(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE kind = 'USER_PREFERENCE' ORDER BY createdAtEpochMs")
    suspend fun listUserPreferences(): List<MemoryEntity>

    @Query("SELECT * FROM memories ORDER BY id LIMIT :limit OFFSET :offset")
    suspend fun listPageForExport(limit: Int, offset: Int): List<MemoryEntity>

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM memories WHERE sourceRunId = :runId AND kind = 'RUN_SUMMARY'")
    suspend fun deleteRunSummaries(runId: String): Int

    @Query(
        "DELETE FROM memories WHERE id IN (SELECT id FROM memories WHERE kind = 'RUN_SUMMARY' " +
            "AND sourceRunId IS NULL AND createdAtEpochMs < :cutoffEpochMs ORDER BY createdAtEpochMs LIMIT :limit)",
    )
    suspend fun deleteOrphanedRunSummariesBefore(cutoffEpochMs: Long, limit: Int): Int
}

@Dao
interface ToolAuditDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ToolAuditEntity)

    @Update
    suspend fun update(entity: ToolAuditEntity)

    @Query("SELECT * FROM tool_audits WHERE idempotencyKey = :key")
    suspend fun findByIdempotencyKey(key: String): ToolAuditEntity?

    @Query(
        "SELECT * FROM tool_audits WHERE runId = :runId AND status = 'SUCCEEDED' ORDER BY updatedAtEpochMs DESC LIMIT 1",
    )
    suspend fun findSuccessfulByRunId(runId: String): ToolAuditEntity?

    @Query("SELECT * FROM tool_audits WHERE subjectRunDigest = :subjectRunDigest ORDER BY createdAtEpochMs")
    suspend fun findBySubjectRunDigest(subjectRunDigest: String): List<ToolAuditEntity>

    @Query("SELECT * FROM tool_audits WHERE runtimeRunId = :runtimeRunId ORDER BY createdAtEpochMs")
    suspend fun findByRuntimeRunId(runtimeRunId: String): List<ToolAuditEntity>

    @Query("UPDATE tool_audits SET resultJson = NULL WHERE runtimeRunId IN (:runtimeRunIds)")
    suspend fun scrubResultsByRuntimeRunIds(runtimeRunIds: List<String>): Int

    @Query("UPDATE tool_audits SET resultJson = NULL, updatedAtEpochMs = :nowEpochMs WHERE runId = :runId")
    suspend fun scrubResultsForRun(runId: String, nowEpochMs: Long): Int

    @Query("DELETE FROM tool_audits WHERE expiresAtEpochMs IS NOT NULL AND expiresAtEpochMs <= :nowEpochMs")
    suspend fun deleteExpired(nowEpochMs: Long): Int

    @Query("DELETE FROM tool_audits WHERE createdAtEpochMs < :cutoffEpochMs")
    suspend fun deleteOlderThan(cutoffEpochMs: Long): Int

    @Query("SELECT COUNT(*) FROM tool_audits")
    suspend fun count(): Int
}

@Dao
internal interface PlanDao {
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.ABORT)
    suspend fun insertVersion(version: PlanVersionEntity)

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun insertVersionIgnore(version: PlanVersionEntity): Long

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.ABORT)
    suspend fun insertDefinition(definition: PlanDefinitionEntity)

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun insertDefinitionIgnore(definition: PlanDefinitionEntity): Long

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.ABORT)
    suspend fun insertNode(node: PlanNodeEntity)

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun insertNodeIgnore(node: PlanNodeEntity): Long

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.ABORT)
    suspend fun insertEdge(edge: PlanEdgeEntity)

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun insertEdgeIgnore(edge: PlanEdgeEntity): Long

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.ABORT)
    suspend fun insertRun(run: PlanRunEntity)

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun insertRunIgnore(run: PlanRunEntity): Long

    @androidx.room.Query(
        "SELECT * FROM plan_nodes WHERE definitionId = :definitionId ORDER BY createdAtEpochMs, nodeKey",
    )
    suspend fun nodesForDefinition(definitionId: String): List<PlanNodeEntity>

    @androidx.room.Query("SELECT * FROM plan_edges WHERE definitionId = :definitionId ORDER BY ordinal")
    suspend fun edgesForDefinition(definitionId: String): List<PlanEdgeEntity>

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.ABORT)
    suspend fun insertAttempt(attempt: NodeAttemptEntity)

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.ABORT)
    suspend fun insertGrant(grant: ApprovalGrantEntity)

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.ABORT)
    suspend fun insertDispatch(dispatch: DispatchOutboxEntity)

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.ABORT)
    suspend fun insertResult(result: ResultLedgerEntity)

    @androidx.room.Query("SELECT * FROM plan_runs WHERE definitionId = :definitionId")
    suspend fun runsForDefinition(definitionId: String): List<PlanRunEntity>

    @androidx.room.Query("SELECT * FROM plan_runs WHERE runId = :runId")
    suspend fun runById(runId: String): PlanRunEntity?

    @androidx.room.Query(
        "UPDATE plan_runs SET activeAttemptId = :attemptId WHERE runId = :runId AND runStatus = :expectedStatus",
    )
    suspend fun updateActiveAttempt(runId: String, expectedStatus: String, attemptId: String): Int

    @androidx.room.Query(
        "UPDATE plan_runs SET runStatus = :newStatus, completedAtEpochMs = :completedAt WHERE runId = :runId AND runStatus = :expectedStatus",
    )
    suspend fun transitionRunStatus(runId: String, expectedStatus: String, newStatus: String, completedAt: Long?): Int

    @androidx.room.Query("SELECT * FROM node_attempts WHERE runId = :runId")
    suspend fun attemptsForRun(runId: String): List<NodeAttemptEntity>

    @androidx.room.Query("SELECT * FROM node_attempts WHERE idempotencyKey = :idempotencyKey")
    suspend fun attemptByIdempotencyKey(idempotencyKey: String): NodeAttemptEntity?

    @androidx.room.Query(
        "UPDATE node_attempts SET status = :newStatus, finishedAtEpochMs = :finishedAt, errorCode = :errorCode WHERE attemptId = :attemptId AND status = :expectedStatus",
    )
    suspend fun transitionAttemptStatus(attemptId: String, expectedStatus: String, newStatus: String, finishedAt: Long?, errorCode: String?): Int

    @androidx.room.Query("SELECT * FROM dispatch_outbox WHERE idempotencyKey = :idempotencyKey")
    suspend fun dispatchByIdempotencyKey(idempotencyKey: String): DispatchOutboxEntity?
}
