package com.zhiban.rebuild.data.contact.enrichment

import android.util.Log
import androidx.room.withTransaction
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.autowrite.ActionDecision
import com.zhiban.rebuild.data.autowrite.ActionPolicy
import com.zhiban.rebuild.data.autowrite.AutoWriteAuditDraft
import com.zhiban.rebuild.data.autowrite.AutoWriteToolNames
import com.zhiban.rebuild.data.autowrite.ChangeLogEntity
import com.zhiban.rebuild.data.autowrite.ReversibleWriteReadiness
import com.zhiban.rebuild.data.autowrite.insertVisibleAutoWrite
import com.zhiban.rebuild.data.common.ConflatedDebouncedTrigger
import com.zhiban.rebuild.data.completion.ContactCompletionRepository
import com.zhiban.rebuild.data.contact.ContactEnrichmentCandidateEntity
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.notification.MessagePlatformCapabilities
import com.zhiban.rebuild.data.notification.NotificationCandidateEntity
import com.zhiban.rebuild.data.suggestion.AgentSuggestionCodecs
import com.zhiban.rebuild.data.suggestion.AgentSuggestionEntity
import com.zhiban.rebuild.data.suggestion.AgentSuggestionRepository
import com.zhiban.rebuild.data.suggestion.AgentSuggestionStatus
import com.zhiban.rebuild.data.suggestion.AgentSuggestionType
import com.zhiban.rebuild.foundation.RuntimeToolRisk
import com.zhiban.rebuild.foundation.changeIdFor
import com.zhiban.rebuild.foundation.runSuspendCatching
import com.zhiban.rebuild.foundation.sha256
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 消息正文 → 联系人资料补全(用户拍板的口径:agent 有把握的自动写,拿不准的出建议卡)。
 * 触发后扫描最近 24h 已关联且结构成熟的平台来消息,本地廉价闸门过滤后付 LLM 抽取对方自述的
 * 公司/职位/手机号;字段空缺才写、绝不覆盖已有值。高置信(≥0.85)走可撤销自动写
 * (REVERSIBLE_AUTO_WRITE + 收据),低置信落「智能完善」候选卡,确认后由既有闭环写入。
 * 幂等(每条消息只处理一次)且尽力而为,任何失败只记日志、不打断消息感知。
 */
@Singleton
internal class MessageContactCompletionCoordinator @Inject constructor(
    private val database: AgentDatabase,
    private val extractor: MessageContactFieldExtraction,
    private val completion: ContactCompletionRepository,
    private val suggestions: AgentSuggestionRepository,
) {
    private val processMutex = Mutex()
    private val trigger = ConflatedDebouncedTrigger(
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO),
        debounceMs = TRIGGER_DEBOUNCE_MS,
        onFailure = { failure -> Log.w(TAG, "completion:scan_failure", failure) },
        action = ::processOnce,
    )

    /** 廉价非阻塞信号(与回复建议同模式):受支持平台来消息后由感知管线触发。 */
    fun onIncomingActivity() {
        trigger.signal()
    }

    internal suspend fun processOnce() {
        processMutex.withLock {
            val now = System.currentTimeMillis()
            // 游标式分页扫描:24h 窗口内消息可能远超一页,固定 LIMIT 会把旧消息的补全请求永久漏掉。
            // 处理幂等(幂等键),重复扫描无害。
            MessagePlatformCapabilities.profileExtractionPlatforms.forEach { platform ->
                scanPlatform(platform, now)
            }
        }
    }

    private suspend fun scanPlatform(platform: String, nowEpochMs: Long) {
        var cursor = nowEpochMs - CANDIDATE_WINDOW_MS
        var page: List<NotificationCandidateEntity>
        do {
            page = database.notificationCandidateDao()
                .incomingAttributedAfter(platform, nowEpochMs - CANDIDATE_WINDOW_MS, cursor, SCAN_PAGE_SIZE)
            page.forEach { candidate ->
                runSuspendCatching { processCandidate(candidate, nowEpochMs) }
                    .onFailure { failure ->
                        if (failure is CancellationException) throw failure
                        Log.w(TAG, "completion:candidate_failure", failure)
                    }
            }
            val lastEpoch = page.lastOrNull()?.postedAtEpochMs
            if (lastEpoch == null || lastEpoch <= cursor) break
            cursor = lastEpoch
        } while (page.size == SCAN_PAGE_SIZE)
    }

    private suspend fun processCandidate(candidate: NotificationCandidateEntity, nowEpochMs: Long) {
        val contactId = candidate.linkedContactId ?: return
        val body = candidate.body?.trim()?.takeIf(String::isNotBlank) ?: return
        // 非自述消息：不付 LLM 抽取钱；联系人资料不全且有微信身份 → 主动起草补全消息建议卡。
        if (!looksLikeSelfIntroduction(body)) {
            maybeStageCompletionSuggestion(contactId, candidate, nowEpochMs)
            return
        }
        // 幂等:一条消息的补全只做一次(无论成功与否,避免重复付钱/重复建议)。
        val idempotencyKey = sha256("completion:${candidate.candidateId}")
        if (database.changeLogDao().findByIdempotencyKey(idempotencyKey) != null) return
        val contact = database.contactDao().findRawById(contactId) ?: return
        if (missingCompletionFields(contact).isEmpty()) return

        val extracted = extractor.extract(candidate.candidateId, contact.displayName, body)
        val applicable = extracted.filter { field -> contactField(contact, field.kind) == null }
        if (applicable.isEmpty()) {
            // 自述消息但没抽到可写字段:对方刚发来消息,资料仍不全 → 转主动补全(请对方补充)。
            maybeStageCompletionSuggestion(contactId, candidate, nowEpochMs)
            return
        }

        val autoFields = applicable.filter { it.confidence >= AUTO_APPLY_CONFIDENCE }
        if (autoFields.isNotEmpty()) {
            applyAutoCompletion(candidate, contact, autoFields, idempotencyKey, nowEpochMs)
        } else {
            // 已付过 LLM 但只出建议卡或没结果:打终态标记(与自动写共用幂等键,互斥),
            // 24h 扫描窗口内后续扫描不再为这条消息重复付钱。
            database.changeLogDao().insert(
                ChangeLogEntity(
                    changeId = changeIdFor(sha256("completion-checked:${candidate.candidateId}")),
                    runtimeRunId = null,
                    toolName = "contact.completion.processed",
                    idempotencyKey = idempotencyKey,
                    targetDomain = "NOTIFICATION",
                    targetId = candidate.candidateId,
                    operation = "CHECKED",
                    beforeDigest = null,
                    afterDigest = sha256(candidate.body.orEmpty()),
                    inversePayloadJson = "{}",
                    undoState = "UNAVAILABLE",
                    createdAtEpochMs = nowEpochMs,
                    undoneAtEpochMs = null,
                    originType = "SYSTEM_PERCEPTION",
                ),
            )
        }
        applicable.filter { it.confidence < AUTO_APPLY_CONFIDENCE }.forEach { field ->
            stageSuggestion(candidate, contactId, field, nowEpochMs)
        }
    }

    /**
     * 主动补全（TASK 74 核心）：微信来消息但对方没自述、或自述没抽到可写字段时，
     * 若联系人资料不全且微信可达 → 复用 [ContactCompletionRepository.prepareOutreach]（闸门：
     * 总开关/免打扰/单活跃请求/微信可达/缺字段上限）起草一条「请补充资料」的消息，
     * 落一条 WAKEUP_COMPLETION 建议卡（dedupeKey = completion-suggest-<contactId>，同联系人只产一条）。
     * 用户点「一键转发」→ 建议卡对话框 → completeAndHandoff 拉起微信预填（知伴绝不代发）。
     * 幂等边界：建议已存在（含已忽略）不再重复起草；insert 幂等冲突时撤掉刚起草的请求，不留孤儿。
     */
    private suspend fun maybeStageCompletionSuggestion(contactId: String, candidate: NotificationCandidateEntity, nowEpochMs: Long) {
        if (!MessagePlatformCapabilities.forPlatform(candidate.platform).completionReplyTracking) return
        val suggestionId = completionSuggestionId(contactId)
        if (database.agentSuggestionDao().find(suggestionId) != null) return
        val draft = try {
            completion.prepareOutreach(contactId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "completion:outreach_failed contact=$contactId", failure)
            null
        } ?: return
        val inserted = suggestions.insert(
            AgentSuggestionEntity(
                suggestionId = suggestionId,
                type = AgentSuggestionType.WAKEUP_COMPLETION,
                title = "「${draft.contactName}」的资料还不全，可以一键转发请 TA 补充",
                body = buildString {
                    append("知伴发现「${draft.contactName}」还缺 ")
                    append(draft.fields.joinToString("、") { it.label })
                    append("。已起草好一条微信消息，确认后会跳到微信，请你选择 TA 发送，请 TA 协助补充。")
                },
                contactId = contactId,
                candidateId = candidate.candidateId,
                sourceEvent = "NOTIFICATION",
                dedupeKey = completionSuggestionId(contactId),
                status = AgentSuggestionStatus.PENDING,
                createdAtEpochMs = nowEpochMs,
                updatedAtEpochMs = nowEpochMs,
                execActionType = EXEC_COMPLETION,
                completionRequestId = draft.requestId,
                forwardMessage = draft.draftText,
                missingFieldsJson = AgentSuggestionCodecs.encodeMissingFields(draft.fields),
            ),
        )
        if (!inserted) {
            // 幂等冲突（罕见）：撤掉刚起草的请求，不留孤儿 DRAFTED 挡下次起草。
            try {
                completion.cancel(draft.requestId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "completion:rollback_draft_failed contact=$contactId", failure)
            }
        }
    }

    /** 高置信自动写:一个事务内改联系人 + 落可撤销变更账 + 收据;策略闸门同互动摘要自动写。 */
    private suspend fun applyAutoCompletion(
        candidate: NotificationCandidateEntity,
        contact: ContactEntity,
        fields: List<ExtractedContactField>,
        idempotencyKey: String,
        nowEpochMs: Long,
    ) {
        if (ActionPolicy().evaluate(
                RuntimeToolRisk.REVERSIBLE_AUTO_WRITE,
                reversibleWriteReadiness = ReversibleWriteReadiness.Ready,
            ) != ActionDecision.AutoExecuteReversibleWrite
        ) {
            return
        }
        database.withTransaction {
            val current = database.contactDao().findRawById(contact.contactId) ?: return@withTransaction
            // 事务内重读后再过滤一次空缺,避免与并发写入竞争后覆盖用户刚填的值。
            val stillMissing = fields.filter { field -> contactField(current, field.kind) == null }
            if (stillMissing.isEmpty()) return@withTransaction
            val before = canonicalContactCompletionDigest(current)
            val updated = stillMissing.fold(current) { acc, field -> acc.withCompletionField(field) }
            val after = canonicalContactCompletionDigest(updated)
            val inverse = buildJsonObject {
                put(
                    "fields",
                    buildJsonObject {
                        COMPLETION_FIELD_NAMES.forEach { (kind, name) ->
                            val old = contactField(current, kind)
                            if (old == null) put(name, JsonNull) else put(name, JsonPrimitive(old))
                        }
                    },
                )
            }
            val summary = stillMissing.joinToString(" · ") { field ->
                "${completionFieldLabel(field.kind)}：${field.value}"
            }
            val changeId = changeIdFor(idempotencyKey)
            database.contactDao().update(
                updated.copy(updatedAtEpochMs = nowEpochMs),
            )
            database.insertVisibleAutoWrite(
                AutoWriteAuditDraft(
                    changeId = changeId,
                    runtimeRunId = null,
                    toolName = AutoWriteToolNames.CONTACT_COMPLETION,
                    idempotencyKey = idempotencyKey,
                    targetDomain = "CONTACT",
                    targetId = current.contactId,
                    operation = "UPDATE",
                    beforeDigest = before,
                    afterDigest = after,
                    inversePayloadJson = inverse.toString(),
                    originType = "SYSTEM_PERCEPTION",
                    subjectContactId = current.contactId,
                    sourceType = candidate.platform,
                    sourceRef = candidate.sourceKey,
                    confidence = stillMissing.minOf(ExtractedContactField::confidence),
                    presentationType = "CONTACT_COMPLETION",
                    correctionRoute = "CONTACT_PROFILE",
                    createdAtEpochMs = nowEpochMs,
                    summary = summary,
                ),
            )
        }
    }

    /** 低置信落「智能完善」候选卡,确认后由既有闭环写入;同请求+字段确定性 id,重复消息不会堆卡。 */
    private suspend fun stageSuggestion(candidate: NotificationCandidateEntity, contactId: String, field: ExtractedContactField, nowEpochMs: Long) {
        database.contactKnowledgeDao().insertEnrichmentCandidateIfAbsent(
            ContactEnrichmentCandidateEntity(
                candidateId = "message-${candidate.candidateId}-${field.kind.lowercase()}",
                contactId = contactId,
                providerId = "message-extraction",
                fieldKind = enrichmentFieldKind(field.kind),
                proposedValueJson = buildJsonObject {
                    when (field.kind) {
                        MessageContactFieldKinds.COMPANY -> put("company", field.value)
                        MessageContactFieldKinds.TITLE -> put("title", field.value)
                        MessageContactFieldKinds.PHONE -> put("phone", field.value)
                    }
                }.toString(),
                sourceRef = messageSourceLabel(candidate.platform),
                confidence = field.confidence,
                status = "PENDING",
                observedAtEpochMs = candidate.postedAtEpochMs,
                expiresAtEpochMs = nowEpochMs + SUGGESTION_TTL_MS,
                createdAtEpochMs = nowEpochMs,
                updatedAtEpochMs = nowEpochMs,
            ),
        )
    }

    private companion object {
        const val TAG = "MessageContactCompletion"
        const val TRIGGER_DEBOUNCE_MS = 2_500L
        const val CANDIDATE_WINDOW_MS = 24 * 60 * 60 * 1_000L
        const val SCAN_PAGE_SIZE = 50
        const val SUGGESTION_TTL_MS = 7L * 24 * 60 * 60 * 1_000
        const val AUTO_APPLY_CONFIDENCE = 0.85

        /** 补全建议卡的执行动作类型（accept 时走 completeAndHandoff）。 */
        const val EXEC_COMPLETION = "CONTACT_COMPLETION"

        /** 幂等建议 id 与 dedupeKey：同一联系人只产一条补全建议（含已忽略，避免重复打扰）。 */
        fun completionSuggestionId(contactId: String): String = "completion-suggest-$contactId"
    }
}

/** 补全自动写的校验摘要:撤销时比对当前值是否仍等于写入后的值,变了就拒绝撤销(先纠正)。 */
internal fun canonicalContactCompletionDigest(contact: ContactEntity): String = sha256(
    buildString {
        COMPLETION_FIELD_NAMES.forEach { (_, name) ->
            append(name).append('=').append(contactField(contact, name)).append('\n')
        }
    },
)

internal fun ContactEntity.withCompletionField(field: ExtractedContactField): ContactEntity = when (field.kind) {
    MessageContactFieldKinds.COMPANY -> copy(company = field.value)
    MessageContactFieldKinds.TITLE -> copy(title = field.value)
    MessageContactFieldKinds.PHONE -> copy(phone = field.value)
    else -> this
}

internal fun completionFieldLabel(kind: String): String = when (kind) {
    MessageContactFieldKinds.COMPANY -> "公司全称"
    MessageContactFieldKinds.TITLE -> "职位"
    MessageContactFieldKinds.PHONE -> "电话"
    else -> kind
}

/** 抽取字段 → 联系人列名;COMPLETION_FIELD_NAMES 的顺序即摘要排序。 */
internal val COMPLETION_FIELD_NAMES = listOf(
    MessageContactFieldKinds.COMPANY to "company",
    MessageContactFieldKinds.TITLE to "title",
    MessageContactFieldKinds.PHONE to "phone",
)

internal fun contactField(contact: ContactEntity, kindOrName: String): String? = when (kindOrName) {
    MessageContactFieldKinds.COMPANY, "company" -> contact.company?.takeIf(String::isNotBlank)
    MessageContactFieldKinds.TITLE, "title" -> contact.title?.takeIf(String::isNotBlank)
    MessageContactFieldKinds.PHONE, "phone" -> contact.phone?.takeIf(String::isNotBlank)
    else -> null
}

internal fun missingCompletionFields(contact: ContactEntity): List<String> =
    COMPLETION_FIELD_NAMES.map { it.first }.filter { contactField(contact, it) == null }

internal fun looksLikeSelfIntroduction(body: String): Boolean = PHONE_HINT.containsMatchIn(body) || INTRO_HINTS.any(body::contains)

private val PHONE_HINT = Regex("""(?<!\d)1[3-9]\d{9}(?!\d)""")
private val INTRO_HINTS = listOf("我是", "我叫", "我们公司", "任职", "在您这", "这是我的", "联系电话")

/** 建议卡走既有智能完善闭环的 fieldKind 词汇。 */
internal fun enrichmentFieldKind(kind: String): String = when (kind) {
    MessageContactFieldKinds.COMPANY -> "ORGANIZATION"
    MessageContactFieldKinds.TITLE -> "EMPLOYMENT"
    MessageContactFieldKinds.PHONE -> "COMMUNICATION_METHOD"
    else -> kind
}

internal fun messageSourceLabel(platform: String): String = when (platform) {
    "WECHAT" -> "微信消息"
    "QQ" -> "QQ 消息"
    "WEWORK" -> "企业微信消息"
    else -> "消息"
}
