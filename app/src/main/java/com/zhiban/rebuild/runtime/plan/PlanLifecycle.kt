package com.zhiban.rebuild.runtime.plan

import androidx.room.withTransaction
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.agent.NodeAttemptEntity
import com.zhiban.rebuild.data.agent.PLAN_STATUS_ACTIVE
import com.zhiban.rebuild.data.agent.PLAN_STATUS_SUPERSEDED
import com.zhiban.rebuild.data.agent.PlanDao
import com.zhiban.rebuild.data.agent.PlanDefinitionEntity
import com.zhiban.rebuild.data.agent.PlanNodeEntity
import com.zhiban.rebuild.data.agent.PlanRunEntity
import com.zhiban.rebuild.data.agent.PlanVersionEntity

/**
 * ADR-006 §3.1: per-(definition) single ACTIVE fence manager.
 *
 * The partial UNIQUE index `index_plan_runs_single_active_per_definition` on
 * `plan_runs(definitionId) WHERE runStatus = 'ACTIVE'` enforces the invariant
 * at the schema level. This manager wraps the DAO in a transaction so callers
 * see atomic supersede / pause / resume / start semantics that the
 * SQL-level constraint does not give by itself.
 */
// roadmap placeholder, not dead code: retains plan orchestration skeleton for future execution DAG rollout.
@Suppress("unused")
internal class PlanLifecycle(private val database: AgentDatabase, private val dao: PlanDao, private val clock: () -> Long) {
    suspend fun insertVersion(version: PlanVersionEntity) = dao.insertVersion(version)

    suspend fun insertDefinition(definition: PlanDefinitionEntity) = dao.insertDefinition(definition)

    suspend fun insertNode(node: PlanNodeEntity) = dao.insertNode(node)

    suspend fun insertAttempt(attempt: NodeAttemptEntity) = dao.insertAttempt(attempt)

    /**
     * Start a brand new ACTIVE run. Fails if any ACTIVE run already exists
     * for [definitionId] (the partial UNIQUE index will raise). Callers that
     * want to replace the current ACTIVE run must use [supersedeAndStart].
     */
    suspend fun startActiveRun(definitionId: String, runId: String): PlanRunEntity {
        val run = PlanRunEntity(
            runId = runId,
            definitionId = definitionId,
            runStatus = PLAN_STATUS_ACTIVE,
            activeAttemptId = null,
            startedAtEpochMs = clock(),
            completedAtEpochMs = null,
        )
        dao.insertRun(run)
        return run
    }

    /**
     * CAS pause: ACTIVE -> PAUSED. Returns true iff the run was ACTIVE and
     * is now PAUSED. A paused run keeps its row, so the (runId) record is
     * preserved for resume / audit.
     */
    suspend fun pauseRun(runId: String, nowMs: Long = clock()): Boolean = dao.transitionRunStatus(
        runId = runId,
        expectedStatus = PLAN_STATUS_ACTIVE,
        newStatus = RUN_STATUS_PAUSED,
        completedAt = nowMs,
    ) > 0

    /**
     * CAS resume: PAUSED -> ACTIVE. The partial UNIQUE fence blocks resume
     * if any other ACTIVE run already exists for the same definition; the
     * caller is expected to supersede or pause the other ACTIVE run first.
     */
    suspend fun resumeRun(runId: String): Boolean {
        val existing = dao.runById(runId) ?: return false
        require(existing.runStatus == RUN_STATUS_PAUSED) {
            "resumeRun requires PAUSED, was ${existing.runStatus}"
        }
        return dao.transitionRunStatus(
            runId = runId,
            expectedStatus = RUN_STATUS_PAUSED,
            newStatus = PLAN_STATUS_ACTIVE,
            completedAt = null,
        ) > 0
    }

    /**
     * Atomically transition the current ACTIVE run for [definitionId] (if
     * any) to SUPERSEDED and insert [newRunId] as the new ACTIVE. If no
     * ACTIVE run exists, just inserts the new ACTIVE. Returns the inserted
     * new run.
     */
    suspend fun supersedeAndStart(definitionId: String, newRunId: String, nowMs: Long = clock()): PlanRunEntity = database.withTransaction {
        val currentActive = dao.runsForDefinition(definitionId)
            .firstOrNull { it.runStatus == PLAN_STATUS_ACTIVE }
        if (currentActive != null) {
            val updated = dao.transitionRunStatus(
                runId = currentActive.runId,
                expectedStatus = PLAN_STATUS_ACTIVE,
                newStatus = PLAN_STATUS_SUPERSEDED,
                completedAt = nowMs,
            )
            require(updated == 1) {
                "supersedeAndStart: expected to transition exactly 1 ACTIVE run, updated=$updated"
            }
        }
        val newRun = PlanRunEntity(
            runId = newRunId,
            definitionId = definitionId,
            runStatus = PLAN_STATUS_ACTIVE,
            activeAttemptId = null,
            startedAtEpochMs = nowMs,
            completedAtEpochMs = null,
        )
        dao.insertRun(newRun)
        newRun
    }

    companion object {
        const val RUN_STATUS_PAUSED: String = "PAUSED"
    }
}
