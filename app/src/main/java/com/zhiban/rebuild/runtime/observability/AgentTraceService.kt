package com.zhiban.rebuild.runtime.observability
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.runtime.runSuspendCatching
import javax.inject.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class AgentRunTrace(
    val runId: String,
    val status: String,
    val durationMs: Long,
    val attemptCount: Int,
    val toolNames: List<String>,
    val degradationPaths: List<String>,
    val eventCount: Int,
    val auditSteps: List<AgentAuditStep>,
    val firstTokenLatencyMs: Long?,
    val retrievalDurationMs: Long?,
    val toolExecutionDurationMs: Long,
    val startedAtEpochMs: Long = 0L,
)

data class AgentAuditStep(val phase: String, val label: String, val status: String, val toolName: String? = null)

data class AgentMetricsSummary(
    val sampledRuns: Int,
    val successRatePercent: Int,
    val averageDurationMs: Long,
    val toolExecutionCount: Int,
    val degradationRatePercent: Int,
    val firstTokenP95Ms: Long?,
    val retrievalP95Ms: Long?,
    val averageToolDurationMs: Long?,
)

/** Read-only, redacted trace projection. It never exposes prompts, user text, tool arguments or provider secrets. */
class AgentTraceService @Inject internal constructor(private val database: AgentDatabase) {
    suspend fun recent(limit: Int = 20): List<AgentRunTrace> = database.runtimeRunDao().recent(limit.coerceIn(1, 50)).map { run ->
        val events = database.runtimeEventDao().listByRunId(run.runId)
        val attempts = database.runtimeAttemptDao().listByRunId(run.runId)
        val executions = database.runtimeToolExecutionDao().listByRunId(run.runId)
        val audits = database.toolAuditDao().findByRuntimeRunId(run.runId)
        val tools = executions.map { it.toolName }.distinct()
        val degradations = events.filter {
            it.eventType in setOf("ContextRetrievalCompleted", "ContextRerankCompleted")
        }
            .flatMap { event ->
                runSuspendCatching {
                    val value = Json.parseToJsonElement(event.payloadJson).jsonObject
                    listOfNotNull(value["degradationPath"]?.jsonPrimitive?.content)
                }.getOrDefault(emptyList())
            }.flatMap { it.split(',') }.map(String::trim).filter(String::isNotBlank).distinct()
        val eventSteps = events.mapNotNull { it.toAuditStep() }
        val executionSteps = executions.map { execution ->
            val verifiedStatus = audits.lastOrNull { it.toolName == execution.toolName }?.status ?: execution.status
            AgentAuditStep("EXECUTION", "工具执行", verifiedStatus, execution.toolName)
        }
        val providerStartedAt = events.firstOrNull { it.eventType == "ProviderAttemptStarted" }?.createdAtEpochMs
        val firstDeltaAt = events.firstOrNull { it.eventType == "AssistantDelta" }?.createdAtEpochMs
        val retrievalDuration = events.firstOrNull {
            it.eventType == "ContextRetrievalCompleted"
        }?.payloadJson?.longValue("durationMs")
        AgentRunTrace(
            run.runId, run.status, (run.updatedAtEpochMs - run.createdAtEpochMs).coerceAtLeast(0), attempts.size,
            tools, degradations, events.size, (eventSteps + executionSteps).distinct(),
            firstTokenLatencyMs = if (providerStartedAt != null &&
                firstDeltaAt != null
            ) {
                (firstDeltaAt - providerStartedAt).coerceAtLeast(0)
            } else {
                null
            },
            retrievalDurationMs = retrievalDuration,
            toolExecutionDurationMs = executions.sumOf {
                (it.updatedAtEpochMs - it.createdAtEpochMs).coerceAtLeast(0)
            },
            startedAtEpochMs = run.createdAtEpochMs,
        )
    }

    suspend fun metrics(limit: Int = 50): AgentMetricsSummary {
        val traces = recent(limit.coerceIn(1, 50))
        if (traces.isEmpty()) return AgentMetricsSummary(0, 0, 0, 0, 0, null, null, null)
        val toolCount = traces.sumOf { it.toolNames.size }
        return AgentMetricsSummary(
            sampledRuns = traces.size,
            successRatePercent = traces.count { it.status == "SUCCEEDED" } * 100 / traces.size,
            averageDurationMs = traces.sumOf(AgentRunTrace::durationMs) / traces.size,
            toolExecutionCount = toolCount,
            degradationRatePercent = traces.count { it.degradationPaths.isNotEmpty() } * 100 / traces.size,
            firstTokenP95Ms = percentile95(traces.mapNotNull(AgentRunTrace::firstTokenLatencyMs)),
            retrievalP95Ms = percentile95(traces.mapNotNull(AgentRunTrace::retrievalDurationMs)),
            averageToolDurationMs = if (toolCount ==
                0
            ) {
                null
            } else {
                traces.sumOf(AgentRunTrace::toolExecutionDurationMs) / toolCount
            },
        )
    }

    private fun percentile95(values: List<Long>): Long? = values.sorted().takeIf(List<Long>::isNotEmpty)
        ?.let { it[((it.size - 1) * P95_QUANTILE).toInt()] }

    private fun String.longValue(name: String): Long? = runCatching {
        Json.parseToJsonElement(this).jsonObject[name]?.jsonPrimitive?.content?.toLongOrNull()
    }.getOrNull()

    private fun com.zhiban.rebuild.runtime.store.RuntimeEventEntity.toAuditStep(): AgentAuditStep? = when (eventType) {
        "RunReceived" -> AgentAuditStep("PERCEPTION", "接收输入", "COMPLETED")
        "PerceptionCompleted" -> AgentAuditStep("PERCEPTION", "意图与实体识别", "COMPLETED")
        "ContextAssemblyStarted" -> AgentAuditStep("MEMORY", "组装上下文", "STARTED")
        "ContextRetrievalCompleted" -> AgentAuditStep("MEMORY", "检索记忆", "COMPLETED")
        "ContextRerankCompleted" -> AgentAuditStep("MEMORY", "重排记忆", "COMPLETED")
        "ProviderAttemptStarted" -> AgentAuditStep("PLANNING", "模型规划", "STARTED")
        "PlanProposed" -> AgentAuditStep("PLANNING", "生成执行计划", "COMPLETED")
        "ApprovalRequested" -> AgentAuditStep("APPROVAL", "等待用户确认", "REQUIRED")
        "ToolExecutionStarted" -> AgentAuditStep("EXECUTION", "开始执行工具", "STARTED")
        "ToolSucceeded" -> AgentAuditStep("EXECUTION", "工具返回结果", "SUCCEEDED")
        "ObservationStarted" -> AgentAuditStep("FEEDBACK", "观察环境结果", "STARTED")
        "UserFeedbackRecorded" -> AgentAuditStep("FEEDBACK", "记录用户反馈", "COMPLETED")
        "RunCompleted" -> AgentAuditStep("FEEDBACK", "完成回复", "SUCCEEDED")
        "RunFailed" -> AgentAuditStep("FEEDBACK", "运行失败", "FAILED")
        "RunCancelRequested" -> AgentAuditStep("FEEDBACK", "用户取消", "CANCELLED")
        "RunRetryStarted" -> AgentAuditStep("FEEDBACK", "恢复重试", "STARTED")
        else -> null
    }
}

private const val P95_QUANTILE = 0.95
