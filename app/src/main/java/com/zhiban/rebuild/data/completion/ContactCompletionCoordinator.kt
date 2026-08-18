package com.zhiban.rebuild.data.completion

import android.util.Log
import androidx.room.withTransaction
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.config.AgentControlStore
import com.zhiban.rebuild.data.contact.ContactEnrichmentCandidateEntity
import com.zhiban.rebuild.data.contact.ContactProfileCompletenessEvaluator
import com.zhiban.rebuild.data.notification.NotificationCandidateEntity
import com.zhiban.rebuild.foundation.runSuspendCatching
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

/**
 * 补全闭环的回复检测。触发驱动（新微信消息 T1 / 前台兜底 T2),CONFLATED + 3s 防抖 + Mutex 串行，
 * 仿 [com.zhiban.rebuild.data.reply.ReplySuggestionCoordinator]，让一串消息塌成一次扫描。
 *
 * 每次扫描：先过期超时请求，再在近 7 天已归因的微信来消息里找"某联系人有 AWAITING_REPLY 请求、且这条
 * 晚于发出时间"的 1:1 回复（**群聊排除**——询问是私聊，群消息不算回复），交解析器抽字段、落候选，
 * 抽到才把请求转 RESPONSE_RECEIVED（抽不到保持 AWAITING，7 天后 EXPIRED，对应"回复无字段"负路径）。
 * 尽力而为：单项失败吞掉（重抛 CancellationException)，绝不让检测故障拖垮消息暂存。
 */
@Singleton
internal class ContactCompletionCoordinator @Inject constructor(
    private val database: AgentDatabase,
    private val parser: ContactCompletionResponseParser,
    private val controls: AgentControlStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val triggers = Channel<Unit>(capacity = Channel.CONFLATED)
    private val processMutex = Mutex()

    @Volatile
    private var consumerStarted = false

    /** 廉价非阻塞信号：微信来消息（T1）或 App 回前台（T2)。 */
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
            val dao = database.contactCompletionRequestDao()
            dao.expireAwaitingBefore(now) // 超时收尾与开关无关,总要跑
            reconcileCompleted(now) // 已收到回复的请求:候选都处理完(或用户已手改补齐)就收敛
            if (!controls.contactCompletionEnabled()) return
            // 游标式分页扫描:7 天窗口内消息可能远超一页,固定取最新 20 条会让旧回复永久错过(P2-5)。
            var cursor = controls.completionScanCursor()
            var page: List<NotificationCandidateEntity>
            do {
                page = database.notificationCandidateDao()
                    .incomingAttributedAfter(WECHAT_PLATFORM, now - CANDIDATE_WINDOW_MS, cursor, SCAN_PAGE_SIZE)
                page.forEach { candidate ->
                    runSuspendCatching { processCandidate(candidate, now) }
                        .onFailure { failure ->
                            if (failure is CancellationException) throw failure
                            Log.w(TAG, "completion:candidate_failure", failure)
                        }
                }
                cursor = page.lastOrNull()?.postedAtEpochMs ?: cursor
                controls.saveCompletionScanCursor(cursor)
            } while (page.size == SCAN_PAGE_SIZE)
        }
    }

    /**
     * RESPONSE_RECEIVED → COMPLETED/EXPIRED 的懒对账。判据:
     * 资料已被填满(用户绕过候选直接手改)→ COMPLETED;候选仍待确认→继续等;
     * 有候选被采纳(APPROVED)→ COMPLETED;候选全过期/全驳回且资料仍未补全→ EXPIRED
     * (回复没兑现成资料,如实标记,不再误标 COMPLETED,P2-6)。
     */
    private suspend fun reconcileCompleted(now: Long) {
        database.contactCompletionRequestDao().responseReceivedRequests().forEach { request ->
            runSuspendCatching {
                val prefix = "completion:${request.requestId}"
                val knowledge = database.contactKnowledgeDao()
                val requestDao = database.contactCompletionRequestDao()
                when {
                    contactProfileComplete(request.contactId) ->
                        requestDao.markStatus(request.requestId, ContactCompletionStatus.COMPLETED, now)

                    knowledge.countPendingBySourceRefPrefix(prefix, now) > 0 -> Unit

                    knowledge.countApprovedBySourceRefPrefix(prefix) > 0 ->
                        requestDao.markStatus(request.requestId, ContactCompletionStatus.COMPLETED, now)

                    else -> requestDao.markStatus(request.requestId, ContactCompletionStatus.EXPIRED, now)
                }
            }.onFailure { failure ->
                if (failure is CancellationException) throw failure
                Log.w(TAG, "completion:reconcile_failure", failure)
            }
        }
    }

    private suspend fun contactProfileComplete(contactId: String): Boolean {
        val contact = database.contactDao().findById(contactId) ?: return false
        val identities = database.contactIdentityDao().platformIdentities(contactId)
        return ContactProfileCompletenessEvaluator.missingFields(contact, identities).isEmpty()
    }

    private suspend fun processCandidate(candidate: NotificationCandidateEntity, now: Long) {
        if (candidate.isGroupChat) return // 群聊排除:补全询问是 1:1,群消息不算这条询问的回复
        val contactId = candidate.linkedContactId
            ?: candidate.suggestedContactId?.takeIf { candidate.suggestedContactConfidence >= ATTRIBUTION_THRESHOLD }
            ?: return
        val dao = database.contactCompletionRequestDao()
        val request = dao.findAwaitingForContact(contactId, now) ?: return // 该联系人无进行中请求
        if (candidate.postedAtEpochMs <= (request.sentAtEpochMs ?: 0L)) return // 必须晚于我们发出

        val extraction = parser.extract(request, candidate.body.orEmpty())
        val candidates = parser.buildCompletionCandidates(request, extraction, now)
        // 候选表+请求表跨表写,同一事务(R10/P2-1):候选与状态转换要么一起落地,要么都不落。
        database.withTransaction { stageCandidatesAndMark(request, candidates, now) }
    }

    /**
     * 抽到字段就转 RESPONSE_RECEIVED 并挂上溯源候选;抽不到保持 AWAITING(7 天后 EXPIRED)。
     * 用 REPLACE 落候选而非 IGNORE:请求过期后重新触达复用同一确定性 candidateId,二次回复的新值必须
     * 覆盖旧 PENDING 候选,且状态推进不依赖插入是否成功——否则候选 PK 全冲突时请求卡 AWAITING、
     * 二次回复丢失(P1-1)。重扫同一回复 REPLACE 同值,依然幂等。事务边界由调用方
     * [processCandidate] 包好(R10)。
     */
    internal suspend fun stageCandidatesAndMark(request: ContactCompletionRequestEntity, candidates: List<ContactEnrichmentCandidateEntity>, now: Long) {
        candidates.forEach { staged -> database.contactKnowledgeDao().upsertEnrichmentCandidate(staged) }
        candidates.firstOrNull()?.let {
            database.contactCompletionRequestDao().markResponseReceived(request.requestId, it.candidateId, now)
        }
    }

    private companion object {
        const val TAG = "ContactCompletion"
        const val WECHAT_PLATFORM = "WECHAT"
        const val SCAN_PAGE_SIZE = 20
        const val ATTRIBUTION_THRESHOLD = 0.6
        const val TRIGGER_DEBOUNCE_MS = 3_000L
        const val CANDIDATE_WINDOW_MS = 7L * 24 * 60 * 60 * 1_000
    }
}
