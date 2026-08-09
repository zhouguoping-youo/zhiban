package com.zhiban.rebuild.runtime.input

import com.zhiban.rebuild.runtime.spi.PendingUserOperationState as RuntimePendingUserOperationState

typealias PendingUserOperationState = RuntimePendingUserOperationState

enum class InputKind { TEXT, IMAGE, AUDIO, VIDEO, FILE }
enum class UserOperationType { PHOTO_PICKER, SAF_OPEN_DOCUMENT, CAMERA_CAPTURE, MICROPHONE_RECORD }
enum class InputError { TOO_MANY_ITEMS, ITEM_TOO_LARGE, AGGREGATE_TOO_LARGE, DURATION_EXCEEDED, MIME_NOT_ALLOWED, MIME_MISMATCH, CAPABILITY_UNAVAILABLE }

sealed interface InputValidation {
    data object Accepted : InputValidation
    data class Rejected(val error: InputError) : InputValidation
}

data class InputLimits(
    val maxAttachmentItems: Int,
    val maxPerItemBytes: Long,
    val maxAggregateBytes: Long,
    val maxAudioDurationMs: Long,
    val allowedMimeTypes: Set<String>,
) {
    init {
        require(maxAttachmentItems > 0 && maxPerItemBytes > 0 && maxAggregateBytes >= maxPerItemBytes)
        require(maxAudioDurationMs > 0 && allowedMimeTypes.isNotEmpty())
    }
}

data class AttachmentInspection(
    val attachmentId: String,
    val declaredMimeType: String,
    val detectedMimeType: String,
    val byteLength: Long,
    val durationMs: Long?,
    val sha256Digest: String,
) {
    init {
        require(attachmentId.isNotBlank() && byteLength >= 0 && sha256Digest.isNotBlank())
    }
}

data class AttachmentRef(
    val attachmentId: String,
    val kind: InputKind,
    val mimeType: String,
    val byteLength: Long,
    val sha256Digest: String,
    val contentRef: String,
    val expiresAtEpochMs: Long,
)

data class InputEnvelope(val inputId: String, val textInputRef: String?, val attachments: List<AttachmentRef>)

data class PendingUserOperation(
    val requestId: String,
    val sessionId: String,
    val runId: String,
    val type: UserOperationType,
    val payloadRef: String,
    val expiresAtEpochMs: Long,
    val state: PendingUserOperationState = PendingUserOperationState.PENDING,
    val resultRef: String? = null,
)

sealed interface UserOperationResolution {
    data class Complete(val resultRef: String) : UserOperationResolution
    data object Cancel : UserOperationResolution
    data object Expire : UserOperationResolution
}

enum class UploadSessionState { PREPARED, UPLOADING, FINALIZED, ABORTED, FAILED }
data class UploadChunkReceipt(val offset: Long, val byteCount: Int, val sha256Digest: String)
data class UploadSession(
    val uploadSessionId: String,
    val attachmentId: String,
    val expectedBytes: Long,
    val expectedDigest: String,
    val state: UploadSessionState,
    val acceptedBytes: Long = 0,
    val acceptedChunkDigests: Map<Long, String> = emptyMap(),
)

enum class TranscriptionMode { BATCH, STREAM }
enum class TranscriptionSessionState { CREATED, RUNNING, FINAL_PENDING_CONFIRMATION, CONFIRMED, CANCELLED, FAILED }
data class TranscriptionSession(
    val transcriptionSessionId: String,
    val attachmentId: String,
    val mode: TranscriptionMode,
    val state: TranscriptionSessionState,
    val partials: Map<Long, String> = emptyMap(),
    val finalText: String? = null,
    val confirmedText: String? = null,
) {
    val canSubmitAsUserInput: Boolean get() = state == TranscriptionSessionState.CONFIRMED && confirmedText != null
}

data class InputProviderCapability(
    val supportedMimeTypes: Set<String>,
    val supportsUpload: Boolean,
    val supportsBatchTranscription: Boolean,
    val supportsStreamTranscription: Boolean,
)

interface AttachmentStagingGateway {
    suspend fun inspect(contentRef: String): AttachmentInspection
    suspend fun stage(sessionId: String, contentRef: String, expiresAtEpochMs: Long): AttachmentRef
    suspend fun discard(attachmentId: String)
}

interface ChunkUploadGateway {
    suspend fun prepare(attachment: AttachmentRef): UploadSession
    suspend fun uploadChunk(sessionId: String, offset: Long, bytes: ByteArray): UploadChunkReceipt
    suspend fun finalize(sessionId: String, expectedDigest: String): UploadSession
    suspend fun abort(sessionId: String)
}

interface TranscriptionGateway {
    suspend fun start(attachment: AttachmentRef, mode: TranscriptionMode): TranscriptionSession
    suspend fun cancel(transcriptionSessionId: String)
}
