package com.zhiban.rebuild.data.completion

import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.notification.NotificationCandidateEntity
import com.zhiban.rebuild.runtime.config.AgentControlStore
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
                runCatching { processOnce() }
                    .onFailure { if (it is CancellationException) throw it }
            }
        }
    }

    internal suspend fun processOnce() {
        processMutex.withLock {
            val now = System.currentTimeMillis()
            val dao = database.contactCompletionRequestDao()
            dao.expireAwaitingBefore(now) // 超时收尾与开关无关,总要跑
            if (!controls.contactCompletionEnabled()) return
            database.notificationCandidateDao()
                .recentIncomingAttributed(WECHAT_PLATFORM, now - CANDIDATE_WINDOW_MS, CANDIDATE_LIMIT)
                .forEach { candidate ->
                    runCatching { processCandidate(candidate, now) }
                        .onFailure { if (it is CancellationException) throw it }
                }
        }
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
        var firstStagedId: String? = null
        candidates.forEach { staged ->
            // PK IGNORE 去重:重复扫描同一回复不重复落库,也不重置 firstStagedId。
            if (database.contactKnowledgeDao().insertEnrichmentCandidateIfAbsent(staged) != -1L && firstStagedId == null) {
                firstStagedId = staged.candidateId
            }
        }
        // 只有真抽到字段才转 RESPONSE_RECEIVED 并挂上溯源候选;抽不到保持 AWAITING(7 天后 EXPIRED)。
        firstStagedId?.let { dao.markResponseReceived(request.requestId, it, now) }
    }

    private companion object {
        const val WECHAT_PLATFORM = "WECHAT"
        const val CANDIDATE_LIMIT = 20
        const val ATTRIBUTION_THRESHOLD = 0.6
        const val TRIGGER_DEBOUNCE_MS = 3_000L
        const val CANDIDATE_WINDOW_MS = 7L * 24 * 60 * 60 * 1_000
    }
}
