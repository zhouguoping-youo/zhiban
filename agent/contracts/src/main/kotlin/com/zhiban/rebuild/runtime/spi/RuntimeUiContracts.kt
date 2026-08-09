package com.zhiban.rebuild.runtime.spi

import kotlinx.coroutines.flow.Flow

enum class RuntimeAction { START, STEER, APPROVE, REJECT, CANCEL, RETRY, RESUME, UNDO, FEEDBACK_POSITIVE, FEEDBACK_NEGATIVE }
enum class CommandReceiptStatus { ACCEPTED, DUPLICATE, CONFLICT, REJECTED }
enum class PendingUserOperationState { PENDING, COMPLETED, CANCELLED, EXPIRED }

data class CommandReceipt(val status: CommandReceiptStatus, val commandId: String, val currentRevision: Long, val errorCode: String? = null)

sealed interface RuntimeUiCommand {
    val sessionId: String
    val runId: String?
    val commandId: String
    val clientActionId: String
    val expectedRevision: Long
    val surfaceId: String

    data class Start(
        override val sessionId: String,
        val inputRef: String,
        override val commandId: String,
        override val clientActionId: String,
        override val expectedRevision: Long,
        override val surfaceId: String,
        override val runId: String? = null,
    ) : RuntimeUiCommand

    data class RunAction(
        val action: RuntimeAction,
        override val sessionId: String,
        override val runId: String,
        override val commandId: String,
        override val clientActionId: String,
        override val expectedRevision: Long,
        override val surfaceId: String,
        val proposalId: String? = null,
        val payloadRef: String? = null,
    ) : RuntimeUiCommand

    data class ResolveUserOperation(
        override val sessionId: String,
        override val runId: String,
        val requestId: String,
        val result: PendingUserOperationState,
        val resultRef: String? = null,
        override val commandId: String,
        override val clientActionId: String,
        override val expectedRevision: Long,
        override val surfaceId: String,
    ) : RuntimeUiCommand
}

data class PendingApprovalProjection(
    val proposalId: String,
    val payloadRef: String,
    val title: String,
    val platform: String? = null,
    val recipient: String? = null,
    val message: String? = null,
    // Schedule detail so the confirmation card shows the resolved date/time the user is about
    // to commit — without it the user confirms "计划：复诊" blind. Raw values; the UI formats them.
    val scheduleStartAtEpochMs: Long? = null,
    val scheduleDurationMinutes: Int? = null,
    val scheduleReminderMinutesBefore: Int? = null,
    val scheduleNote: String? = null,
    // Generic confirmation body (memory content, CRM summary, …) for tools that are not messaging.
    val details: String? = null,
    // Opaque staged-content reference (memory candidate id). Never carries the content itself; the UI
    // resolves it against the short-lived staging area at display time so the body stays out of the journal.
    val candidateId: String? = null,
)
data class BudgetProjection(val usedTokens: Int, val maxTokens: Int)
data class SourceProjection(val sourceId: String, val label: String)
data class PendingUserOperationProjection(
    val requestId: String,
    val type: String,
    val expiresAtEpochMs: Long,
    val state: PendingUserOperationState = PendingUserOperationState.PENDING,
)
data class UserOperationResultProjection(val requestId: String, val state: PendingUserOperationState)

data class SessionProjection(
    val sessionId: String,
    val runId: String? = null,
    val lastAppliedSequence: Long = 0,
    val revision: Long = 0,
    val runStatus: RuntimeRunStatus = RuntimeRunStatus.RECEIVED,
    val assistantText: String = "",
    val assistantFinal: Boolean = false,
    val safeFailureCode: String? = null,
    val degradationReasons: Set<String> = emptySet(),
    val appliedDeltaOrdinals: Set<String> = emptySet(),
    val pendingApproval: PendingApprovalProjection? = null,
    // Transient confirmation body (e.g. the memory content being approved), carried only by the
    // single-row snapshot while the run is AWAITING_CONFIRMATION. Never in the event journal.
    val pendingDetails: String? = null,
    val budget: BudgetProjection? = null,
    val sources: List<SourceProjection> = emptyList(),
    val pendingUserOperation: PendingUserOperationProjection? = null,
    val lastUserOperationResult: UserOperationResultProjection? = null,
    val lastChangeId: String? = null,
    val undoAvailable: Boolean = false,
    val recoveryNeeded: Boolean = false,
    val readOnly: Boolean = false,
    val allowedActions: Set<RuntimeAction> = setOf(RuntimeAction.START),
)

sealed interface RuntimeUiEvent {
    val sessionId: String
    val runId: String?
    val sequence: Long
    val revision: Long

    data class JournalAdvanced(override val sessionId: String, override val runId: String?, override val sequence: Long, override val revision: Long) :
        RuntimeUiEvent

    data class RunStatusChanged(
        override val sessionId: String,
        override val runId: String,
        override val sequence: Long,
        override val revision: Long,
        val status: RuntimeRunStatus,
        val safeFailureCode: String? = null,
    ) : RuntimeUiEvent

    data class AssistantDelta(
        override val sessionId: String,
        override val runId: String,
        val attemptId: String,
        override val sequence: Long,
        override val revision: Long,
        val ordinal: Long,
        val part: String,
        val final: Boolean,
        val providerOffset: Long? = null,
    ) : RuntimeUiEvent

    data class AssistantAttemptStarted(
        override val sessionId: String,
        override val runId: String,
        val attemptId: String,
        override val sequence: Long,
        override val revision: Long,
    ) : RuntimeUiEvent

    data class ApprovalRequested(
        override val sessionId: String,
        override val runId: String,
        override val sequence: Long,
        override val revision: Long,
        val proposalId: String,
        val payloadRef: String,
        val title: String,
        val platform: String? = null,
        val recipient: String? = null,
        val message: String? = null,
        val scheduleStartAtEpochMs: Long? = null,
        val scheduleDurationMinutes: Int? = null,
        val scheduleReminderMinutesBefore: Int? = null,
        val scheduleNote: String? = null,
        val details: String? = null,
        val candidateId: String? = null,
    ) : RuntimeUiEvent

    data class BudgetChanged(
        override val sessionId: String,
        override val runId: String,
        override val sequence: Long,
        override val revision: Long,
        val usedTokens: Int,
        val maxTokens: Int,
    ) : RuntimeUiEvent

    data class SourceAttached(
        override val sessionId: String,
        override val runId: String,
        override val sequence: Long,
        override val revision: Long,
        val sourceId: String,
        val label: String,
    ) : RuntimeUiEvent

    data class UnsupportedSchema(
        override val sessionId: String,
        override val runId: String?,
        override val sequence: Long,
        override val revision: Long,
        val producerVersion: Int,
    ) : RuntimeUiEvent

    data class ProjectionDegraded(
        override val sessionId: String,
        override val runId: String?,
        override val sequence: Long,
        override val revision: Long,
        val reasonCode: String,
    ) : RuntimeUiEvent

    data class UserOperationRequested(
        override val sessionId: String,
        override val runId: String,
        override val sequence: Long,
        override val revision: Long,
        val requestId: String,
        val type: String,
        val expiresAtEpochMs: Long,
    ) : RuntimeUiEvent

    data class UserOperationResolved(
        override val sessionId: String,
        override val runId: String,
        override val sequence: Long,
        override val revision: Long,
        val requestId: String,
        val state: PendingUserOperationState,
    ) : RuntimeUiEvent

    data class ChangeCommitted(
        override val sessionId: String,
        override val runId: String,
        override val sequence: Long,
        override val revision: Long,
        val changeId: String,
        val targetDomain: String,
        val targetId: String,
    ) : RuntimeUiEvent

    data class ChangeUndone(
        override val sessionId: String,
        override val runId: String,
        override val sequence: Long,
        override val revision: Long,
        val changeId: String,
    ) : RuntimeUiEvent
}

interface RuntimeUiClient {
    suspend fun dispatch(command: RuntimeUiCommand): CommandReceipt
    suspend fun getSessionProjection(sessionId: String): SessionProjection
    fun observeSession(sessionId: String, afterSequenceExclusive: Long): Flow<RuntimeUiEvent>

    /** See [RuntimeProjectionGateway.stagedCandidateContent]; resolved in-memory, never journaled. */
    suspend fun stagedCandidateContent(candidateId: String): String? = null
}
