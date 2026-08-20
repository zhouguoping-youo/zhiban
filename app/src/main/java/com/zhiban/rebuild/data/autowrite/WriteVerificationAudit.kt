package com.zhiban.rebuild.data.autowrite

import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.agent.ToolAuditEntity
import com.zhiban.rebuild.foundation.sha256

/** Persists a fixed-code audit when a transactional write cannot be read back. */
internal suspend fun AgentDatabase.recordWriteVerificationFailure(
    toolName: String,
    targetId: String,
    idempotencyKey: String,
    reasonCode: String,
    nowEpochMs: Long,
) {
    val code = reasonCode.takeIf {
        it in setOf(
            "SCHEDULE_WRITE_VERIFY_FAILED",
            "CONTACT_WRITE_VERIFY_FAILED",
            "RELATIONSHIP_WRITE_VERIFY_FAILED",
        )
    } ?: "WRITE_VERIFY_FAILED"
    val auditKey = "write-verification-failure:$idempotencyKey"
    if (toolAuditDao().findByIdempotencyKey(auditKey) != null) return
    toolAuditDao().insert(
        ToolAuditEntity(
            id = "audit-${sha256(auditKey).take(32)}",
            runId = null,
            subjectRunDigest = sha256(targetId),
            toolCallId = "write-verification",
            toolName = toolName,
            idempotencyKey = auditKey,
            argumentsDigest = sha256(code),
            status = "FAILED_VERIFICATION",
            resultJson = "{\"code\":\"$code\"}",
            expiresAtEpochMs = null,
            createdAtEpochMs = nowEpochMs,
            updatedAtEpochMs = nowEpochMs,
        ),
    )
}
