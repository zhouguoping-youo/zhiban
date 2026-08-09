package com.zhiban.rebuild.runtime.tool

import com.zhiban.rebuild.data.contact.ContactDao
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.crm.CrmDao
import com.zhiban.rebuild.runtime.governance.ReversibleWriteReadiness
import com.zhiban.rebuild.runtime.provider.ProviderFailure
import com.zhiban.rebuild.runtime.store.RoomRuntimeStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal class CrmMutationToolBinding(
    override val spec: RuntimeToolSpec,
    private val store: RoomRuntimeStore,
    private val crm: CrmDao,
    private val contacts: ContactDao,
    private val executor: RoomCrmToolExecutor,
) : ReversibleAutoWriteBinding {
    init {
        require(spec.name in TOOL_NAMES)
        val expectedRisk = if (spec.name in AUTO_TOOL_NAMES) {
            RuntimeToolRisk.REVERSIBLE_AUTO_WRITE
        } else {
            RuntimeToolRisk.WRITE_CONFIRMATION_REQUIRED
        }
        require(spec.risk == expectedRisk)
    }

    override suspend fun requestApproval(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): Boolean {
        val plan = buildPlan(request, context)
        return store.requestCrmMutationApproval(
            plan,
            request.providerCallId,
            context.sessionId,
            context.runId,
            context.attemptId,
            context.ownerId,
            context.fencingEpoch,
            context.nowEpochMs,
        )
    }

    override suspend fun executeApproved(planJson: String, context: ConfirmedToolExecutionContext): RoutedToolResult {
        val plan = parseAndValidateCrmPlan(planJson, spec.name)
        val result = executor.execute(plan, context)
        return RoutedToolResult(spec.name, plan.requiredText("providerCallId"), result.safeResultJson)
    }

    override suspend fun reversibleWriteReadiness(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): ReversibleWriteReadiness {
        if (spec.name !in AUTO_TOOL_NAMES) return ReversibleWriteReadiness.Unavailable
        val plan = Json.parseToJsonElement(buildPlan(request, context)).jsonObject
        val rejection = when (spec.name) {
            LEAD_CREATE -> if (
                plan.requiredText("confidence").toDouble() < 0.99 || plan.optionalText("sourceRef") == null
            ) {
                "auto_write:evidence_insufficient"
            } else {
                null
            }

            ACTIVITY_APPEND -> if (
                plan.optionalText("sourceRef") == null ||
                plan.requiredText("occurredAtEpochMs").toLong() > context.nowEpochMs + 5 * 60_000L
            ) {
                "auto_write:evidence_insufficient"
            } else {
                null
            }

            ACTION_CREATE -> if (
                plan.optionalText("evidenceSummary") == null ||
                (plan["dueAtEpochMs"]?.jsonPrimitive?.longOrNull?.let { it <= context.nowEpochMs } == true)
            ) {
                "auto_write:evidence_insufficient"
            } else {
                null
            }

            else -> "auto_write:policy_rejected"
        }
        return ReversibleWriteReadiness(true, true, true, rejection)
    }

    override suspend fun executeReversibleAutoWrite(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): RoutedToolResult {
        val plan = Json.parseToJsonElement(buildPlan(request, context)).jsonObject
        val result = executor.executeAuto(plan, context)
        return RoutedToolResult(spec.name, request.providerCallId, result.safeResultJson)
    }

    private suspend fun buildPlan(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): String {
        val raw = parseToolArgs(request.argumentsJson, null) { throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false) }
        val data = normalizeArguments(spec.name, raw)
        val contextLabel = validateDomainReferences(spec.name, data)
        val digest = sha256(data.toString())
        val envelope = PlanEnvelopeFactory.create(request, context, spec.name, digest, payloadPrefix = "crm-plan")
        return buildJsonObject {
            put("toolName", spec.name)
            put("providerCallId", request.providerCallId)
            put("logicalStepId", "step-${request.providerCallId}")
            put("proposalId", envelope.proposalId)
            put("payloadRef", envelope.payloadRef)
            put("revision", context.revision)
            put("canonicalInputDigest", digest)
            put("idempotencyKey", envelope.idempotencyKey)
            put("runId", context.runId)
            put("attemptId", context.attemptId)
            data.forEach { (key, value) -> put(key, value) }
            put("title", approvalTitle(spec.name, contextLabel))
            put("message", approvalMessage(data, contextLabel))
        }.toString()
    }

    private suspend fun validateDomainReferences(toolName: String, data: JsonObject): String {
        suspend fun realContact(id: String): ContactEntity {
            val contact = contacts.findById(id) ?: throw ProviderFailure("CRM_CONTACT_NOT_FOUND", false)
            if (contact.source == "CRM_DEMO" || id.startsWith("crm-demo-")) {
                throw ProviderFailure("CRM_CONTACT_MUST_BE_REAL", false)
            }
            return contact
        }
        suspend fun realOpportunity(id: String) = crm.findOpportunity(id)
            ?.takeUnless { it.sourceType == "DEMO" }
            ?: throw ProviderFailure("CRM_OPPORTUNITY_NOT_FOUND", false)

        return when (toolName) {
            LEAD_CREATE -> realContact(data.requiredText("contactId")).displayName

            OPPORTUNITY_CREATE -> {
                // Contact is optional: only validate it when the agent actually supplied one (still
                // guards against hallucinated ids). Without a contact the confirmation title falls back
                // to the account name so the card never shows a blank subject.
                val contact = data.optionalText("primaryContactId")?.let { realContact(it) }
                data.optionalText("sourceLeadId")?.let { leadId ->
                    val lead = crm.findLead(leadId)
                    if (lead == null || lead.sourceType == "DEMO") throw ProviderFailure("CRM_LEAD_NOT_FOUND", false)
                }
                contact?.displayName ?: data.requiredText("accountName")
            }

            OPPORTUNITY_UPDATE -> {
                val opportunity = realOpportunity(data.requiredText("opportunityId"))
                data.optionalText("primaryContactId")?.let { realContact(it) }
                opportunity.title
            }

            OPPORTUNITY_STAGE -> realOpportunity(data.requiredText("opportunityId")).title

            ACTIVITY_APPEND -> {
                val opportunity = realOpportunity(data.requiredText("opportunityId"))
                val contact = realContact(data.requiredText("contactId"))
                "${opportunity.title} · ${contact.displayName}"
            }

            ACTION_CREATE -> {
                val opportunity = realOpportunity(data.requiredText("opportunityId"))
                data.optionalText("contactId")?.let { realContact(it) }
                opportunity.title
            }

            ACTION_UPDATE, ACTION_COMPLETE -> {
                val action = crm.findAction(data.requiredText("actionId"))
                    ?: throw ProviderFailure("CRM_ACTION_NOT_FOUND", false)
                realOpportunity(action.opportunityId)
                data.optionalText("contactId")?.let { realContact(it) }
                action.title
            }

            else -> error("unsupported CRM mutation")
        }
    }

    private fun approvalTitle(toolName: String, subject: String): String = when (toolName) {
        LEAD_CREATE -> "创建线索候选：$subject"
        OPPORTUNITY_CREATE -> "创建机会：$subject"
        OPPORTUNITY_UPDATE -> "修改机会：$subject"
        OPPORTUNITY_STAGE -> "调整机会阶段：$subject"
        ACTIVITY_APPEND -> "追加沟通活动：$subject"
        ACTION_CREATE -> "创建下一步动作：$subject"
        ACTION_UPDATE -> "修改下一步动作：$subject"
        ACTION_COMPLETE -> "完成下一步动作：$subject"
        else -> "确认个人 CRM 写入"
    }.take(120)

    private fun approvalMessage(data: JsonObject, subject: String): String = buildString {
        append("关联对象：").append(subject)
        data.forEach { (key, value) ->
            append('\n').append(FIELD_LABELS[key] ?: key).append("：").append(value.jsonPrimitive.content.take(1_000))
        }
        if (data.containsKey("calendarStartAtEpochMs")) {
            append("\n日程说明：这里只完成 CRM 动作；创建日程将另行确认。")
        }
    }.take(8_000)

    companion object {
        const val LEAD_CREATE = "crm.lead.createCandidate"
        const val OPPORTUNITY_CREATE = "crm.opportunity.create"
        const val OPPORTUNITY_UPDATE = "crm.opportunity.update"
        const val OPPORTUNITY_STAGE = "crm.opportunity.changeStage"
        const val ACTIVITY_APPEND = "crm.activity.append"
        const val ACTION_CREATE = "crm.nextAction.create"
        const val ACTION_UPDATE = "crm.nextAction.update"
        const val ACTION_COMPLETE = "crm.nextAction.complete"
        val TOOL_NAMES = setOf(
            LEAD_CREATE,
            OPPORTUNITY_CREATE,
            OPPORTUNITY_UPDATE,
            OPPORTUNITY_STAGE,
            ACTIVITY_APPEND,
            ACTION_CREATE,
            ACTION_UPDATE,
            ACTION_COMPLETE,
        )
        val AUTO_TOOL_NAMES = setOf(LEAD_CREATE, ACTIVITY_APPEND, ACTION_CREATE)

        private val FIELD_LABELS = mapOf(
            "contactId" to "联系人", "primaryContactId" to "主要联系人", "sourceLeadId" to "来源线索",
            "opportunityId" to "机会", "actionId" to "动作", "crmTitle" to "标题", "accountName" to "客户名称",
            "stage" to "阶段", "valueMinor" to "金额（最小货币单位）", "currencyCode" to "币种",
            "expectedCloseAtEpochMs" to "预计完成时间", "productSummary" to "产品", "needSummary" to "需求",
            "fitSummary" to "线索判断", "confidence" to "置信度", "activityType" to "沟通类型",
            "summary" to "沟通摘要", "occurredAtEpochMs" to "发生时间", "actionType" to "动作类型",
            "dueAtEpochMs" to "截止时间", "priority" to "优先级", "rationale" to "原因",
            "reason" to "阶段调整原因", "evidenceSummary" to "关联证据", "sourceRef" to "证据引用",
            "completionNote" to "完成说明", "calendarTitle" to "日程候选标题",
            "calendarStartAtEpochMs" to "日程候选时间", "calendarDurationMinutes" to "日程候选时长",
            "calendarNote" to "日程候选备注",
        )
    }
}

internal fun parseAndValidateCrmPlan(value: String, expectedTool: String? = null): JsonObject {
    require(value.toByteArray().size <= 24 * 1024) { "CRM_PLAN_TOO_LARGE" }
    val plan = Json.parseToJsonElement(value).jsonObject
    val toolName = plan.requiredText("toolName")
    require(toolName in CrmMutationToolBinding.TOOL_NAMES) { "CRM_PLAN_INVALID" }
    expectedTool?.let { require(toolName == it) { "CRM_TOOL_MISMATCH" } }
    META_REQUIRED.forEach { plan.requiredText(it) }
    require(plan.requiredText("revision").toLongOrNull() != null)
    val data = JsonObject(plan.filterKeys { it in TOOL_DATA_FIELDS.getValue(toolName) })
    val expectedDigest = sha256(data.toString())
    require(plan.requiredText("canonicalInputDigest") == expectedDigest) { "CRM_PLAN_DIGEST_MISMATCH" }
    return plan
}

private fun normalizeArguments(toolName: String, raw: JsonObject): JsonObject {
    val allowed = TOOL_ALLOWED.getValue(toolName)
    if (raw.keys.any { it !in allowed }) throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
    TOOL_REQUIRED.getValue(toolName).forEach { key ->
        if (raw[key]?.jsonPrimitive?.content?.trim().isNullOrEmpty()) {
            throw ProviderFailure(
                "INVALID_TOOL_ARGUMENTS",
                false,
            )
        }
    }
    if (toolName in setOf(CrmMutationToolBinding.OPPORTUNITY_UPDATE, CrmMutationToolBinding.ACTION_UPDATE) &&
        raw.size == 1
    ) {
        throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
    }
    val ctx = NormalizeCtx(raw)
    return buildJsonObject {
        when (toolName) {
            CrmMutationToolBinding.LEAD_CREATE -> normalizeLeadCreate(this, ctx)
            CrmMutationToolBinding.OPPORTUNITY_CREATE -> normalizeOpportunityCreate(this, ctx)
            CrmMutationToolBinding.OPPORTUNITY_UPDATE -> normalizeOpportunityUpdate(this, ctx)
            CrmMutationToolBinding.OPPORTUNITY_STAGE -> normalizeOpportunityStage(this, ctx)
            CrmMutationToolBinding.ACTIVITY_APPEND -> normalizeActivityAppend(this, ctx)
            CrmMutationToolBinding.ACTION_CREATE -> normalizeActionCreate(this, ctx)
            CrmMutationToolBinding.ACTION_UPDATE -> normalizeActionUpdate(this, ctx)
            CrmMutationToolBinding.ACTION_COMPLETE -> normalizeActionComplete(this, ctx)
        }
    }
}

private data class NormalizeCtx(val raw: JsonObject)

private fun NormalizeCtx.text(key: String, max: Int, required: Boolean = false): String? {
    val value = raw[key]?.jsonPrimitive?.content?.trim()
    if (required && value.isNullOrEmpty()) throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
    if (value != null &&
        (value.isEmpty() || value.length > max)
    ) {
        throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
    }
    return value
}

private fun NormalizeCtx.long(key: String, positive: Boolean = false): Long? {
    val value = raw[key]?.jsonPrimitive?.longOrNull ?: return null
    if (positive && value <= 0) throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
    return value
}

private fun NormalizeCtx.copyText(target: kotlinx.serialization.json.JsonObjectBuilder, key: String, max: Int, required: Boolean = false) {
    text(key, max, required)?.let { target.put(key, it) }
}

private fun NormalizeCtx.copyBusinessTitle(target: kotlinx.serialization.json.JsonObjectBuilder, required: Boolean = false) {
    text("title", 200, required)?.let { target.put("crmTitle", it) }
}

private fun normalizeLeadCreate(target: kotlinx.serialization.json.JsonObjectBuilder, ctx: NormalizeCtx) {
    with(target) {
        ctx.copyText(this, "contactId", 128, true)
        ctx.copyText(this, "fitSummary", 1_000)
        val confidence = ctx.raw["confidence"]?.jsonPrimitive?.doubleOrNull ?: 0.7
        if (confidence !in 0.0..1.0) throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
        put("confidence", confidence)
        ctx.copyText(this, "evidenceSummary", 1_000, true)
        ctx.copyText(this, "sourceRef", 500)
    }
}

private fun normalizeOpportunityCreate(target: kotlinx.serialization.json.JsonObjectBuilder, ctx: NormalizeCtx) {
    with(target) {
        ctx.copyBusinessTitle(this, true)
        ctx.copyText(this, "accountName", 200, true)
        ctx.copyText(this, "primaryContactId", 128)
        ctx.copyText(this, "sourceLeadId", 128)
        val stage =
            ctx.text("stage", 40) ?: "LEAD"
        if (stage !in
            CRM_STAGE_VALUES
        ) {
            throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
        }
        put("stage", stage)
        ctx.long("valueMinor")?.let {
            if (it <
                0
            ) {
                throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
            } else {
                put("valueMinor", it)
            }
        }
        put("currencyCode", (ctx.text("currencyCode", 3) ?: "CNY").uppercase())
        ctx.long("expectedCloseAtEpochMs", true)?.let { put("expectedCloseAtEpochMs", it) }
        ctx.copyText(this, "productSummary", 1_000)
        ctx.copyText(this, "needSummary", 2_000)
        ctx.copyText(this, "evidenceSummary", 1_000)
    }
}

private fun normalizeOpportunityUpdate(target: kotlinx.serialization.json.JsonObjectBuilder, ctx: NormalizeCtx) {
    with(target) {
        ctx.copyText(this, "opportunityId", 128, true)
        ctx.copyBusinessTitle(this)
        ctx.copyText(this, "accountName", 200)
        ctx.copyText(this, "primaryContactId", 128)
        ctx.long("valueMinor")?.let {
            if (it <
                0
            ) {
                throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
            } else {
                put("valueMinor", it)
            }
        }
        ctx.copyText(this, "currencyCode", 3)
        ctx.long("expectedCloseAtEpochMs", true)?.let { put("expectedCloseAtEpochMs", it) }
        ctx.copyText(this, "productSummary", 1_000)
        ctx.copyText(this, "needSummary", 2_000)
        ctx.copyText(this, "evidenceSummary", 1_000)
    }
}

private fun normalizeOpportunityStage(target: kotlinx.serialization.json.JsonObjectBuilder, ctx: NormalizeCtx) {
    with(target) {
        ctx.copyText(this, "opportunityId", 128, true)
        val stage = ctx.text("stage", 40, true)!!
        if (stage !in
            CRM_STAGE_VALUES
        ) {
            throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
        }
        put("stage", stage)
        ctx.copyText(this, "reason", 500, true)
        ctx.copyText(this, "evidenceSummary", 1_000)
    }
}

private fun normalizeActivityAppend(target: kotlinx.serialization.json.JsonObjectBuilder, ctx: NormalizeCtx) {
    with(target) {
        ctx.copyText(this, "opportunityId", 128, true)
        ctx.copyText(this, "contactId", 128, true)
        val type = ctx.text("activityType", 40, true)!!
        if (type !in
            ACTIVITY_TYPES
        ) {
            throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
        }
        put("activityType", type)
        ctx.copyBusinessTitle(this, true)
        ctx.copyText(this, "summary", 2_000, true)
        put(
            "occurredAtEpochMs",
            ctx.long("occurredAtEpochMs", true) ?: throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false),
        )
        ctx.copyText(this, "evidenceSummary", 1_000, true)
        ctx.copyText(this, "sourceRef", 500)
    }
}

private fun normalizeActionCreate(target: kotlinx.serialization.json.JsonObjectBuilder, ctx: NormalizeCtx) {
    with(target) {
        ctx.copyText(this, "opportunityId", 128, true)
        ctx.copyText(this, "contactId", 128)
        ctx.copyBusinessTitle(this, true)
        ctx.copyText(this, "actionType", 80, true)
        ctx.long("dueAtEpochMs", true)?.let { put("dueAtEpochMs", it) }
        val priority = ctx.raw["priority"]?.jsonPrimitive?.longOrNull?.toInt() ?: 50
        if (priority !in
            0..100
        ) {
            throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
        }
        put("priority", priority)
        ctx.copyText(this, "rationale", 1_000)
        ctx.copyText(this, "evidenceSummary", 1_000)
    }
}

private fun normalizeActionUpdate(target: kotlinx.serialization.json.JsonObjectBuilder, ctx: NormalizeCtx) {
    with(target) {
        ctx.copyText(this, "actionId", 128, true)
        ctx.copyText(this, "contactId", 128)
        ctx.copyBusinessTitle(this)
        ctx.copyText(this, "actionType", 80)
        ctx.long("dueAtEpochMs", true)?.let { put("dueAtEpochMs", it) }
        ctx.raw["priority"]?.jsonPrimitive?.longOrNull?.toInt()?.let {
            if (it !in
                0..100
            ) {
                throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
            } else {
                put("priority", it)
            }
        }
        ctx.copyText(this, "rationale", 1_000)
        ctx.copyText(this, "evidenceSummary", 1_000)
    }
}

private fun normalizeActionComplete(target: kotlinx.serialization.json.JsonObjectBuilder, ctx: NormalizeCtx) {
    with(target) {
        ctx.copyText(this, "actionId", 128, true)
        ctx.copyText(this, "completionNote", 1_000)
        val calendarFields =
            listOf("calendarTitle", "calendarStartAtEpochMs", "calendarDurationMinutes", "calendarNote")
        val hasCalendar = calendarFields.any(ctx.raw::containsKey)
        if (hasCalendar &&
            !listOf("calendarTitle", "calendarStartAtEpochMs", "calendarDurationMinutes").all(ctx.raw::containsKey)
        ) {
            throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
        }
        ctx.copyText(this, "calendarTitle", 200)
        ctx.long("calendarStartAtEpochMs", true)?.let { put("calendarStartAtEpochMs", it) }
        ctx.raw["calendarDurationMinutes"]?.jsonPrimitive?.longOrNull?.toInt()?.let {
            if (it !in
                1..1440
            ) {
                throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
            } else {
                put("calendarDurationMinutes", it)
            }
        }
        ctx.copyText(this, "calendarNote", 2_000)
    }
}

internal fun JsonObject.requiredText(name: String): String = this[name]?.jsonPrimitive?.content
    ?.takeIf(String::isNotBlank) ?: error("CRM_PLAN_MISSING_$name")
internal fun JsonObject.optionalText(name: String): String? = this[name]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)

private val META_REQUIRED = setOf(
    "toolName", "providerCallId", "logicalStepId", "proposalId", "payloadRef", "revision",
    "canonicalInputDigest", "idempotencyKey", "runId", "attemptId",
)
private val CRM_STAGE_VALUES = setOf("LEAD", "CONTACTED", "QUALIFIED", "PROPOSAL", "NEGOTIATION", "WON", "LOST")
private val ACTIVITY_TYPES = setOf("MEETING", "CALL", "MESSAGE", "EMAIL", "NOTE")
private val TOOL_REQUIRED = mapOf(
    CrmMutationToolBinding.LEAD_CREATE to setOf("contactId", "evidenceSummary"),
    CrmMutationToolBinding.OPPORTUNITY_CREATE to setOf("title", "accountName"),
    CrmMutationToolBinding.OPPORTUNITY_UPDATE to setOf("opportunityId"),
    CrmMutationToolBinding.OPPORTUNITY_STAGE to setOf("opportunityId", "stage", "reason"),
    CrmMutationToolBinding.ACTIVITY_APPEND to
        setOf("opportunityId", "contactId", "activityType", "title", "summary", "occurredAtEpochMs", "evidenceSummary"),
    CrmMutationToolBinding.ACTION_CREATE to setOf("opportunityId", "title", "actionType"),
    CrmMutationToolBinding.ACTION_UPDATE to setOf("actionId"),
    CrmMutationToolBinding.ACTION_COMPLETE to setOf("actionId"),
)
private val TOOL_ALLOWED = mapOf(
    CrmMutationToolBinding.LEAD_CREATE to setOf(
        "contactId",
        "fitSummary",
        "confidence",
        "evidenceSummary",
        "sourceRef",
    ),
    CrmMutationToolBinding.OPPORTUNITY_CREATE to
        setOf(
            "title", "accountName", "primaryContactId", "sourceLeadId", "stage", "valueMinor", "currencyCode",
            "expectedCloseAtEpochMs", "productSummary", "needSummary", "evidenceSummary",
        ),
    CrmMutationToolBinding.OPPORTUNITY_UPDATE to
        setOf(
            "opportunityId", "title", "accountName", "primaryContactId", "valueMinor", "currencyCode",
            "expectedCloseAtEpochMs", "productSummary", "needSummary", "evidenceSummary",
        ),
    CrmMutationToolBinding.OPPORTUNITY_STAGE to setOf("opportunityId", "stage", "reason", "evidenceSummary"),
    CrmMutationToolBinding.ACTIVITY_APPEND to
        setOf(
            "opportunityId",
            "contactId",
            "activityType",
            "title",
            "summary",
            "occurredAtEpochMs",
            "evidenceSummary",
            "sourceRef",
        ),
    CrmMutationToolBinding.ACTION_CREATE to
        setOf(
            "opportunityId",
            "contactId",
            "title",
            "actionType",
            "dueAtEpochMs",
            "priority",
            "rationale",
            "evidenceSummary",
        ),
    CrmMutationToolBinding.ACTION_UPDATE to
        setOf(
            "actionId",
            "contactId",
            "title",
            "actionType",
            "dueAtEpochMs",
            "priority",
            "rationale",
            "evidenceSummary",
        ),
    CrmMutationToolBinding.ACTION_COMPLETE to
        setOf(
            "actionId",
            "completionNote",
            "calendarTitle",
            "calendarStartAtEpochMs",
            "calendarDurationMinutes",
            "calendarNote",
        ),
)
private val TOOL_DATA_FIELDS = TOOL_ALLOWED.mapValues { (toolName, fields) ->
    if (toolName in setOf(
            CrmMutationToolBinding.OPPORTUNITY_CREATE,
            CrmMutationToolBinding.OPPORTUNITY_UPDATE,
            CrmMutationToolBinding.ACTIVITY_APPEND,
            CrmMutationToolBinding.ACTION_CREATE,
            CrmMutationToolBinding.ACTION_UPDATE,
        )
    ) {
        (fields - "title") + "crmTitle"
    } else {
        fields
    }
}
