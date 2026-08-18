package com.zhiban.rebuild.runtime.kernel

import com.zhiban.agent.skills.SkillActivation
import com.zhiban.agent.skills.SkillActivator
import com.zhiban.agent.skills.SkillOrigin
import com.zhiban.agent.skills.SkillSpec
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.notification.NotificationInsightAnalyzer
import com.zhiban.rebuild.runtime.context.ContextBlock
import com.zhiban.rebuild.runtime.context.ContextKind
import com.zhiban.rebuild.runtime.context.ContextLayer
import com.zhiban.rebuild.runtime.context.ContextProvenance
import com.zhiban.rebuild.runtime.context.ContextRetrievalResult
import com.zhiban.rebuild.runtime.context.PerceptionGateway
import com.zhiban.rebuild.runtime.context.PromptAssembler
import com.zhiban.rebuild.runtime.context.PromptBudget
import com.zhiban.rebuild.runtime.context.QueryContext
import com.zhiban.rebuild.runtime.context.RoomContextRetrievalPipeline
import com.zhiban.rebuild.runtime.context.RoomPerceptionPipeline
import com.zhiban.rebuild.runtime.context.Sensitivity
import com.zhiban.rebuild.runtime.context.TrustLevel
import com.zhiban.rebuild.runtime.context.reranked
import com.zhiban.rebuild.runtime.context.resolveCalendarStartEpochMs
import com.zhiban.rebuild.runtime.context.withDegradations
import com.zhiban.rebuild.runtime.governance.AutoWritePresentationRegistry
import com.zhiban.rebuild.runtime.governance.ChangeUndoCoordinator
import com.zhiban.rebuild.runtime.governance.ContactDomainWriter
import com.zhiban.rebuild.runtime.governance.RelationshipDomainWriter
import com.zhiban.rebuild.runtime.memory.RoomMemoryGate
import com.zhiban.rebuild.runtime.provider.CapabilitySnapshot
import com.zhiban.rebuild.runtime.provider.ModelEvent
import com.zhiban.rebuild.runtime.provider.ModelMessage
import com.zhiban.rebuild.runtime.provider.ModelRequest
import com.zhiban.rebuild.runtime.provider.OutboundChannel
import com.zhiban.rebuild.runtime.provider.OutboundProvenance
import com.zhiban.rebuild.runtime.provider.OutboundPurpose
import com.zhiban.rebuild.runtime.provider.OutboundSensitivity
import com.zhiban.rebuild.runtime.provider.ProviderAdapter
import com.zhiban.rebuild.runtime.provider.ProviderFailure
import com.zhiban.rebuild.runtime.provider.ProviderModelPolicy
import com.zhiban.rebuild.runtime.provider.ProviderProfile
import com.zhiban.rebuild.runtime.provider.ProviderProfileStore
import com.zhiban.rebuild.runtime.spi.RuntimeRunStatus
import com.zhiban.rebuild.runtime.store.AttemptStartRequest
import com.zhiban.rebuild.runtime.store.RoomRuntimeStore
import com.zhiban.rebuild.runtime.store.startAttempt
import com.zhiban.rebuild.runtime.store.RuntimeEventDraft
import com.zhiban.rebuild.runtime.store.RuntimeRecoveryHandle
import com.zhiban.rebuild.runtime.tool.CalendarConflictToolBinding
import com.zhiban.rebuild.runtime.tool.CalendarMutationToolBinding
import com.zhiban.rebuild.runtime.tool.CalendarSearchToolBinding
import com.zhiban.rebuild.runtime.tool.CapabilityRouter
import com.zhiban.rebuild.runtime.tool.CommunicationMessageToolBinding
import com.zhiban.rebuild.runtime.tool.ConfirmedToolExecutionContext
import com.zhiban.rebuild.runtime.tool.ContactCreateCandidateToolBinding
import com.zhiban.rebuild.runtime.tool.ContactDetailToolBinding
import com.zhiban.rebuild.runtime.tool.ContactProfileUpdateCandidateToolBinding
import com.zhiban.rebuild.runtime.tool.ContactSearchToolBinding
import com.zhiban.rebuild.runtime.tool.ContactTagDomainWriter
import com.zhiban.rebuild.runtime.tool.ContactTagToolBinding
import com.zhiban.rebuild.runtime.tool.CrmMutationToolBinding
import com.zhiban.rebuild.runtime.tool.CrmOpportunityDetailToolBinding
import com.zhiban.rebuild.runtime.tool.CrmOpportunityListToolBinding
import com.zhiban.rebuild.runtime.tool.MemoryDeleteToolBinding
import com.zhiban.rebuild.runtime.tool.MemoryRememberPlanValidator
import com.zhiban.rebuild.runtime.tool.MemoryRememberToolBinding
import com.zhiban.rebuild.runtime.tool.MemoryRememberToolCall
import com.zhiban.rebuild.runtime.tool.MemorySearchToolBinding
import com.zhiban.rebuild.runtime.tool.RelationshipCreateCandidateToolBinding
import com.zhiban.rebuild.runtime.tool.RelationshipEvidenceToolBinding
import com.zhiban.rebuild.runtime.tool.RelationshipSearchToolBinding
import com.zhiban.rebuild.runtime.tool.RemoteMcpToolBinding
import com.zhiban.rebuild.runtime.tool.RoomCrmToolExecutor
import com.zhiban.rebuild.runtime.tool.RoomMemoryToolExecutor
import com.zhiban.rebuild.runtime.tool.RoomScheduleToolExecutor
import com.zhiban.rebuild.runtime.tool.RoutedToolResult
import com.zhiban.rebuild.runtime.tool.RuntimeToolCallRequest
import com.zhiban.rebuild.runtime.tool.RuntimeToolCatalog
import com.zhiban.rebuild.runtime.tool.RuntimeToolRouteContext
import com.zhiban.rebuild.runtime.tool.ScheduleCreateToolBinding
import com.zhiban.rebuild.runtime.tool.ScheduleCreateToolCall
import com.zhiban.rebuild.runtime.tool.SchedulePlanValidator
import com.zhiban.rebuild.runtime.tool.ToolConfirmation
import com.zhiban.rebuild.runtime.tool.ToolDisposition
import com.zhiban.rebuild.runtime.tool.canonicalMemoryDigest
import com.zhiban.rebuild.runtime.tool.canonicalMemoryIdempotencyKey
import com.zhiban.rebuild.runtime.tool.canonicalScheduleDigest
import com.zhiban.rebuild.runtime.tool.canonicalToolIdempotencyKey
import com.zhiban.rebuild.runtime.tool.sha256
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Executes only the provider portion of a Runtime v2 run. It never exposes credential material. */
internal class RuntimeEventAppender(private val store: RoomRuntimeStore, private val ownerId: String, private val clock: () -> Long) {
    internal suspend fun failBeforeAttempt(runId: String, sessionId: String, fencingEpoch: Long, safeCode: String, retryable: Boolean): Boolean {
        val run = requireNotNull(store.runById(runId))
        val attempts = store.recoverySnapshot(runId, "ui").attempts
        val activeAttempt = run.activeAttemptId?.let { id ->
            attempts.firstOrNull {
                it.attemptId == id &&
                    it.status == "ACTIVE"
            }
        }
        if (activeAttempt == null) {
            store.startAttempt(
                AttemptStartRequest(
                    "attempt-$runId-${attempts.size + 1}",
                    runId,
                    attempts.size + 1,
                    ownerId,
                    fencingEpoch,
                    clock(),
                ),
            )
        }
        store.finishProviderRun(
            runId,
            if (retryable) RuntimeRunStatus.FAILED_RETRYABLE.name else RuntimeRunStatus.FAILED_FINAL.name,
            if (retryable) "RunFailedRetryable" else "RunFailedFinal",
            buildJsonObject { put("errorCode", safeCode) }.toString(),
            "FAILED", ownerId, fencingEpoch, clock(), deleteInput = !retryable,
        )
        return false
    }

    internal suspend fun append(draft: RuntimeEventDraft, fencingEpoch: Long) {
        store.appendProviderEventOnce(draft, ownerId, fencingEpoch, clock())
    }

    internal suspend fun appendProviderDelta(event: ModelEvent.Delta, attemptId: String, ids: RunIdentifiers) {
        append(
            RuntimeEventDraft(
                "event-provider-$attemptId-delta-${event.ordinal}",
                "AssistantDelta",
                ids.sessionId,
                ids.runId,
                attemptId,
                attemptId,
                ids.runId,
                buildJsonObject {
                    put("ordinal", event.ordinal)
                    put("part", event.text)
                    put("final", false)
                    put("providerOffset", event.ordinal)
                }.toString(),
                clock(),
            ),
            ids.fencingEpoch,
        )
    }

    internal suspend fun appendProviderUsage(event: ModelEvent.Usage, capability: CapabilitySnapshot, attemptId: String, ids: RunIdentifiers) {
        append(
            RuntimeEventDraft(
                "event-provider-$attemptId-usage",
                "ProviderUsageRecorded",
                ids.sessionId,
                ids.runId,
                attemptId,
                attemptId,
                ids.runId,
                buildJsonObject {
                    put("usedTokens", event.inputTokens + event.outputTokens)
                    put("maxTokens", capability.maxContextTokens)
                }.toString(),
                clock(),
            ),
            ids.fencingEpoch,
        )
    }

    internal suspend fun appendProviderFinal(event: ModelEvent.Final, lastOrdinal: Long, attemptId: String, ids: RunIdentifiers) {
        append(
            RuntimeEventDraft(
                "event-provider-$attemptId-final-delta",
                "AssistantDelta",
                ids.sessionId,
                ids.runId,
                attemptId,
                attemptId,
                ids.runId,
                buildJsonObject {
                    put("ordinal", lastOrdinal + 1)
                    put("part", "")
                    put("final", true)
                    put("finishReason", event.finishReason)
                }.toString(),
                clock(),
            ),
            ids.fencingEpoch,
        )
    }

    internal suspend fun appendObservation(attemptId: String, ids: RunIdentifiers, suffix: String, type: String, payload: String) {
        store.appendObservationEventOnce(
            RuntimeEventDraft(
                "event-observation-$attemptId-$suffix",
                type,
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

    internal suspend fun appendObservationDelta(event: ModelEvent.Delta, attemptId: String, ids: RunIdentifiers) = appendObservation(
        attemptId,
        ids,
        "delta-${event.ordinal}",
        "AssistantDelta",
        buildJsonObject {
            put("ordinal", event.ordinal)
            put("part", event.text)
            put("final", false)
            put("providerOffset", event.ordinal)
        }.toString(),
    )

    internal suspend fun appendObservationUsage(event: ModelEvent.Usage, capability: CapabilitySnapshot, attemptId: String, ids: RunIdentifiers) =
        appendObservation(
            attemptId,
            ids,
            "usage",
            "ProviderUsageRecorded",
            buildJsonObject {
                put("usedTokens", event.inputTokens + event.outputTokens)
                put("maxTokens", capability.maxContextTokens)
            }.toString(),
        )

    internal suspend fun appendObservationFallback(lastOrdinal: Long, fallback: String, attemptId: String, ids: RunIdentifiers) = appendObservation(
        attemptId,
        ids,
        "deterministic-result",
        "AssistantDelta",
        buildJsonObject {
            put("ordinal", lastOrdinal)
            put("part", fallback)
            put("final", false)
            put("providerOffset", lastOrdinal)
        }.toString(),
    )

    internal suspend fun appendObservationFinal(event: ModelEvent.Final, lastOrdinal: Long, attemptId: String, ids: RunIdentifiers) = appendObservation(
        attemptId,
        ids,
        "final-delta",
        "AssistantDelta",
        buildJsonObject {
            put("ordinal", lastOrdinal + 1)
            put("part", "")
            put("final", true)
            put("finishReason", event.finishReason)
        }.toString(),
    )

    internal suspend fun appendPerception(
        plannedAttemptId: String,
        runId: String,
        sessionId: String,
        fencingEpoch: Long,
        queryContext: QueryContext,
        durationMs: Long,
        degraded: Boolean,
    ) {
        val payload = buildJsonObject {
            put("plannedAttemptId", plannedAttemptId)
            put("intentLabel", queryContext.intentLabel.name)
            put("intentConfidence", queryContext.intentConfidence)
            put("entityCount", queryContext.entities.size)
            put("entityTypes", queryContext.entities.map { it.type.name }.distinct().sorted().joinToString(","))
            put(
                "linkedEntityIds",
                queryContext.entities.mapNotNull {
                    it.linkedId
                }.distinct().sorted().joinToString(","),
            )
            queryContext.timeRange?.let {
                put("timeStartEpochMs", it.startEpochMs)
                put("timeEndExclusiveEpochMs", it.endExclusiveEpochMs)
            }
            put("durationMs", durationMs)
            put("degraded", degraded)
        }.toString()
        store.appendRuntimeEventOnce(
            RuntimeEventDraft(
                eventId = "event-perception-$plannedAttemptId",
                eventType = "PerceptionCompleted",
                sessionId = sessionId,
                runId = runId,
                attemptId = null,
                causationId = runId,
                correlationId = runId,
                payloadJson = payload,
                createdAtEpochMs = clock(),
            ),
            ownerId,
            fencingEpoch,
            clock(),
        )
    }

    internal suspend fun appendRetrieval(
        plannedAttemptId: String,
        runId: String,
        sessionId: String,
        fencingEpoch: Long,
        result: ContextRetrievalResult,
        durationMs: Long,
    ) {
        val payload = buildJsonObject {
            put("plannedAttemptId", plannedAttemptId)
            put("structuredCandidateCount", result.structuredCandidateCount)
            put("selectedCount", result.items.size)
            put("sourceKinds", result.items.map { it.candidate.sourceKind }.distinct().sorted().joinToString(","))
            put("sourceRefs", result.items.map { it.candidate.sourceRef }.distinct().sorted().joinToString(","))
            put("estimatedTokens", result.estimatedTokens)
            put("degradationPath", result.degradationPath.joinToString(","))
            put("durationMs", durationMs)
        }.toString()
        store.appendRuntimeEventOnce(
            RuntimeEventDraft(
                eventId = "event-retrieval-$plannedAttemptId",
                eventType = "ContextRetrievalCompleted",
                sessionId = sessionId,
                runId = runId,
                attemptId = null,
                causationId = "event-perception-$plannedAttemptId",
                correlationId = runId,
                payloadJson = payload,
                createdAtEpochMs = clock(),
            ),
            ownerId,
            fencingEpoch,
            clock(),
        )
    }
}
