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
import com.zhiban.rebuild.data.contact.ContactEnrichmentCandidateEntity
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.notification.NotificationCandidateEntity
import com.zhiban.rebuild.foundation.RuntimeToolRisk
import com.zhiban.rebuild.foundation.changeIdFor
import com.zhiban.rebuild.foundation.runSuspendCatching
import com.zhiban.rebuild.foundation.sha256
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 消息正文 → 联系人资料补全(用户拍板的口径:agent 有把握的自动写,拿不准的出建议卡)。
 * 触发后扫描最近 24h 已关联的微信来消息,本地廉价闸门过滤后付 LLM 抽取对方自述的
 * 公司/职位/手机号;字段空缺才写、绝不覆盖已有值。高置信(≥0.85)走可撤销自动写
 * (REVERSIBLE_AUTO_WRITE + 收据),低置信落「智能完善」候选卡,确认后由既有闭环写入。
 * 幂等(每条消息只处理一次)且尽力而为,任何失败只记日志、不打断消息感知。
 */
@Singleton
internal class MessageContactCompletionCoordinator @Inject constructor(
    private val database: AgentDatabase,
    private val extractor: MessageContactFieldExtraction,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val triggers = Channel<Unit>(capacity = Channel.CONFLATED)
    private val processMutex = Mutex()

    @Volatile
    private var consumerStarted = false

    /** 廉价非阻塞信号(与回复建议同模式):微信来消息后由感知管线触发。 */
    fun onIncomingWechatActivity() {
        ensureConsumerStarted()
        triggers.trySend(Unit)
    }

    @Synchronized
    private fun ensureConsumerStarted() {
        if (consumerStarted) return
        consumerStarted = true
        scope.launch {
            for (trigger in triggers) {
                delay(TRIGGER_DEBOUNCE_MS)
                runSuspendCatching { processOnce() }
                    .onFailure { failure ->
                        if (failure is CancellationException) throw failure
                        Log.w(TAG, "completion:scan_failure", failure)
                    }
            }
        }
    }

    internal suspend fun processOnce() {
        processMutex.withLock {
            val now = System.currentTimeMillis()
            database.notificationCandidateDao()
                .recentIncomingAttributed(WECHAT_PLATFORM, now - CANDIDATE_WINDOW_MS, CANDIDATE_LIMIT)
                .forEach { candidate ->
                    runSuspendCatching { processCandidate(candidate, now) }
                        .onFailure { failure ->
                            if (failure is CancellationException) throw failure
                            Log.w(TAG, "completion:candidate_failure", failure)
                        }
                }
        }
    }

    private suspend fun processCandidate(candidate: NotificationCandidateEntity, nowEpochMs: Long) {
        val contactId = candidate.linkedContactId ?: return
        val body = candidate.body?.trim()?.takeIf(String::isNotBlank) ?: return
        // 廉价本地闸门:消息里既没有手机号也没有自述信号,不值得付 LLM 钱。
        if (!looksLikeSelfIntroduction(body)) return
        // 幂等:一条消息的补全只做一次(无论成功与否,避免重复付钱/重复建议)。
        val idempotencyKey = sha256("completion:${candidate.candidateId}")
        if (database.changeLogDao().findByIdempotencyKey(idempotencyKey) != null) return
        val contact = database.contactDao().findRawById(contactId) ?: return
        if (missingCompletionFields(contact).isEmpty()) return

        val extracted = extractor.extract(candidate.candidateId, contact.displayName, body)
        val applicable = extracted.filter { field -> contactField(contact, field.kind) == null }
        if (applicable.isEmpty()) return

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
                reversibleWriteReadiness = ReversibleWriteReadiness(true, true, true),
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
                sourceRef = "微信消息",
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
        const val WECHAT_PLATFORM = "WECHAT"
        const val TRIGGER_DEBOUNCE_MS = 2_500L
        const val CANDIDATE_WINDOW_MS = 24 * 60 * 60 * 1_000L
        const val CANDIDATE_LIMIT = 30
        const val SUGGESTION_TTL_MS = 7L * 24 * 60 * 60 * 1_000
        const val AUTO_APPLY_CONFIDENCE = 0.85
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
