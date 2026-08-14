package com.zhiban.rebuild.ui.agent

enum class DevicePermissionState { UNKNOWN, REQUESTABLE, DENIED, PERMANENTLY_DENIED, GRANTED }
enum class ProviderCapabilityState { PROBING, VERIFIED, EXPIRED, FAILED }
enum class AttachmentPhase { SELECTED, PREFLIGHTING, READY, UPLOADING, FINALIZING, COMPLETED, FAILED, CANCELLING, CANCELLED, URI_EXPIRED }
enum class AttachmentAction { CANCEL, RETRY, DELETE, RESELECT }
enum class TranscriptionPhase { IDLE, RECORDING, UPLOADING, TRANSCRIBING, FINAL, FAILED, CANCELLED }
enum class InputModality { IMAGE, VIDEO, AUDIO, FILE }

data class AttachmentUiState(
    val attachmentId: String,
    val displayName: String,
    val modality: InputModality,
    val sourceUri: String? = null,
    val phase: AttachmentPhase = AttachmentPhase.SELECTED,
    val bytesSent: Long = 0,
    val totalBytes: Long = 0,
    val retryable: Boolean = false,
    val retryAfterSeconds: Long? = null,
    val safeMessage: String? = null,
    val actions: Set<AttachmentAction> = defaultActions(phase, retryable),
) {
    val progress: Float get() = if (totalBytes <= 0) 0f else (bytesSent.toFloat() / totalBytes).coerceIn(0f, 1f)

    companion object {
        fun defaultActions(phase: AttachmentPhase, retryable: Boolean): Set<AttachmentAction> = when (phase) {
            AttachmentPhase.SELECTED, AttachmentPhase.READY -> setOf(AttachmentAction.DELETE)

            AttachmentPhase.PREFLIGHTING, AttachmentPhase.UPLOADING, AttachmentPhase.FINALIZING -> setOf(
                AttachmentAction.CANCEL,
            )

            AttachmentPhase.FAILED -> buildSet {
                if (retryable) add(AttachmentAction.RETRY)
                add(AttachmentAction.DELETE)
            }

            AttachmentPhase.CANCELLED, AttachmentPhase.COMPLETED -> setOf(AttachmentAction.DELETE)

            AttachmentPhase.CANCELLING -> emptySet()

            AttachmentPhase.URI_EXPIRED -> setOf(AttachmentAction.RESELECT, AttachmentAction.DELETE)
        }
    }
}

data class AttachmentBatchUiState(val items: List<AttachmentUiState> = emptyList()) {
    val bytesSent: Long get() = items.sumOf { it.bytesSent }
    val totalBytes: Long get() = items.sumOf { it.totalBytes }
    val progress: Float get() = if (totalBytes <= 0) 0f else (bytesSent.toFloat() / totalBytes).coerceIn(0f, 1f)
}

data class TranscriptionUiState(
    val phase: TranscriptionPhase = TranscriptionPhase.IDLE,
    val partialText: String = "",
    val finalText: String = "",
    val originalAudioRetained: Boolean = false,
    val safeCode: String? = null,
    val safeMessage: String? = null,
    val retryable: Boolean = true,
    /** Smoothed microphone amplitude in the inclusive 0f..1f range. */
    val inputLevel: Float = 0f,
) {
    val canConfirmFinal: Boolean get() = phase == TranscriptionPhase.FINAL && finalText.isNotBlank()
}

data class MultimodalUiState(
    val cameraPermission: DevicePermissionState = DevicePermissionState.UNKNOWN,
    val microphonePermission: DevicePermissionState = DevicePermissionState.UNKNOWN,
    val capability: Map<InputModality, ProviderCapabilityState> = InputModality.entries.associateWith {
        ProviderCapabilityState.PROBING
    },
    val attachments: AttachmentBatchUiState = AttachmentBatchUiState(),
    val transcription: TranscriptionUiState = TranscriptionUiState(),
) {
    fun isCaptureEnabled(modality: InputModality): Boolean = capability[modality] == ProviderCapabilityState.VERIFIED
}
