package com.zhiban.rebuild.runtime.tool

import androidx.room.withTransaction
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.agent.ToolAuditEntity
import com.zhiban.rebuild.data.crm.CrmActionStatus
import com.zhiban.rebuild.data.crm.CrmActivityEntity
import com.zhiban.rebuild.data.crm.CrmLeadEntity
import com.zhiban.rebuild.data.crm.CrmLeadStatus
import com.zhiban.rebuild.data.crm.CrmNextActionEntity
import com.zhiban.rebuild.data.crm.CrmOpportunityEntity
import com.zhiban.rebuild.data.crm.CrmOpportunityStage
import com.zhiban.rebuild.data.crm.CrmRecordStatus
import com.zhiban.rebuild.data.crm.CrmStageHistoryEntity
import com.zhiban.rebuild.runtime.governance.AutoWriteAuditDraft
import com.zhiban.rebuild.runtime.governance.ChangeLogEntity
import com.zhiban.rebuild.runtime.governance.canonicalChangeDigest
import com.zhiban.rebuild.runtime.governance.insertVisibleAutoWrite
import com.zhiban.rebuild.runtime.runSuspendCatching
import com.zhiban.rebuild.runtime.spi.RuntimeRunStatus
import com.zhiban.rebuild.runtime.store.RoomRuntimeStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal data class CrmMutationResult(val targetId: String, val safeResultJson: String)

/** Atomic CRM domain writer for confirmation-gated runtime tools. */
internal class RoomCrmToolExecutor(private val database: AgentDatabase, private val store: RoomRuntimeStore) {
    suspend fun execute(plan: JsonObject, context: ConfirmedToolExecutionContext): CrmMutationResult = database.withTransaction {
        val resolved = validateConfirmedExecution(plan, context)
        val toolName = resolved.toolName
        val runId = resolved.runId
        val attemptId = resolved.attemptId

        val idempotencyKey = plan.requiredText("idempotencyKey")
        val digest = plan.requiredText("canonicalInputDigest")
        val idempotencyHit = findConfirmedIdempotencyHit(plan, toolName, runId, idempotencyKey, digest)
        if (idempotencyHit != null) return@withTransaction idempotencyHit
        check(resolved.runStatusName == RuntimeRunStatus.EXECUTING.name) { "CRM_WRITE_REQUIRES_CONFIRMATION" }

        val mutation = applyMutation(plan, context.nowEpochMs, idempotencyKey, auto = false)
        val changeId = changeIdFor(idempotencyKey)
        val safeResult = buildJsonObject {
            put("targetId", mutation.targetId)
            put("status", mutation.status)
            put("changeId", changeId)
            put("toolName", toolName)
            mutation.calendarCandidate?.let { candidate ->
                put("calendarCandidate", candidate)
                put("requiresSeparateCalendarConfirmation", true)
            }
        }.toString()
        insertConfirmedChangeLog(
            ConfirmedChangeLogInputs(
                changeId = changeId,
                runId = runId,
                toolName = toolName,
                idempotencyKey = idempotencyKey,
                mutation = mutation,
                afterDigest = digest,
                nowEpochMs = context.nowEpochMs,
            ),
        )
        insertConfirmedToolAudit(
            ConfirmedToolAuditInputs(
                idempotencyKey = idempotencyKey,
                runId = runId,
                attemptId = attemptId,
                plan = plan,
                toolName = toolName,
                digest = digest,
                safeResult = safeResult,
                nowEpochMs = context.nowEpochMs,
            ),
        )
        val execution = store.completeApprovedRemoteTool(
            runId = runId,
            providerCallId = plan.requiredText("providerCallId"),
            logicalStepId = plan.requiredText("logicalStepId"),
            toolName = toolName,
            toolSpecVersion = 1,
            canonicalInputDigest = digest,
            idempotencyKey = idempotencyKey,
            safeResultJson = safeResult,
            ownerId = context.ownerId,
            fencingEpoch = context.fencingEpoch,
            nowEpochMs = context.nowEpochMs,
        )
        CrmMutationResult(requireNotNull(execution.resultRef), safeResult)
    }

    private data class ConfirmedExecutionResolution(val toolName: String, val runId: String, val attemptId: String, val runStatusName: String)

    private suspend fun validateConfirmedExecution(plan: JsonObject, context: ConfirmedToolExecutionContext): ConfirmedExecutionResolution {
        val toolName = plan.requiredText("toolName")
        require(toolName in CrmMutationToolBinding.TOOL_NAMES)
        val runId = plan.requiredText("runId")
        require(runId == context.runId) { "CRM_RUN_MISMATCH" }
        val run = requireNotNull(database.runtimeRunDao().find(runId)) { "CRM_RUN_NOT_FOUND" }
        val attemptId = requireNotNull(run.activeAttemptId) { "CRM_ATTEMPT_MISSING" }
        require(attemptId == plan.requiredText("attemptId")) { "CRM_ATTEMPT_MISMATCH" }
        val session = requireNotNull(database.runtimeSessionDao().find(run.sessionId))
        check(
            session.leaseOwnerId == context.ownerId &&
                session.leaseEpoch == context.fencingEpoch &&
                (session.leaseExpiresAtEpochMs ?: Long.MIN_VALUE) > context.nowEpochMs,
        ) { "stale CRM tool writer" }
        requirePersistedApproval(plan, runId)
        return ConfirmedExecutionResolution(toolName, runId, attemptId, run.status)
    }

    private suspend fun findConfirmedIdempotencyHit(
        plan: JsonObject,
        toolName: String,
        runId: String,
        idempotencyKey: String,
        digest: String,
    ): CrmMutationResult? = database.runtimeToolExecutionDao().findByKey(idempotencyKey)?.let { existing ->
        if (existing.runId != runId || existing.toolName != toolName ||
            existing.canonicalInputDigest != digest ||
            existing.providerCallId != plan.requiredText("providerCallId")
        ) {
            throw ToolIdempotencyConflictException("CRM idempotency key payload conflict")
        }
        CrmMutationResult(
            requireNotNull(existing.resultRef),
            requireNotNull(existing.safeResultJson),
        )
    }

    private data class ConfirmedChangeLogInputs(
        val changeId: String,
        val runId: String,
        val toolName: String,
        val idempotencyKey: String,
        val mutation: AppliedCrmMutation,
        val afterDigest: String,
        val nowEpochMs: Long,
    )

    private suspend fun insertConfirmedChangeLog(inputs: ConfirmedChangeLogInputs) {
        val (changeId, runId, toolName, idempotencyKey, mutation, afterDigest, nowEpochMs) = inputs
        database.changeLogDao().insert(
            ChangeLogEntity(
                changeId = changeId,
                runtimeRunId = runId,
                toolName = toolName,
                idempotencyKey = idempotencyKey,
                targetDomain = "CRM",
                targetId = mutation.targetId,
                operation = mutation.operation,
                beforeDigest = mutation.beforeDigest,
                afterDigest = afterDigest,
                inversePayloadJson = mutation.inversePayloadJson,
                undoState = "UNAVAILABLE",
                createdAtEpochMs = nowEpochMs,
                undoneAtEpochMs = null,
            ),
        )
    }

    private data class ConfirmedToolAuditInputs(
        val idempotencyKey: String,
        val runId: String,
        val attemptId: String,
        val plan: JsonObject,
        val toolName: String,
        val digest: String,
        val safeResult: String,
        val nowEpochMs: Long,
    )

    private suspend fun insertConfirmedToolAudit(inputs: ConfirmedToolAuditInputs) {
        val (idempotencyKey, runId, attemptId, plan, toolName, digest, safeResult, nowEpochMs) = inputs
        database.toolAuditDao().insert(
            ToolAuditEntity(
                id = auditIdFor(idempotencyKey),
                runId = null,
                subjectRunDigest = sha256(runId),
                toolCallId = plan.requiredText("providerCallId"),
                toolName = toolName,
                idempotencyKey = idempotencyKey,
                argumentsDigest = digest,
                runtimeRunId = runId,
                runtimeAttemptId = attemptId,
                proposalId = plan.requiredText("proposalId"),
                payloadRefDigest = sha256(plan.requiredText("payloadRef")),
                approvalRevision = plan.requiredText("revision").toLong(),
                status = "SUCCEEDED",
                resultJson = safeResult,
                expiresAtEpochMs = null,
                createdAtEpochMs = nowEpochMs,
                updatedAtEpochMs = nowEpochMs,
            ),
        )
    }

    suspend fun executeAuto(plan: JsonObject, context: RuntimeToolRouteContext): CrmMutationResult = database.withTransaction {
        val toolName = plan.requiredText("toolName")
        require(toolName in CrmMutationToolBinding.AUTO_TOOL_NAMES) { "CRM_AUTO_TOOL_NOT_ALLOWED" }
        require(plan.requiredText("runId") == context.runId) { "CRM_RUN_MISMATCH" }
        val idempotencyKey = plan.requiredText("idempotencyKey")
        val digest = plan.requiredText("canonicalInputDigest")
        val idempotencyHit = findAutoIdempotencyHit(plan, context, toolName, digest)
        if (idempotencyHit != null) return@withTransaction idempotencyHit
        val mutation = applyMutation(plan, context.nowEpochMs, idempotencyKey, auto = true)
        val changeId = changeIdFor(idempotencyKey)
        val targetDigest = computeAutoTargetDigest(toolName, mutation)
        val (domain, subjectContactId, presentation, correction) = resolveAutoRouting(toolName, plan)
        recordAutoWriteAudit(
            AutoWriteAuditInputs(
                changeId = changeId,
                runId = context.runId,
                toolName = toolName,
                idempotencyKey = idempotencyKey,
                domain = domain,
                presentation = presentation,
                correction = correction,
                mutation = mutation,
                targetDigest = targetDigest,
                subjectContactId = subjectContactId,
                plan = plan,
                nowEpochMs = context.nowEpochMs,
            ),
        )
        val safeResult = buildJsonObject {
            put("targetId", mutation.targetId)
            put("status", mutation.status)
            put("changeId", changeId)
            put("toolName", toolName)
            put("undoAvailable", true)
            if (toolName == CrmMutationToolBinding.LEAD_CREATE) put("candidatePool", true)
        }.toString()
        store.completeReadOnlyTool(
            context.runId, plan.requiredText("providerCallId"), toolName, 1, digest, safeResult,
            context.ownerId, context.fencingEpoch, context.nowEpochMs,
        )
        CrmMutationResult(mutation.targetId, safeResult)
    }

    private suspend fun findAutoIdempotencyHit(plan: JsonObject, context: RuntimeToolRouteContext, toolName: String, digest: String): CrmMutationResult? {
        val executionKey = sha256("${context.runId}|${plan.requiredText("providerCallId")}|$toolName|$digest")
        return database.runtimeToolExecutionDao().findByKey(executionKey)?.let { existing ->
            if (existing.runId != context.runId || existing.toolName != toolName ||
                existing.canonicalInputDigest != digest ||
                existing.providerCallId != plan.requiredText("providerCallId")
            ) {
                throw ToolIdempotencyConflictException("CRM idempotency key payload conflict")
            }
            CrmMutationResult(
                requireNotNull(existing.resultRef),
                requireNotNull(existing.safeResultJson),
            )
        }
    }

    private suspend fun computeAutoTargetDigest(toolName: String, mutation: AppliedCrmMutation): String = when (toolName) {
        CrmMutationToolBinding.LEAD_CREATE -> canonicalChangeDigest(
            requireNotNull(database.crmDao().findLead(mutation.targetId)),
        )

        CrmMutationToolBinding.ACTIVITY_APPEND -> canonicalChangeDigest(
            requireNotNull(database.crmDao().findActivity(mutation.targetId)),
        )

        CrmMutationToolBinding.ACTION_CREATE -> canonicalChangeDigest(
            requireNotNull(database.crmDao().findAction(mutation.targetId)),
        )

        else -> error("CRM_AUTO_TOOL_NOT_ALLOWED")
    }

    private fun resolveAutoRouting(toolName: String, plan: JsonObject): List<String> = when (toolName) {
        CrmMutationToolBinding.LEAD_CREATE -> listOf(
            "CRM_LEAD",
            plan.requiredText("contactId"),
            "CRM_LEAD_CANDIDATE",
            "CRM_CANDIDATE_POOL",
        )

        CrmMutationToolBinding.ACTIVITY_APPEND -> listOf(
            "CRM_ACTIVITY",
            plan.requiredText("contactId"),
            "CRM_ACTIVITY",
            "CRM_OPPORTUNITY_DETAIL",
        )

        CrmMutationToolBinding.ACTION_CREATE -> listOf(
            "CRM_ACTION",
            plan.optionalText("contactId").orEmpty(),
            "CRM_NEXT_ACTION",
            "CRM_OPPORTUNITY_DETAIL",
        )

        else -> error("CRM_AUTO_TOOL_NOT_ALLOWED")
    }

    private data class AutoWriteAuditInputs(
        val changeId: String,
        val runId: String,
        val toolName: String,
        val idempotencyKey: String,
        val domain: String,
        val presentation: String,
        val correction: String,
        val mutation: AppliedCrmMutation,
        val targetDigest: String,
        val subjectContactId: String,
        val plan: JsonObject,
        val nowEpochMs: Long,
    )

    private suspend fun recordAutoWriteAudit(inputs: AutoWriteAuditInputs) {
        val (changeId, runId, toolName, idempotencyKey, domain, presentation, correction, mutation, targetDigest, subjectContactId, plan, nowEpochMs) = inputs
        database.insertVisibleAutoWrite(
            AutoWriteAuditDraft(
                changeId = changeId,
                runtimeRunId = runId,
                toolName = toolName,
                idempotencyKey = idempotencyKey,
                targetDomain = domain,
                targetId = mutation.targetId,
                operation = mutation.operation,
                afterDigest = targetDigest,
                inversePayloadJson = mutation.inversePayloadJson,
                originType = "RUNTIME_TOOL",
                subjectContactId = subjectContactId.ifBlank { null },
                sourceType = "AGENT_INFERENCE",
                sourceRef = plan.optionalText(
                    "sourceRef",
                ) ?: plan.optionalText("evidenceSummary") ?: idempotencyKey,
                confidence = plan["confidence"]?.jsonPrimitive?.content?.toDoubleOrNull(),
                presentationType = presentation,
                correctionRoute = correction,
                createdAtEpochMs = nowEpochMs,
            ),
        )
    }

    private suspend fun requirePersistedApproval(plan: JsonObject, runId: String) {
        val event = database.runtimeEventDao().latestByType(runId, "ApprovalRequested")
            ?: throw ToolPolicyRejectedException("CRM approval is missing")
        val approved = runSuspendCatching { Json.parseToJsonElement(event.payloadJson).jsonObject }.getOrNull()
            ?: throw ToolPolicyRejectedException("CRM approval is malformed")
        for (field in listOf(
            "toolName",
            "proposalId",
            "payloadRef",
            "revision",
            "canonicalInputDigest",
            "idempotencyKey",
        )) {
            if (approved[field]?.jsonPrimitive?.content != plan[field]?.jsonPrimitive?.content) {
                throw ToolPolicyRejectedException("CRM approval does not bind the tool call")
            }
        }
    }

    private suspend fun applyMutation(plan: JsonObject, now: Long, idempotencyKey: String, auto: Boolean): AppliedCrmMutation {
        val crm = database.crmDao()
        val toolName = plan.requiredText("toolName")
        fun id(prefix: String) = "$prefix-${sha256(idempotencyKey).take(24)}"
        fun recordStatus(stage: String) = when (stage) {
            CrmOpportunityStage.WON -> CrmRecordStatus.WON
            CrmOpportunityStage.LOST -> CrmRecordStatus.LOST
            else -> CrmRecordStatus.OPEN
        }
        suspend fun realContact(contactId: String) = requireNotNull(database.contactDao().findById(contactId)) {
            "CRM_CONTACT_NOT_FOUND"
        }.also {
            require(it.source != "CRM_DEMO" && !it.contactId.startsWith("crm-demo-")) { "CRM_CONTACT_MUST_BE_REAL" }
        }
        suspend fun opportunity(opportunityId: String) = requireNotNull(crm.findOpportunity(opportunityId)) {
            "CRM_OPPORTUNITY_NOT_FOUND"
        }.also { require(it.sourceType != "DEMO") { "CRM_OPPORTUNITY_MUST_BE_REAL" } }

        val ctx = MutationBranchContext(
            crm = crm,
            plan = plan,
            now = now,
            auto = auto,
            id = { prefix -> id(prefix) },
            recordStatus = { stage -> recordStatus(stage) },
            realContact = { contactId -> realContact(contactId) },
            opportunity = { opportunityId -> opportunity(opportunityId) },
        )

        return when (toolName) {
            CrmMutationToolBinding.LEAD_CREATE -> applyLeadCreate(ctx)
            CrmMutationToolBinding.OPPORTUNITY_CREATE -> applyOpportunityCreate(ctx)
            CrmMutationToolBinding.OPPORTUNITY_UPDATE -> applyOpportunityUpdate(ctx)
            CrmMutationToolBinding.OPPORTUNITY_STAGE -> applyOpportunityStage(ctx)
            CrmMutationToolBinding.ACTIVITY_APPEND -> applyActivityAppend(ctx)
            CrmMutationToolBinding.ACTION_CREATE -> applyActionCreate(ctx)
            CrmMutationToolBinding.ACTION_UPDATE -> applyActionUpdate(ctx)
            CrmMutationToolBinding.ACTION_COMPLETE -> applyActionComplete(ctx)
            else -> error("unsupported CRM mutation")
        }
    }

    private data class MutationBranchContext(
        val crm: com.zhiban.rebuild.data.crm.CrmDao,
        val plan: JsonObject,
        val now: Long,
        val auto: Boolean,
        val id: (String) -> String,
        val recordStatus: (String) -> String,
        val realContact: suspend (String) -> com.zhiban.rebuild.data.contact.ContactEntity,
        val opportunity: suspend (String) -> CrmOpportunityEntity,
    )

    private suspend fun applyLeadCreate(ctx: MutationBranchContext): AppliedCrmMutation = with(ctx) {
        val contact = realContact(plan.requiredText("contactId"))
        val targetId = id("lead")
        check(
            crm.insertLead(
                CrmLeadEntity(
                    leadId = targetId,
                    contactId = contact.contactId,
                    displayNameSnapshot = contact.displayName,
                    companyNameSnapshot = contact.company,
                    // This tool only stages a candidate. Confirmation authorizes the write, but it
                    // must not silently promote the lead into the formal funnel; promotion has its
                    // own explicit user action and audit trail.
                    status = CrmLeadStatus.CANDIDATE,
                    sourceType = if (auto) "AGENT_AUTO" else "AGENT_CONFIRMED",
                    sourceRef = plan.optionalText("sourceRef"),
                    fitSummary = plan.optionalText("fitSummary") ?: plan.requiredText("evidenceSummary"),
                    confidence = plan.requiredText("confidence").toDouble(),
                    userConfirmed = !auto,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                ),
            ) != -1L,
        )
        AppliedCrmMutation(targetId, "created", "CREATE", null, "{\"deleteLeadId\":\"$targetId\"}")
    }

    private suspend fun applyOpportunityCreate(ctx: MutationBranchContext): AppliedCrmMutation = with(ctx) {
        // Optional contact: validate only when present; the nullable FK column accepts NULL.
        val contact = plan.optionalText("primaryContactId")?.let { realContact(it) }
        val sourceLead = plan.optionalText("sourceLeadId")?.let { leadId ->
            requireNotNull(crm.findLead(leadId)) {
                "CRM_LEAD_NOT_FOUND"
            }.also {
                require(it.sourceType != "DEMO")
                require(it.status in CrmLeadStatus.convertibleStatuses) { "CRM_LEAD_ALREADY_CONVERTED" }
                require(crm.findOpportunityBySourceLead(leadId) == null) { "CRM_LEAD_ALREADY_CONVERTED" }
            }
        }
        val stage = plan.requiredText("stage")
        val targetId = id("opportunity")
        sourceLead?.let { check(crm.markLeadConverted(it.leadId, now) == 1) }
        crm.insertOpportunity(
            CrmOpportunityEntity(
                opportunityId = targetId,
                title = plan.requiredText("crmTitle"),
                accountNameSnapshot = plan.requiredText("accountName"),
                primaryContactId = contact?.contactId,
                sourceLeadId = plan.optionalText("sourceLeadId"),
                stage = stage,
                status = recordStatus(stage),
                valueMinor = plan["valueMinor"]?.jsonPrimitive?.longOrNull,
                currencyCode = plan.requiredText("currencyCode").uppercase(),
                probabilityPercent = CrmOpportunityStage.probabilityPercent(stage),
                expectedCloseAtEpochMs = plan["expectedCloseAtEpochMs"]?.jsonPrimitive?.longOrNull,
                productSummary = plan.optionalText("productSummary"),
                needSummary = plan.optionalText("needSummary"),
                lossReason = null,
                sourceType = if (auto) "AGENT_AUTO" else "AGENT_CONFIRMED",
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
            ),
        )
        crm.insertStageHistory(
            CrmStageHistoryEntity(
                id("stage-history"),
                targetId,
                null,
                stage,
                plan.optionalText("evidenceSummary"),
                "AGENT_CONFIRMED",
                true,
                now,
            ),
        )
        AppliedCrmMutation(targetId, "created", "CREATE", null, "{\"deleteOpportunityId\":\"$targetId\"}")
    }

    private suspend fun applyOpportunityUpdate(ctx: MutationBranchContext): AppliedCrmMutation = with(ctx) {
        val targetId = plan.requiredText("opportunityId")
        val existing = opportunity(targetId)
        plan.optionalText("primaryContactId")?.let { realContact(it) }
        val updated = existing.copy(
            title = plan.optionalText("crmTitle") ?: existing.title,
            accountNameSnapshot = plan.optionalText("accountName") ?: existing.accountNameSnapshot,
            primaryContactId = plan.optionalText("primaryContactId") ?: existing.primaryContactId,
            valueMinor = plan["valueMinor"]?.jsonPrimitive?.longOrNull ?: existing.valueMinor,
            currencyCode = plan.optionalText("currencyCode")?.uppercase() ?: existing.currencyCode,
            expectedCloseAtEpochMs =
                plan["expectedCloseAtEpochMs"]?.jsonPrimitive?.longOrNull ?: existing.expectedCloseAtEpochMs,
            productSummary = plan.optionalText("productSummary") ?: existing.productSummary,
            needSummary = plan.optionalText("needSummary") ?: existing.needSummary,
            updatedAtEpochMs = now,
        )
        check(crm.updateOpportunity(updated) == 1)
        AppliedCrmMutation(targetId, "updated", "UPDATE", canonicalChangeDigest(existing), "{}")
    }

    private suspend fun applyOpportunityStage(ctx: MutationBranchContext): AppliedCrmMutation = with(ctx) {
        val targetId = plan.requiredText("opportunityId")
        val existing = opportunity(targetId)
        val stage = plan.requiredText("stage")
        CrmOpportunityStage.requireTransitionAllowed(existing.stage, stage)
        check(
            crm.updateOpportunityStage(
                targetId,
                stage,
                recordStatus(stage),
                CrmOpportunityStage.probabilityPercent(stage),
                now,
            ) == 1,
        )
        crm.insertStageHistory(
            CrmStageHistoryEntity(
                id("stage-history"),
                targetId,
                existing.stage,
                stage,
                listOfNotNull(
                    plan.optionalText("reason"),
                    plan.optionalText("evidenceSummary"),
                ).joinToString("；"),
                "AGENT_CONFIRMED",
                true,
                now,
            ),
        )
        AppliedCrmMutation(
            targetId,
            "stage_changed",
            "UPDATE",
            sha256(existing.stage),
            "{\"stage\":\"${existing.stage}\"}",
        )
    }

    private suspend fun applyActivityAppend(ctx: MutationBranchContext): AppliedCrmMutation = with(ctx) {
        val opportunityId = plan.requiredText("opportunityId")
        opportunity(opportunityId)
        val contact = realContact(plan.requiredText("contactId"))
        val targetId = id("activity")
        crm.insertActivity(
            CrmActivityEntity(
                activityId = targetId,
                opportunityId = opportunityId,
                contactId = contact.contactId,
                activityType = plan.requiredText("activityType"),
                title = plan.requiredText("crmTitle"),
                summary = plan.requiredText("summary"),
                occurredAtEpochMs = plan.requiredText("occurredAtEpochMs").toLong(),
                sourceType = if (auto) "AGENT_AUTO" else "AGENT_CONFIRMED",
                sourceRef = plan.optionalText("sourceRef"),
                evidenceSummary = plan.requiredText("evidenceSummary"),
                userConfirmed = !auto,
                createdAtEpochMs = now,
            ),
        )
        AppliedCrmMutation(targetId, "appended", "CREATE", null, "{\"deleteActivityId\":\"$targetId\"}")
    }

    private suspend fun applyActionCreate(ctx: MutationBranchContext): AppliedCrmMutation = with(ctx) {
        val opportunityId = plan.requiredText("opportunityId")
        opportunity(opportunityId)
        plan.optionalText("contactId")?.let { realContact(it) }
        val targetId = id("next-action")
        crm.insertAction(
            CrmNextActionEntity(
                actionId = targetId,
                opportunityId = opportunityId,
                contactId = plan.optionalText("contactId"),
                actionType = plan.requiredText("actionType"),
                title = plan.requiredText("crmTitle"),
                dueAtEpochMs = plan["dueAtEpochMs"]?.jsonPrimitive?.longOrNull,
                status = CrmActionStatus.PENDING,
                priority = plan.requiredText("priority").toInt(),
                rationale = listOfNotNull(
                    plan.optionalText("rationale"),
                    plan.optionalText("evidenceSummary"),
                ).joinToString("；").ifBlank {
                    null
                },
                sourceType = if (auto) "AGENT_AUTO" else "AGENT_CONFIRMED",
                scheduleId = null,
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
            ),
        )
        AppliedCrmMutation(targetId, "created", "CREATE", null, "{\"deleteActionId\":\"$targetId\"}")
    }

    private suspend fun applyActionUpdate(ctx: MutationBranchContext): AppliedCrmMutation = with(ctx) {
        val targetId = plan.requiredText("actionId")
        val existing = requireNotNull(crm.findAction(targetId)) { "CRM_ACTION_NOT_FOUND" }
        opportunity(existing.opportunityId)
        plan.optionalText("contactId")?.let { realContact(it) }
        val updated = existing.copy(
            contactId = plan.optionalText("contactId") ?: existing.contactId,
            actionType = plan.optionalText("actionType") ?: existing.actionType,
            title = plan.optionalText("crmTitle") ?: existing.title,
            dueAtEpochMs = plan["dueAtEpochMs"]?.jsonPrimitive?.longOrNull ?: existing.dueAtEpochMs,
            priority = plan["priority"]?.jsonPrimitive?.longOrNull?.toInt() ?: existing.priority,
            rationale = listOfNotNull(
                plan.optionalText("rationale") ?: existing.rationale,
                plan.optionalText("evidenceSummary"),
            ).joinToString("；").ifBlank {
                null
            },
            updatedAtEpochMs = now,
        )
        check(crm.updateAction(updated) == 1)
        AppliedCrmMutation(targetId, "updated", "UPDATE", canonicalChangeDigest(existing), "{}")
    }

    private suspend fun applyActionComplete(ctx: MutationBranchContext): AppliedCrmMutation = with(ctx) {
        val targetId = plan.requiredText("actionId")
        val existing = requireNotNull(crm.findAction(targetId)) { "CRM_ACTION_NOT_FOUND" }
        opportunity(existing.opportunityId)
        check(
            crm.updateAction(
                existing.copy(
                    status = CrmActionStatus.COMPLETED,
                    rationale = listOfNotNull(
                        existing.rationale,
                        plan.optionalText("completionNote"),
                    ).joinToString("；").ifBlank {
                        null
                    },
                    updatedAtEpochMs = now,
                ),
            ) == 1,
        )
        val calendarCandidate = plan.optionalText("calendarTitle")?.let { title ->
            buildJsonObject {
                put("title", title)
                put("startAtEpochMs", plan.requiredText("calendarStartAtEpochMs").toLong())
                put("durationMinutes", plan.requiredText("calendarDurationMinutes").toInt())
                plan.optionalText("calendarNote")?.let { put("note", it) }
            }
        }
        AppliedCrmMutation(
            targetId,
            "completed",
            "UPDATE",
            canonicalChangeDigest(existing),
            "{\"status\":\"${existing.status}\"}",
            calendarCandidate,
        )
    }

    private data class AppliedCrmMutation(
        val targetId: String,
        val status: String,
        val operation: String,
        val beforeDigest: String?,
        val inversePayloadJson: String,
        val calendarCandidate: JsonObject? = null,
    )
}
