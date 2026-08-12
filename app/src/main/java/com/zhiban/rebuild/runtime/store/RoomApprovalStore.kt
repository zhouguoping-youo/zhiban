package com.zhiban.rebuild.runtime.store

import androidx.room.withTransaction
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.agent.PLAN_STATUS_ACTIVE
import com.zhiban.rebuild.data.agent.PLAN_STATUS_TERMINAL
import com.zhiban.rebuild.data.agent.PlanDefinitionEntity
import com.zhiban.rebuild.data.agent.PlanEdgeEntity
import com.zhiban.rebuild.data.agent.PlanNodeEntity
import com.zhiban.rebuild.data.agent.PlanRunEntity
import com.zhiban.rebuild.data.agent.PlanVersionEntity
import com.zhiban.rebuild.data.agent.ScheduleEntity
import com.zhiban.rebuild.data.agent.ToolAuditEntity
import com.zhiban.rebuild.data.contact.StagedContactCandidateEntity
import com.zhiban.rebuild.runtime.context.FactEntity
import com.zhiban.rebuild.runtime.context.FactIndex
import com.zhiban.rebuild.runtime.context.StagedMemoryCandidateEntity
import com.zhiban.rebuild.runtime.governance.ChangeLogEntity
import com.zhiban.rebuild.runtime.governance.ChangeUndoCoordinator
import com.zhiban.rebuild.runtime.governance.ContactCreateCandidateCall
import com.zhiban.rebuild.runtime.governance.ContactIdentityResolutionCall
import com.zhiban.rebuild.runtime.governance.RelationshipCandidateCall
import com.zhiban.rebuild.runtime.kernel.RuntimeSignal
import com.zhiban.rebuild.runtime.kernel.RuntimeStateMachine
import com.zhiban.rebuild.runtime.memory.RoomMemoryGate
import com.zhiban.rebuild.runtime.runSuspendCatching
import com.zhiban.rebuild.runtime.spi.CommandReceipt
import com.zhiban.rebuild.runtime.spi.CommandReceiptStatus
import com.zhiban.rebuild.runtime.spi.RUNTIME_SCHEMA_VERSION
import com.zhiban.rebuild.runtime.spi.RuntimeAction
import com.zhiban.rebuild.runtime.spi.RuntimeCommandStatus
import com.zhiban.rebuild.runtime.spi.RuntimeRunStatus
import com.zhiban.rebuild.runtime.spi.RuntimeUiCommand
import com.zhiban.rebuild.runtime.tool.CalendarMutationToolBinding
import com.zhiban.rebuild.runtime.tool.MemoryRememberToolCall
import com.zhiban.rebuild.runtime.tool.ScheduleCreateToolCall
import com.zhiban.rebuild.runtime.tool.auditIdFor
import com.zhiban.rebuild.runtime.tool.changeIdFor
import com.zhiban.rebuild.runtime.tool.sha256
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class RoomApprovalStore(
    private val database: AgentDatabase,
    private val producerVersion: String,
    private val requireActiveLease: suspend (String, String, Long, Long) -> Unit,
    private val appendEventInTransaction: suspend (RuntimeEventDraft, Long) -> RuntimeEventEntity,
    private val scheduleJson: (ScheduleEntity) -> String,
    private val putScheduleFact: suspend (ScheduleEntity, String, Long) -> Unit,
    private val completeApprovedRemoteTool: suspend (
        String,
        String,
        String,
        String,
        Int,
        String,
        String,
        String,
        String,
        Long,
        Long,
    ) -> RuntimeToolExecutionEntity,
) {
    suspend fun requestScheduleApproval(
        call: ScheduleCreateToolCall,
        sessionId: String,
        runId: String,
        attemptId: String,
        ownerId: String,
        fencingEpoch: Long,
        nowEpochMs: Long,
    ): Boolean = database.withTransaction {
        requestToolApprovalInTransaction(
            scheduleApprovalPayload(call),
            call.providerCallId,
            sessionId,
            runId,
            attemptId,
            ownerId,
            fencingEpoch,
            nowEpochMs,
        )
    }

    suspend fun requestMemoryApproval(
        call: MemoryRememberToolCall,
        sessionId: String,
        runId: String,
        attemptId: String,
        ownerId: String,
        fencingEpoch: Long,
        nowEpochMs: Long,
    ): Boolean = database.withTransaction {
        val now = nowEpochMs
        database.stagedMemoryCandidateDao().insertIgnore(
            StagedMemoryCandidateEntity(
                call.candidateId, "GLOBAL", null, call.content, sha256(call.content), call.content.toByteArray().size,
                Json.encodeToString(listOf("runtime:$runId")), "PERSONAL", "PENDING", null, 0,
                now, now + 24 * 60 * 60 * 1_000L, now,
            ),
        )
        requestToolApprovalInTransaction(
            memoryApprovalPayload(call),
            call.providerCallId,
            sessionId,
            runId,
            attemptId,
            ownerId,
            fencingEpoch,
            nowEpochMs,
        )
    }

    suspend fun requestMemoryDeleteApproval(
        payloadJson: String,
        providerCallId: String,
        sessionId: String,
        runId: String,
        attemptId: String,
        ownerId: String,
        fencingEpoch: Long,
        nowEpochMs: Long,
    ): Boolean = database.withTransaction {
        require(payloadJson.toByteArray().size <= 16 * 1024) { "MEMORY_DELETE_PLAN_TOO_LARGE" }
        require(Json.parseToJsonElement(payloadJson).jsonObject["toolName"]?.jsonPrimitive?.content == "memory.delete")
        requestToolApprovalInTransaction(
            payloadJson,
            providerCallId,
            sessionId,
            runId,
            attemptId,
            ownerId,
            fencingEpoch,
            nowEpochMs,
        )
    }

    /** Tombstone, index invalidation, audit and runtime observation are one durable transaction. */
    suspend fun completeApprovedMemoryDelete(plan: JsonObject, ownerId: String, fencingEpoch: Long, nowEpochMs: Long): RuntimeToolExecutionEntity =
        database.withTransaction {
            fun text(name: String) = plan[name]?.jsonPrimitive?.content ?: error("MEMORY_DELETE_PLAN_INVALID")
            require(text("toolName") == "memory.delete")
            val runId = text("runId")
            val run = requireNotNull(database.runtimeRunDao().find(runId))
            requireActiveLease(run.sessionId, ownerId, fencingEpoch, nowEpochMs)
            val attemptId = requireNotNull(run.activeAttemptId)
            require(attemptId == text("attemptId"))
            check(run.status == RuntimeRunStatus.EXECUTING.name)
            val idempotencyKey = text("idempotencyKey")
            database.runtimeToolExecutionDao().findByKey(idempotencyKey)?.let { return@withTransaction it }
            val logicalMemoryId = text("logicalMemoryId")
            val digest = text("canonicalInputDigest")
            require(digest == sha256("${logicalMemoryId.toByteArray().size}:$logicalMemoryId"))
            val deletion = RoomMemoryGate(database) { nowEpochMs }.delete("runtime-global", logicalMemoryId, digest)
            val safeResult = buildJsonObject {
                put("logicalMemoryId", logicalMemoryId)
                put("status", "deleted")
                put("invalidationGeneration", deletion.generation)
            }.toString()
            database.toolAuditDao().insert(
                ToolAuditEntity(
                    id = auditIdFor(idempotencyKey), runId = null, subjectRunDigest = sha256(runId),
                    toolCallId = text("providerCallId"), toolName = "memory.delete", idempotencyKey = idempotencyKey,
                    argumentsDigest = digest, runtimeRunId = runId, runtimeAttemptId = attemptId,
                    proposalId = text("proposalId"), payloadRefDigest = sha256(text("payloadRef")),
                    approvalRevision = text("revision").toLong(), status = "SUCCEEDED", resultJson = safeResult,
                    expiresAtEpochMs = null, createdAtEpochMs = nowEpochMs, updatedAtEpochMs = nowEpochMs,
                ),
            )
            completeApprovedRemoteTool(
                runId, text("providerCallId"), text("logicalStepId"), "memory.delete", 1,
                digest, idempotencyKey, safeResult, ownerId, fencingEpoch, nowEpochMs,
            )
        }

    suspend fun requestContactApproval(
        call: ContactCreateCandidateCall,
        stagedPayloadJson: String,
        displayName: String,
        sessionId: String,
        runId: String,
        attemptId: String,
        ownerId: String,
        fencingEpoch: Long,
        nowEpochMs: Long,
    ): Boolean = database.withTransaction {
        database.stagedContactCandidateDao().purgeExpired(nowEpochMs)
        database.stagedContactCandidateDao().insertIgnore(
            StagedContactCandidateEntity(
                call.candidateId,
                stagedPayloadJson,
                call.canonicalInputDigest,
                "PENDING",
                nowEpochMs,
                nowEpochMs + 24 * 60 * 60 * 1_000L,
                nowEpochMs,
            ),
        )
        val payload = buildJsonObject {
            put("toolName", "contact.createCandidate")
            put("providerCallId", call.providerCallId)
            put("logicalStepId", call.logicalStepId)
            put("proposalId", call.proposalId)
            put("payloadRef", call.payloadRef)
            put("revision", call.revision)
            put("canonicalInputDigest", call.canonicalInputDigest)
            put("idempotencyKey", call.idempotencyKey)
            put("candidateId", call.candidateId)
            put("contactId", call.contactId)
            put("title", "创建联系人候选：${displayName.take(40)}")
        }.toString()
        requestToolApprovalInTransaction(
            payload,
            call.providerCallId,
            sessionId,
            runId,
            attemptId,
            ownerId,
            fencingEpoch,
            nowEpochMs,
        )
    }

    suspend fun requestContactProfileApproval(
        call: com.zhiban.rebuild.runtime.governance.ContactProfileCandidateCall,
        stagedPayloadJson: String,
        displayName: String,
        sessionId: String,
        runId: String,
        attemptId: String,
        ownerId: String,
        fencingEpoch: Long,
        nowEpochMs: Long,
    ): Boolean = database.withTransaction {
        database.stagedContactCandidateDao().purgeExpired(nowEpochMs)
        database.stagedContactCandidateDao().insertIgnore(
            com.zhiban.rebuild.data.contact.StagedContactCandidateEntity(
                call.candidateId,
                stagedPayloadJson,
                call.canonicalInputDigest,
                "PENDING",
                nowEpochMs,
                nowEpochMs + 24 * 60 * 60 * 1_000L,
                nowEpochMs,
            ),
        )
        val staged = Json.parseToJsonElement(stagedPayloadJson).jsonObject
        val fieldLabels = linkedMapOf(
            "phone" to "手机号",
            "email" to "邮箱",
            "wechatId" to "微信",
            "company" to "公司全称",
            "title" to "职位",
            "note" to "备注",
        )
        val preview = buildList {
            fieldLabels.forEach { (field, label) ->
                staged[field]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)?.let { add("$label：${it.take(200)}") }
            }
            staged["factText"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)?.let { add("联系人事实：${it.take(300)}") }
        }.joinToString("\n")
        val payload = buildJsonObject {
            put("toolName", com.zhiban.rebuild.runtime.governance.ContactProfileDomainWriter.TOOL_NAME)
            put("providerCallId", call.providerCallId)
            put("logicalStepId", call.logicalStepId)
            put("proposalId", call.proposalId)
            put("payloadRef", call.payloadRef)
            put("revision", call.revision)
            put("canonicalInputDigest", call.canonicalInputDigest)
            put("idempotencyKey", call.idempotencyKey)
            put("candidateId", call.candidateId)
            put("contactId", call.contactId)
            put("confidence", call.confidence)
            put(
                "title",
                if (call.contactId == com.zhiban.rebuild.data.contact.RelationshipPersonIds.SELF) {
                    "确认本人当前任职"
                } else {
                    "完善联系人档案：${displayName.take(40)}"
                },
            )
            put("details", preview)
        }.toString()
        requestToolApprovalInTransaction(
            payload,
            call.providerCallId,
            sessionId,
            runId,
            attemptId,
            ownerId,
            fencingEpoch,
            nowEpochMs,
        )
    }

    suspend fun requestContactIdentityResolutionApproval(
        call: ContactIdentityResolutionCall,
        visibleHandle: String,
        platform: String,
        contactName: String,
        sessionId: String,
        runId: String,
        attemptId: String,
        ownerId: String,
        fencingEpoch: Long,
        nowEpochMs: Long,
    ): Boolean = database.withTransaction {
        val payload = buildJsonObject {
            put("toolName", "contact.identity.resolve")
            put("providerCallId", call.providerCallId)
            put("logicalStepId", call.logicalStepId)
            put("proposalId", call.proposalId)
            put("payloadRef", call.payloadRef)
            put("revision", call.revision)
            put("canonicalInputDigest", call.canonicalInputDigest)
            put("idempotencyKey", call.idempotencyKey)
            put("sourceIdentityId", call.sourceIdentityId)
            put("contactId", call.contactId)
            put("evidenceDigest", call.evidenceDigest)
            put("confidence", call.confidence)
            put("previousStatus", call.previousStatus)
            put("previousConfidence", call.previousConfidence)
            put("title", "关联社交身份")
            put("details", "$platform · ${visibleHandle.take(80)}\n关联到：${contactName.take(80)}")
        }.toString()
        requestToolApprovalInTransaction(
            payload,
            call.providerCallId,
            sessionId,
            runId,
            attemptId,
            ownerId,
            fencingEpoch,
            nowEpochMs,
        )
    }

    suspend fun requestRelationshipApproval(
        call: RelationshipCandidateCall,
        sessionId: String,
        runId: String,
        attemptId: String,
        ownerId: String,
        fencingEpoch: Long,
        nowEpochMs: Long,
    ): Boolean = database.withTransaction {
        val payload = buildJsonObject {
            put("toolName", "relationship.createCandidate")
            put("providerCallId", call.providerCallId)
            put("logicalStepId", call.logicalStepId)
            put("proposalId", call.proposalId)
            put("payloadRef", call.payloadRef)
            put("revision", call.revision)
            put("canonicalInputDigest", call.canonicalInputDigest)
            put("idempotencyKey", call.idempotencyKey)
            put("edgeId", call.edgeId)
            put("fromContactId", call.fromContactId)
            put("toContactId", call.toContactId)
            put("relationType", call.relationType)
            put("evidenceDigest", call.evidenceDigest)
            put("confidence", call.confidence)
            put("temporalState", call.temporalState)
            call.skillId?.let { put("skillId", it) }
            put(
                "title",
                "确认${if (call.temporalState == "PAST") "过往" else "当前"}联系人关系：${call.relationType}",
            )
        }.toString()
        requestToolApprovalInTransaction(
            payload,
            call.providerCallId,
            sessionId,
            runId,
            attemptId,
            ownerId,
            fencingEpoch,
            nowEpochMs,
        )
    }

    /** Remote MCP calls are always confirmation-gated and use the same durable plan/event path. */
    suspend fun requestRemoteMcpApproval(
        payloadJson: String,
        providerCallId: String,
        sessionId: String,
        runId: String,
        attemptId: String,
        ownerId: String,
        fencingEpoch: Long,
        nowEpochMs: Long,
    ): Boolean = database.withTransaction {
        require(payloadJson.toByteArray().size <= 24 * 1024) { "MCP_PLAN_TOO_LARGE" }
        val payload = Json.parseToJsonElement(payloadJson).jsonObject
        require(payload["toolName"]?.jsonPrimitive?.content?.startsWith("mcp.") == true) { "MCP_PLAN_INVALID" }
        requestToolApprovalInTransaction(
            payloadJson,
            providerCallId,
            sessionId,
            runId,
            attemptId,
            ownerId,
            fencingEpoch,
            nowEpochMs,
        )
    }

    suspend fun requestCommunicationApproval(
        payloadJson: String,
        providerCallId: String,
        sessionId: String,
        runId: String,
        attemptId: String,
        ownerId: String,
        fencingEpoch: Long,
        nowEpochMs: Long,
    ): Boolean = database.withTransaction {
        require(payloadJson.toByteArray().size <= 16 * 1024) { "COMMUNICATION_PLAN_TOO_LARGE" }
        val payload = Json.parseToJsonElement(payloadJson).jsonObject
        require(payload["toolName"]?.jsonPrimitive?.content == "communication.message.compose") {
            "COMMUNICATION_PLAN_INVALID"
        }
        requestToolApprovalInTransaction(
            payloadJson,
            providerCallId,
            sessionId,
            runId,
            attemptId,
            ownerId,
            fencingEpoch,
            nowEpochMs,
        )
    }

    suspend fun requestCalendarMutationApproval(
        payloadJson: String,
        providerCallId: String,
        sessionId: String,
        runId: String,
        attemptId: String,
        ownerId: String,
        fencingEpoch: Long,
        nowEpochMs: Long,
    ): Boolean = database.withTransaction {
        require(payloadJson.toByteArray().size <= 16 * 1024) { "CALENDAR_PLAN_TOO_LARGE" }
        val tool = Json.parseToJsonElement(payloadJson).jsonObject["toolName"]?.jsonPrimitive?.content
        require(tool in setOf(CalendarMutationToolBinding.UPDATE, CalendarMutationToolBinding.DELETE))
        requestToolApprovalInTransaction(
            payloadJson,
            providerCallId,
            sessionId,
            runId,
            attemptId,
            ownerId,
            fencingEpoch,
            nowEpochMs,
        )
    }

    suspend fun requestCrmMutationApproval(
        payloadJson: String,
        providerCallId: String,
        sessionId: String,
        runId: String,
        attemptId: String,
        ownerId: String,
        fencingEpoch: Long,
        nowEpochMs: Long,
    ): Boolean = database.withTransaction {
        require(payloadJson.toByteArray().size <= 24 * 1024) { "CRM_PLAN_TOO_LARGE" }
        val tool = Json.parseToJsonElement(payloadJson).jsonObject["toolName"]?.jsonPrimitive?.content
        require(tool in CRM_MUTATION_TOOLS) { "CRM_PLAN_INVALID" }
        requestToolApprovalInTransaction(
            payloadJson,
            providerCallId,
            sessionId,
            runId,
            attemptId,
            ownerId,
            fencingEpoch,
            nowEpochMs,
        )
    }

    suspend fun requestContactTagApproval(
        payloadJson: String,
        providerCallId: String,
        sessionId: String,
        runId: String,
        attemptId: String,
        ownerId: String,
        fencingEpoch: Long,
        nowEpochMs: Long,
    ): Boolean = database.withTransaction {
        require(payloadJson.toByteArray().size <= 16 * 1024) { "CONTACT_TAG_PLAN_TOO_LARGE" }
        val tool = Json.parseToJsonElement(payloadJson).jsonObject["toolName"]?.jsonPrimitive?.content
        require(tool == "contact.tag.add") { "CONTACT_TAG_PLAN_INVALID" }
        requestToolApprovalInTransaction(
            payloadJson,
            providerCallId,
            sessionId,
            runId,
            attemptId,
            ownerId,
            fencingEpoch,
            nowEpochMs,
        )
    }

    /** Calendar mutation, audit, ChangeLog, Fact projection and Runtime observation commit atomically. */
    suspend fun completeApprovedCalendarMutation(plan: JsonObject, ownerId: String, fencingEpoch: Long, nowEpochMs: Long): RuntimeToolExecutionEntity =
        database.withTransaction {
            fun text(name: String) = plan[name]?.jsonPrimitive?.content ?: error("CALENDAR_PLAN_INVALID")
            val toolName = text("toolName")
            require(toolName in setOf(CalendarMutationToolBinding.UPDATE, CalendarMutationToolBinding.DELETE))
            val scheduleId = text("scheduleId")
            val runId = text("runId")
            val run = requireNotNull(database.runtimeRunDao().find(runId))
            requireActiveLease(run.sessionId, ownerId, fencingEpoch, nowEpochMs)
            val attemptId = requireNotNull(run.activeAttemptId)
            require(attemptId == text("attemptId"))
            val digest = text("canonicalInputDigest")
            val idempotencyKey = text("idempotencyKey")
            database.runtimeToolExecutionDao().findByKey(idempotencyKey)?.let { return@withTransaction it }
            check(run.status == RuntimeRunStatus.EXECUTING.name)
            val existing = requireNotNull(database.scheduleDao().findById(scheduleId)) { "CALENDAR_SCHEDULE_NOT_FOUND" }
            val oldJson = scheduleJson(existing)
            val safeResult: String
            val operation: String
            val undoPayload: String
            if (toolName == CalendarMutationToolBinding.UPDATE) {
                val start = text("startAtEpochMs").toLong()
                val duration = text("durationMinutes").toInt()
                val end = Math.addExact(start, duration * 60_000L)
                check(database.scheduleDao().findConflicts(start, end, scheduleId).isEmpty()) {
                    "CALENDAR_SCHEDULE_CONFLICT"
                }
                val updated = existing.copy(
                    title = text("title"),
                    startAtEpochMs = start,
                    durationMinutes = duration,
                    note = plan["note"]?.jsonPrimitive?.content,
                    updatedAtEpochMs = nowEpochMs,
                )
                check(database.scheduleDao().update(updated) == 1)
                putScheduleFact(updated, runId, nowEpochMs)
                operation = "UPDATE"
                undoPayload = oldJson
                safeResult =
                    buildJsonObject {
                        put("scheduleId", scheduleId)
                        put("status", "updated")
                        put("undoAvailable", true)
                    }.toString()
            } else {
                check(database.scheduleDao().deleteById(scheduleId) == 1)
                FactIndex(database).delete("schedule:$scheduleId")
                operation = "DELETE"
                undoPayload = oldJson
                safeResult =
                    buildJsonObject {
                        put("scheduleId", scheduleId)
                        put("status", "deleted")
                        put("undoAvailable", true)
                    }.toString()
            }
            val changeId = changeIdFor(idempotencyKey)
            database.changeLogDao().insert(
                ChangeLogEntity(
                    changeId, runId, toolName, idempotencyKey, "CALENDAR", scheduleId, operation,
                    sha256(oldJson), if (operation == "UPDATE") digest else null, undoPayload,
                    "AVAILABLE", nowEpochMs, null,
                ),
            )
            database.toolAuditDao().insert(
                ToolAuditEntity(
                    id = auditIdFor(idempotencyKey), runId = null, subjectRunDigest = sha256(runId),
                    toolCallId = text("providerCallId"), toolName = toolName, idempotencyKey = idempotencyKey,
                    argumentsDigest = digest, runtimeRunId = runId, runtimeAttemptId = attemptId,
                    proposalId = text("proposalId"), payloadRefDigest = sha256(text("payloadRef")),
                    approvalRevision = text("revision").toLong(), status = "SUCCEEDED", resultJson = safeResult,
                    expiresAtEpochMs = null, createdAtEpochMs = nowEpochMs, updatedAtEpochMs = nowEpochMs,
                ),
            )
            completeApprovedRemoteTool(
                runId, text("providerCallId"), text("logicalStepId"), toolName, 1, digest,
                idempotencyKey, safeResult, ownerId, fencingEpoch, nowEpochMs,
            )
        }

    private suspend fun requestToolApprovalInTransaction(
        payloadJson: String,
        providerCallId: String,
        sessionId: String,
        runId: String,
        attemptId: String,
        ownerId: String,
        fencingEpoch: Long,
        nowEpochMs: Long,
    ): Boolean {
        requireActiveLease(sessionId, ownerId, fencingEpoch, nowEpochMs)
        val run = requireNotNull(database.runtimeRunDao().find(runId))
        if (run.status !in setOf(RuntimeRunStatus.INFERENCING.name, RuntimeRunStatus.OBSERVING.name) ||
            run.activeAttemptId != attemptId
        ) {
            return false
        }
        val sourceStatus = run.status
        recordPlanNodeInTransaction(payloadJson, runId, attemptId, nowEpochMs)
        val proposed = appendEventInTransaction(
            RuntimeEventDraft(
                "event-plan-$attemptId-$providerCallId", "PlanProposed", sessionId, runId, attemptId,
                providerCallId, runId, payloadJson, nowEpochMs,
            ),
            fencingEpoch,
        )
        check(
            database.runtimeRunDao().transition(
                runId,
                sourceStatus,
                RuntimeRunStatus.VALIDATING_PLAN.name,
                proposed.sequence,
                nowEpochMs,
            ) ==
                1,
        )
        val requested = appendEventInTransaction(
            RuntimeEventDraft(
                "event-approval-$attemptId-$providerCallId", "ApprovalRequested", sessionId, runId, attemptId,
                providerCallId, runId, payloadJson, nowEpochMs,
            ),
            fencingEpoch,
        )
        check(
            database.runtimeRunDao().transition(
                runId,
                RuntimeRunStatus.VALIDATING_PLAN.name,
                RuntimeRunStatus.AWAITING_CONFIRMATION.name,
                requested.sequence,
                nowEpochMs,
            ) ==
                1,
        )
        // The confirmation card should show what is about to be remembered. The content never enters
        // the durable event journal (MemoryRememberPlanValidator forbids it); the snapshot is a single
        // overwritten row, so surfacing the staged content here — only while awaiting confirmation —
        // is transient. After the decision the run leaves AWAITING_CONFIRMATION and this stops applying.
        val candidateId = runSuspendCatching {
            Json.parseToJsonElement(payloadJson).jsonObject["candidateId"]?.jsonPrimitive?.content
        }.getOrNull()
        val stagedContent = candidateId?.let { database.stagedMemoryCandidateDao().find(it) }?.content
        val envelope =
            encodeProjectionEnvelope(
                buildJsonObject {
                    put("runId", runId)
                    put("status", RuntimeRunStatus.AWAITING_CONFIRMATION.name)
                    stagedContent?.let { put("details", it.take(MAX_APPROVAL_DETAILS_CHARS)) }
                }.toString(),
            )
        val inserted =
            database.runtimeProjectionDao().insert(
                RuntimeProjectionEntity("ui", sessionId, requested.sequence, envelope, nowEpochMs),
            ) !=
                -1L
        check(
            inserted ||
                database.runtimeProjectionDao().advance("ui", sessionId, requested.sequence, envelope, nowEpochMs) == 1,
        )
        return true
    }

    suspend fun pendingToolPlan(runId: String): String? = database.runtimeEventDao().latestByType(runId, "ApprovalRequested")?.payloadJson

    suspend fun stagedMemoryContent(candidateId: String, nowEpochMs: Long): String? = database.withTransaction {
        database.stagedMemoryCandidateDao().purgeExpired(nowEpochMs)
        database.stagedMemoryCandidateDao().find(candidateId)?.takeIf {
            it.state in setOf("PENDING", "APPROVED")
        }?.content
    }

    suspend fun latestToolExecution(runId: String): RuntimeToolExecutionEntity? = database.runtimeToolExecutionDao().listByRunId(runId).lastOrNull()

    suspend fun completedToolNames(runId: String): Set<String> = database.runtimeToolExecutionDao().listByRunId(runId)
        .asSequence()
        .filter { it.status == "SUCCEEDED" }
        .map { it.toolName }
        .toSet()

    suspend fun toolProposalCount(runId: String, toolName: String): Int = maxOf(
        database.runtimeToolExecutionDao().countByRunAndTool(runId, toolName),
        database.runtimeEventDao().listByRunId(runId).count { event ->
            event.eventType == "ApprovalRequested" && runSuspendCatching {
                Json.parseToJsonElement(event.payloadJson).jsonObject["toolName"]?.jsonPrimitive?.content == toolName
            }.getOrDefault(false)
        },
    )

    suspend fun totalToolInvocationCount(runId: String): Int {
        val executions = database.runtimeToolExecutionDao().countByRun(runId)
        val pendingApprovals = database.runtimeEventDao().listByRunId(runId).count { event ->
            event.eventType == "ApprovalRequested"
        }
        return executions + pendingApprovals
    }

    suspend fun recentFeedback(sessionId: String, limit: Int = 8): List<String> = database.runtimeEventDao().listAfter(sessionId, 0).asReversed()
        .filter { it.eventType == "UserFeedbackRecorded" }.take(limit).asReversed()
        .mapNotNull {
            runSuspendCatching {
                Json.parseToJsonElement(it.payloadJson).jsonObject["rating"]?.jsonPrimitive?.content
            }.getOrNull()
        }

    suspend fun recentConversation(sessionId: String, excludeRunId: String, limit: Int = 12): List<RuntimeConversationTurnEntity> =
        database.runtimeConversationTurnDao().recent(sessionId, excludeRunId, limit)

    suspend fun conversationContext(sessionId: String, excludeRunId: String, recentLimit: Int = 12, scanLimit: Int = 80): SessionConversationContext =
        database.withTransaction {
            require(recentLimit in 1 until scanLimit)
            val now = System.currentTimeMillis()
            database.runtimeSessionWorkspaceDao().insert(
                RuntimeSessionWorkspaceEntity(
                    sessionId = sessionId,
                    directoryName = "session-${sha256(sessionId).take(32)}",
                    state = "ACTIVE",
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                ),
            )
            val workspace = requireNotNull(database.runtimeSessionWorkspaceDao().find(sessionId))
            val window = database.runtimeConversationTurnDao().recent(sessionId, excludeRunId, scanLimit)
            val older = window.dropLast(recentLimit)
                .filter { it.createdAtEpochMs > (workspace.summaryThroughTurnAtEpochMs ?: Long.MIN_VALUE) }
            var summary = workspace.summaryText
            if (older.isNotEmpty()) {
                val additions = older.joinToString("\n") { turn ->
                    val role = if (turn.role == "user") "用户" else "知伴"
                    "$role：${userFacingConversationText(turn.content).take(500)}"
                }
                summary = listOfNotNull(summary?.takeIf(String::isNotBlank), additions)
                    .joinToString("\n")
                    .takeLast(MAX_SESSION_SUMMARY_CHARS)
                check(
                    database.runtimeSessionWorkspaceDao().updateSummary(
                        sessionId,
                        summary,
                        older.maxOf { it.createdAtEpochMs },
                        now,
                    ) == 1,
                )
            }
            SessionConversationContext(summary, window.takeLast(recentLimit))
        }

    suspend fun saveAssistantTurn(sessionId: String, runId: String, content: String, nowEpochMs: Long) {
        if (content.isBlank()) return
        database.runtimeConversationTurnDao().insert(
            RuntimeConversationTurnEntity(
                "turn-$runId-assistant",
                sessionId,
                runId,
                "assistant",
                content,
                sha256(content),
                estimateTurnTokens(content),
                nowEpochMs,
            ),
        )
    }

    private fun scheduleApprovalPayload(call: ScheduleCreateToolCall): String = buildJsonObject {
        put("toolName", "calendar.schedule.create")
        put("providerCallId", call.providerCallId)
        put("logicalStepId", call.logicalStepId)
        put("proposalId", call.proposalId)
        put("payloadRef", call.payloadRef)
        put("revision", call.revision)
        put("canonicalInputDigest", call.canonicalInputDigest)
        put("idempotencyKey", call.idempotencyKey)
        put("scheduleId", call.scheduleId)
        put("title", call.title)
        put("startAtEpochMs", call.startAtEpochMs)
        put("durationMinutes", call.durationMinutes)
        call.note?.let { put("note", it) }
        call.reminderMinutesBefore?.let { put("reminderMinutesBefore", it) }
    }.toString()

    private companion object {
        const val MAX_SESSION_SUMMARY_CHARS = 6_000

        // The confirmation-card body lives only in the single-row snapshot (never the event journal);
        // keep it bounded.
        const val MAX_APPROVAL_DETAILS_CHARS = 500
        val CRM_MUTATION_TOOLS = setOf(
            "crm.lead.createCandidate",
            "crm.opportunity.create",
            "crm.opportunity.update",
            "crm.opportunity.changeStage",
            "crm.activity.append",
            "crm.nextAction.create",
            "crm.nextAction.update",
            "crm.nextAction.complete",
        )
    }

    private fun memoryApprovalPayload(call: MemoryRememberToolCall): String = buildJsonObject {
        put("toolName", "memory.remember")
        put("providerCallId", call.providerCallId)
        put("logicalStepId", call.logicalStepId)
        put("proposalId", call.proposalId)
        put("payloadRef", call.payloadRef)
        put("revision", call.revision)
        put("canonicalInputDigest", call.canonicalInputDigest)
        put("idempotencyKey", call.idempotencyKey)
        put("candidateId", call.candidateId)
        put("memoryType", call.memoryType)
        put("subjectKey", call.subjectKey)
        put("predicateKey", call.predicateKey)
        put("title", "保存一条${call.memoryType.lowercase()}记忆")
    }.toString()

    private suspend fun recordPlanNodeInTransaction(payloadJson: String, runId: String, attemptId: String, nowEpochMs: Long) {
        val payload = Json.parseToJsonElement(payloadJson).jsonObject
        val providerCallId = payload.getValue("providerCallId").jsonPrimitive.content
        val toolName = payload.getValue("toolName").jsonPrimitive.content
        val definitionId = "runtime-plan-$runId"
        val versionId = "runtime-plan-schema-v1"
        val dao = database.planDao()
        dao.insertVersionIgnore(PlanVersionEntity(versionId, 1, nowEpochMs, "Runtime v2 generated plan"))
        dao.insertDefinitionIgnore(
            PlanDefinitionEntity(
                definitionId,
                versionId,
                "runtime",
                sha256("definition:$runId"),
                "{\"runtimeRunId\":\"$runId\"}",
                nowEpochMs,
            ),
        )
        val previous = dao.nodesForDefinition(definitionId).lastOrNull()
        val nodeId = "node-${sha256("$runId:$providerCallId").take(24)}"
        val nodePayload = buildJsonObject {
            put("tool", toolName)
            put("version", "1")
            put("providerCallId", providerCallId)
            put("approvalPayloadDigest", sha256(payloadJson))
        }.toString()
        dao.insertNodeIgnore(
            PlanNodeEntity(
                nodeId,
                definitionId,
                payload["logicalStepId"]?.jsonPrimitive?.content ?: providerCallId,
                "TOOL",
                nodePayload,
                true,
                "runtime",
                nowEpochMs,
            ),
        )
        if (previous != null && previous.nodeId != nodeId) {
            val ordinal = dao.nodesForDefinition(definitionId).size - 1
            dao.insertEdgeIgnore(
                PlanEdgeEntity(
                    "edge-${sha256("${previous.nodeId}:$nodeId").take(24)}",
                    definitionId,
                    previous.nodeId,
                    nodeId,
                    null,
                    ordinal,
                ),
            )
        }
        dao.insertRunIgnore(PlanRunEntity(runId, definitionId, PLAN_STATUS_ACTIVE, attemptId, nowEpochMs, null))
    }

    private fun estimateTurnTokens(value: String): Int = (value.toByteArray().size / 4 + 1).coerceAtLeast(1)

    private fun encodeProjectionEnvelope(payloadJson: String): String = buildJsonObject {
        put("snapshotSchemaVersion", RUNTIME_SCHEMA_VERSION)
        put("snapshotProducerVersion", producerVersion)
        put("payloadJson", payloadJson)
    }.toString()
}
