package com.zhiban.rebuild.data.agent

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "schedules",
    foreignKeys = [
        ForeignKey(
            entity = AgentRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["createdByRunId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(
            "createdByRunId",
        ), Index("createdByRuntimeRunId"), Index("createdByRuntimeAttemptId"), Index("startAtEpochMs"),
    ],
)
data class ScheduleEntity(
    @PrimaryKey val id: String,
    val title: String,
    val startAtEpochMs: Long,
    val durationMinutes: Int,
    val note: String?,
    val createdByRunId: String?,
    val createdByRuntimeRunId: String? = null,
    val createdByRuntimeAttemptId: String? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val reminderMinutesBefore: Int? = null,
)

data class ScheduleProjection(
    val id: String,
    val title: String,
    val startAtEpochMs: Long,
    val durationMinutes: Int,
    val note: String?,
    val reminderMinutesBefore: Int? = null,
)

@Entity(tableName = "agent_runs", indices = [Index("expiresAtEpochMs")])
data class AgentRunEntity(
    @PrimaryKey val id: String,
    val userInput: String?,
    val status: String,
    val pendingToolCallJson: String?,
    val schemaVersion: Int = 1,
    val expiresAtEpochMs: Long?,
    val errorCode: String?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "memories",
    foreignKeys = [
        ForeignKey(
            entity = AgentRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceRunId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sourceRunId"), Index("kind")],
)
data class MemoryEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val content: String,
    val sourceRunId: String?,
    val schemaVersion: Int = 1,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "tool_audits",
    foreignKeys = [
        ForeignKey(
            entity = AgentRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("runId"),
        Index("runtimeRunId"),
        Index("runtimeAttemptId"),
        Index(value = ["idempotencyKey"], unique = true),
        Index("expiresAtEpochMs"),
    ],
)
data class ToolAuditEntity(
    @PrimaryKey val id: String,
    val runId: String?,
    val subjectRunDigest: String,
    val toolCallId: String,
    val toolName: String,
    val idempotencyKey: String,
    val argumentsDigest: String,
    val runtimeRunId: String? = null,
    val runtimeAttemptId: String? = null,
    val proposalId: String? = null,
    val payloadRefDigest: String? = null,
    val approvalRevision: Long? = null,
    val schemaVersion: Int = 1,
    val status: String,
    val resultJson: String?,
    val expiresAtEpochMs: Long?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)
