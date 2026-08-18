package com.zhiban.rebuild.ui.agent.projection

import com.zhiban.rebuild.foundation.runSuspendCatching
import com.zhiban.rebuild.runtime.input.AttachmentRef
import com.zhiban.rebuild.runtime.spi.CommandReceiptStatus
import com.zhiban.rebuild.runtime.spi.RuntimeUiClient
import com.zhiban.rebuild.runtime.spi.TextInputGateway
import com.zhiban.rebuild.ui.agent.AgentConversationStage
import com.zhiban.rebuild.ui.agent.AgentConversationUiState
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class V2AgentConversationBackend(
    runtimeUiClient: RuntimeUiClient,
    private val textInputGateway: TextInputGateway,
    sessionId: String,
    private val scope: CoroutineScope,
    idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val controller = AgentRuntimeProjectionController(
        client = runtimeUiClient,
        sessionId = sessionId,
        surfaceId = "compose-agent-conversation",
        scope = scope,
        idFactory = idFactory,
    )

    // A session is a continuous conversation. The main view renders all turns in
    // this session; the history drawer switches between separate sessions.
    private val mutableUiState = MutableStateFlow(AgentConversationUiState(stage = AgentConversationStage.EMPTY))
    val uiState: StateFlow<AgentConversationUiState> = mutableUiState.asStateFlow()
    private var projectionJob: Job? = null
    private var startInFlight = false

    fun initialize() {
        if (projectionJob != null) return
        controller.initialize()
        projectionJob = scope.launch {
            controller.projection.collect { projection ->
                val previous = mutableUiState.value
                val mapped = AgentProjectionUiMapper.map(projection)
                mutableUiState.value = mapped.copy(
                    userMessage = mapped.userMessage ?: previous.userMessage,
                )
            }
        }
    }

    fun plan(rawText: String, mode: String = "Work", model: String? = null, level: String? = null, attachments: List<AttachmentRef> = emptyList()) {
        val normalized = rawText.trim()
        if (normalized.isEmpty() || startInFlight) return
        startInFlight = true
        mutableUiState.value = AgentConversationUiState(
            stage = AgentConversationStage.PLANNING,
            userMessage = normalized,
            assistantMessage = "正在理解你的安排…",
            inputEnabled = false,
        )
        scope.launch {
            var stagedRef: String? = null
            try {
                runSuspendCatching {
                    withTimeoutOrNull(INPUT_START_TIMEOUT_MS) {
                        val runtimeInput = encodeRuntimeInput(normalized, mode, model, level, attachments)
                        textInputGateway.stage(runtimeInput).also { stagedRef = it.inputRef }
                            .let { controller.start(it.inputRef) }
                    } ?: throw InputStartTimeoutException()
                }.onSuccess { receipt ->
                    if (receipt.status in setOf(CommandReceiptStatus.CONFLICT, CommandReceiptStatus.REJECTED)) {
                        stagedRef?.let { textInputGateway.discard(it) }
                        handleNonAcceptedReceipt(receipt.status)
                    }
                }.onFailure { failure ->
                    stagedRef?.let { runSuspendCatching { textInputGateway.discard(it) } }
                    applyFailure(failure)
                }
            } finally {
                startInFlight = false
            }
        }
    }

    private fun encodeRuntimeInput(text: String, mode: String, model: String?, level: String?, attachments: List<AttachmentRef>): String {
        if (mode == "Chat" && model == null && level == null && attachments.isEmpty()) return text
        return buildJsonObject {
            put("schemaVersion", 1)
            put("text", text)
            put("mode", mode)
            model?.let { put("model", it) }
            level?.let { put("level", it) }
            putJsonArray("attachments") {
                attachments.forEach { ref ->
                    add(
                        buildJsonObject {
                            put("attachmentId", ref.attachmentId)
                            put("kind", ref.kind.name)
                            put("mimeType", ref.mimeType)
                            put("byteLength", ref.byteLength)
                            put("sha256Digest", ref.sha256Digest)
                            put("contentRef", ref.contentRef)
                            put("expiresAtEpochMs", ref.expiresAtEpochMs)
                        },
                    )
                }
            }
        }.toString()
    }

    fun approve() = launchAction { controller.approve() }
    fun reject() = launchAction { controller.reject() }
    fun cancel() = launchAction { controller.cancel() }
    fun retry() = launchAction { controller.retry() }
    fun resume() = launchAction { controller.resume() }
    fun undo() = launchAction { controller.undo() }
    fun positiveFeedback() = launchAction { controller.positiveFeedback() }
    fun negativeFeedback() = launchAction { controller.negativeFeedback() }

    fun close() {
        projectionJob?.cancel()
        projectionJob = null
        controller.close()
    }

    private fun launchAction(action: suspend () -> com.zhiban.rebuild.runtime.spi.CommandReceipt) {
        scope.launch {
            runSuspendCatching { action() }
                .onSuccess {
                    if (it.status in
                        setOf(CommandReceiptStatus.CONFLICT, CommandReceiptStatus.REJECTED)
                    ) {
                        handleNonAcceptedReceipt(it.status)
                    }
                }
                .onFailure(::applyFailure)
        }
    }

    private suspend fun handleNonAcceptedReceipt(status: CommandReceiptStatus) {
        val previous = mutableUiState.value
        controller.refresh()
        kotlinx.coroutines.yield()
        val authoritative = AgentProjectionUiMapper.map(controller.projection.value)
        mutableUiState.value = authoritative.copy(
            userMessage = authoritative.userMessage ?: previous.userMessage,
            safeMessage = if (status == CommandReceiptStatus.CONFLICT) "会话状态已更新，请重试。" else "当前操作未被允许。",
        )
    }

    private fun applyFailure(failure: Throwable) {
        mutableUiState.value = mutableUiState.value.copy(
            stage = AgentConversationStage.FAILED_FINAL,
            assistantMessage = "这次没有完成。",
            safeMessage = when (failure) {
                is IllegalArgumentException -> "当前操作已失效，请刷新后重试。"
                is InputStartTimeoutException -> "提交超时，请检查网络后重试。"
                else -> "暂时无法完成，请稍后重试。"
            },
            inputEnabled = true,
        )
    }
}

private class InputStartTimeoutException : IllegalStateException()
private const val INPUT_START_TIMEOUT_MS = 15_000L
