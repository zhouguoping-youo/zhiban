package com.zhiban.rebuild.runtime.kernel

import com.zhiban.agent.skills.SkillActivation
import com.zhiban.agent.skills.SkillActivator
import com.zhiban.agent.skills.SkillOrigin
import com.zhiban.agent.skills.SkillSpec
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.notification.NotificationInsightAnalyzer
import com.zhiban.rebuild.runtime.context.ContextBlock
import com.zhiban.rebuild.runtime.context.ContextKind
import com.zhiban.rebuild.runtime.context.ContextLayer
import com.zhiban.rebuild.runtime.context.ContextProvenance
import com.zhiban.rebuild.runtime.context.ContextRetrievalResult
import com.zhiban.rebuild.runtime.context.PerceptionGateway
import com.zhiban.rebuild.runtime.context.PromptAssembler
import com.zhiban.rebuild.runtime.context.PromptBudget
import com.zhiban.rebuild.runtime.context.QueryContext
import com.zhiban.rebuild.runtime.context.RoomContextRetrievalPipeline
import com.zhiban.rebuild.runtime.context.RoomPerceptionPipeline
import com.zhiban.rebuild.runtime.context.Sensitivity
import com.zhiban.rebuild.runtime.context.TrustLevel
import com.zhiban.rebuild.runtime.context.reranked
import com.zhiban.rebuild.runtime.context.resolveCalendarStartEpochMs
import com.zhiban.rebuild.runtime.context.withDegradations
import com.zhiban.rebuild.runtime.governance.AutoWritePresentationRegistry
import com.zhiban.rebuild.runtime.governance.ChangeUndoCoordinator
import com.zhiban.rebuild.runtime.governance.ContactDomainWriter
import com.zhiban.rebuild.runtime.governance.RelationshipDomainWriter
import com.zhiban.rebuild.runtime.memory.RoomMemoryGate
import com.zhiban.rebuild.runtime.provider.CapabilitySnapshot
import com.zhiban.rebuild.runtime.provider.ModelEvent
import com.zhiban.rebuild.runtime.provider.ModelMessage
import com.zhiban.rebuild.runtime.provider.ModelRequest
import com.zhiban.rebuild.runtime.provider.OutboundChannel
import com.zhiban.rebuild.runtime.provider.OutboundProvenance
import com.zhiban.rebuild.runtime.provider.OutboundPurpose
import com.zhiban.rebuild.runtime.provider.OutboundSensitivity
import com.zhiban.rebuild.runtime.provider.ProviderAdapter
import com.zhiban.rebuild.runtime.provider.ProviderFailure
import com.zhiban.rebuild.runtime.provider.ProviderModelPolicy
import com.zhiban.rebuild.runtime.provider.ProviderProfile
import com.zhiban.rebuild.runtime.provider.ProviderProfileStore
import com.zhiban.rebuild.runtime.spi.RuntimeRunStatus
import com.zhiban.rebuild.runtime.store.RoomRuntimeStore
import com.zhiban.rebuild.runtime.store.RuntimeEventDraft
import com.zhiban.rebuild.runtime.store.RuntimeRecoveryHandle
import com.zhiban.rebuild.runtime.tool.CalendarConflictToolBinding
import com.zhiban.rebuild.runtime.tool.CalendarMutationToolBinding
import com.zhiban.rebuild.runtime.tool.CalendarSearchToolBinding
import com.zhiban.rebuild.runtime.tool.CapabilityRouter
import com.zhiban.rebuild.runtime.tool.CommunicationMessageToolBinding
import com.zhiban.rebuild.runtime.tool.ConfirmedToolExecutionContext
import com.zhiban.rebuild.runtime.tool.ContactCreateCandidateToolBinding
import com.zhiban.rebuild.runtime.tool.ContactDetailToolBinding
import com.zhiban.rebuild.runtime.tool.ContactProfileUpdateCandidateToolBinding
import com.zhiban.rebuild.runtime.tool.ContactSearchToolBinding
import com.zhiban.rebuild.runtime.tool.ContactTagDomainWriter
import com.zhiban.rebuild.runtime.tool.ContactTagToolBinding
import com.zhiban.rebuild.runtime.tool.CrmMutationToolBinding
import com.zhiban.rebuild.runtime.tool.CrmOpportunityDetailToolBinding
import com.zhiban.rebuild.runtime.tool.CrmOpportunityListToolBinding
import com.zhiban.rebuild.runtime.tool.MemoryDeleteToolBinding
import com.zhiban.rebuild.runtime.tool.MemoryRememberPlanValidator
import com.zhiban.rebuild.runtime.tool.MemoryRememberToolBinding
import com.zhiban.rebuild.runtime.tool.MemoryRememberToolCall
import com.zhiban.rebuild.runtime.tool.MemorySearchToolBinding
import com.zhiban.rebuild.runtime.tool.RelationshipCreateCandidateToolBinding
import com.zhiban.rebuild.runtime.tool.RelationshipEvidenceToolBinding
import com.zhiban.rebuild.runtime.tool.RelationshipSearchToolBinding
import com.zhiban.rebuild.runtime.tool.RemoteMcpToolBinding
import com.zhiban.rebuild.runtime.tool.RoomCrmToolExecutor
import com.zhiban.rebuild.runtime.tool.RoomMemoryToolExecutor
import com.zhiban.rebuild.runtime.tool.RoomScheduleToolExecutor
import com.zhiban.rebuild.runtime.tool.RoutedToolResult
import com.zhiban.rebuild.runtime.tool.RuntimeToolCallRequest
import com.zhiban.rebuild.runtime.tool.RuntimeToolCatalog
import com.zhiban.rebuild.runtime.tool.RuntimeToolRouteContext
import com.zhiban.rebuild.runtime.tool.ScheduleCreateToolBinding
import com.zhiban.rebuild.runtime.tool.ScheduleCreateToolCall
import com.zhiban.rebuild.runtime.tool.SchedulePlanValidator
import com.zhiban.rebuild.runtime.tool.ToolConfirmation
import com.zhiban.rebuild.runtime.tool.ToolDisposition
import com.zhiban.rebuild.runtime.tool.canonicalMemoryDigest
import com.zhiban.rebuild.runtime.tool.canonicalMemoryIdempotencyKey
import com.zhiban.rebuild.runtime.tool.canonicalScheduleDigest
import com.zhiban.rebuild.runtime.tool.canonicalToolIdempotencyKey
import com.zhiban.rebuild.runtime.tool.sha256
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val ENGLISH_NAMED_TITLE = Regex(
    """\b(?:called|titled|named)\s+(.+?)(?=,?\s+(?:with\s+)?remind(?:er|\s+me)?\b|,\s*with\b|[.!?]\s*$|$)""",
    RegexOption.IGNORE_CASE,
)
private val CHINESE_NAMED_TITLE = Regex("""(?:叫|名为|标题是)\s*([^，。,.]{1,80})""")
private val CHINESE_AFTER_TIME_TITLE =
    Regex("""(?:点|分)\s*([^，。,.]{1,40}?)(?=，|。|,|提醒|$)""")
private val ENGLISH_REMINDER =
    Regex("""\b(\d{1,4})\s*minutes?\s*before\b""", RegexOption.IGNORE_CASE)
private val CHINESE_REMINDER = Regex("""提前\s*(\d{1,4})\s*分钟""")

/**
 * Forced calendar-create suppresses the model's streamed prose so the user sees only the
 * confirmation card, not a possibly-wrong restated time. All other paths stream text normally.
 */
internal fun shouldStreamAssistantText(forcedCanonicalTool: String?): Boolean = forcedCanonicalTool != SchedulePlanValidator.TOOL_NAME

/** Executes only the provider portion of a Runtime v2 run. It never exposes credential material. */
internal fun normalizeCalendarToolCall(
    canonicalName: (String) -> String,
    event: ModelEvent.ToolCall,
    input: DecodedInput,
    queryContext: QueryContext,
    nowEpochMs: Long? = null,
): ModelEvent.ToolCall {
    if (canonicalName(event.name) != SchedulePlanValidator.TOOL_NAME) return event
    val arguments = runCatching { Json.parseToJsonElement(event.argumentsJson).jsonObject }
        .getOrElse { throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false) }
    val modelStart = (arguments["startAtEpochMs"] ?: arguments["startTimeEpochMs"])
        ?.jsonPrimitive?.content?.toLongOrNull()
    val exactLocalStart = resolveCalendarStartEpochMs(input.text, queryContext.timeRange, nowEpochMs = nowEpochMs)
    val isSchedulePlan = canonicalName(event.name) == SchedulePlanValidator.TOOL_NAME
    val rawTitle = arguments["title"]?.jsonPrimitive?.content.orEmpty()
    val safeTitle = if (isSchedulePlan) {
        sanitizeScheduleTitleFromText(input.text, rawTitle).ifBlank {
            "新日程"
        }
    } else {
        rawTitle
    }
    val normalizedStart = exactLocalStart ?: modelStart?.takeIf { candidate ->
        queryContext.timeRange?.let { candidate >= it.startEpochMs && candidate < it.endExclusiveEpochMs } ?: true
    }
    if (queryContext.timeRange != null && normalizedStart == null) {
        throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
    }
    val titleUpdated = isSchedulePlan && safeTitle.isNotBlank() && safeTitle != rawTitle
    if (!titleUpdated && (normalizedStart == null || normalizedStart == modelStart)) return event
    val normalizedArguments = buildJsonObject {
        arguments.forEach { (key, value) ->
            if (key !in setOf("startAtEpochMs", "startTimeEpochMs", "title")) {
                put(key, value)
            }
        }
        if (safeTitle.isNotBlank()) put("title", safeTitle)
        put("startAtEpochMs", normalizedStart)
    }.toString()
    return event.copy(argumentsJson = normalizedArguments)
}

internal fun deterministicCalendarToolCall(input: DecodedInput, queryContext: QueryContext, nowEpochMs: Long? = null): ModelEvent.ToolCall? {
    val startAt = resolveCalendarStartEpochMs(input.text, queryContext.timeRange, nowEpochMs = nowEpochMs) ?: return null
    val title = ENGLISH_NAMED_TITLE.find(input.text)?.groupValues?.get(1)
        ?: CHINESE_NAMED_TITLE.find(input.text)?.groupValues?.get(1)
        ?: CHINESE_AFTER_TIME_TITLE.find(input.text)?.groupValues?.get(1)
        ?: "新日程"
    val normalizedTitle = sanitizeScheduleTitleFromText(input.text, title)
    val reminder = ENGLISH_REMINDER.find(input.text)?.groupValues?.get(1)?.toIntOrNull()
        ?: CHINESE_REMINDER.find(input.text)?.groupValues?.get(1)?.toIntOrNull()
        ?: if (input.text.contains("提醒") || input.text.contains("remind", ignoreCase = true)) 10 else null
    val safeReminder = reminder?.takeIf { it in setOf(10, 30, 60, 1_440) }
    val arguments = buildJsonObject {
        put("title", normalizedTitle)
        put("startAtEpochMs", startAt)
        put("durationMinutes", 60)
        safeReminder?.let { put("reminderMinutesBefore", it) }
    }.toString()
    return ModelEvent.ToolCall(
        ordinal = 0,
        providerCallId = "local-${sha256("${input.text}|$startAt|$normalizedTitle").take(24)}",
        name = SchedulePlanValidator.TOOL_NAME,
        argumentsJson = arguments,
    )
}

internal fun sanitizeScheduleTitleFromText(inputText: String, candidate: String): String {
    val candidateNormalized = sanitizeCalendarCandidateTitle(candidate)
    if (candidateNormalized.isNotBlank() && candidateNormalized != "待确认安排") return candidateNormalized
    val fullTextNormalized = NotificationInsightAnalyzer.sanitizeScheduleTitle(inputText, null)
    if (fullTextNormalized.isNotBlank() && fullTextNormalized != "待确认安排") return fullTextNormalized
    return "新日程"
}

internal fun sanitizeCalendarCandidateTitle(value: String): String {
    val fromAnalyzer = NotificationInsightAnalyzer.sanitizeScheduleTitle(value, null)
    if (fromAnalyzer.isNotBlank() && fromAnalyzer != "待确认安排") return fromAnalyzer

    var title = value.trim()
        .replace(Regex("""^[\"“”‘’'`]+|[\"“”‘’'`]+$"""), "")
        .replace(Regex("""^[（(][^）)]{0,20}[)）]\s*"""), "")
        .replace(Regex("""^\s*(?:我|你|对方|他|她|它|系统|知伴|客服|联系人)\s*[:：,，]?\s*"""), "")
        .replace(Regex("""^\s*(?:\w+)?\s*(?:说|提到|提及|发来|告诉|反馈|转告)[:：]?\s*"""), "")
        .replace(Regex("""^(?:\s*(?:请|帮我|安排|创建|加上|新增|新建)\s*)"""), "")
        .replace(Regex("""^\s*和(?:我|你|他|她|它|对方)?\s*"""), "")
        .replace(Regex("""^(?:明天|后天|今天|周[一二三四五六日天]|星期[一二三四五六日天])\s*"""), "")
        .replace(Regex("""^(?:上|下|中)?\s*(?:上午|中午|下午|晚上|凌晨|傍晚|早上)\s*"""), "")
        .replace(
            Regex(
                """^\s*(?:明天|后天|今天|周[一二三四五六日天]|星期[一二三四五六日天])?\s*(?:下|上)?(?:[上下]?(?:上午|中午|下午|晚上|早上|凌晨|傍晚)\s*)?(?:\d{1,2}|[零一二三四五六七八九十]{1,3})(?:[:：]\s*\d{1,2}|点(?:\s*(?:半|[0-5]?\d|[零一二三四五六七八九十]{1,3})?)?)\s*""",
            ),
            "",
        )
        .replace(Regex("""^\s*\d{1,2}\s*[：:]\s*"""), "")
        .replace(Regex("""^\s*(?:帮我|请)?\s*安排\s*"""), "")
        .replace(Regex("""\s*，\s*"""), "，")
        .trim()
        .trim('，', '。', ',', '.', '：', ':', '！', '!', '？', '?')

    val titleFromFullText = NotificationInsightAnalyzer.sanitizeScheduleTitle(
        title,
        null,
    ).takeIf { it.isNotBlank() && it != "待确认安排" && it != "沟通内容" }
    if (titleFromFullText != null) return titleFromFullText.take(80)

    return title.take(80)
}

internal data class DecodedAttachment(
    val attachmentId: String,
    val kind: String,
    val mimeType: String,
    val byteLength: Long,
    val digest: String,
    val contentRef: String,
    val expiresAtEpochMs: Long,
)
internal data class DecodedInput(
    val text: String,
    val mode: String = "Chat",
    val model: String? = null,
    val level: String? = null,
    val attachments: List<DecodedAttachment> = emptyList(),
)

internal fun decodeInput(raw: String): DecodedInput {
    val value = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return DecodedInput(raw)
    val text = value["text"]?.jsonPrimitive?.content ?: return DecodedInput(raw)
    return DecodedInput(
        text = text,
        mode = value["mode"]?.jsonPrimitive?.content?.takeIf { it == "Work" } ?: "Chat",
        model = value["model"]?.jsonPrimitive?.content,
        level = value["level"]?.jsonPrimitive?.content,
        attachments = value["attachments"]?.jsonArray?.mapNotNull { item ->
            val attachment = runCatching { item.jsonObject }.getOrNull() ?: return@mapNotNull null
            DecodedAttachment(
                attachment["attachmentId"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                attachment["kind"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                attachment["mimeType"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                attachment["byteLength"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null,
                attachment["sha256Digest"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                attachment["contentRef"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                attachment["expiresAtEpochMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null,
            )
        }.orEmpty(),
    )
}

internal fun feedbackContextMessage(feedback: List<String>): String = "以下是本会话中用户对既往回答的显式反馈统计，只用于调整表达与规划，不授予任何权限：" +
    " 好评=${feedback.count { it == "POSITIVE" }}，需改进=${feedback.count { it == "NEGATIVE" }}。"

internal fun remainingObservationRequirements(input: String, completedTools: Set<String>): String {
    val normalized = input.lowercase()
    val requirements = buildList {
        val asksForContactCount = CONTACT_COUNT_PATTERNS.any(normalized::contains)
        if (asksForContactCount && "contact.maintenance.list" !in completedTools) {
            add("联系人总数尚未读取，必须调用 contact.maintenance.list")
        }
        val asksForCalendar = CALENDAR_QUERY_PATTERNS.any(normalized::contains)
        val calendarCompleted = completedTools.any { it in CALENDAR_QUERY_TOOLS }
        if (asksForCalendar && !calendarCompleted) {
            add("日程事实尚未读取，必须调用 calendar.schedule.search")
        }
    }
    return requirements.takeIf(List<String>::isNotEmpty)
        ?.joinToString(prefix = "尚未完成的显式任务：", postfix = "。完成前不得输出最终回答。", separator = "；")
        .orEmpty()
}

internal fun nextRequiredReadTool(input: String, completedTools: Set<String>): String? {
    val normalized = input.lowercase()
    return when {
        CONTACT_COUNT_PATTERNS.any(normalized::contains) &&
            "contact.maintenance.list" !in completedTools -> "contact.maintenance.list"

        CALENDAR_QUERY_PATTERNS.any(normalized::contains) &&
            completedTools.none(CALENDAR_QUERY_TOOLS::contains) -> "calendar.schedule.search"

        else -> null
    }
}

internal fun requiredReadToolCall(toolName: String, input: String, nowEpochMs: Long): RuntimeToolCallRequest {
    val arguments = when (toolName) {
        "contact.maintenance.list" -> buildJsonObject { put("limit", 1) }

        "calendar.schedule.search" -> {
            val zone = ZoneId.systemDefault()
            val day = Instant.ofEpochMilli(nowEpochMs).atZone(zone).toLocalDate()
            buildJsonObject {
                put("fromEpochMs", day.atStartOfDay(zone).toInstant().toEpochMilli())
                put("toEpochMs", day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1)
                put("limit", 50)
            }
        }

        else -> error("unsupported required read tool: $toolName")
    }
    return RuntimeToolCallRequest(
        providerCallId = "required-${sha256("$toolName|$input").take(24)}",
        name = toolName,
        argumentsJson = arguments.toString(),
    )
}

private val CONTACT_COUNT_PATTERNS = listOf("联系人数量", "联系人总数", "多少联系人", "多少位联系人", "统计联系人", "count my contact", "how many contact")
private val CALENDAR_QUERY_PATTERNS = listOf("今天日程", "今日日程", "今天安排", "今日安排", "today's schedule", "today%27s schedule", "today schedule")
private val CALENDAR_QUERY_TOOLS = setOf("calendar.search", "calendar.schedule.search")

internal data class AssembledModelContext(val messages: List<ModelMessage>, val sources: List<String>)

internal fun shouldCompleteObservationDeterministically(toolName: String, intentLabel: com.zhiban.rebuild.runtime.context.IntentLabel): Boolean = (
    toolName == SchedulePlanValidator.TOOL_NAME &&
        intentLabel == com.zhiban.rebuild.runtime.context.IntentLabel.CALENDAR_CREATE
    ) ||
    toolName == CommunicationMessageToolBinding.TOOL_NAME ||
    toolName == CrmMutationToolBinding.LEAD_CREATE

internal fun deterministicToolSummary(toolName: String, safeResultJson: String): String {
    val result = runCatching { Json.parseToJsonElement(safeResultJson).jsonObject }.getOrNull()
    val count = result?.get("count")?.jsonPrimitive?.content?.toIntOrNull()
    return when (toolName) {
        "schedule.create", "calendar.create", "calendar.schedule.create" ->
            scheduleCreatedSummary(result)

        "calendar.search", "calendar.schedule.search" -> if (count == 0) "这个时间范围内没有日程安排。" else "已查到 ${count ?: 0} 条日程。"

        "calendar.conflicts", "calendar.schedule.conflicts" -> if (result?.get("hasConflict")?.jsonPrimitive?.content ==
            "true"
        ) {
            "检测到 ${count ?: 1} 个日程冲突。"
        } else {
            "没有检测到日程冲突。"
        }

        "contacts.search", "contact.search" -> if (count == 0) "没有找到匹配的联系人。" else "已找到 ${count ?: 0} 位联系人。"

        "relationships.search", "relationship.search" -> if (count ==
            0
        ) {
            "没有找到匹配的联系人关系。"
        } else {
            "已找到 ${count ?: 0} 条联系人关系。"
        }

        "crm.opportunity.list" -> {
            val opportunities = result?.get("opportunities")?.jsonArray.orEmpty()
            if (opportunities.isEmpty()) {
                "没有找到匹配的机会，我没有修改个人 CRM。"
            } else {
                val preview = opportunities.take(3).joinToString("；") { value ->
                    val row = value.jsonObject
                    val title = row["title"]?.jsonPrimitive?.content.orEmpty()
                    val stage = crmStageSummaryLabel(row["stage"]?.jsonPrimitive?.content.orEmpty())
                    "$title（$stage）"
                }
                "已读取 ${opportunities.size} 条机会：$preview。以上只是查询结果，我没有修改个人 CRM。"
            }
        }

        "crm.opportunity.get" -> crmOpportunityFallbackSummary(result)

        CrmMutationToolBinding.LEAD_CREATE ->
            "已放入“知伴发现的候选线索”，尚未转为正式机会。可在个人 CRM 中查看、转正或忽略。"

        CommunicationMessageToolBinding.TOOL_NAME -> {
            val platform = result?.get("platform")?.jsonPrimitive?.content.orEmpty()
            val recipient = result?.get("recipient")?.jsonPrimitive?.content.orEmpty()
            "已打开${CommunicationMessageToolBinding.platformLabel(
                platform,
            )}发送界面。请核对${recipient.takeIf(String::isNotBlank)?.let {
                "收件人“$it”和"
            }.orEmpty()}正文，并在目标应用中点击发送；知伴目前不会把“已打开”当成“已送达”。"
        }

        else -> "操作已完成。"
    }
}

/**
 * Post-confirmation schedule summary that states the *verified* wall-clock from the persisted
 * result, so it authoritatively corrects any date the model hallucinated in its streamed text
 * (e.g. it said "明天" while the confirmed card read today). Falls back to the generic line when
 * the result predates the embedded fields (e.g. an idempotent replay of an older write).
 */
private fun scheduleCreatedSummary(result: kotlinx.serialization.json.JsonObject?): String {
    val startAt = result?.get("startAtEpochMs")?.jsonPrimitive?.content?.toLongOrNull()
        ?: return "日程已创建，可在日历中查看；如有需要，可以撤销这次操作。"
    val title = result["title"]?.jsonPrimitive?.content.orEmpty().ifBlank { "日程" }
    val duration = result["durationMinutes"]?.jsonPrimitive?.content?.toIntOrNull()
    val start = SCHEDULE_SUMMARY_TIME.format(Instant.ofEpochMilli(startAt).atZone(ZoneId.systemDefault()))
    val durationPart = duration?.let { "，时长 $it 分钟" }.orEmpty()
    return "已创建日程“$title”，$start$durationPart，可在日历中查看；如有需要，可以撤销这次操作。"
}

private val SCHEDULE_SUMMARY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 HH:mm")

internal fun crmOpportunityFallbackSummary(result: kotlinx.serialization.json.JsonObject?): String {
    if (result?.get("found")?.jsonPrimitive?.content == "false") return "没有找到这条机会，我没有修改个人 CRM。"
    val opportunity = result?.get("opportunity")?.jsonObject ?: return "已读取机会，但结果不完整；我没有修改个人 CRM。"
    val title = opportunity["title"]?.jsonPrimitive?.content.orEmpty().ifBlank { "这条机会" }
    val account = opportunity["accountName"]?.jsonPrimitive?.content.orEmpty()
    val stage = crmStageSummaryLabel(opportunity["stage"]?.jsonPrimitive?.content.orEmpty())
    val probability = opportunity["probabilityPercent"]?.jsonPrimitive?.content
    val actions = result["nextActions"]?.jsonArray.orEmpty()
    val pendingAction = actions.firstOrNull { it.jsonObject["status"]?.jsonPrimitive?.content == "PENDING" }?.jsonObject
    val suggestions = result["agentSuggestions"]?.jsonArray.orEmpty()
    val pendingSuggestion = suggestions.firstOrNull {
        it.jsonObject["status"]?.jsonPrimitive?.content == "PENDING"
    }?.jsonObject
    return buildString {
        append("已读取“").append(title).append('”')
        if (account.isNotBlank()) append("（").append(account).append("）")
        append("：当前阶段为").append(stage)
        probability?.let { append("，成交概率 ").append(it).append('%') }
        append('。')
        pendingSuggestion?.let { row ->
            append("\n当前风险：").append(row["rationale"]?.jsonPrimitive?.content.orEmpty())
        }
        pendingAction?.let { row ->
            append("\n下一步：").append(row["title"]?.jsonPrimitive?.content.orEmpty())
            row["rationale"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)?.let { append("；依据：").append(it) }
        } ?: append("\n下一步：目前没有已确认动作，建议先确认负责人和时间，再写入日历。")
        append("\n我没有修改 CRM、日历或发送消息。")
    }
}

internal fun crmStageSummaryLabel(stage: String): String = when (stage) {
    "LEAD" -> "线索"
    "CONTACTED" -> "已联系"
    "QUALIFIED" -> "已确认需求"
    "PROPOSAL" -> "方案/报价"
    "NEGOTIATION" -> "商务推进"
    "WON" -> "成交"
    "LOST" -> "流失"
    else -> stage.ifBlank { "未标记" }
}
