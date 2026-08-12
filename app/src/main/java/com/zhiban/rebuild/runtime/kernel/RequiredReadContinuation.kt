package com.zhiban.rebuild.runtime.kernel

import com.zhiban.rebuild.runtime.store.RoomRuntimeStore
import com.zhiban.rebuild.runtime.tool.CapabilityRouter
import com.zhiban.rebuild.runtime.tool.RoutedToolResult
import com.zhiban.rebuild.runtime.tool.RuntimeToolRouteContext
import com.zhiban.rebuild.runtime.tool.sha256
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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

    suspend fun completionResult(input: String, runId: String, fallback: RoutedToolResult): RoutedToolResult = completionSummary(
        input,
        runId,
    )?.let { summary ->
        RoutedToolResult(
            "required.read.summary",
            fallback.providerCallId,
            buildJsonObject { put("summary", summary) }.toString(),
        )
    } ?: fallback
}
