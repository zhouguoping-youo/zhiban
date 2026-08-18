package com.zhiban.rebuild.runtime.kernel

import com.zhiban.rebuild.runtime.context.QueryContext
import com.zhiban.rebuild.runtime.provider.ProviderFailure
import com.zhiban.rebuild.runtime.store.AttemptStartRequest
import com.zhiban.rebuild.runtime.store.RoomRuntimeStore
import com.zhiban.rebuild.runtime.store.RuntimeEventDraft
import com.zhiban.rebuild.runtime.store.startObservationAttempt
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Completes verified single-tool writes without making their success depend on another model call. */
private data class DeterministicObservationDelta(val suffix: String, val ordinal: Long, val part: String, val final: Boolean, val finishReason: String)

internal class DeterministicObservationCompleter(
    private val store: RoomRuntimeStore,
    private val ownerId: String,
    private val clock: () -> Long,
    private val perceive: suspend (DecodedInput) -> QueryContext,
) {
    suspend fun complete(ids: RunIdentifiers, toolName: String, safeResultJson: String): Boolean {
        val rawInput = store.readRunInput(ids.runId, clock())
            ?: throw ProviderFailure("INPUT_EXPIRED_OR_MISSING", false)
        val input = decodeInput(rawInput)
        val queryContext = perceive(input)
        if (!shouldCompleteObservationDeterministically(toolName, queryContext.intentLabel)) return false
        // A deterministic write must not close the run while the same input still has an explicit
        // read domain outstanding; defer so the observation path forces that read (R3 M3).
        if (nextRequiredReadTool(input.text, store.completedToolNames(ids.runId)) != null) return false
        val attemptId = startObservationAttempt(ids)
        val summary = deterministicToolSummary(toolName, safeResultJson)
        appendDelta(ids, attemptId, DeterministicObservationDelta("deterministic-result", 0, summary, false, ""))
        appendDelta(ids, attemptId, DeterministicObservationDelta("deterministic-final", 1, "", true, "verified_local_write"))
        store.completeObservationWithAssistantTurn(
            ids.runId,
            summary,
            "{\"observation\":\"verified_local_write\"}",
            ownerId,
            ids.fencingEpoch,
            clock(),
        )
        return true
    }

    private suspend fun startObservationAttempt(ids: RunIdentifiers): String {
        val attempts = store.recoverySnapshot(ids.runId, "ui").attempts
        val attemptId = "attempt-${ids.runId}-${attempts.size + 1}"
        store.startObservationAttempt(
            AttemptStartRequest(attemptId, ids.runId, attempts.size + 1, ownerId, ids.fencingEpoch, clock()),
        )
        return attemptId
    }

    private suspend fun appendDelta(ids: RunIdentifiers, attemptId: String, delta: DeterministicObservationDelta) {
        val payload = buildJsonObject {
            put("ordinal", delta.ordinal)
            put("part", delta.part)
            put("final", delta.final)
            put("providerOffset", delta.ordinal)
            if (delta.finishReason.isNotEmpty()) put("finishReason", delta.finishReason)
        }.toString()
        store.appendObservationEventOnce(
            RuntimeEventDraft(
                "event-observation-$attemptId-${delta.suffix}",
                "AssistantDelta",
                ids.sessionId,
                ids.runId,
                attemptId,
                attemptId,
                ids.runId,
                payload,
                clock(),
            ),
            ownerId,
            ids.fencingEpoch,
            clock(),
        )
    }
}
