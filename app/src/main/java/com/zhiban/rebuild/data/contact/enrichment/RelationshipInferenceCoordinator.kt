package com.zhiban.rebuild.data.contact.enrichment

import android.util.Log
import androidx.room.withTransaction
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.autowrite.ActionDecision
import com.zhiban.rebuild.data.autowrite.ActionPolicy
import com.zhiban.rebuild.data.autowrite.AutoWriteAuditDraft
import com.zhiban.rebuild.data.autowrite.AutoWriteToolNames
import com.zhiban.rebuild.data.autowrite.ReversibleWriteReadiness
import com.zhiban.rebuild.data.autowrite.insertVisibleAutoWrite
import com.zhiban.rebuild.data.common.ConflatedDebouncedTrigger
import com.zhiban.rebuild.data.contact.ContactEnrichmentCandidateEntity
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.RelationshipEdgeEntity
import com.zhiban.rebuild.data.contact.RelationshipPersonIds
import com.zhiban.rebuild.foundation.RuntimeToolRisk
import com.zhiban.rebuild.foundation.changeIdFor
import com.zhiban.rebuild.foundation.runSuspendCatching
import com.zhiban.rebuild.foundation.sha256
import com.zhiban.rebuild.relationship.RelationshipTaxonomy
import com.zhiban.rebuild.runtime.personalization.UserProfileStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 关系类型推断结果。 */
data class InferredRelationship(val relationType: String, val confidence: Double, val evidence: String)

/** 关系推断能力的最小接口;真实现走 LLM,测试可注入固定结果。 */
internal fun interface RelationshipTypeExtraction {
    suspend fun infer(requestId: String, contactName: String, evidence: String): InferredRelationship?
}

/**
 * 关系图谱自动补全(用户拍板口径:有把握自动写可撤,拿不准出建议卡):
 * - 本地信号(零 LLM):联系人与"我"同公司 → 自动写 COLLEAGUE 边(置信度 0.95);
 * - LLM 推断:从互动摘要推断 客户/供应商/同事/朋友/家人 → ≥0.85 自动写可撤销边,
 *   拿不准落「智能完善」建议卡(RELATIONSHIP 字段),确认后由既有闭环写确认边;
 * - 已有活跃边的联系人不再动;每条幂等;只写 SELF↔联系人 的边。
 */
@Singleton
internal class RelationshipInferenceCoordinator @Inject constructor(
    private val database: AgentDatabase,
    private val extractor: RelationshipTypeExtraction,
    private val userProfileStore: UserProfileStore,
) {
    private val processMutex = Mutex()
    private val trigger = ConflatedDebouncedTrigger(
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO),
        debounceMs = TRIGGER_DEBOUNCE_MS,
        onFailure = { failure -> Log.w(TAG, "relation:scan_failure", failure) },
        action = ::processOnce,
    )

    fun onIncomingWechatActivity() {
        trigger.signal()
    }

    internal suspend fun processOnce() {
        processMutex.withLock {
            val now = System.currentTimeMillis()
            runSuspendCatching { applyCompanyColleagueEdges(now) }
                .onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    Log.w(TAG, "relation:company_failure", failure)
                }
            runSuspendCatching { inferFromInteractions(now) }
                .onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    Log.w(TAG, "relation:inference_failure", failure)
                }
        }
    }

    /** ③ 同公司 → 同事(本地信号,零 LLM)。幂等键保证撤销后不会被重新写回。 */
    private suspend fun applyCompanyColleagueEdges(nowEpochMs: Long) {
        val identity = resolveOwnerIdentity()
        val ownerIds = identity.ownerIds
        // 本人公司多源回退:本人卡片 company → 本人当前任职记录(companyNameSnapshot)。
        // 仅读本人卡片常因本人资料未补全而漏判整批同事,任职记录是第二可靠来源。
        val ownerCompany = identity.company ?: database.contactIntelligenceDao().listAllEmployments()
            .firstOrNull { it.personId in ownerIds && it.currentState == "CURRENT" }
            ?.companyNameSnapshot?.trim()?.takeIf(String::isNotBlank)
        val ownerKey = ownerCompany?.normalizedCompanyKey() ?: run {
            Log.i(TAG, "relation:company_scan ownerCompany=null ownerIds=${ownerIds.size} skipped")
            return
        }
        val existing = database.relationshipEdgeDao()
            .touching(listOf(RelationshipPersonIds.SELF), MAX_EDGES)
            .mapTo(hashSetOf()) { it.toContactId.takeIf { id -> id != RelationshipPersonIds.SELF } ?: it.fromContactId }
        val candidates = database.contactDao().listActiveForIntelligence()
            // 排除本人联系人卡片:「我↔我自己」的同事边是语义错误,与互动投影层口径一致。
            .filter { it.contactId !in ownerIds }
            .filter { contact ->
                val key = contact.company?.normalizedCompanyKey() ?: return@filter false
                // 精确相等或包含匹配(全称 vs 简称),短名 ≥4 字符防误判。
                key == ownerKey || companyNamesMatch(key, ownerKey)
            }
        var matched = 0
        candidates.forEach { contact ->
            if (contact.contactId in existing) return@forEach
            val idempotencyKey = sha256("relation-company:${contact.contactId}")
            if (database.changeLogDao().findByIdempotencyKey(idempotencyKey) != null) return@forEach
            matched++
            writeAutoEdge(
                contactId = contact.contactId,
                relationType = "COLLEAGUE",
                confidence = 0.95,
                evidence = "与你是同公司（$ownerCompany）",
                evidenceRefs = emptyList(),
                idempotencyKey = idempotencyKey,
                nowEpochMs = nowEpochMs,
            )
        }
        Log.i(
            TAG,
            "relation:company_scan ownerKey=$ownerKey owner=$ownerCompany " +
                "candidates=${candidates.size} matched=$matched existing=${existing.size}",
        )
    }

    /** 本人身份解析结果。 */
    private data class OwnerIdentity(val ownerIds: Set<String>, val company: String?)

    /**
     * 本人身份解析——用户拍板口径:「本人资料不必存在于联系人列表」。
     *
     * 公司来源(权威优先):
     * 1. 「我的」页个人资料 company 字段 —— 用户主动填写,最权威,不依赖任何联系人卡片;
     * 2. owner_contact_links 中用户确认过的本人卡片(「与我的资料合并」)的 company;
     * 3. profile 手机/微信/姓名匹配联系人库命中的本人卡片 company(仅内存级,不写 link)。
     *
     * ownerIds 仅用于构建「本人排除集」(避免给本人卡片写同事/朋友等边),不是判定前提:
     * 即使联系人库完全没有本人卡片,只要 profile.company 有值,同公司推断照常工作。
     */
    private suspend fun resolveOwnerIdentity(): OwnerIdentity {
        val profile = userProfileStore.profile.value
        val profileCompany = profile.company.trim().takeIf(String::isNotBlank)
        val ownerLinks = database.contactKnowledgeDao().listActiveOwnerContactLinks()
        val linkIds = ownerLinks.mapTo(hashSetOf()) { it.contactId }
        val all = database.contactDao().listActiveForIntelligence()
        val matchedIds = matchOwnerContactIds(profile, all)
        val ownerIds = (linkIds + matchedIds).toHashSet()
        // 联系人卡片公司(仅 owner link 与 profile 命中者),作为 profile.company 缺失时的回退。
        val contactCompany = (linkIds + matchedIds).firstNotNullOfOrNull { id ->
            all.firstOrNull { it.contactId == id }?.company?.trim()?.takeIf(String::isNotBlank)
        }
        val company = profileCompany ?: contactCompany
        val source = when {
            profileCompany != null -> "profile_company"
            contactCompany != null -> if (linkIds.isNotEmpty()) "owner_link_contact" else "profile_matched_contact"
            else -> "none"
        }
        Log.i(
            TAG,
            "relation:owner_profile_match source=$source links=${linkIds.size} matched=${matchedIds.size} " +
                "owners=${ownerIds.size} company=${company ?: "null"}",
        )
        return OwnerIdentity(ownerIds, company)
    }

    /** ② 从互动摘要 LLM 推断关系类型。 */
    private suspend fun inferFromInteractions(nowEpochMs: Long) {
        // 与同公司推断共用同一本人识别口径,避免 profile 匹配到的本人卡片被误推断关系。
        val ownerIds = resolveOwnerIdentity().ownerIds
        val existing = database.relationshipEdgeDao()
            .touching(listOf(RelationshipPersonIds.SELF), MAX_EDGES)
            .mapTo(hashSetOf()) { it.toContactId.takeIf { id -> id != RelationshipPersonIds.SELF } ?: it.fromContactId }
        val interactions = database.factDao().observeRecentInteractions(nowEpochMs, MAX_FACTS).first()
        interactions.groupBy { it.contactId }.forEach { (contactId, facts) ->
            val idempotencyKey = sha256("relation-infer:$contactId")
            if (contactId == null || contactId in existing || contactId in ownerIds) return@forEach
            if (database.changeLogDao().findByIdempotencyKey(idempotencyKey) != null) return@forEach
            val contact = database.contactDao().findRawById(contactId) ?: return@forEach
            val evidence = facts.take(5).joinToString(" · ") { it.textContent }
            // 本地规则启发式优先（零 LLM）：未配置 provider 时 LLM 推断直接返回 null，
            // 图谱永远只剩「有联系 · 来自消息互动」。规则给出保守归类后：
            // ≥AUTO_APPLY_CONFIDENCE 直接自动写可撤销边，否则落建议卡（与 LLM 同一处理分支）。
            val local = LocalRelationshipHeuristics.infer(contact, evidence)
            val inferred = if (local != null) {
                Log.i(TAG, "relation:local_hit contact=$contactId type=${local.relationType} conf=${local.confidence}")
                local
            } else {
                extractor.infer("relation-$contactId", contact.displayName, evidence) ?: return@forEach
            }
            if (inferred.relationType !in INFERABLE_RELATION_TYPES) return@forEach
            if (inferred.confidence >= AUTO_APPLY_CONFIDENCE) {
                writeAutoEdge(
                    contactId = contactId,
                    relationType = inferred.relationType,
                    confidence = inferred.confidence,
                    evidence = inferred.evidence,
                    evidenceRefs = facts.map { it.factId }.take(5),
                    idempotencyKey = idempotencyKey,
                    nowEpochMs = nowEpochMs,
                )
            } else {
                // 已付过 LLM:打终态标记防重复付费;建议卡走智能完善闭环。
                database.changeLogDao().insert(
                    com.zhiban.rebuild.data.autowrite.ChangeLogEntity(
                        changeId = changeIdFor(sha256("relation-checked:$contactId")),
                        runtimeRunId = null,
                        toolName = "contact.relationship.processed",
                        idempotencyKey = idempotencyKey,
                        targetDomain = "RELATIONSHIP",
                        targetId = contactId,
                        operation = "CHECKED",
                        beforeDigest = null,
                        afterDigest = sha256(evidence),
                        inversePayloadJson = "{}",
                        undoState = "UNAVAILABLE",
                        createdAtEpochMs = nowEpochMs,
                        undoneAtEpochMs = null,
                        originType = "SYSTEM_PERCEPTION",
                    ),
                )
                stageSuggestion(contactId, inferred, nowEpochMs)
            }
        }
    }

    private suspend fun writeAutoEdge(
        contactId: String,
        relationType: String,
        confidence: Double,
        evidence: String,
        evidenceRefs: List<String>,
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
            if (database.relationshipEdgeDao()
                    .touching(listOf(RelationshipPersonIds.SELF), MAX_EDGES)
                    .any { it.fromContactId == contactId || it.toContactId == contactId }
            ) {
                return@withTransaction
            }
            val edgeId = "auto-edge-user:self-$contactId-$relationType".take(220)
            val edge = RelationshipEdgeEntity(
                edgeId = edgeId,
                fromContactId = RelationshipPersonIds.SELF,
                toContactId = contactId,
                relationType = relationType,
                evidenceDigest = evidence.take(120),
                evidenceRefsJson = evidenceRefs.toString(),
                confidence = confidence,
                userConfirmed = false,
                skillId = null,
                status = "ACTIVE",
                createdAtEpochMs = nowEpochMs,
                updatedAtEpochMs = nowEpochMs,
            )
            database.relationshipEdgeDao().upsert(edge)
            database.insertVisibleAutoWrite(
                AutoWriteAuditDraft(
                    changeId = changeIdFor(idempotencyKey),
                    runtimeRunId = null,
                    toolName = AutoWriteToolNames.RELATIONSHIP_AUTO_INFER,
                    idempotencyKey = idempotencyKey,
                    targetDomain = "RELATIONSHIP",
                    targetId = edgeId,
                    operation = "CREATE",
                    beforeDigest = null,
                    afterDigest = canonicalRelationshipDigest(edge),
                    inversePayloadJson = buildJsonObject { put("edgeId", edgeId) }.toString(),
                    originType = "SYSTEM_PERCEPTION",
                    subjectContactId = contactId,
                    sourceType = "SYSTEM_PERCEPTION",
                    sourceRef = edgeId,
                    confidence = confidence,
                    presentationType = "RELATIONSHIP_INFERRED",
                    correctionRoute = "RELATIONSHIP_EDITOR",
                    createdAtEpochMs = nowEpochMs,
                    summary = "关系：${RelationshipTaxonomy.find(relationType)?.displayName ?: relationType}（${evidence.take(80)}）",
                ),
            )
        }
    }

    private suspend fun stageSuggestion(contactId: String, inferred: InferredRelationship, nowEpochMs: Long) {
        database.contactKnowledgeDao().insertEnrichmentCandidateIfAbsent(
            ContactEnrichmentCandidateEntity(
                candidateId = "relation-$contactId-${inferred.relationType.lowercase()}",
                contactId = contactId,
                providerId = "relationship-inference",
                fieldKind = RELATIONSHIP_FIELD_KIND,
                proposedValueJson = buildJsonObject {
                    put("relationType", inferred.relationType)
                    put("relationLabel", RelationshipTaxonomy.find(inferred.relationType)?.displayName ?: inferred.relationType)
                    put("evidence", inferred.evidence.take(120))
                }.toString(),
                sourceRef = "互动推断",
                confidence = inferred.confidence,
                status = "PENDING",
                observedAtEpochMs = nowEpochMs,
                expiresAtEpochMs = nowEpochMs + SUGGESTION_TTL_MS,
                createdAtEpochMs = nowEpochMs,
                updatedAtEpochMs = nowEpochMs,
            ),
        )
    }

    internal companion object {
        const val TAG = "RelationshipInference"
        const val TRIGGER_DEBOUNCE_MS = 2_500L
        const val MAX_EDGES = 200
        const val MAX_FACTS = 200
        const val SUGGESTION_TTL_MS = 7L * 24 * 60 * 60 * 1_000
        const val AUTO_APPLY_CONFIDENCE = 0.85
        const val RELATIONSHIP_FIELD_KIND = "RELATIONSHIP"
        val INFERABLE_RELATION_TYPES = setOf("COLLEAGUE", "CUSTOMER", "SUPPLIER", "FRIEND", "FAMILY")
    }
}

/** 关系边的校验摘要:撤销时比对当前值是否仍等于写入后的值。 */
internal fun canonicalRelationshipDigest(edge: RelationshipEdgeEntity): String = sha256(
    "${edge.fromContactId}|${edge.toContactId}|${edge.relationType}|${edge.status}",
)

/** 关系类型 LLM 抽取:从互动摘要推断对方与用户的关系,response_format 约束 JSON。 */
@Singleton
internal class RelationshipTypeExtractor @Inject constructor(
    private val provider: com.zhiban.rebuild.provider.ProviderAdapter,
    private val profileStore: com.zhiban.rebuild.provider.ProviderProfileStore,
) : RelationshipTypeExtraction {
    override suspend fun infer(requestId: String, contactName: String, evidence: String): InferredRelationship? {
        val profile = profileStore.load() ?: return null
        val capability = runSuspendCatching { provider.probe(profile, requestId) }.getOrNull() ?: return null
        repeat(MAX_ATTEMPTS) { attempt ->
            val result = runSuspendCatching { inferOnce(requestId, contactName, evidence, profile, capability, attempt) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
            if (result != null) return result
        }
        return null
    }

    private suspend fun inferOnce(
        requestId: String,
        contactName: String,
        evidence: String,
        profile: com.zhiban.rebuild.provider.ProviderProfile,
        capability: com.zhiban.rebuild.provider.CapabilitySnapshot,
        attempt: Int,
    ): InferredRelationship? {
        val output = StringBuilder()
        var final = false
        provider.stream(
            com.zhiban.rebuild.provider.ModelRequest(
                requestId = "$requestId-attempt-$attempt",
                channel = com.zhiban.rebuild.provider.OutboundChannel.LLM_INFERENCE,
                profile = profile,
                messages = listOf(
                    com.zhiban.rebuild.provider.ModelMessage(
                        "system",
                        SYSTEM_PROMPT,
                        com.zhiban.rebuild.provider.OutboundSensitivity.PUBLIC,
                        com.zhiban.rebuild.provider.OutboundPurpose.SYSTEM_INSTRUCTION,
                        com.zhiban.rebuild.provider.OutboundProvenance("system_policy", "relationship-infer-v1"),
                    ),
                    com.zhiban.rebuild.provider.ModelMessage(
                        "user",
                        "联系人：$contactName\n互动证据：$evidence\n请推断关系。",
                        com.zhiban.rebuild.provider.OutboundSensitivity.PERSONAL,
                        com.zhiban.rebuild.provider.OutboundPurpose.AUTO_RETRIEVED,
                        com.zhiban.rebuild.provider.OutboundProvenance("interaction_evidence", "$requestId-attempt-$attempt"),
                    ),
                ),
                capability = capability,
                maxTokens = minOf(MAX_OUTPUT_TOKENS, capability.maxOutputTokens),
                jsonSchema = RELATION_SCHEMA,
            ),
        ).collect { event ->
            when (event) {
                is com.zhiban.rebuild.provider.ModelEvent.Delta ->
                    if (output.length + event.text.length <= MAX_OUTPUT_CHARS) output.append(event.text)

                is com.zhiban.rebuild.provider.ModelEvent.Final -> final = true

                is com.zhiban.rebuild.provider.ModelEvent.ToolCall -> error("RELATION_INFER_TOOL_CALL_FORBIDDEN")

                is com.zhiban.rebuild.provider.ModelEvent.Usage -> Unit
            }
        }
        if (!final) return null
        return parseInferredRelationship(output.toString())
    }

    private companion object {
        const val MAX_ATTEMPTS = 2
        const val MAX_OUTPUT_TOKENS = 256
        const val MAX_OUTPUT_CHARS = 1_024
    }
}

/** 容错解析:取第一个 {...};relationType 不在可推断集合或置信度越界即丢弃。 */
internal fun parseInferredRelationship(raw: String): InferredRelationship? {
    val firstBrace = raw.indexOf('{')
    val lastBrace = raw.lastIndexOf('}')
    if (firstBrace < 0 || lastBrace < firstBrace) return null
    val obj = runCatching { Json.parseToJsonElement(raw.substring(firstBrace, lastBrace + 1)).jsonObject }
        .getOrNull() ?: return null
    val relationType = obj["relationType"]?.jsonPrimitive?.contentOrNull?.trim()?.uppercase()
        ?.takeIf { it in RelationshipInferenceCoordinator.INFERABLE_RELATION_TYPES } ?: return null
    val confidence = (obj["confidence"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
        ?.coerceIn(0.0, 1.0) ?: return null
    val evidence = obj["evidence"]?.jsonPrimitive?.contentOrNull?.trim()
        ?.takeIf(String::isNotBlank)?.take(200) ?: "互动推断"
    return InferredRelationship(relationType = relationType, confidence = confidence, evidence = evidence)
}

/**
 * 公司名归一化键:去空白、小写。门槛 2 字——3 字简称(如"九州通")是真实业务场景,
 * 4 字门槛会把本人公司简称直接拒掉导致整批同事漏判。2 字风险由"可撤销自动边"兜底。
 */
internal fun String.normalizedCompanyKey(): String? = trim().replace(Regex("\\s+"), " ").lowercase().replace(" ", "")
    .takeIf { it.length >= 2 }

/** 公司名包含匹配(归一化后):短名 ≥2 字符且完全包含于长名,覆盖"全称 vs 简称"漏判。 */
internal fun companyNamesMatch(a: String, b: String): Boolean {
    if (a == b) return true
    val short = if (a.length <= b.length) a else b
    val long = if (a.length <= b.length) b else a
    return short.length >= 2 && long.contains(short)
}

/**
 * 用「我的」页个人资料（手机号/微信号/姓名 contains）匹配联系人库中的本人卡片。
 * 协调器与图谱 UI 共用同一口径，保证「本人排除集」一致（手机号精确 > 微信号精确 > 姓名包含）。
 */
internal fun matchOwnerContactIds(profile: com.zhiban.rebuild.runtime.personalization.UserProfile, contacts: List<ContactEntity>): Set<String> {
    val phone = profile.phone.trim()
    val wechat = profile.wechatId.trim().trimStart('@').lowercase()
    val names: List<String> = listOf(profile.name, profile.preferredName).map(String::trim).filter(String::isNotBlank)
    val byPhone: List<ContactEntity> = if (phone.isNotBlank()) {
        contacts.filter { it.phone?.trim() == phone }
    } else {
        emptyList()
    }
    val byWechat: List<ContactEntity> = if (wechat.isNotBlank()) {
        contacts.filter { it.wechatId?.trim()?.trimStart('@')?.lowercase() == wechat }
    } else {
        emptyList()
    }
    // 姓名用 contains 而非精确相等:覆盖 displayName 带备注后缀或近音字场景。
    val byName: List<ContactEntity> = if (names.isNotEmpty()) {
        contacts.filter { contact -> names.any { name -> name.isNotBlank() && contact.displayName.trim().contains(name) } }
    } else {
        emptyList()
    }
    return (byPhone + byWechat + byName).mapTo(hashSetOf()) { it.contactId }
}

private val SYSTEM_PROMPT = """
你是知伴的关系推断助手。给定联系人名称和互动证据（来自微信等消息的摘要），推断这个人与用户最可能的关系类型。
规则：
- relationType 只能是 COLLEAGUE(同事)、CUSTOMER(客户)、SUPPLIER(供应商)、FRIEND(朋友)、FAMILY(家人) 之一；
- 只基于证据推断，证据不足或判断不了时 confidence 给 0.5 以下；
- confidence 表示把握：证据明确 0.85-1.0；模糊 0.5-0.8；
- evidence 用一句话说明依据（中文）；
- 严格按 JSON schema 输出。
""".trim()

private val RELATION_SCHEMA = """
{
  "name": "relationship_inference",
  "strict": true,
  "schema": {
    "type": "object",
    "properties": {
      "relationType": {"type": "string", "enum": ["COLLEAGUE", "CUSTOMER", "SUPPLIER", "FRIEND", "FAMILY"]},
      "confidence": {"type": "number"},
      "evidence": {"type": "string"}
    },
    "required": ["relationType", "confidence", "evidence"],
    "additionalProperties": false
  }
}
""".trim()
