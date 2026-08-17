package com.zhiban.rebuild.ui.agent

enum class AgentConversationStage {
    EMPTY,
    RECOVERING,
    PLANNING,
    AWAITING_CONFIRMATION,
    EXECUTING,
    SUCCEEDED,
    FAILED_RETRYABLE,
    FAILED_FINAL,
    REJECTED,
    CANCELLED,
}

data class AgentPlanUi(
    val title: String,
    val subject: String = "",
    val schedule: String = "",
    val reminder: String = "",
    val platform: String = "",
    val recipient: String = "",
    val message: String = "",
    // Generic body for non-message confirmations (memory content, CRM summary, …). Unlike
    // [message], it carries no "将发送 / 打开目标应用" messaging semantics.
    val details: String = "",
    // True when confirming delivers the message for real (e.g. WeChat via iLink), as opposed to
    // opening the target app for the user to finish sending. Drives which disclaimer the card shows.
    val deliversDirectly: Boolean = false,
)

data class AgentConversationMessageUi(val turnId: String, val role: String, val text: String)

enum class AgentArtifactKind { ATTACHMENT, GENERATED_FILE, TOOL_RESULT, EXPORT }

data class AgentArtifactUi(val artifactId: String, val title: String, val mimeType: String, val byteLength: Long, val kind: AgentArtifactKind) {
    val isUserVisibleOutput: Boolean
        get() = kind == AgentArtifactKind.GENERATED_FILE || kind == AgentArtifactKind.EXPORT
}

data class AgentConversationUiState(
    val stage: AgentConversationStage = AgentConversationStage.EMPTY,
    /** Runtime identity of the active turn; used to reconcile optimistic and persisted rows. */
    val runtimeRunId: String? = null,
    val userMessage: String? = null,
    val assistantMessage: String? = null,
    val messages: List<AgentConversationMessageUi> = emptyList(),
    val artifacts: List<AgentArtifactUi> = emptyList(),
    val memoryHint: String? = null,
    val recoveredMessageCount: Int? = null,
    val plan: AgentPlanUi? = null,
    val safeMessage: String? = null,
    val permission: AgentPermissionUi? = null,
    val pendingProposalId: String? = null,
    val usedTokens: Int? = null,
    val maxTokens: Int? = null,
    val sourceLabels: List<String> = emptyList(),
    val canCancel: Boolean = false,
    val canResume: Boolean = false,
    val canUndo: Boolean = false,
    val inputEnabled: Boolean = true,
    val isCredentialMissing: Boolean = false,
    val safeFailureCode: String? = null,
) {
    val isInputEnabled: Boolean get() = inputEnabled && stage != AgentConversationStage.EXECUTING

    // The actionable confirmation card (with 确认/拒绝 buttons) is only meaningful while the
    // run is actually awaiting a decision or executing it. Once the run reaches a terminal or
    // failed state, the decision is over: the result surface (ToolResultCard / action row) takes
    // over, and leaving the plan card up would both mislead the user and strand dead buttons.
    val showPlan: Boolean get() = plan != null && stage in setOf(
        AgentConversationStage.AWAITING_CONFIRMATION,
        AgentConversationStage.EXECUTING,
    )
}

enum class AgentPermissionUi { MICROPHONE, ATTACHMENT }
