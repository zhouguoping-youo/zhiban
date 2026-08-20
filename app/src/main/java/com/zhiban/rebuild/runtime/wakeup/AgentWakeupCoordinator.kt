package com.zhiban.rebuild.runtime.wakeup

import android.util.Log
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.notification.ScheduleInsight
import com.zhiban.rebuild.data.suggestion.AgentSuggestionCodecs
import com.zhiban.rebuild.data.suggestion.AgentSuggestionEntity
import com.zhiban.rebuild.data.suggestion.AgentSuggestionRepository
import com.zhiban.rebuild.data.suggestion.AgentSuggestionStatus
import com.zhiban.rebuild.data.suggestion.AgentSuggestionType
import com.zhiban.rebuild.data.suggestion.EventIntentExtractor
import com.zhiban.rebuild.foundation.runSuspendCatching
import com.zhiban.rebuild.runtime.spi.RuntimeRunStatus
import com.zhiban.rebuild.runtime.spi.RuntimeUiClient
import com.zhiban.rebuild.runtime.spi.TextInputGateway
import com.zhiban.rebuild.runtime.workspace.SessionWorkspaceGateway
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 事件驱动唤醒协调器：感知事件（当前为微信消息候选）规则流水线跑完后，
 * 由 [WakeupDecider] 裁决是否唤醒 LLM；唤醒则用 [HeadlessAgentSession] 在
 * 无 UI 会话里综合判断，产出落建议中心（agent_suggestions）。
 *
 * 模式对齐 [com.zhiban.rebuild.data.reply.ReplySuggestionCoordinator]：
 * 触发驱动 + 汇合去抖 + 串行处理 + 全程尽力而为（任何失败都不影响消息入库主链路）。
 * 红线：需要用户确认的动作在后台一律自动拒绝，转为建议卡；绝不静默通过确认闸门。
 */
@Singleton
internal class AgentWakeupCoordinator @Inject constructor(
    private val database: AgentDatabase,
    private val runtimeUiClient: RuntimeUiClient,
    private val textInputGateway: TextInputGateway,
    private val sessionWorkspace: SessionWorkspaceGateway,
    private val suggestions: AgentSuggestionRepository,
    private val contextLoader: WakeupContextLoader,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val triggers = Channel<String>(capacity = TRIGGER_BUFFER_CAPACITY)
    private val processMutex = Mutex()
    private val throttle = WakeupThrottle()

    @Volatile
    private var consumerStarted = false

    /** 规则流水线跑完后的一声"去看看"：传入刚处理完的通知候选 id。 */
    fun onCandidateProcessed(candidateId: String) {
        ensureConsumerStarted()
        triggers.trySend(candidateId)
    }

    @Synchronized
    private fun ensureConsumerStarted() {
        if (consumerStarted) return
        consumerStarted = true
        scope.launch {
            for (candidateId in triggers) {
                delay(TRIGGER_DEBOUNCE_MS)
                runSuspendCatching { processCandidate(candidateId) }
                    .onFailure { failure ->
                        if (failure is CancellationException) throw failure
                        Log.w(TAG, "wakeup:candidate_failure", failure)
                    }
            }
        }
    }

    internal suspend fun processCandidate(candidateId: String) {
        processMutex.withLock {
            val candidate = database.notificationCandidateDao().find(candidateId) ?: return
            val contactId = candidate.linkedContactId ?: candidate.suggestedContactId
            val hasOpenCrmOpportunity = contactId != null &&
                database.crmDao().findOpenOpportunityByContact(contactId) != null
            val decision = WakeupDecider.decide(candidate, hasOpenCrmOpportunity, System.currentTimeMillis(), throttle)
            Log.d(
                TAG,
                "wakeup:decide candidate=$candidateId contact=$contactId drift=${candidate.identityDriftJson != null} schedule=${candidate.createdScheduleId} decision=$decision",
            )
            if (decision !is WakeupDecision.Wake) return
            Log.d(TAG, "wakeup:run reason=${decision.reason} contact=${decision.contactId}")
            runWakeup(candidateId, decision)
        }
    }

    private suspend fun runWakeup(candidateId: String, decision: WakeupDecision.Wake) {
        val candidate = database.notificationCandidateDao().find(candidateId) ?: return
        val contact = decision.contactId?.let { database.contactDao().findById(it) }
        val opportunity = decision.contactId?.let { database.crmDao().findOpenOpportunityByContact(it) }
        val context = contextLoader.load(candidate, decision.contactId, System.currentTimeMillis())
        // 每次唤醒用唯一 sessionId：复用旧 session 会因 revision 已推进导致 start CONFLICT。
        // 建议去重仍走 dedupeKey="wakeup-<candidateId>"，幂等不受影响。
        val sessionId = "wakeup-$candidateId-${System.currentTimeMillis()}"
        val session = HeadlessAgentSession(runtimeUiClient, textInputGateway, scope)
        sessionWorkspace.ensure(sessionId)
        val input = encodeRuntimeInput(buildWakeupPrompt(candidate, contact, opportunity, context))
        val result = session.run(sessionId, input)
        persistSuggestion(candidateId, decision, result)
        // 保留会话痕迹（可在历史里回看），不清不删；上限由既有会话治理兜底。
    }

    private fun buildWakeupPrompt(
        candidate: com.zhiban.rebuild.data.notification.NotificationCandidateEntity,
        contact: com.zhiban.rebuild.data.contact.ContactEntity?,
        opportunity: com.zhiban.rebuild.data.crm.CrmOpportunityEntity?,
        context: WakeupContext,
    ): String = buildString {
        appendLine("【知伴主动助手·后台唤醒】刚收到一条来自「${candidate.senderName ?: candidate.conversationTitle ?: "未知发送者"}」的微信消息。")
        appendLine("消息正文：${candidate.body.orEmpty().take(MAX_PROMPT_BODY_LENGTH)}")
        if (contact != null) {
            appendLine(
                "已归因联系人：${contact.displayName}" + listOfNotNull(
                    contact.company?.takeIf(String::isNotBlank),
                    contact.title?.takeIf(String::isNotBlank),
                ).joinToString(prefix = "（", postfix = "）", separator = " · "),
            )
        } else {
            appendLine("未归因到任何联系人（可能是陌生人）。")
        }
        if (opportunity != null) appendLine("CRM 状态：存在进行中机会「${opportunity.title}」（阶段 ${opportunity.stage}）。")
        appendLine(buildWakeupContextPrompt(context))
        appendLine()
        appendLine("请作为用户的个人助理快速判断：")
        appendLine("1. 这条消息是否需要用户跟进？给出一条不超过 150 字的简短建议，说明下一步做什么。")
        appendLine("2. 消息里如果有可以安全自动完成的事（例如补充联系人资料、记录备忘），可以直接执行可撤销操作。")
        appendLine("3. 拿不准的事情只给建议；不要编造事实，不要猜测未提供的信息，不要执行需要用户确认的写操作。")
        appendLine("请严格按指定 JSON schema 输出，不要输出 Markdown 或额外解释。")
        append("日程时间字段只提取消息原文中的表达，不要自行换算日期；最终时间由本地规则校验归一。")
    }

    private fun encodeRuntimeInput(text: String): String = buildJsonObject {
        put("schemaVersion", 1)
        put("text", text)
        put("mode", "Work")
        put("origin", "AUTO_RETRIEVED")
        put("responseJsonSchema", WakeupStructuredOutputCodec.RESPONSE_SCHEMA)
    }.toString()

    private suspend fun persistSuggestion(candidateId: String, decision: WakeupDecision.Wake, result: HeadlessAgentSession.HeadlessResult) {
        val structured = WakeupStructuredOutputCodec.decode(result.assistantText)
        val body = resolveSuggestionBody(result, structured)
        if (body.isBlank()) {
            Log.i(TAG, "wakeup:skip_empty_result candidate=$candidateId status=${result.finalStatus} failure=${result.failureCode}")
            return
        }
        val candidate = database.notificationCandidateDao().find(candidateId)
        val sender = candidate?.senderName ?: "联系人"
        val intent = extractEventIntent(candidate, decision, sender)
        val title = suggestionTitle(intent, decision.reason, sender)
        val executed = if (result.changeCommitted) "知伴已顺带完成可撤销的自动操作，可在「自动整理」查看与撤销。" else ""
        val now = System.currentTimeMillis()
        suggestions.insert(
            buildSuggestion(candidateId, decision, intent, title, body, executed, now),
        )
        Log.i(
            TAG,
            "wakeup:suggestion_saved candidate=$candidateId reason=${decision.reason} " +
                "status=${result.finalStatus} modelIntent=${structured?.intent ?: "fallback"} " +
                "confidence=${structured?.confidence ?: 0.0} exec=${if (intent.canCreateSchedule) "SCHEDULE" else "none"}",
        )
    }

    private fun resolveSuggestionBody(result: HeadlessAgentSession.HeadlessResult, structured: WakeupStructuredOutput?): String =
        structured?.suggestion ?: result.assistantText.trim().ifBlank {
            result.pendingApprovalTitle
                ?.let { "知伴刚才会话中有一项操作需要你确认（$it），已为你保留，未自动执行。" }
                .orEmpty()
        }

    private suspend fun extractEventIntent(
        candidate: com.zhiban.rebuild.data.notification.NotificationCandidateEntity?,
        decision: WakeupDecision.Wake,
        sender: String,
    ): EventIntentExtractor.EventIntent {
        if (decision.reason != REASON_UNSCHEDULED_TIME_INTENT) {
            return EventIntentExtractor.EventIntent(hasScheduleIntent = false)
        }
        val contactName = decision.contactId
            ?.let { database.contactDao().findById(it)?.displayName }
            ?: sender
        val context = loadExtractorContext(decision.contactId)
        val authoritativeInsight = candidate?.let(ScheduleInsight::from)
        return EventIntentExtractor.extract(
            body = candidate?.body.orEmpty(),
            contactName = contactName,
            knownCompanies = context.knownCompanies,
            nowEpochMs = System.currentTimeMillis(),
            knownCompanyAddresses = context.knownCompanyAddresses,
            contacts = context.contacts,
            departure = context.departure,
            pickupCoordinate = context.pickupCoordinate,
            authoritativeStartAtEpochMs = authoritativeInsight?.startAtEpochMs,
            authoritativeDurationMinutes = authoritativeInsight?.durationMinutes,
            authoritativeTitle = authoritativeInsight?.title,
        )
    }

    private fun suggestionTitle(intent: EventIntentExtractor.EventIntent, reason: String, sender: String): String = when {
        intent.canCreateSchedule && !intent.title.isNullOrBlank() -> intent.title!!
        reason == "unlinked_identity_self_description" -> "「$sender」发来了身份信息，可能是新联系人"
        reason == "identity_drift" -> "「$sender」的备注可能改名了"
        reason == "cross_source_crm_opportunity" -> "「$sender」有进行中的机会，刚发来消息"
        else -> "「$sender」刚发来消息，建议看看"
    }

    private fun buildSuggestion(
        candidateId: String,
        decision: WakeupDecision.Wake,
        intent: EventIntentExtractor.EventIntent,
        title: String,
        body: String,
        executed: String,
        now: Long,
    ) = AgentSuggestionEntity(
        suggestionId = "agent-suggestion:wakeup:$candidateId",
        type = suggestionType(decision.reason),
        title = title,
        body = listOf(body, executed).filter(String::isNotBlank).joinToString("\n"),
        contactId = decision.contactId,
        candidateId = candidateId,
        sourceEvent = "NOTIFICATION",
        dedupeKey = "wakeup-$candidateId",
        status = AgentSuggestionStatus.PENDING,
        createdAtEpochMs = now,
        updatedAtEpochMs = now,
        execActionType = if (intent.canCreateSchedule) EXEC_SCHEDULE else null,
        scheduleTitle = intent.title,
        startAtEpochMs = intent.startAtEpochMs,
        durationMinutes = intent.durationMinutes,
        location = intent.location,
        companyFull = intent.companyFull,
        confirmNotes = intent.needsConfirmation.takeIf(List<String>::isNotEmpty)?.joinToString("\n"),
        planId = null,
        pickupLocation = intent.pickupLocation,
        visitLocation = intent.visitLocation,
        visitLocationSource = intent.visitLocationSource,
        contactCandidatesJson = AgentSuggestionCodecs.encodeCandidates(intent.contactCandidates),
        departAtEpochMs = intent.departAtEpochMs,
        travelNote = intent.travelNote,
    )

    private fun suggestionType(reason: String): String = when (reason) {
        "unlinked_identity_self_description" -> AgentSuggestionType.WAKEUP_CONTACT
        "identity_drift" -> AgentSuggestionType.WAKEUP_IDENTITY
        "cross_source_crm_opportunity" -> AgentSuggestionType.WAKEUP_CRM
        "unscheduled_time_intent" -> AgentSuggestionType.WAKEUP_SCHEDULE
        else -> AgentSuggestionType.WAKEUP_GENERAL
    }

    /** extractor 上下文装载：公司全称/公司地址/对接人候选/出发位置/接人点坐标，一次查询批量取齐。 */
    private data class ExtractorContext(
        val knownCompanies: List<String>,
        val knownCompanyAddresses: List<EventIntentExtractor.CompanyAddress>,
        val contacts: List<EventIntentExtractor.ContactCandidate>,
        val departure: EventIntentExtractor.DepartureContext?,
        val pickupCoordinate: EventIntentExtractor.PickupContext?,
    )

    private suspend fun loadExtractorContext(contactId: String?): ExtractorContext {
        val activeContacts = database.contactDao().listActiveForIntelligence()
        val knowledge = database.contactKnowledgeDao()

        // 公司地址检索链：联系人库 contact_addresses(CONTACT) → 公司注册地址注册表(REGISTRY)
        val knownCompanyAddresses = buildList {
            knowledge.listCompanyContactAddresses().forEach { row ->
                add(
                    EventIntentExtractor.CompanyAddress(
                        company = row.company,
                        address = row.formattedAddress,
                        latitude = row.latitude,
                        longitude = row.longitude,
                        source = ADDRESS_SOURCE_CONTACT,
                    ),
                )
            }
            knowledge.listOrganizationsWithAddress().forEach { org ->
                val address = org.registeredAddress?.takeIf(String::isNotBlank)
                if (address != null) {
                    add(
                        EventIntentExtractor.CompanyAddress(
                            company = org.canonicalName,
                            address = address,
                            latitude = org.latitude,
                            longitude = org.longitude,
                            source = ADDRESS_SOURCE_REGISTRY,
                        ),
                    )
                }
            }
        }

        // 对接人候选：全部活跃联系人（含公司/职位），由 extractor 按公司过滤
        val contacts = activeContacts.map {
            EventIntentExtractor.ContactCandidate(
                contactId = it.contactId,
                name = it.displayName,
                title = it.title,
                company = it.company,
            )
        }

        // 出发位置：本人（owner 联系人）的 HOME 地址优先；无地址/无坐标则留空进待确认
        val departure = database.contactKnowledgeDao().listActiveOwnerContactLinks()
            .firstOrNull()
            ?.contactId
            ?.let { ownerId -> database.contactKnowledgeDao().listAddresses(ownerId) }
            ?.let { addresses -> addresses.firstOrNull { it.kind == ADDRESS_KIND_HOME } ?: addresses.firstOrNull() }
            ?.let { addr ->
                EventIntentExtractor.DepartureContext(
                    locationName = addr.formattedAddress.takeIf(String::isNotBlank),
                    latitude = addr.latitude,
                    longitude = addr.longitude,
                )
            }

        // 接人点坐标：接送对象（消息发送者）联系人的地址
        val pickupCoordinate = contactId
            ?.let { cid -> database.contactKnowledgeDao().listAddresses(cid) }
            ?.firstOrNull { it.latitude != null && it.longitude != null }
            ?.let { addr ->
                EventIntentExtractor.PickupContext(
                    locationName = addr.formattedAddress.takeIf(String::isNotBlank),
                    latitude = addr.latitude,
                    longitude = addr.longitude,
                )
            }

        return ExtractorContext(
            knownCompanies = activeContacts.mapNotNull { it.company?.takeIf(String::isNotBlank) }.distinct(),
            knownCompanyAddresses = knownCompanyAddresses,
            contacts = contacts,
            departure = departure,
            pickupCoordinate = pickupCoordinate,
        )
    }

    private companion object {
        const val TAG = "AgentWakeup"
        const val TRIGGER_BUFFER_CAPACITY = 64
        const val TRIGGER_DEBOUNCE_MS = 5_000L
        const val MAX_PROMPT_BODY_LENGTH = 600
        const val REASON_UNSCHEDULED_TIME_INTENT = "unscheduled_time_intent"
        const val EXEC_SCHEDULE = "SCHEDULE"
        const val ADDRESS_SOURCE_CONTACT = "CONTACT"
        const val ADDRESS_SOURCE_REGISTRY = "REGISTRY"
        const val ADDRESS_KIND_HOME = "HOME"
    }
}
