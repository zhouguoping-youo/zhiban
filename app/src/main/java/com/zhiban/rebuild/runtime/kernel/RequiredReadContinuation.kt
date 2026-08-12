package com.zhiban.rebuild.runtime.kernel

import com.zhiban.rebuild.runtime.store.AttemptStartRequest
import com.zhiban.rebuild.runtime.store.RoomRuntimeStore
import com.zhiban.rebuild.runtime.store.RuntimeEventDraft
import com.zhiban.rebuild.runtime.tool.CapabilityRouter
import com.zhiban.rebuild.runtime.tool.RoutedToolResult
import com.zhiban.rebuild.runtime.tool.RuntimeToolRouteContext
import com.zhiban.rebuild.runtime.tool.sha256

/** Enforces explicitly requested read-only domains when a model ends observation too early. */
internal class RequiredReadContinuation(
    private val capabilityRouter: CapabilityRouter,
    private val store: RoomRuntimeStore,
    private val ownerId: String,
    private val clock: () -> Long,
) {
    suspend fun execute(
        input: String,
        completedTools: Set<String>,
        runId: String,
        sessionId: String,
        attemptId: String,
        fencingEpoch: Long,
    ): RoutedToolResult? {
        val requiredTool = nextRequiredReadTool(input, completedTools) ?: return null
        val request = requiredReadToolCall(requiredTool, input, clock())
        val context = RuntimeToolRouteContext(
            runId,
            sessionId,
            attemptId,
            ownerId,
            fencingEpoch,
            store.projectionSnapshot(sessionId, "ui").currentRevision,
            clock(),
        )
        val result = capabilityRouter.executeReadOnly(request, context)
        store.completeReadOnlyTool(
            runId,
            request.providerCallId,
            result.canonicalName,
            1,
            sha256(request.argumentsJson),
            result.safeResultJson,
            ownerId,
            fencingEpoch,
            clock(),
        )
        return result
    }

    suspend fun completionSummary(input: String, runId: String): String? = requiredReadCompletionSummary(
        input,
        store.completedToolResults(runId),
    )

    suspend fun completeSummary(input: String, runId: String, sessionId: String, fencingEpoch: Long): Boolean {
        val summary = completionSummary(input, runId) ?: return false
        val attemptOrdinal = store.recoverySnapshot(runId, "ui").attempts.size + 1
        val attemptId = "attempt-$runId-$attemptOrdinal"
        store.startObservationAttempt(AttemptStartRequest(attemptId, runId, attemptOrdinal, ownerId, fencingEpoch, clock()))
        appendSummaryEvent(runId, sessionId, attemptId, fencingEpoch, 0, summary, final = false)
        appendSummaryEvent(runId, sessionId, attemptId, fencingEpoch, 1, "", final = true)
        store.completeObservationWithAssistantTurn(runId, summary, "{}", ownerId, fencingEpoch, clock())
        return true
    }

    private suspend fun appendSummaryEvent(
        runId: String,
        sessionId: String,
        attemptId: String,
        fencingEpoch: Long,
        ordinal: Long,
        part: String,
        final: Boolean,
    ) {
        val payload = "{\"ordinal\":$ordinal,\"part\":${jsonString(part)},\"final\":$final}"
        store.appendObservationEventOnce(
            RuntimeEventDraft(
                "event-required-summary-$attemptId-$ordinal",
                "AssistantDelta",
                sessionId,
                runId,
                attemptId,
                attemptId,
                runId,
                payload,
                clock(),
            ),
            ownerId,
            fencingEpoch,
            clock(),
        )
    }

    private fun jsonString(value: String): String = kotlinx.serialization.json.JsonPrimitive(value).toString()
}
