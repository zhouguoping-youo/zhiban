package com.zhiban.rebuild.data.completion

import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.contact.ContactProfileCompletenessEvaluator
import com.zhiban.rebuild.data.contact.ContactProfileField
import com.zhiban.rebuild.runtime.config.AgentControlStore
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** [prepareOutreach] 的返回：渲染确认卡所需的视图（请求 id + 联系人 + 要问的字段 + 草稿）。 */
data class ContactCompletionDraft(
    val requestId: String,
    val contactId: String,
    val contactName: String,
    val fields: List<ContactProfileField>,
    val draftText: String,
)

/**
 * 触达跳转缝隙：包装 CommunicationHandoffLauncher（final、无法 fake），让测试能模拟微信在/不在两条路径。
 * 仿 AutoWriteSink/ScheduleReminderSink 的 fun-interface 注入约定；生产实现放在 DI（try/catch 也在那）。
 */
fun interface CompletionHandoff {
    /** 打开目标平台预填面板（用户仍需亲选联系人亲发）。目标应用不可用返回 false。绝不返回"已发送"。 */
    fun openComposer(platform: String, recipientDisplayName: String, message: String): Boolean
}

/**
 * 补全触达的读写面。与 [com.zhiban.rebuild.data.agent.AgentDataRepository] 分开（后者逼近行数上限）。
 *
 * 半自动纪律：草稿只在 [confirmAndHandoff] 里交给微信预填、由用户亲选联系人亲发，知伴绝不代发；
 * handoff 成功才转 AWAITING_REPLY。闸门（总开关/免打扰/单活跃请求/有缺失/微信可达）任一不过即返回 null，
 * 不往 UI 抛异常。
 *
 * Public 类型 + internal 构造（仿 [com.zhiban.rebuild.data.reply.ReplySuggestionRepository]），可跨进
 * public 的 RelationViewModel 而构造留在 DI 后。
 */
class ContactCompletionRepository internal constructor(
    private val database: AgentDatabase,
    private val handoff: CompletionHandoff,
    private val outreachGenerator: ContactCompletionOutreachGenerator,
    private val controls: AgentControlStore,
) {
    private val dao get() = database.contactCompletionRequestDao()
    private val contactDao get() = database.contactDao()
    private val identityDao get() = database.contactIdentityDao()

    /** 该联系人进行中的可操作请求（DRAFTED/AWAITING_REPLY/RESPONSE_RECEIVED，未过期），供 UI 徽标。 */
    fun observeActionable(contactId: String): Flow<List<ContactCompletionRequestEntity>> =
        dao.observeActionableForContact(contactId, System.currentTimeMillis())

    /**
     * 闸门过后起草并落一条 DRAFTED 请求（7 天过期）。任一闸门不过或起草失败返回 null。
     * 确定性 requestId：同一联系人同一字段集重复起草会 upsert 覆盖而非重复。
     */
    suspend fun prepareOutreach(contactId: String): ContactCompletionDraft? {
        if (!controls.contactCompletionEnabled()) return null
        if (controls.isCompletionOptedOut(contactId)) return null
        val now = System.currentTimeMillis()
        if (dao.countActiveForContact(contactId, now) > 0) return null // 每联系人至多一个进行中请求
        val contact = contactDao.findById(contactId) ?: return null
        val identities = identityDao.platformIdentities(contactId)
        // 触达只走微信：无 wechatId 也无 WECHAT 平台身份（stub 的 handle 身份）则无法触达。
        val wechatReachable = !contact.wechatId.isNullOrBlank() || identities.any { it.platform == PLATFORM_WECHAT }
        if (!wechatReachable) return null
        val fieldsToAsk = selectCompletionFieldsToAsk(ContactProfileCompletenessEvaluator.missingFields(contact, identities))
        if (fieldsToAsk.isEmpty()) return null // 资料已完整
        val businessContext = listOfNotNull(contact.company, contact.title).joinToString(" / ").takeIf { it.isNotBlank() }
        val draft = outreachGenerator.generateDraft(contact.displayName, fieldsToAsk, businessContext, "cc-$contactId")
            ?: return null

        val entity = ContactCompletionRequestEntity(
            requestId = contactCompletionRequestId(contactId, fieldsToAsk.joinToString("+") { it.name }),
            contactId = contactId,
            requestedFieldsJson = JsonArray(fieldsToAsk.map { JsonPrimitive(it.name) }).toString(),
            draftText = draft,
            status = ContactCompletionStatus.DRAFTED,
            createdAtEpochMs = now,
            expiresAtEpochMs = now + REQUEST_TTL_MS,
            updatedAtEpochMs = now,
        )
        dao.upsert(entity)
        return ContactCompletionDraft(entity.requestId, contactId, contact.displayName, fieldsToAsk, draft)
    }

    /**
     * 用户确认 → 跳转微信预填（用户亲选联系人亲发）。handoff 成功才转 AWAITING_REPLY（重置 7 天过期）。
     * 微信未装/不可达保持 DRAFTED 并返回 false。
     */
    suspend fun confirmAndHandoff(requestId: String): Boolean {
        val request = dao.findById(requestId) ?: return false
        if (request.status != ContactCompletionStatus.DRAFTED) return false
        val contact = contactDao.findById(request.contactId) ?: return false
        if (!handoff.openComposer(PLATFORM_WECHAT, contact.displayName, request.draftText)) return false
        val now = System.currentTimeMillis()
        dao.markAwaiting(requestId, sentAtEpochMs = now, expiresAtEpochMs = now + REQUEST_TTL_MS, nowEpochMs = now)
        return true
    }

    suspend fun cancel(requestId: String) {
        dao.markStatus(requestId, ContactCompletionStatus.CANCELLED, System.currentTimeMillis())
    }

    /** "不再打扰该联系人"：记入免打扰并撤掉其进行中的请求。 */
    suspend fun optOutContact(contactId: String) {
        controls.setCompletionOptOut(contactId, true)
        dao.cancelActiveForContact(contactId, System.currentTimeMillis())
    }

    private companion object {
        const val PLATFORM_WECHAT = "WECHAT"
        val REQUEST_TTL_MS = 7L * 24 * 60 * 60 * 1_000
    }
}
