package com.zhiban.rebuild.ui.agent

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhiban.rebuild.data.agent.AgentDataRepository
import com.zhiban.rebuild.data.notification.NotificationCandidateEntity
import com.zhiban.rebuild.runtime.input.AttachmentStagingGateway
import com.zhiban.rebuild.runtime.input.asr.RealtimeVoiceState
import com.zhiban.rebuild.runtime.input.asr.StepFunRealtimeVoiceController
import com.zhiban.rebuild.runtime.runSuspendCatching
import com.zhiban.rebuild.runtime.spi.RuntimeUiClient
import com.zhiban.rebuild.runtime.spi.TextInputGateway
import com.zhiban.rebuild.ui.agent.projection.V2AgentConversationBackend
import com.zhiban.rebuild.ui.chat.PreferencesManager
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Bundles the conversation/session persistence gateways the ViewModel coordinates. */
class ConversationPersistence @Inject constructor(
    val historyGateway: com.zhiban.rebuild.runtime.store.ConversationHistoryGateway,
    val sessionWorkspace: com.zhiban.rebuild.runtime.workspace.SessionWorkspaceGateway,
)

/** Bundles the cloud + realtime voice entry points the ViewModel exposes. */
class VoiceEntryPoints @Inject constructor(
    val cloudAsrGateway: com.zhiban.rebuild.runtime.input.asr.CloudAsrGateway,
    val realtimeVoice: StepFunRealtimeVoiceController,
)

/** Bundles the runtime-input gateways used to feed a conversation turn. */
class BackendGateways @Inject constructor(
    val runtimeUiClient: RuntimeUiClient,
    val textInputGateway: TextInputGateway,
    val attachmentStagingGateway: AttachmentStagingGateway,
)

@HiltViewModel
class AgentConversationViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val preferencesManager: PreferencesManager,
    private val agentControls: com.zhiban.rebuild.runtime.config.AgentControlStore,
    private val providerEnvironment: com.zhiban.rebuild.runtime.provider.ProviderEnvironmentManager,
    private val persistence: ConversationPersistence,
    private val voice: VoiceEntryPoints,
    private val gateways: BackendGateways,
    private val agentDataRepository: AgentDataRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AgentConversationUiState())
    val uiState: StateFlow<AgentConversationUiState> = _uiState.asStateFlow()
    private var initialized = false
    private var v2Backend: V2AgentConversationBackend? = null
    private var activeSessionId: String? = null
    private var projectionJob: kotlinx.coroutines.Job? = null
    private var submissionInFlight = false
    private val _conversationHistory =
        MutableStateFlow<List<com.zhiban.rebuild.runtime.store.ConversationSummary>>(emptyList())
    val conversationHistory: StateFlow<List<com.zhiban.rebuild.runtime.store.ConversationSummary>> = _conversationHistory.asStateFlow()
    val realtimeVoiceState: StateFlow<RealtimeVoiceState> = voice.realtimeVoice.state
    val perceptionCandidates: StateFlow<List<NotificationCandidateEntity>> =
        agentDataRepository.observeNotificationCandidates().stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000L),
            emptyList(),
        )

    fun initialize(initialDraft: String, initialMode: String = "Chat") {
        if (initialized) {
            viewModelScope.launch {
                _cloudAsrAvailability.value = voice.cloudAsrGateway.availability()
            }
            return
        }
        initialized = true
        viewModelScope.launch {
            runSuspendCatching {
                val activeProfile = providerEnvironment.activeProfile()
                val preset = com.zhiban.rebuild.runtime.provider.TrustedProviderRegistry().preset(
                    activeProfile?.providerId
                        ?: com.zhiban.rebuild.runtime.provider.ProviderConfigurationManager.DEFAULT_PROVIDER,
                )
                // One user-facing automatic model. The runtime selects the trusted vision model
                // internally whenever an image or PDF is attached.
                _availableModels.value = listOf(preset.defaultModel)
                _selectedModel.value = preset.defaultModel
                // 问问为单一 coworker 模式，恒为 Work；initialMode 入参保留兼容但不再生效。
                _selectedMode.value = AGENT_RUNTIME_MODE
                _cloudAsrAvailability.value = voice.cloudAsrGateway.availability()
                val sessionId = if (initialDraft.isNotBlank()) {
                    UUID.randomUUID().toString()
                } else {
                    savedStateHandle[ACTIVE_SESSION_ID]
                        ?: preferencesManager.getActiveRuntimeSessionId()
                        ?: UUID.randomUUID().toString()
                }
                activateSession(sessionId)
                if (initialDraft.isNotBlank()) v2Backend?.plan(initialDraft, AGENT_RUNTIME_MODE)
            }.onFailure { showSubmissionFailure(AgentConversationStage.FAILED_RETRYABLE, "对话初始化失败，请稍后重试。") }
        }
    }

    private suspend fun activateSession(sessionId: String) {
        projectionJob?.cancel()
        v2Backend?.close()
        _uiState.value = AgentConversationUiState()
        savedStateHandle[ACTIVE_SESSION_ID] = sessionId
        activeSessionId = sessionId
        preferencesManager.saveActiveRuntimeSessionId(sessionId)
        persistence.sessionWorkspace.ensure(sessionId)
        val backend = V2AgentConversationBackend(gateways.runtimeUiClient, gateways.textInputGateway, sessionId, viewModelScope)
        v2Backend = backend
        backend.initialize()
        projectionJob = viewModelScope.launch {
            combine(
                backend.uiState,
                persistence.historyGateway.observeTurns(sessionId),
                persistence.sessionWorkspace.observeArtifacts(sessionId),
            ) { state, turns, workspaceArtifacts ->
                val messages = turns.map {
                    AgentConversationMessageUi(
                        turnId = it.turnId,
                        role = it.role,
                        text = if (it.role == "user") AgentPromptEnvelope.displayText(it.text) else it.text,
                    )
                }
                val artifacts = workspaceArtifacts.map {
                    AgentArtifactUi(
                        it.artifactId,
                        it.displayName,
                        it.mimeType,
                        it.byteLength,
                        AgentArtifactKind.valueOf(it.kind.name),
                    )
                }
                state.copy(
                    userMessage = state.userMessage?.let(AgentPromptEnvelope::displayText),
                    messages = messages,
                    artifacts = artifacts,
                )
            }.retryWhen { _, _ ->
                showSubmissionFailure(AgentConversationStage.FAILED_RETRYABLE, "会话刷新失败，正在恢复。")
                kotlinx.coroutines.delay(PROJECTION_RETRY_DELAY_MS)
                true
            }.collect(_uiState)
        }
    }

    fun loadConversationHistory() {
        viewModelScope.launch {
            runSuspendCatching { persistence.historyGateway.list() }
                .onSuccess { _conversationHistory.value = it }
                .onFailure { showSubmissionFailure(AgentConversationStage.FAILED_RETRYABLE, "会话列表加载失败，请重试。") }
        }
    }

    fun openConversation(sessionId: String) {
        viewModelScope.launch { activateSessionSafely(sessionId) }
    }

    fun newConversation() {
        viewModelScope.launch { activateSessionSafely(UUID.randomUUID().toString()) }
    }

    private suspend fun activateSessionSafely(sessionId: String) {
        runSuspendCatching { activateSession(sessionId) }
            .onFailure { showSubmissionFailure(AgentConversationStage.FAILED_RETRYABLE, "会话暂时无法打开，请稍后重试。") }
    }

    fun confirmPerceptionCandidate(candidate: NotificationCandidateEntity) {
        viewModelScope.launch {
            runSuspendCatching {
                val contactId = candidate.linkedContactId ?: candidate.suggestedContactId
                if (candidate.linkedContactId == null && contactId != null) {
                    check(agentDataRepository.confirmNotificationCandidate(candidate.candidateId, contactId))
                }
                if (com.zhiban.rebuild.data.notification.ScheduleInsight.from(candidate) != null &&
                    candidate.createdScheduleId == null
                ) {
                    agentDataRepository.confirmNotificationSchedule(candidate.candidateId)
                }
            }.onFailure { failure ->
                _uiState.update { it.copy(safeMessage = failure.message ?: "这条建议暂时无法整理") }
            }
        }
    }

    fun dismissPerceptionCandidate(candidateId: String) {
        viewModelScope.launch { agentDataRepository.dismissNotificationCandidate(candidateId) }
    }

    fun deleteConversation(sessionId: String) {
        viewModelScope.launch {
            if (sessionId == activeSessionId) activateSession(UUID.randomUUID().toString())
            val deleted = runSuspendCatching {
                persistence.sessionWorkspace.delete(sessionId)
                persistence.historyGateway.delete(sessionId)
            }.getOrElse { false }
            if (!deleted) {
                _uiState.update {
                    it.copy(
                        safeMessage = "这段对话没有完全删除，请稍后重试。",
                        stage = AgentConversationStage.FAILED_RETRYABLE,
                        inputEnabled = true,
                    )
                }
            }
            _conversationHistory.value = persistence.historyGateway.list()
        }
    }

    fun plan(input: String, mode: String = AGENT_RUNTIME_MODE, attachmentContentRefs: List<String> = emptyList(), onAccepted: () -> Unit = {}) {
        if (input.isBlank() || submissionInFlight) return
        // 问问是单一 coworker 模式：始终以 Work 下发，工具一直可用，写入不再因模式被屏蔽。
        // 速度由"快速/标准/深入"级别控制（快速仍走单步直接回答），不再有 Chat/Work 的能力割裂。
        submissionInFlight = true
        _uiState.update { it.copy(inputEnabled = false, safeMessage = null) }
        viewModelScope.launch {
            val staged = mutableListOf<com.zhiban.rebuild.runtime.input.AttachmentRef>()
            try {
                val sessionId = activeSessionId
                if (sessionId == null || v2Backend == null) {
                    _uiState.update {
                        it.copy(
                            stage = AgentConversationStage.FAILED_RETRYABLE,
                            safeMessage = "对话还没有准备好，请稍后重试。",
                            inputEnabled = true,
                        )
                    }
                    return@launch
                }
                if (!prepareAttachments(sessionId, attachmentContentRefs, staged)) return@launch
                onAccepted()
                v2Backend?.plan(input, AGENT_RUNTIME_MODE, _selectedModel.value, _selectedLevel.value, staged)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                staged.forEach { runSuspendCatching { gateways.attachmentStagingGateway.discard(it.attachmentId) } }
                _uiState.update {
                    it.copy(
                        stage = AgentConversationStage.FAILED_RETRYABLE,
                        safeMessage = "暂时无法提交，请稍后重试。",
                        inputEnabled = true,
                    )
                }
            } finally {
                submissionInFlight = false
            }
        }
    }

    private suspend fun prepareAttachments(
        sessionId: String,
        contentRefs: List<String>,
        staged: MutableList<com.zhiban.rebuild.runtime.input.AttachmentRef>,
    ): Boolean {
        val expiresAt = System.currentTimeMillis() + 24 * 60 * 60 * 1_000L
        try {
            withTimeout(ATTACHMENT_PREPARATION_TIMEOUT_MS) {
                contentRefs.distinct().forEach { contentRef ->
                    staged += gateways.attachmentStagingGateway.stage(sessionId, contentRef, expiresAt)
                }
            }
        } catch (_: TimeoutCancellationException) {
            discardStagedAttachments(staged)
            showSubmissionFailure(AgentConversationStage.FAILED_RETRYABLE, "附件读取超时，请重新选择或重试。")
            return false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            discardStagedAttachments(staged)
            showSubmissionFailure(AgentConversationStage.FAILED_FINAL, "附件无法安全读取，原内容已保留，请重新选择或重试。")
            return false
        }
        val preserved = withTimeoutOrNull(ATTACHMENT_PRESERVE_TIMEOUT_MS) {
            runSuspendCatching {
                staged.forEach { persistence.sessionWorkspace.preserveAttachment(sessionId, it) }
            }.isSuccess
        } == true
        if (!preserved) {
            discardStagedAttachments(staged)
            showSubmissionFailure(AgentConversationStage.FAILED_FINAL, "附件未能保存到本次对话，原内容已保留，请重试。")
        }
        return preserved
    }

    private suspend fun discardStagedAttachments(staged: List<com.zhiban.rebuild.runtime.input.AttachmentRef>) {
        staged.forEach { runSuspendCatching { gateways.attachmentStagingGateway.discard(it.attachmentId) } }
    }

    private fun showSubmissionFailure(stage: AgentConversationStage, message: String) {
        _uiState.update { it.copy(stage = stage, safeMessage = message, inputEnabled = true) }
    }

    // The user sees one automatic Agent model. Runtime switches to Step 3
    // internally when an image is attached; provider model names are not a
    // user decision.
    private val _selectedModel =
        MutableStateFlow(com.zhiban.rebuild.runtime.provider.ProviderConfigurationManager.DEFAULT_MODEL)
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()
    private val _selectedMode = MutableStateFlow(AGENT_RUNTIME_MODE)
    val selectedMode: StateFlow<String> = _selectedMode.asStateFlow()
    private val _availableModels = MutableStateFlow(
        listOf(com.zhiban.rebuild.runtime.provider.ProviderConfigurationManager.DEFAULT_MODEL),
    )
    val availableModels: StateFlow<List<String>> = _availableModels.asStateFlow()
    fun selectModel(model: String) {
        if (model in _availableModels.value) _selectedModel.value = model
    }
    fun selectMode(mode: String) {
        val safeMode = if (mode == "Work") "Work" else "Chat"
        _selectedMode.value = safeMode
        viewModelScope.launch { preferencesManager.saveAgentMode(safeMode) }
    }

    private val _selectedLevel = MutableStateFlow(agentControls.execution().runtimeLevel)
    val selectedLevel: StateFlow<String> = _selectedLevel.asStateFlow()
    private val levels = listOf("深入", "标准", "快速")
    fun selectLevel(level: String) {
        if (level !in levels) return
        _selectedLevel.value = level
        val preference = when (level) {
            "深入" -> com.zhiban.rebuild.runtime.config.ExecutionPreference.DEEP
            "快速" -> com.zhiban.rebuild.runtime.config.ExecutionPreference.FAST
            else -> com.zhiban.rebuild.runtime.config.ExecutionPreference.BALANCED
        }
        agentControls.saveExecution(preference)
    }
    fun availableLevels(): List<String> = levels

    private val _cloudAsrAvailability =
        MutableStateFlow(com.zhiban.rebuild.runtime.input.asr.CloudAsrAvailability.PROVIDER_NOT_CONFIGURED)
    val cloudAsrAvailability: StateFlow<com.zhiban.rebuild.runtime.input.asr.CloudAsrAvailability> = _cloudAsrAvailability.asStateFlow()
    suspend fun transcribeCloud(audio: java.io.File): com.zhiban.rebuild.runtime.input.asr.CloudAsrResult = voice.cloudAsrGateway.transcribe(audio)

    fun confirm() {
        v2Backend?.approve()
    }
    fun retry() {
        v2Backend?.retry()
    }
    fun reject() {
        v2Backend?.reject()
    }
    fun positiveFeedback() {
        if (agentControls.feedback().useHumanFeedback) {
            agentControls.recordHumanFeedback(true)
            v2Backend?.positiveFeedback()
        }
    }
    fun negativeFeedback() {
        if (agentControls.feedback().useHumanFeedback) {
            agentControls.recordHumanFeedback(false)
            v2Backend?.negativeFeedback()
        }
    }
    fun isHumanFeedbackEnabled(): Boolean = agentControls.feedback().useHumanFeedback
    fun cancel() {
        v2Backend?.cancel()
    }
    fun resume() {
        v2Backend?.resume()
    }
    fun undo() {
        v2Backend?.undo()
    }

    fun startRealtimeVoice() = voice.realtimeVoice.start()
    fun finishRealtimeVoiceInput() = voice.realtimeVoice.finishInput()
    fun cancelRealtimeVoice() = voice.realtimeVoice.cancel()
    fun showRealtimeExchange(exchangeId: String, transcript: String, reply: String) {
        if (transcript.isBlank() && reply.isBlank()) return
        viewModelScope.launch {
            val sessionId = activeSessionId ?: return@launch
            val persisted = persistence.historyGateway.recordRealtimeExchange(sessionId, exchangeId, transcript, reply)
            _uiState.value = AgentConversationUiState(
                stage = AgentConversationStage.SUCCEEDED,
                userMessage = transcript.ifBlank { null },
                assistantMessage = reply.ifBlank { null },
                safeMessage = if (persisted) null else "实时语音结果未能保存，请重试。",
                sourceLabels = listOf("阶跃星辰实时语音"),
            )
            _conversationHistory.value = persistence.historyGateway.list()
        }
    }

    override fun onCleared() {
        projectionJob?.cancel()
        v2Backend?.close()
        voice.realtimeVoice.cancel()
        super.onCleared()
    }

    companion object {
        /** Runtime compatibility value. This is not a user-facing mode. */
        const val AGENT_RUNTIME_MODE = "Work"
        private const val ACTIVE_SESSION_ID = "agent.runtimeSessionId"
        private const val ATTACHMENT_PREPARATION_TIMEOUT_MS = 30_000L
        private const val ATTACHMENT_PRESERVE_TIMEOUT_MS = 15_000L
        private const val PROJECTION_RETRY_DELAY_MS = 1_000L
    }
}
