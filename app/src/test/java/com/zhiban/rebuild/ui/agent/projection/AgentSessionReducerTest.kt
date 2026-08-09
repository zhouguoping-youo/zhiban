package com.zhiban.rebuild.ui.agent.projection

import com.zhiban.rebuild.runtime.spi.PendingUserOperationState
import com.zhiban.rebuild.runtime.spi.RuntimeAction
import com.zhiban.rebuild.runtime.spi.RuntimeRunStatus
import com.zhiban.rebuild.runtime.spi.RuntimeUiEvent
import com.zhiban.rebuild.runtime.spi.SessionProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSessionReducerTest {
    private val reducer = AgentSessionReducer()

    @Test
    fun `replay ignores an event at or below the projection watermark`() {
        val projection = SessionProjection(sessionId = "session-1", lastAppliedSequence = 3, revision = 2)

        val result = reducer.reduce(
            projection,
            RuntimeUiEvent.RunStatusChanged(
                sessionId = "session-1",
                runId = "run-1",
                sequence = 3,
                revision = 3,
                status = RuntimeRunStatus.EXECUTING,
            ),
        )

        assertEquals(projection, result)
    }

    @Test
    fun `assistant deltas merge once by attempt and ordinal then finalize`() {
        val started = SessionProjection(sessionId = "session-1")
        val first = RuntimeUiEvent.AssistantDelta(
            sessionId = "session-1",
            runId = "run-1",
            attemptId = "attempt-1",
            sequence = 1,
            revision = 1,
            ordinal = 0,
            part = "你",
            final = false,
        )
        val duplicatePayload = first.copy(sequence = 2, revision = 2)
        val final = first.copy(sequence = 3, revision = 3, ordinal = 1, part = "好", final = true)

        val afterFirst = reducer.reduce(started, first)
        val afterDuplicate = reducer.reduce(afterFirst, duplicatePayload)
        val result = reducer.reduce(afterDuplicate, final)

        assertEquals("你好", result.assistantText)
        assertTrue(result.assistantFinal)
        assertEquals(3, result.lastAppliedSequence)
    }

    @Test
    fun `approval budget and source become projection data`() {
        val start = SessionProjection(sessionId = "session-1")
        val approval = RuntimeUiEvent.ApprovalRequested(
            sessionId = "session-1",
            runId = "run-1",
            sequence = 1,
            revision = 4,
            proposalId = "proposal-1",
            payloadRef = "payload-ref-1",
            title = "创建日程",
        )
        val budget = RuntimeUiEvent.BudgetChanged(
            sessionId = "session-1",
            runId = "run-1",
            sequence = 2,
            revision = 5,
            usedTokens = 120,
            maxTokens = 1000,
        )
        val source = RuntimeUiEvent.SourceAttached(
            sessionId = "session-1",
            runId = "run-1",
            sequence = 3,
            revision = 6,
            sourceId = "calendar:42",
            label = "日历",
        )

        val result = listOf(approval, budget, source).fold(start, reducer::reduce)

        assertEquals("proposal-1", result.pendingApproval?.proposalId)
        assertEquals("payload-ref-1", result.pendingApproval?.payloadRef)
        assertEquals(120, result.budget?.usedTokens)
        assertEquals(listOf("日历"), result.sources.map { it.label })
        assertEquals(6, result.revision)
    }

    @Test
    fun `unknown schema is projected read only and clears actions`() {
        val start = SessionProjection(sessionId = "session-1")
        val result = reducer.reduce(
            start,
            RuntimeUiEvent.UnsupportedSchema(
                sessionId = "session-1",
                runId = "run-1",
                sequence = 1,
                revision = 1,
                producerVersion = 99,
            ),
        )

        assertTrue(result.readOnly)
        assertEquals(RuntimeRunStatus.FAILED_FINAL, result.runStatus)
        assertTrue(result.allowedActions.isEmpty())
    }

    @Test
    fun `pending user operation survives projection and terminal result clears it`() {
        val start = SessionProjection(sessionId = "session-1")
        val requested = RuntimeUiEvent.UserOperationRequested(
            sessionId = "session-1",
            runId = "run-1",
            sequence = 1,
            revision = 1,
            requestId = "request-1",
            type = "PHOTO_PICKER",
            expiresAtEpochMs = 9_999,
        )
        val completed = RuntimeUiEvent.UserOperationResolved(
            sessionId = "session-1",
            runId = "run-1",
            sequence = 2,
            revision = 2,
            requestId = "request-1",
            state = PendingUserOperationState.COMPLETED,
        )

        val pending = reducer.reduce(start, requested)
        val result = reducer.reduce(pending, completed)

        assertEquals("request-1", pending.pendingUserOperation?.requestId)
        assertNull(result.pendingUserOperation)
        assertEquals(PendingUserOperationState.COMPLETED, result.lastUserOperationResult?.state)
    }

    @Test
    fun `live event clears cold recovery resume action`() {
        val cold = SessionProjection(
            sessionId = "session-1",
            runId = "run-1",
            recoveryNeeded = true,
            runStatus = RuntimeRunStatus.INFERENCING,
            allowedActions = setOf(
                com.zhiban.rebuild.runtime.spi.RuntimeAction.CANCEL,
                com.zhiban.rebuild.runtime.spi.RuntimeAction.RESUME,
            ),
        )
        val live = reducer.reduce(
            cold,
            RuntimeUiEvent.RunStatusChanged(
                "session-1",
                "run-1",
                1,
                1,
                RuntimeRunStatus.INFERENCING,
            ),
        )

        assertTrue(!live.recoveryNeeded)
        assertTrue(com.zhiban.rebuild.runtime.spi.RuntimeAction.RESUME !in live.allowedActions)
        assertTrue(com.zhiban.rebuild.runtime.spi.RuntimeAction.CANCEL in live.allowedActions)
    }

    @Test
    fun `committed change exposes undo and undone event removes it`() {
        val committed = reducer.reduce(
            SessionProjection("session-1"),
            RuntimeUiEvent.ChangeCommitted("session-1", "run-1", 1, 1, "change-1", "CONTACT", "contact-1"),
        )
        assertEquals("change-1", committed.lastChangeId)
        assertTrue(committed.undoAvailable)
        assertTrue(RuntimeAction.UNDO in committed.allowedActions)

        val undone = reducer.reduce(committed, RuntimeUiEvent.ChangeUndone("session-1", "run-1", 2, 2, "change-1"))
        assertTrue(!undone.undoAvailable)
        assertTrue(RuntimeAction.UNDO !in undone.allowedActions)
    }
}
