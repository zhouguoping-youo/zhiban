package com.zhiban.rebuild.data.agent

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

internal const val PLAN_STATUS_ACTIVE: String = "ACTIVE"
internal const val PLAN_STATUS_TERMINAL: String = "TERMINAL"
internal const val PLAN_STATUS_SUPERSEDED: String = "SUPERSEDED"

@Entity(
    tableName = "plan_versions",
    indices = [Index("schemaVersion"), Index("createdAtEpochMs")],
)
data class PlanVersionEntity(@PrimaryKey val versionId: String, val schemaVersion: Int, val createdAtEpochMs: Long, val note: String?)

@Entity(
    tableName = "plan_definitions",
    foreignKeys = [
        ForeignKey(
            entity = PlanVersionEntity::class,
            parentColumns = ["versionId"],
            childColumns = ["versionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("versionId"),
        Index("ownerNamespace"),
        Index("fingerprint", unique = true),
    ],
)
data class PlanDefinitionEntity(
    @PrimaryKey val definitionId: String,
    val versionId: String,
    val ownerNamespace: String,
    val fingerprint: String,
    val payloadJson: String,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "plan_nodes",
    foreignKeys = [
        ForeignKey(
            entity = PlanDefinitionEntity::class,
            parentColumns = ["definitionId"],
            childColumns = ["definitionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("definitionId"),
        Index(value = ["definitionId", "nodeKey"], unique = true),
        Index("nodeType"),
        Index("requiresApproval"),
    ],
)
data class PlanNodeEntity(
    @PrimaryKey val nodeId: String,
    val definitionId: String,
    val nodeKey: String,
    val nodeType: String,
    val payloadJson: String,
    val requiresApproval: Boolean,
    val sensitivity: String,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "plan_edges",
    foreignKeys = [
        ForeignKey(
            entity = PlanDefinitionEntity::class,
            parentColumns = ["definitionId"],
            childColumns = ["definitionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PlanNodeEntity::class,
            parentColumns = ["nodeId"],
            childColumns = ["fromNodeId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PlanNodeEntity::class,
            parentColumns = ["nodeId"],
            childColumns = ["toNodeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("definitionId"),
        Index("fromNodeId"),
        Index("toNodeId"),
        Index(value = ["definitionId", "fromNodeId", "toNodeId"], unique = true),
    ],
)
data class PlanEdgeEntity(
    @PrimaryKey val edgeId: String,
    val definitionId: String,
    val fromNodeId: String,
    val toNodeId: String,
    val condition: String?,
    val ordinal: Int,
)

@Entity(
    tableName = "plan_runs",
    foreignKeys = [
        ForeignKey(
            entity = PlanDefinitionEntity::class,
            parentColumns = ["definitionId"],
            childColumns = ["definitionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("definitionId"),
        Index("runStatus"),
        Index("activeAttemptId"),
    ],
)
data class PlanRunEntity(
    @PrimaryKey val runId: String,
    val definitionId: String,
    val runStatus: String,
    val activeAttemptId: String?,
    val startedAtEpochMs: Long,
    val completedAtEpochMs: Long?,
)

@Entity(
    tableName = "node_attempts",
    foreignKeys = [
        ForeignKey(
            entity = PlanRunEntity::class,
            parentColumns = ["runId"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PlanNodeEntity::class,
            parentColumns = ["nodeId"],
            childColumns = ["nodeId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("runId"),
        Index("nodeId"),
        Index("status"),
        Index(value = ["idempotencyKey"], unique = true),
        Index(value = ["runId", "nodeId", "ordinal"], unique = true),
    ],
)
data class NodeAttemptEntity(
    @PrimaryKey val attemptId: String,
    val runId: String,
    val nodeId: String,
    val ordinal: Int,
    val status: String,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long?,
    val idempotencyKey: String,
    val errorCode: String?,
)

@Entity(
    tableName = "approval_grants",
    foreignKeys = [
        ForeignKey(
            entity = NodeAttemptEntity::class,
            parentColumns = ["attemptId"],
            childColumns = ["attemptId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("attemptId"),
        Index("decision"),
        Index("decidedByUser"),
    ],
)
data class ApprovalGrantEntity(
    @PrimaryKey val grantId: String,
    val attemptId: String,
    val decision: String,
    val decidedByUser: String,
    val title: String,
    val impact: String,
    val actions: String,
    val verificationComponentKey: String,
    val decidedAtEpochMs: Long,
)

@Entity(
    tableName = "dispatch_outbox",
    foreignKeys = [
        ForeignKey(
            entity = NodeAttemptEntity::class,
            parentColumns = ["attemptId"],
            childColumns = ["attemptId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("attemptId"),
        Index("status"),
        Index(value = ["idempotencyKey"], unique = true),
        Index(value = ["attemptId", "ordinal"], unique = true),
    ],
)
data class DispatchOutboxEntity(
    @PrimaryKey val jobId: String,
    val attemptId: String,
    val toolName: String,
    val argumentsDigest: String,
    val idempotencyKey: String,
    val status: String,
    val ordinal: Int,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "result_ledger",
    foreignKeys = [
        ForeignKey(
            entity = NodeAttemptEntity::class,
            parentColumns = ["attemptId"],
            childColumns = ["attemptId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("attemptId"),
        Index("durabilityClass"),
        Index(value = ["attemptId", "ordinal"], unique = true),
    ],
)
data class ResultLedgerEntity(
    @PrimaryKey val resultId: String,
    val attemptId: String,
    val resultJson: String,
    val durabilityClass: String,
    val ordinal: Int,
    val recordedAtEpochMs: Long,
)

@Entity(
    tableName = "resource_leases",
    foreignKeys = [
        ForeignKey(
            entity = NodeAttemptEntity::class,
            parentColumns = ["attemptId"],
            childColumns = ["attemptId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("attemptId"),
        Index("resourceKey"),
        Index("ownerId"),
        Index(value = ["attemptId", "resourceKey"], unique = true),
    ],
)
data class ResourceLeaseEntity(
    @PrimaryKey val leaseId: String,
    val attemptId: String,
    val resourceKey: String,
    val epoch: Long,
    val ownerId: String,
    val expiresAtEpochMs: Long?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "session_leases",
    foreignKeys = [
        ForeignKey(
            entity = PlanRunEntity::class,
            parentColumns = ["runId"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("runId"),
        Index("sessionKey"),
        Index("ownerId"),
        Index(value = ["runId", "sessionKey"], unique = true),
    ],
)
data class SessionLeaseEntity(
    @PrimaryKey val leaseId: String,
    val runId: String,
    val sessionKey: String,
    val epoch: Long,
    val ownerId: String,
    val expiresAtEpochMs: Long?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)
