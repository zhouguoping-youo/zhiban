package com.zhiban.rebuild.ui.agent.settings

import com.zhiban.rebuild.runtime.observability.AgentRunTrace
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunHistoryFilterTest {

    private fun trace(status: String = "SUCCEEDED", tools: List<String> = emptyList(), degradations: List<String> = emptyList()) = AgentRunTrace(
        runId = "r",
        status = status,
        durationMs = 100,
        attemptCount = 1,
        toolNames = tools,
        degradationPaths = degradations,
        eventCount = 3,
        auditSteps = emptyList(),
        firstTokenLatencyMs = null,
        retrievalDurationMs = null,
        toolExecutionDurationMs = 0,
        startedAtEpochMs = 1_700_000_000_000,
    )

    @Test fun allMatchesEverything() {
        assertTrue(RunHistoryFilter.ALL.matches(trace(status = "FAILED_FINAL")))
        assertTrue(RunHistoryFilter.ALL.matches(trace()))
    }

    @Test fun successMatchesOnlySucceeded() {
        assertTrue(RunHistoryFilter.SUCCESS.matches(trace(status = "SUCCEEDED")))
        assertFalse(RunHistoryFilter.SUCCESS.matches(trace(status = "FAILED_FINAL")))
        assertFalse(RunHistoryFilter.SUCCESS.matches(trace(status = "CANCELLED")))
    }

    @Test fun failureMatchesAnyFailedPrefix() {
        assertTrue(RunHistoryFilter.FAILURE.matches(trace(status = "FAILED_FINAL")))
        assertTrue(RunHistoryFilter.FAILURE.matches(trace(status = "FAILED_RETRYABLE")))
        assertFalse(RunHistoryFilter.FAILURE.matches(trace(status = "SUCCEEDED")))
    }

    @Test fun degradedMatchesNonEmptyDegradationPaths() {
        assertTrue(RunHistoryFilter.DEGRADED.matches(trace(degradations = listOf("no_remote_vector"))))
        assertFalse(RunHistoryFilter.DEGRADED.matches(trace()))
    }

    @Test fun toolMatchesRunsThatInvokedTools() {
        assertTrue(RunHistoryFilter.TOOL.matches(trace(tools = listOf("calendar.schedule.create"))))
        assertFalse(RunHistoryFilter.TOOL.matches(trace()))
    }
}
