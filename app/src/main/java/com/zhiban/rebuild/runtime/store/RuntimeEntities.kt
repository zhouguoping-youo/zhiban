package com.zhiban.rebuild.runtime.store

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "runtime_sessions")
data class RuntimeSessionEntity(
    @PrimaryKey val sessionId: String,
    val nextSequence: Long = 1,
    val leaseOwnerId: String? = null,
    val leaseEpoch: Long = 0,
    val leaseExpiresAtEpochMs: Long? = null,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "runtime_runs",
    foreignKeys = [
        ForeignKey(
            entity = RuntimeSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId"), Index("status")],
)
data class RuntimeRunEntity(
    @PrimaryKey val runId: String,
    val sessionId: String,
    val schemaVersion: Int,
    val status: String,
    val activeAttemptId: String? = null,
    val budgetJson: String,
    val recoveryCursor: Long = 0,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "runtime_attempts",
    foreignKeys = [
        ForeignKey(
            entity = RuntimeRunEntity::class,
            parentColumns = ["runId"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("runId"), Index(value = ["runId", "ordinal"], unique = true)],
)
data class RuntimeAttemptEntity(
    @PrimaryKey val attemptId: String,
    val runId: String,
    val ordinal: Int,
    val status: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "runtime_command_inbox",
    foreignKeys = [
        ForeignKey(
            entity = RuntimeSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = RuntimeRunEntity::class,
            parentColumns = ["runId"],
            childColumns = ["runId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("sessionId"), Index("runId"), Index("status")],
)
data class RuntimeCommandInboxEntity(
    @PrimaryKey val commandId: String,
    val schemaVersion: Int,
    val commandType: String,
    val sessionId: String,
    val runId: String?,
    val correlationId: String,
    val payloadJson: String,
    val status: String,
    val receiptJson: String? = null,
    val resultJson: String? = null,
    val claimedBy: String? = null,
    val claimedLeaseEpoch: Long? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "runtime_events",
    foreignKeys = [
        ForeignKey(
            entity = RuntimeSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = RuntimeRunEntity::class,
            parentColumns = ["runId"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = RuntimeAttemptEntity::class,
            parentColumns = ["attemptId"],
            childColumns = ["attemptId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["sessionId", "sequence"], unique = true),
        Index("runId"), Index("attemptId"), Index("correlationId"), Index("causationId"),
    ],
)
data class RuntimeEventEntity(
    @PrimaryKey val eventId: String,
    val schemaVersion: Int,
    val eventType: String,
    val sessionId: String,
    val runId: String,
    val attemptId: String?,
    val sequence: Long,
    val causationId: String? = null,
    val correlationId: String,
    val producerVersion: String,
    val payloadJson: String,
    val createdAtEpochMs: Long,
    val fencingEpoch: Long,
)

@Entity(
    tableName = "runtime_conversation_turns",
    foreignKeys = [
        ForeignKey(
            entity = RuntimeSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = RuntimeRunEntity::class,
            parentColumns = ["runId"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId"), Index("runId"), Index(value = ["runId", "role"], unique = true)],
)
data class RuntimeConversationTurnEntity(
    @PrimaryKey val turnId: String,
    val sessionId: String,
    val runId: String,
    val role: String,
    val content: String,
    val contentDigest: String,
    val tokenEstimate: Int,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "runtime_tool_executions",
    foreignKeys = [
        ForeignKey(
            entity = RuntimeRunEntity::class,
            parentColumns = ["runId"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            "runId",
        ), Index("attemptId"), Index("logicalStepId"), Index(value = ["idempotencyKey"], unique = true),
    ],
)
data class RuntimeToolExecutionEntity(
    @PrimaryKey val executionId: String,
    val runId: String,
    val logicalStepId: String,
    val toolName: String,
    val toolSpecVersion: Int,
    val canonicalInputDigest: String,
    val idempotencyKey: String,
    val providerCallId: String? = null,
    val proposalId: String? = null,
    val payloadRefDigest: String? = null,
    val approvalRevision: Long? = null,
    val attemptId: String? = null,
    val status: String,
    val resultRef: String? = null,
    val safeResultJson: String? = null,
    val fencingEpoch: Long,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "runtime_projections",
    primaryKeys = ["projectionName", "sessionId"],
    foreignKeys = [
        ForeignKey(
            entity = RuntimeSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class RuntimeProjectionEntity(
    val projectionName: String,
    val sessionId: String,
    val consumedSequence: Long,
    val snapshotJson: String,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "runtime_input_staging",
    indices = [Index("expiresAtEpochMs")],
)
data class RuntimeInputStagingEntity(
    @PrimaryKey val inputRef: String,
    val rawText: String,
    val utf8Length: Int,
    val sha256Digest: String,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long,
)

@Entity(
    tableName = "runtime_approval_staging",
    foreignKeys = [
        ForeignKey(
            entity = RuntimeRunEntity::class,
            parentColumns = ["runId"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["runId"], unique = true), Index("expiresAtEpochMs")],
)
data class RuntimeApprovalStagingEntity(
    @PrimaryKey val stagedRef: String,
    val runId: String,
    val payloadJson: String,
    val payloadDigest: String,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long,
)

@Entity(
    tableName = "runtime_run_inputs",
    foreignKeys = [
        ForeignKey(
            entity = RuntimeRunEntity::class,
            parentColumns = ["runId"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["runId"], unique = true), Index("expiresAtEpochMs")],
)
data class RuntimeRunInputEntity(
    @PrimaryKey val inputRef: String,
    val runId: String,
    val rawText: String,
    val utf8Length: Int,
    val sha256Digest: String,
    val expiresAtEpochMs: Long,
)

@Entity(
    tableName = "runtime_session_workspaces",
    foreignKeys = [
        ForeignKey(
            entity = RuntimeSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("updatedAtEpochMs")],
)
data class RuntimeSessionWorkspaceEntity(
    @PrimaryKey val sessionId: String,
    val directoryName: String,
    val state: String,
    val summaryText: String? = null,
    val summaryThroughTurnAtEpochMs: Long? = null,
    val totalArtifactBytes: Long = 0,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "runtime_artifacts",
    foreignKeys = [
        ForeignKey(
            entity = RuntimeSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = RuntimeRunEntity::class,
            parentColumns = ["runId"],
            childColumns = ["runId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("sessionId"),
        Index("runId"),
        Index("status"),
        Index(value = ["sessionId", "sha256Digest"]),
    ],
)
data class RuntimeArtifactEntity(
    @PrimaryKey val artifactId: String,
    val sessionId: String,
    val runId: String?,
    val kind: String,
    val displayName: String,
    val mimeType: String,
    val relativePath: String,
    val byteLength: Long,
    val sha256Digest: String,
    val status: String,
    val provenance: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)
