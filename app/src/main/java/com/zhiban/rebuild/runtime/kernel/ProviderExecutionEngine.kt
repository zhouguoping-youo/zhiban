package com.zhiban.rebuild.runtime.kernel

import com.zhiban.agent.skills.SkillActivation
import com.zhiban.agent.skills.SkillActivator
import com.zhiban.agent.skills.SkillOrigin
import com.zhiban.agent.skills.SkillSpec
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.autowrite.AutoWritePresentationRegistry
import com.zhiban.rebuild.data.config.ExecutionPreference
import com.zhiban.rebuild.data.config.FeedbackPolicy
import com.zhiban.rebuild.data.config.MemoryPolicy
import com.zhiban.rebuild.data.notification.MessageCollectionPreferences
import com.zhiban.rebuild.data.notification.NotificationInsightAnalyzer
import com.zhiban.rebuild.foundation.Sensitivity
import com.zhiban.rebuild.foundation.runSuspendCatching
import com.zhiban.rebuild.foundation.sha256
import com.zhiban.rebuild.provider.CapabilitySnapshot
import com.zhiban.rebuild.provider.ModelEvent
import com.zhiban.rebuild.provider.ModelMessage
import com.zhiban.rebuild.provider.ModelRequest
import com.zhiban.rebuild.provider.OutboundChannel
import com.zhiban.rebuild.provider.OutboundProvenance
import com.zhiban.rebuild.provider.OutboundPurpose
import com.zhiban.rebuild.provider.OutboundSensitivity
import com.zhiban.rebuild.provider.ProviderAdapter
import com.zhiban.rebuild.provider.ProviderFailure
import com.zhiban.rebuild.provider.ProviderModelPolicy
import com.zhiban.rebuild.provider.ProviderProfile
import com.zhiban.rebuild.provider.ProviderProfileStore
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
import com.zhiban.rebuild.runtime.context.TrustLevel
import com.zhiban.rebuild.runtime.context.reranked
import com.zhiban.rebuild.runtime.context.resolveCalendarStartEpochMs
import com.zhiban.rebuild.runtime.context.withDegradations
import com.zhiban.rebuild.runtime.governance.ChangeUndoCoordinator
import com.zhiban.rebuild.runtime.governance.ContactDomainWriter
import com.zhiban.rebuild.runtime.governance.ContactIdentityResolutionDomainWriter
import com.zhiban.rebuild.runtime.governance.RelationshipDomainWriter
import com.zhiban.rebuild.runtime.input.asr.PrivacyConsent
import com.zhiban.rebuild.runtime.memory.RoomMemoryGate
import com.zhiban.rebuild.runtime.network.NetworkQuality
import com.zhiban.rebuild.runtime.spi.RuntimeRunStatus
import com.zhiban.rebuild.runtime.store.AttemptStartRequest
import com.zhiban.rebuild.runtime.store.RoomRuntimeStore
import com.zhiban.rebuild.runtime.store.RuntimeEventDraft
import com.zhiban.rebuild.runtime.store.RuntimeRecoveryHandle
import com.zhiban.rebuild.runtime.store.startAttempt
import com.zhiban.rebuild.runtime.store.startObservationAttempt
import com.zhiban.rebuild.runtime.tool.CalendarConflictToolBinding
import com.zhiban.rebuild.runtime.tool.CalendarMutationToolBinding
import com.zhiban.rebuild.runtime.tool.CalendarSearchToolBinding
import com.zhiban.rebuild.runtime.tool.CapabilityPolicy
import com.zhiban.rebuild.runtime.tool.CapabilityRouter
import com.zhiban.rebuild.runtime.tool.CommunicationMessageToolBinding
import com.zhiban.rebuild.runtime.tool.ConfirmedToolExecutionContext
import com.zhiban.rebuild.runtime.tool.ContactCreateCandidateToolBinding
import com.zhiban.rebuild.runtime.tool.ContactDetailToolBinding
import com.zhiban.rebuild.runtime.tool.ContactEnrichmentSuggestToolBinding
import com.zhiban.rebuild.runtime.tool.ContactIdentityResolutionToolBinding
import com.zhiban.rebuild.runtime.tool.ContactMaintenanceToolBinding
import com.zhiban.rebuild.runtime.tool.ContactOwnerProfileSnapshot
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
import com.zhiban.rebuild.runtime.tool.MemoryUpsertDomainWriter
import com.zhiban.rebuild.runtime.tool.MemoryUpsertToolBinding
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
import com.zhiban.rebuild.runtime.tool.WebSearchToolBinding
import com.zhiban.rebuild.runtime.tool.canonicalMemoryDigest
import com.zhiban.rebuild.runtime.tool.canonicalMemoryIdempotencyKey
import com.zhiban.rebuild.runtime.tool.canonicalScheduleDigest
import com.zhiban.rebuild.runtime.tool.canonicalToolIdempotencyKey
import com.zhiban.rebuild.runtime.tool.locationToolBindings
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
private const val LEASE_DURATION_MS = 120_000L
private const val DEFAULT_TOTAL_TIMEOUT_MS = 120_000L
private const val DEFAULT_IDLE_TIMEOUT_MS = 30_000L
private const val DEFAULT_HEARTBEAT_INTERVAL_MS = 10_000L
private const val DEFAULT_RERANK_TIMEOUT_MS = 2_500L
private const val DEFAULT_TOOL_EXECUTION_TIMEOUT_MS = 30_000L

/**
 * 运行期可变的引擎配置（超时 + 策略 lambda）。从原本散列在构造函数里的十几个参数收敛而来，
 * 让 ProviderExecutionEngine 的构造参数降到 ≤8，调用方按"配置"成组传入。
 */
internal data class ProviderEngineConfig(
    val totalTimeoutMs: Long = DEFAULT_TOTAL_TIMEOUT_MS,
    val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
    val heartbeatIntervalMs: Long = DEFAULT_HEARTBEAT_INTERVAL_MS,
    val leaseDurationMs: Long = LEASE_DURATION_MS,
    val rerankTimeoutMs: Long = DEFAULT_RERANK_TIMEOUT_MS,
    val toolExecutionTimeoutMs: Long = DEFAULT_TOOL_EXECUTION_TIMEOUT_MS,
    val personalization: () -> String? = { null },
    val ownerProfile: () -> ContactOwnerProfileSnapshot = { ContactOwnerProfileSnapshot() },
    val memoryPolicy: () -> com.zhiban.rebuild.data.config.MemoryPolicy = {
        com.zhiban.rebuild.data.config.MemoryPolicy()
    },
    val feedbackPolicy: () -> com.zhiban.rebuild.data.config.FeedbackPolicy = {
        com.zhiban.rebuild.data.config.FeedbackPolicy()
    },
    val toolEnabled: (String) -> Boolean = { true },
    val webSearchOptIn: () -> Boolean = { false },
    val networkQuality: () -> com.zhiban.rebuild.runtime.network.NetworkQuality = {
        com.zhiban.rebuild.runtime.network.NetworkQuality.NORMAL
    },
    val dynamicConfig: () -> com.zhiban.rebuild.runtime.config.AgentDynamicConfig = {
        com.zhiban.rebuild.runtime.config.AgentDynamicConfig()
    },
    val executionPreference: () -> com.zhiban.rebuild.data.config.ExecutionPreference = {
        com.zhiban.rebuild.data.config.ExecutionPreference.BALANCED
    },
    val skillSpecs: () -> List<SkillSpec> = { com.zhiban.agent.skills.BuiltInSkills.all },
    val onScheduleSaved: (com.zhiban.rebuild.data.agent.ScheduleEntity) -> Unit = {},
    val onScheduleUndo: (String, com.zhiban.rebuild.data.agent.ScheduleEntity?) -> Unit = { _, _ -> },
)

/** 可选的外部依赖网关；缺省为 null，引擎按既定降级路径走。 */
internal data class ProviderEngineInfrastructure(
    val mcpEnvironment: com.zhiban.rebuild.runtime.mcp.McpRemoteEnvironment? = null,
    val embeddingGateway: com.zhiban.rebuild.runtime.context.EmbeddingGateway? = null,
    val messageCollectionPreferences: MessageCollectionPreferences? = null,
    val communicationHandoffLauncher: com.zhiban.rebuild.data.communication.CommunicationHandoffLauncher? = null,
    val externalCalendarConflicts: com.zhiban.rebuild.data.calendar.ExternalCalendarConflictSource? = null,
    val perception: PerceptionGateway? = null,
    val webSearchGateway: com.zhiban.rebuild.provider.WebSearchGateway? = null,
    val locationGateway: com.zhiban.rebuild.provider.LocationGateway? = null,
    /**
     * 定位读取的用户同意开关(设置 → 隐私与安全 → 定位,默认关)。null/未接线一律视为未同意——
     * 位置数据默认不出云,宁可不读也不静默外发。
     */
    val locationConsent: (() -> Boolean)? = null,
)

internal class ProviderExecutionEngine(
    database: AgentDatabase,
    internal val provider: ProviderAdapter,
    private val profiles: ProviderProfileStore,
    internal val ownerId: String,
    internal val clock: () -> Long = System::currentTimeMillis,
    internal val config: ProviderEngineConfig = ProviderEngineConfig(),
    private val infrastructure: ProviderEngineInfrastructure = ProviderEngineInfrastructure(),
) {
    // 解构 config / infrastructure 到私有属性，execute()/observeToolResult 等内部调用保持不变。
    internal val totalTimeoutMs: Long = config.totalTimeoutMs
    internal val idleTimeoutMs: Long = config.idleTimeoutMs
    internal val heartbeatIntervalMs: Long = config.heartbeatIntervalMs
    internal val leaseDurationMs: Long = config.leaseDurationMs
    internal val rerankTimeoutMs: Long = config.rerankTimeoutMs
    private val personalization: () -> String? = config.personalization
    internal val memoryPolicy: () -> com.zhiban.rebuild.data.config.MemoryPolicy = config.memoryPolicy
    private val feedbackPolicy: () -> com.zhiban.rebuild.data.config.FeedbackPolicy = config.feedbackPolicy
    internal val toolEnabled: (String) -> Boolean = config.toolEnabled
    private val webSearchOptIn: () -> Boolean = config.webSearchOptIn
    private val networkQuality: () -> com.zhiban.rebuild.runtime.network.NetworkQuality = config.networkQuality
    internal val dynamicConfig: () -> com.zhiban.rebuild.runtime.config.AgentDynamicConfig = {
        // Apply the user's execution preference (快速/平衡/深度) over the snapshot so every
        // retrieval and assembly path sees the adjusted forceFtsOnly/maxContextTokens.
        config.dynamicConfig().withExecutionPreference(config.executionPreference())
    }
    internal val skillSpecs: () -> List<SkillSpec> = config.skillSpecs
    private val onScheduleSaved: (com.zhiban.rebuild.data.agent.ScheduleEntity) -> Unit = config.onScheduleSaved
    private val mcpEnvironment = infrastructure.mcpEnvironment
    private val embeddingGateway = infrastructure.embeddingGateway
    private val messageCollectionPreferences = infrastructure.messageCollectionPreferences
    private val communicationHandoffLauncher = infrastructure.communicationHandoffLauncher
    private val externalCalendarConflicts = infrastructure.externalCalendarConflicts
    private val perception = infrastructure.perception
    private val webSearchGateway = infrastructure.webSearchGateway
    internal val store = RoomRuntimeStore(database, producerVersion = "runtime-v2-provider")
    internal val events = RuntimeEventAppender(store, ownerId, clock)
    internal val deterministicObservation = DeterministicObservationCompleter(store, ownerId, clock, ::perceiveForObservation)
    internal val contextAssembler = ProviderContextAssembler(clock, personalization)
    private val scheduleExecutor = RoomScheduleToolExecutor(
        database,
        externalConflicts = externalCalendarConflicts,
        onScheduleSaved = onScheduleSaved,
    )
    private val memoryWriteConsent = {
        if (memoryPolicy().longTermMemoryEnabled) PrivacyConsent.Granted else PrivacyConsent.NotGranted
    }
    private val memoryExecutor = RoomMemoryToolExecutor({ database }, clock, memoryWriteConsent)
    private val crmExecutor = RoomCrmToolExecutor(database, store)
    private val perceptionPipeline: PerceptionGateway = perception ?: RoomPerceptionPipeline(database, clock)
    internal val retrievalPipeline = RoomContextRetrievalPipeline(
        database = database,
        clock = clock,
        messageCollectionPreferences = messageCollectionPreferences,
        embeddingGateway = embeddingGateway,
    )
    internal val retrievalReranker = ProviderRetrievalReranker(provider)
    internal val toolCatalog = RuntimeToolCatalog.production()
    internal val capabilityRouter = CapabilityRouter(
        bindings = listOf(
            webSearchGateway?.let {
                WebSearchToolBinding(
                    toolCatalog.requireRegistered(WebSearchToolBinding.TOOL_NAME),
                    it,
                )
            },
            CalendarSearchToolBinding(
                toolCatalog.requireRegistered("calendar.schedule.search"),
                database.scheduleDao(),
            ),
            CalendarConflictToolBinding(
                toolCatalog.requireRegistered("calendar.schedule.conflicts"),
                database.scheduleDao(),
                externalCalendarConflicts,
            ),
            ScheduleCreateToolBinding(
                toolCatalog.requireRegistered(SchedulePlanValidator.TOOL_NAME),
                store,
                scheduleExecutor,
            ),
            CalendarMutationToolBinding(toolCatalog.requireRegistered(CalendarMutationToolBinding.UPDATE), store),
            CalendarMutationToolBinding(toolCatalog.requireRegistered(CalendarMutationToolBinding.DELETE), store),
            MemoryRememberToolBinding(
                toolCatalog.requireRegistered(MemoryRememberPlanValidator.TOOL_NAME),
                store,
                memoryExecutor,
            ),
            MemoryUpsertToolBinding(
                toolCatalog.requireRegistered(MemoryUpsertToolBinding.TOOL_NAME),
                store,
                MemoryUpsertDomainWriter(database, store, memoryWriteConsent),
            ),
            MemorySearchToolBinding(toolCatalog.requireRegistered("memory.search"), RoomMemoryGate(database, clock)),
            MemoryDeleteToolBinding(toolCatalog.requireRegistered("memory.delete"), store),
            ContactSearchToolBinding(toolCatalog.requireRegistered("contact.search"), database.contactDao()),
            ContactDetailToolBinding(toolCatalog.requireRegistered("contact.getDetail"), database.contactDao()),
            ContactMaintenanceToolBinding(
                toolCatalog.requireRegistered("contact.maintenance.list"),
                database.contactDao(),
                database.contactIdentityDao(),
                database.contactIntelligenceDao(),
                database.contactKnowledgeDao(),
                config.ownerProfile,
            ),
            ContactIdentityResolutionToolBinding(
                toolCatalog.requireRegistered("contact.identity.resolve"),
                database.contactDao(),
                database.contactIntelligenceDao(),
                store,
                ContactIdentityResolutionDomainWriter(database),
            ),
            ContactTagToolBinding(
                toolCatalog.requireRegistered(ContactTagToolBinding.TOOL_NAME),
                store,
                ContactTagDomainWriter(database, store),
            ),
            ContactCreateCandidateToolBinding(
                toolCatalog.requireRegistered("contact.createCandidate"),
                store,
                ContactDomainWriter(database),
            ),
            ContactProfileUpdateCandidateToolBinding(
                toolCatalog.requireRegistered(
                    com.zhiban.rebuild.runtime.governance.ContactProfileDomainWriter.TOOL_NAME,
                ),
                database.contactDao(),
                store,
                com.zhiban.rebuild.runtime.governance.ContactProfileDomainWriter(database),
                config.ownerProfile,
            ),
            ContactEnrichmentSuggestToolBinding(
                toolCatalog.requireRegistered("contact.enrichment.suggest"),
                database.contactDao(),
                database.contactKnowledgeDao(),
            ),
            RelationshipSearchToolBinding(
                toolCatalog.requireRegistered("relationship.search"),
                database.relationshipEdgeDao(),
                database.relationshipEventDao(),
                database.contactIntelligenceDao(),
            ),
            RelationshipCreateCandidateToolBinding(
                toolCatalog.requireRegistered("relationship.createCandidate"),
                store,
                RelationshipDomainWriter(database),
            ),
            RelationshipEvidenceToolBinding(
                toolCatalog.requireRegistered("relationship.getEvidence"),
                database.relationshipEdgeDao(),
            ),
            CrmOpportunityListToolBinding(toolCatalog.requireRegistered("crm.opportunity.list"), database.crmDao()),
            CrmOpportunityDetailToolBinding(
                toolCatalog.requireRegistered("crm.opportunity.get"),
                database.crmDao(),
                database.contactDao(),
            ),
            CrmMutationToolBinding(
                toolCatalog.requireRegistered(CrmMutationToolBinding.LEAD_CREATE),
                store,
                database.crmDao(),
                database.contactDao(),
                crmExecutor,
            ),
            CrmMutationToolBinding(
                toolCatalog.requireRegistered(CrmMutationToolBinding.OPPORTUNITY_CREATE),
                store,
                database.crmDao(),
                database.contactDao(),
                crmExecutor,
            ),
            CrmMutationToolBinding(
                toolCatalog.requireRegistered(CrmMutationToolBinding.OPPORTUNITY_UPDATE),
                store,
                database.crmDao(),
                database.contactDao(),
                crmExecutor,
            ),
            CrmMutationToolBinding(
                toolCatalog.requireRegistered(CrmMutationToolBinding.OPPORTUNITY_STAGE),
                store,
                database.crmDao(),
                database.contactDao(),
                crmExecutor,
            ),
            CrmMutationToolBinding(
                toolCatalog.requireRegistered(CrmMutationToolBinding.ACTIVITY_APPEND),
                store,
                database.crmDao(),
                database.contactDao(),
                crmExecutor,
            ),
            CrmMutationToolBinding(
                toolCatalog.requireRegistered(CrmMutationToolBinding.ACTION_CREATE),
                store,
                database.crmDao(),
                database.contactDao(),
                crmExecutor,
            ),
            CrmMutationToolBinding(
                toolCatalog.requireRegistered(CrmMutationToolBinding.ACTION_UPDATE),
                store,
                database.crmDao(),
                database.contactDao(),
                crmExecutor,
            ),
            CrmMutationToolBinding(
                toolCatalog.requireRegistered(CrmMutationToolBinding.ACTION_COMPLETE),
                store,
                database.crmDao(),
                database.contactDao(),
                crmExecutor,
            ),
            communicationHandoffLauncher?.let {
                CommunicationMessageToolBinding(
                    toolCatalog.requireRegistered(CommunicationMessageToolBinding.TOOL_NAME),
                    store,
                    it,
                )
            },
        ).filterNotNull(),
        proposalCount = store::toolProposalCount,
        totalCallCount = store::totalToolInvocationCount,
        completedToolNames = store::completedToolNames,
        policy = CapabilityPolicy(
            isEnabled = toolEnabled,
            autoUndoTools = ChangeUndoCoordinator.AUTO_TOOL_NAMES,
            autoPresentationTools = AutoWritePresentationRegistry.toolNames,
        ),
        dynamicBindings = {
            val mcp = if (dynamicConfig().enableMcpRemote) {
                mcpEnvironment?.tools()?.map { RemoteMcpToolBinding(it, mcpEnvironment, store) }.orEmpty()
            } else {
                emptyList()
            }
            mcp + locationToolBindings(toolCatalog, infrastructure.locationGateway, infrastructure.locationConsent ?: { false })
        },
        timeoutMs = config.toolExecutionTimeoutMs,
    )
    internal val calendarConflictGuard = CalendarConflictGuard(capabilityRouter)
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<String, Job>()
    internal val activeRequests = ConcurrentHashMap<String, String>()
    private val recoveryQueue = ArrayDeque<RuntimeRecoveryHandle>()
    private val recoveryQueueMutex = Mutex()

    private fun launchRunJob(runId: String, sessionId: String, fencingEpoch: Long, clearActiveRequest: Boolean = true, block: suspend () -> Unit): Boolean {
        val job = scope.launchGuardedRuntimeJob(
            onFailure = { containEscapedFailure(runId, sessionId, fencingEpoch) },
            onFinally = {
                if (clearActiveRequest) activeRequests.remove(runId)
                activeJobs.remove(runId)
            },
            block = block,
        )
        val previous = activeJobs.putIfAbsent(runId, job)
        if (previous != null) {
            job.cancel()
            return false
        }
        job.start()
        return true
    }

    fun launch(runId: String, sessionId: String, fencingEpoch: Long): Boolean = launchRunJob(runId, sessionId, fencingEpoch) {
        execute(runId, sessionId, fencingEpoch)
    }

    suspend fun launchAfterCurrent(runId: String, sessionId: String, fencingEpoch: Long): Boolean =
        launchWhenRunIsIdle(runId) { launch(runId, sessionId, fencingEpoch) }

    suspend fun launchApprovedToolAfterCurrent(runId: String, sessionId: String, fencingEpoch: Long): Boolean =
        launchWhenRunIsIdle(runId) { launchApprovedTool(runId, sessionId, fencingEpoch) }

    suspend fun resumeAfterCurrent(runId: String, sessionId: String, fencingEpoch: Long): Boolean =
        launchWhenRunIsIdle(runId) { resume(runId, sessionId, fencingEpoch) }

    private suspend fun launchWhenRunIsIdle(runId: String, launch: suspend () -> Boolean): Boolean {
        while (true) {
            activeJobs[runId]?.join() ?: return launch()
        }
    }

    private suspend fun finishFailure(runId: String, fencingEpoch: Long, failure: Throwable): Boolean {
        val providerFailure = failure as? ProviderFailure
        val retryable = providerFailure?.retryable ?: (failure is IOException)
        val safeCode = providerFailure?.code?.takeIf { it in SAFE_FAILURE_CODES }
            ?: if (retryable) "PROVIDER_UNAVAILABLE" else "PROVIDER_FAILED"
        val payload = buildJsonObject {
            put("errorCode", safeCode)
            providerFailure?.safeRequestId?.let { put("safeRequestId", it) }
            providerFailure?.retryAfterMillis?.let { put("retryAfterMillis", it) }
        }.toString()
        store.finishProviderRun(
            runId,
            if (retryable) RuntimeRunStatus.FAILED_RETRYABLE.name else RuntimeRunStatus.FAILED_FINAL.name,
            if (retryable) "RunFailedRetryable" else "RunFailedFinal",
            payload, "FAILED", ownerId, fencingEpoch, clock(), deleteInput = !retryable,
        )
        return false
    }

    suspend fun cancel(runId: String, sessionId: String, fencingEpoch: Long): Boolean {
        activeRequests[runId]?.let(provider::cancel)
        val job = activeJobs[runId]
        job?.cancel(CancellationException("RUN_CANCEL_REQUESTED"))
        job?.cancelAndJoin()
        val run = store.runById(runId)
        if (run?.status == RuntimeRunStatus.CANCEL_REQUESTED.name) {
            store.cancelProviderRun(runId, ownerId, fencingEpoch, clock())
            return true
        }
        return job != null || run?.status == RuntimeRunStatus.CANCELLED.name
    }

    suspend fun recoverNext(): Boolean {
        val handle = recoveryQueueMutex.withLock {
            if (recoveryQueue.isEmpty()) {
                recoveryQueue.addAll(
                    store.claimRecoverable(ownerId, clock(), leaseDurationMs, "ui")
                        .filter { it.snapshot.run.status in RECOVERABLE_EXECUTION_STATUSES },
                )
            }
            recoveryQueue.removeFirstOrNull()
        } ?: return false
        if (handle.snapshot.run.status == RuntimeRunStatus.EXECUTING.name) {
            return launchApprovedTool(handle.snapshot.run.runId, handle.sessionId, handle.leaseEpoch)
        }
        if (handle.snapshot.run.status == RuntimeRunStatus.OBSERVING.name) {
            return launchObservation(handle.snapshot.run.runId, handle.sessionId, handle.leaseEpoch)
        }
        return launch(handle.snapshot.run.runId, handle.sessionId, handle.leaseEpoch)
    }

    suspend fun resume(runId: String, sessionId: String, fencingEpoch: Long): Boolean = when (store.runById(runId)?.status) {
        RuntimeRunStatus.ASSEMBLING_CONTEXT.name,
        RuntimeRunStatus.INFERENCING.name,
        -> launch(runId, sessionId, fencingEpoch)

        RuntimeRunStatus.EXECUTING.name -> launchApprovedTool(runId, sessionId, fencingEpoch)

        RuntimeRunStatus.OBSERVING.name -> launchObservation(runId, sessionId, fencingEpoch)

        RuntimeRunStatus.CANCEL_REQUESTED.name -> cancel(runId, sessionId, fencingEpoch)

        else -> false
    }

    private suspend fun launchObservation(runId: String, sessionId: String, fencingEpoch: Long): Boolean {
        val execution = store.latestToolExecution(runId)
        if (execution == null) {
            store.failBrokenObservationRecovery(runId, ownerId, fencingEpoch, clock())
            return true
        }
        return launchRunJob(runId, sessionId, fencingEpoch, clearActiveRequest = false) {
            observeToolResult(
                runId,
                sessionId,
                fencingEpoch,
                execution.toolName,
                execution.providerCallId.orEmpty(),
                execution.safeResultJson.orEmpty(),
            )
        }
    }

    private suspend fun performRetrieval(
        text: String,
        queryContext: QueryContext,
        currentNetwork: com.zhiban.rebuild.runtime.network.NetworkQuality,
        config: com.zhiban.rebuild.runtime.config.AgentDynamicConfig,
        policy: com.zhiban.rebuild.data.config.MemoryPolicy,
    ): ContextRetrievalResult = runSuspendCatching {
        retrievalPipeline.retrieve(
            text,
            queryContext,
            policy.longTermMemoryEnabled && !policy.temporaryModeEnabled,
            allowRemoteVector = currentNetwork == com.zhiban.rebuild.runtime.network.NetworkQuality.NORMAL &&
                config.enableHybridRetrieval && !config.forceFtsOnly,
            remoteVectorSkipReason = when {
                config.forceFtsOnly -> "remote_force_fts_only"
                !config.enableHybridRetrieval -> "feature_disabled"
                else -> "weak_network"
            },
            recallLimit = config.retrievalRecallLimit,
        )
    }.getOrElse { failure ->
        if (failure is CancellationException) throw failure
        ContextRetrievalResult(emptyList(), 0, listOf("retrieval_pipeline_failed"), 0)
    }

    private suspend fun perceiveForRun(input: DecodedInput): PerceptionResult {
        val startedAt = clock()
        var degraded = false
        val context = withTimeoutOrNull(ENTITY_EXTRACTION_TIMEOUT_MS) {
            perceptionPipeline.perceive(input.text)
        } ?: perceptionPipeline.fallback(input.text).also { degraded = true }
        return PerceptionResult(context, (clock() - startedAt).coerceAtLeast(0), degraded)
    }

    internal suspend fun perceiveForObservation(input: DecodedInput): QueryContext = withTimeoutOrNull(ENTITY_EXTRACTION_TIMEOUT_MS) {
        perceptionPipeline.perceive(input.text)
    } ?: perceptionPipeline.fallback(input.text)

    internal suspend fun selectProfile(input: DecodedInput, dynamicConfig: com.zhiban.rebuild.runtime.config.AgentDynamicConfig): ProviderProfile {
        val stored = profiles.load() ?: throw ProviderFailure("PROVIDER_NOT_CONFIGURED", retryable = false)
        if (stored.providerId in dynamicConfig.providerBlacklist) {
            throw ProviderFailure("PROVIDER_DISABLED", retryable = false)
        }
        return ProviderModelPolicy.selectForInput(
            stored,
            input.model,
            input.attachments.any { it.mimeType.startsWith("image/") || it.mimeType == "application/pdf" },
        )
    }

    internal suspend fun loadMemoryContext(
        initialRetrieval: ContextRetrievalResult,
        policy: com.zhiban.rebuild.data.config.MemoryPolicy,
        sessionId: String,
        runId: String,
    ): MemoryContext {
        val approvedRecall = if (policy.longTermMemoryEnabled && !policy.temporaryModeEnabled) {
            memoryExecutor.recallApproved()
        } else {
            com.zhiban.rebuild.runtime.tool.ApprovedMemoryRecallResult(emptyList())
        }
        val conversation = if (policy.sessionMemoryEnabled && !policy.temporaryModeEnabled) {
            store.conversationContext(sessionId, runId)
        } else {
            com.zhiban.rebuild.runtime.store.SessionConversationContext(null, emptyList())
        }
        val feedback = if (feedbackPolicy().useHumanFeedback) store.recentFeedback(sessionId) else emptyList()
        return MemoryContext(
            retrieval = initialRetrieval.withDegradations(approvedRecall.degradationReasons),
            approvedMemories = approvedRecall.items,
            conversation = conversation,
            feedback = feedback,
        )
    }

    private suspend fun startRunAttempt(activeAttemptId: String?, runId: String, fencingEpoch: Long): String {
        val attempts = store.recoverySnapshot(runId, "ui").attempts
        val activeAttempt = attempts.firstOrNull { it.attemptId == activeAttemptId && it.status == "ACTIVE" }
        val nextAttemptId = "attempt-$runId-${attempts.size + 1}"
        val request = AttemptStartRequest(nextAttemptId, runId, attempts.size + 1, ownerId, fencingEpoch, clock())
        if (activeAttempt == null) {
            store.startAttempt(request)
        } else {
            store.supersedeAttemptAndStart(
                activeAttempt.attemptId,
                nextAttemptId,
                runId,
                attempts.size + 1,
                ownerId,
                fencingEpoch,
                clock(),
            )
        }
        return nextAttemptId
    }

    private suspend fun prepareRun(runActiveAttemptId: String?, runId: String, sessionId: String, fencingEpoch: Long): PreparedRun {
        val rawInput = store.readRunInput(runId, clock()) ?: return PreparedRun.Failure("INPUT_EXPIRED_OR_MISSING", retryable = false)
        val input = decodeInput(rawInput)
        val perception = perceiveForRun(input)
        val config = dynamicConfig()
        val activatedSkills = activatedSkillsFor(perception.context, config, skillSpecs(), toolCatalog.names(), toolEnabled)
        val localCalendarTool = if (input.attachments.isNotEmpty()) {
            null
        } else if (
            perception.context.intentLabel == com.zhiban.rebuild.runtime.context.IntentLabel.CALENDAR_CREATE &&
            toolEnabled(SchedulePlanValidator.TOOL_NAME)
        ) {
            deterministicCalendarToolCall(input, perception.context, nowEpochMs = clock())
                ?.let { it to SchedulePlanValidator.TOOL_NAME }
        } else if (toolEnabled("calendar.schedule.conflicts")) {
            deterministicCalendarConflictToolCall(input, perception.context, clock())
                ?.let { it to "calendar.schedule.conflicts" }
        } else {
            null
        }
        if (localCalendarTool != null) {
            val attemptId = startRunAttempt(runActiveAttemptId, runId, fencingEpoch)
            events.appendPerception(
                attemptId,
                runId,
                sessionId,
                fencingEpoch,
                perception.context,
                perception.durationMs,
                perception.degraded,
            )
            return PreparedRun.LocalCalendarTool(
                input,
                perception.context,
                activatedSkills,
                attemptId,
                localCalendarTool.first,
                localCalendarTool.second,
            )
        }
        val currentNetwork = networkQuality()
        networkPreflightFailure(currentNetwork, input.attachments.isNotEmpty())?.let { (code, retryable) ->
            return PreparedRun.Failure(code, retryable)
        }
        val profile = try {
            selectProfile(input, config)
        } catch (failure: ProviderFailure) {
            return PreparedRun.Failure(failure.code, failure.retryable)
        }
        val policy = memoryPolicy()
        val retrievalStartedAt = clock()
        val initialRetrieval = performRetrieval(input.text, perception.context, currentNetwork, config, policy)
        val retrievalDurationMs = (clock() - retrievalStartedAt).coerceAtLeast(0)
        val memory = loadMemoryContext(initialRetrieval, policy, sessionId, runId)
        val attemptId = startRunAttempt(runActiveAttemptId, runId, fencingEpoch)
        events.appendPerception(
            attemptId,
            runId,
            sessionId,
            fencingEpoch,
            perception.context,
            perception.durationMs,
            perception.degraded,
        )
        events.appendRetrieval(attemptId, runId, sessionId, fencingEpoch, memory.retrieval, retrievalDurationMs)
        return PreparedRun.Ready(
            input, perception.context, memory.retrieval, memory.approvedMemories, memory.conversation,
            memory.feedback, activatedSkills, profile, config, currentNetwork, attemptId,
        )
    }

    suspend fun execute(runId: String, sessionId: String, fencingEpoch: Long): Boolean {
        val run = requireNotNull(store.runById(runId))
        if (run.status !in
            setOf(RuntimeRunStatus.ASSEMBLING_CONTEXT.name, RuntimeRunStatus.INFERENCING.name)
        ) {
            return false
        }
        val prepared = prepareRun(run.activeAttemptId, runId, sessionId, fencingEpoch)
        return when (prepared) {
            is PreparedRun.Failure -> events.failBeforeAttempt(runId, sessionId, fencingEpoch, prepared.code, prepared.retryable)
            is PreparedRun.LocalCalendarTool -> runLocalCalendarTool(prepared, runId, sessionId, fencingEpoch)
            is PreparedRun.Ready -> runReActLoop(prepared, runId, sessionId, fencingEpoch)
        }
    }

    internal suspend fun buildReActModelRequest(
        ready: PreparedRun.Ready,
        capability: CapabilitySnapshot,
        assembledMessages: AssembledModelContext,
    ): PreparedReActRequest {
        val input = ready.input
        val activatedSkills = ready.activatedSkills
        val queryContext = ready.queryContext
        val attemptId = ready.attemptId
        val profile = ready.profile
        val allowedTools = toolAllowlist(activatedSkills)
        val forcedCanonicalTool = selectForcedCanonicalTool(
            ForcedToolSelection(
                calendarCreateIntent = queryContext.intentLabel == com.zhiban.rebuild.runtime.context.IntentLabel.CALENDAR_CREATE,
                input = input.text,
                availableTools = capabilityRouter.canonicalNames(),
                allowedTools = allowedTools,
                enabled = toolEnabled,
            ),
        )
        val forcedToolName = forcedCanonicalTool?.let(capabilityRouter::providerName)
        val toolRestriction = forcedCanonicalTool?.let(::setOf) ?: allowedTools
        val request = ModelRequest(
            requestId = attemptId,
            channel = OutboundChannel.LLM_INFERENCE,
            profile = profile,
            messages = assembledMessages.messages, capability = capability,
            maxTokens = minOf(DEFAULT_MAX_OUTPUT_TOKENS, capability.maxOutputTokens),
            toolsJson = if ("tools" in capability.features) {
                capabilityRouter.providerToolsJson(toolRestriction)
            } else {
                null
            },
            allowWebSearch = forcedToolName == null && "web_search" in capability.features && webSearchOptIn(),
            attachments = input.attachments.map { attachment ->
                com.zhiban.rebuild.provider.ModelAttachment(
                    attachment.attachmentId, attachment.kind, attachment.mimeType, attachment.byteLength,
                    attachment.digest, attachment.contentRef, attachment.expiresAtEpochMs,
                    OutboundSensitivity.SENSITIVE,
                    OutboundPurpose.USER_SELECTED_ATTACHMENT,
                    OutboundProvenance("user_attachment", attachment.attachmentId),
                )
            },
            forcedToolName = forcedToolName,
        )
        return PreparedReActRequest(request, forcedCanonicalTool, capability)
    }

    private suspend fun runReActLoop(ready: PreparedRun.Ready, runId: String, sessionId: String, fencingEpoch: Long): Boolean {
        val attemptId = ready.attemptId
        activeRequests[runId] = attemptId
        val ids = RunIdentifiers(runId, sessionId, fencingEpoch)
        val outcome = try {
            // Multimodal reasoning has a materially longer time-to-first-token than text.
            // Applying the 30s text budget made valid Step-3 image requests fail before
            // the first SSE event arrived.
            withTimeout(
                reactTimeoutMs(
                    ready.input.attachments.isNotEmpty(),
                    ready.config.llmTimeoutSeconds,
                    ready.currentNetwork,
                    totalTimeoutMs,
                ),
            ) { consumeWithHeartbeat(ready, ids) }
        } catch (_: TimeoutCancellationException) {
            provider.cancel(attemptId)
            return finishFailure(runId, fencingEpoch, ProviderFailure("TIMEOUT", retryable = true))
        } catch (cancelled: CancellationException) {
            provider.cancel(attemptId)
            return handleReactCancellation(cancelled, ids)
        } catch (failure: Throwable) {
            return finishFailure(runId, fencingEpoch, failure)
        }
        return completeReactOutcome(outcome, ids, ready.input.text, ready.attemptId)
    }

    private suspend fun runLocalCalendarTool(prepared: PreparedRun.LocalCalendarTool, runId: String, sessionId: String, fencingEpoch: Long): Boolean {
        val ids = RunIdentifiers(runId, sessionId, fencingEpoch)
        val outcome = try {
            handleReActToolCall(
                ToolCallContext(
                    prepared.input,
                    prepared.queryContext,
                    prepared.activatedSkills,
                    prepared.attemptId,
                    providerRequestActive = false,
                ),
                prepared.toolCall,
                prepared.forcedCanonicalTool,
                ids,
            )
        } catch (cancelled: CancellationException) {
            return handleReactCancellation(cancelled, ids)
        } catch (failure: Throwable) {
            return finishFailure(runId, fencingEpoch, failure)
        }
        return completeReactOutcome(outcome, ids, prepared.input.text, prepared.attemptId)
    }
    suspend fun executeApprovedTool(runId: String, sessionId: String, fencingEpoch: Long): Boolean = withSessionLeaseHeartbeat(
        sessionId,
        fencingEpoch,
    ) {
        executeApprovedToolWithLease(runId, sessionId, fencingEpoch)
    }

    private suspend fun executeApprovedToolWithLease(runId: String, sessionId: String, fencingEpoch: Long): Boolean {
        val planReadAt = clock()
        val planJson = store.pendingToolPlan(runId, planReadAt) ?: run {
            if (store.runById(runId)?.status == RuntimeRunStatus.EXECUTING.name) {
                store.finishExecutingRunFailure(
                    runId,
                    "APPROVAL_EXPIRED_OR_MISSING",
                    ownerId,
                    fencingEpoch,
                    planReadAt,
                )
            }
            return false
        }
        return try {
            val toolName = Json.parseToJsonElement(planJson).jsonObject["toolName"]?.jsonPrimitive?.content
                ?: throw ProviderFailure("INVALID_TOOL_CALL", false)
            val context = ConfirmedToolExecutionContext(runId, ownerId, fencingEpoch, clock())
            val result = capabilityRouter.executeApproved(toolName, planJson, context)
            observeToolResult(
                runId,
                sessionId,
                fencingEpoch,
                result.canonicalName,
                result.providerCallId,
                result.safeResultJson,
            )
        } catch (_: TimeoutCancellationException) {
            store.finishExecutingRunFailure(
                runId,
                "TIMEOUT",
                ownerId,
                fencingEpoch,
                clock(),
                retryable = true,
            )
            false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            val run = store.runById(runId)
            if (run?.status == RuntimeRunStatus.OBSERVING.name) {
                store.finishObservationRun(
                    runId,
                    RuntimeRunStatus.FAILED_FINAL,
                    "RunFailedFinal",
                    "{\"errorCode\":\"TOOL_OBSERVATION_FAILED\"}",
                    ownerId,
                    fencingEpoch,
                    clock(),
                )
                false
            } else if (run?.status == RuntimeRunStatus.EXECUTING.name) {
                val code = (failure as? ProviderFailure)?.code
                    ?: failure.message?.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{2,63}")) }
                    ?: "TOOL_EXECUTION_FAILED"
                store.finishExecutingRunFailure(runId, code, ownerId, fencingEpoch, clock())
                false
            } else {
                finishFailure(runId, fencingEpoch, failure)
            }
        }
    }

    internal suspend fun <T> withSessionLeaseHeartbeat(sessionId: String, fencingEpoch: Long, block: suspend () -> T): T = coroutineScope {
        renewCurrentLease(sessionId, fencingEpoch)
        val heartbeat = launch {
            while (isActive) {
                delay(heartbeatIntervalMs)
                renewCurrentLease(sessionId, fencingEpoch)
            }
        }
        try {
            block()
        } finally {
            heartbeat.cancelAndJoin()
        }
    }

    private suspend fun renewCurrentLease(sessionId: String, fencingEpoch: Long) {
        val claim = store.tryClaimSession(sessionId, ownerId, clock(), leaseDurationMs)
        check(claim.claimed && claim.leaseEpoch == fencingEpoch) {
            "session lease is no longer owned by this execution"
        }
    }

    fun launchApprovedTool(runId: String, sessionId: String, fencingEpoch: Long): Boolean =
        launchRunJob(runId, sessionId, fencingEpoch) { executeApprovedTool(runId, sessionId, fencingEpoch) }

    private suspend fun containEscapedFailure(runId: String, sessionId: String, fencingEpoch: Long) {
        activeRequests[runId]?.let(provider::cancel)
        when (store.runById(runId)?.status) {
            RuntimeRunStatus.RECEIVED.name,
            RuntimeRunStatus.ASSEMBLING_CONTEXT.name,
            -> events.failBeforeAttempt(runId, sessionId, fencingEpoch, "RUNTIME_INTERRUPTED", retryable = true)

            RuntimeRunStatus.INFERENCING.name ->
                finishFailure(runId, fencingEpoch, ProviderFailure("RUNTIME_INTERRUPTED", retryable = true))

            RuntimeRunStatus.EXECUTING.name ->
                store.finishExecutingRunFailure(runId, "RUNTIME_INTERRUPTED", ownerId, fencingEpoch, clock())

            RuntimeRunStatus.OBSERVING.name -> store.finishObservationRun(
                runId,
                RuntimeRunStatus.FAILED_RETRYABLE,
                "RunFailedRetryable",
                "{\"errorCode\":\"RUNTIME_INTERRUPTED\"}",
                ownerId,
                fencingEpoch,
                clock(),
            )

            RuntimeRunStatus.CANCEL_REQUESTED.name -> store.cancelProviderRun(runId, ownerId, fencingEpoch, clock())
        }
    }

    internal suspend fun requestToolApproval(event: ModelEvent.ToolCall, runId: String, sessionId: String, attemptId: String, fencingEpoch: Long): Boolean {
        val revision = store.projectionSnapshot(sessionId, "ui").currentRevision + 2
        return capabilityRouter.requestApproval(
            RuntimeToolCallRequest(event.providerCallId, event.name, event.argumentsJson),
            RuntimeToolRouteContext(runId, sessionId, attemptId, ownerId, fencingEpoch, revision, clock()),
        )
    }

    internal fun toolObservationSensitivity(toolName: String): OutboundSensitivity = when {
        toolName.startsWith("relationship.") -> OutboundSensitivity.SENSITIVE
        toolName.startsWith("mcp.") -> OutboundSensitivity.SENSITIVE
        toolName == "communication.message.compose" -> OutboundSensitivity.SENSITIVE
        else -> OutboundSensitivity.PERSONAL
    }

    internal fun toolAllowlist(skills: List<SkillActivation>): Set<String>? = skills.takeIf { values -> values.any { it.origin == SkillOrigin.SIGNED_PACKAGE } }
        ?.flatMapTo(linkedSetOf()) { it.requiredTools }

    internal fun requireSkillAllowsTool(name: String, skills: List<SkillActivation>) {
        val allowed = toolAllowlist(skills) ?: return
        val canonical = capabilityRouter.canonicalName(name)
        if (canonical !in allowed) {
            throw com.zhiban.rebuild.runtime.tool.ToolPolicyRejectedException(
                "tool is not declared by the active signed skill",
            )
        }
    }

    private companion object {
        val RECOVERABLE_EXECUTION_STATUSES = setOf(
            RuntimeRunStatus.ASSEMBLING_CONTEXT.name,
            RuntimeRunStatus.INFERENCING.name,
            RuntimeRunStatus.EXECUTING.name,
            RuntimeRunStatus.OBSERVING.name,
        )
        const val ENTITY_EXTRACTION_TIMEOUT_MS = 50L
        val SAFE_FAILURE_CODES = setOf(
            "AUTHENTICATION_FAILED", "TIMEOUT", "RATE_LIMITED", "PROVIDER_UNAVAILABLE", "TLS_VERIFICATION_FAILED",
            "PROVIDER_REJECTED", "MODEL_NOT_AVAILABLE", "CAPABILITY_EXPIRED",
            "PROVIDER_RESPONSE_TOO_LARGE", "PROVIDER_FRAME_TOO_LARGE", "INVALID_TOOL_CALL",
            "INVALID_TOOL_ARGUMENTS", "TOOL_ARGUMENTS_TOO_LARGE", "CONTEXT_TOKEN_LIMIT_EXCEEDED",
            "OUTPUT_TOKEN_LIMIT_EXCEEDED",
            "PROVIDER_STREAM_INCOMPLETE",
            "EMPTY_RESPONSE",
            "TOOL_CALL_UNSUPPORTED",
            "BUDGET_EXCEEDED",
            "CAPABILITY_UNAVAILABLE",
            "TARGET_APP_UNAVAILABLE",
            "INSUFFICIENT_QUOTA", "INPUT_SENSITIVE", "OUTPUT_SENSITIVE", "INVALID_REQUEST",
            // 定位读取未开启(默认关):告诉用户去哪开,而不是给个兜底失败文案。
            "LOCATION_CONSENT_REQUIRED",
            "MEMORY_CONSENT_REQUIRED",
        )
        val ENGLISH_CALLED_TITLE = Regex(
            """\bcalled\s+(.+?)(?=,\s*remind\b|,\s*with\b|[.!?]\s*$|$)""",
            RegexOption.IGNORE_CASE,
        )
        val CHINESE_NAMED_TITLE = Regex("""(?:叫|名为|标题是)\s*([^，。,.]{1,80})""")
        val CHINESE_AFTER_TIME_TITLE =
            Regex("""(?:点|分)\s*([^，。,.]{1,40}?)(?=，|。|,|提醒|$)""")
        val ENGLISH_REMINDER =
            Regex("""\b(\d{1,4})\s*minutes?\s*before\b""", RegexOption.IGNORE_CASE)
        val CHINESE_REMINDER = Regex("""提前\s*(\d{1,4})\s*分钟""")
    }
}
