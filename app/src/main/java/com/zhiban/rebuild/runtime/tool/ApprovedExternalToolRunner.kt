package com.zhiban.rebuild.runtime.tool

import com.zhiban.rebuild.runtime.provider.ProviderFailure
import com.zhiban.rebuild.runtime.runSuspendCatching
import com.zhiban.rebuild.runtime.store.ApprovedExternalToolReservationRequest
import com.zhiban.rebuild.runtime.store.ApprovedToolExecutionRequest
import com.zhiban.rebuild.runtime.store.RoomRuntimeStore
import com.zhiban.rebuild.runtime.store.abandonApprovedExternalToolReservation
import com.zhiban.rebuild.runtime.store.reserveApprovedExternalTool
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Shared reserve → run → commit scaffolding for approved external side effects (message handoff,
 * WeChat iLink send). Both follow the same idempotency protocol: reserve a reservation keyed by the
 * idempotency key, run the side effect once, then commit the result under `NonCancellable` so a
 * recover replay never repeats an already-visible effect (app opened / message delivered). Callers
 * supply only what differs — the side effect and how its failure maps to a safe code.
 *
 * @param onFailure maps a thrown side-effect failure to the tool's safe [ProviderFailure].
 * @param sideEffect performs the external action and returns the `safeResultJson` to persist.
 */
internal suspend fun RoomRuntimeStore.executeApprovedExternalTool(
    context: ConfirmedToolExecutionContext,
    spec: RuntimeToolSpec,
    toolName: String,
    providerCallId: String,
    logicalStepId: String,
    expectedDigest: String,
    idempotencyKey: String,
    onFailure: (Throwable) -> ProviderFailure,
    sideEffect: suspend () -> String,
): RoutedToolResult {
    val reservation = reserveApprovedExternalTool(
        ApprovedExternalToolReservationRequest(
            context.runId,
            providerCallId,
            logicalStepId,
            toolName,
            spec.version,
            expectedDigest,
            idempotencyKey,
            context.ownerId,
            context.fencingEpoch,
            context.nowEpochMs,
        ),
    )
    if (!reservation.acquired) throw ProviderFailure("EXTERNAL_SIDE_EFFECT_OUTCOME_UNKNOWN", false)
    val safeResult = runSuspendCatching { sideEffect() }
        .getOrElse { failure ->
            abandonApprovedExternalToolReservation(reservation.execution.executionId, context.fencingEpoch)
            throw onFailure(failure)
        }
    // The side effect may already be externally visible, so committing the idempotency record must
    // survive cancellation; otherwise a recover replay finds no record and repeats the effect.
    withContext(NonCancellable) {
        completeApprovedRemoteTool(
            ApprovedToolExecutionRequest(
                context.runId,
                providerCallId,
                logicalStepId,
                toolName,
                spec.version,
                expectedDigest,
                idempotencyKey,
                safeResult,
                context.ownerId,
                context.fencingEpoch,
                context.nowEpochMs,
            ),
        )
    }
    return RoutedToolResult(toolName, providerCallId, safeResult)
}
