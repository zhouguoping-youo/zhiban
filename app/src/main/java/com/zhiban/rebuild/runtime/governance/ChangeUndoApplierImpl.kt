package com.zhiban.rebuild.runtime.governance

import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.autowrite.ChangeUndoApplier
import javax.inject.Inject
import javax.inject.Singleton

/** Bridges the data-layer [ChangeUndoApplier] contract to [ChangeUndoCoordinator]. */
@Singleton
internal class ChangeUndoApplierImpl @Inject constructor(private val database: AgentDatabase) : ChangeUndoApplier {
    override suspend fun undoVisible(changeId: String, nowEpochMs: Long): Boolean =
        ChangeUndoCoordinator(database).undoVisibleInTransaction(changeId, nowEpochMs) != null

    override suspend fun undoForRun(changeId: String, runId: String, nowEpochMs: Long): Boolean =
        ChangeUndoCoordinator(database).undoInTransaction(changeId, runId, nowEpochMs) != null
}
