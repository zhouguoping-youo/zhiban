package com.zhiban.rebuild.data.reply

import android.util.Log
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.notification.NotificationCandidateEntity
import com.zhiban.rebuild.data.notification.SensitiveMessageFilter
import com.zhiban.rebuild.runtime.config.AgentControlStore
import com.zhiban.rebuild.runtime.runSuspendCatching
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
 * Orchestrates reply-suggestion generation. Trigger-driven (a new WeChat message, or app foreground as a
 * fallback sweep), conflated and debounced like [com.zhiban.rebuild.data.ilink.IlinkFetchCoordinator] so a
 * burst of messages collapses into one pass. Each pass scans the last 24h of attributed incoming WeChat
 * candidates, gates them through the free [ReplyWorthinessAnalyzer], and only then pays for drafting.
 * Idempotent (per-candidate and per-thread dedupe) and best-effort: any failure is swallowed so a
 * suggestion hiccup never breaks message staging.
 */
@Singleton
internal class ReplySuggestionCoordinator @Inject constructor(
    private val database: AgentDatabase,
    private val generator: ReplyDraftGenerator,
    private val controls: AgentControlStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val triggers = Channel<Unit>(capacity = Channel.CONFLATED)
    private val processMutex = Mutex()

    @Volatile
    private var consumerStarted = false

    /** Cheap, non-blocking signal that a WeChat message arrived (T1) or the app came to the foreground (T2). */
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
                        Log.w(TAG, "reply:scan_failure", failure)
                    }
            }
        }
    }

    internal suspend fun processOnce() {
        processMutex.withLock {
            val now = System.currentTimeMillis()
            // Reconcile forwarded groups first, even when generation is toggled off: the user may have
            // completed the send in WeChat and the accessibility service has since captured the OUTGOING.
            confirmForwardedReplies(now)
            if (!controls.replySuggestionsEnabled()) return
            database.notificationCandidateDao()
                .recentIncomingAttributed(WECHAT_PLATFORM, now - CANDIDATE_WINDOW_MS, CANDIDATE_LIMIT)
                .forEach { candidate ->
                    runSuspendCatching { processCandidate(candidate) }
                        .onFailure { failure ->
                            if (failure is CancellationException) throw failure
                            Log.w(TAG, "reply:candidate_failure", failure)
                        }
                }
            database.replySuggestionDao().expirePendingBefore(now - SUGGESTION_TTL_MS)
        }
    }

    /**
     * Reconciles FORWARDED groups. Upgrades to SENT_CONFIRMED once the captured thread shows an OUTGOING
     * message after the forward; reverts to PENDING when none appears within [FORWARD_REVERT_AFTER_MS]
     * (the user cancelled the share sheet — the card must come back, FORWARDED is not a terminal state,
     * P1-7). Relies on the existing outgoing-message perception (zero new tracking); when the
     * accessibility service is off, confirmation never lands and the revert re-surfaces the card — the
     * accepted degradation.
     */
    private suspend fun confirmForwardedReplies(now: Long) {
        val replyDao = database.replySuggestionDao()
        replyDao.forwardedGroups()
            .groupBy { it.threadKey }
            .forEach { (threadKey, rows) ->
                val latestForward = rows.mapNotNull { it.forwardedAtEpochMs }.maxOrNull() ?: return@forEach
                val platform = threadKey.substringBefore('|')
                val title = threadKey.substringAfter('|', "")
                val sentAfter = database.notificationCandidateDao()
                    .threadMessages(platform, title, latestForward, THREAD_QUERY_LIMIT)
                    .any { it.direction == "OUTGOING" }
                when {
                    sentAfter -> replyDao.markThreadSentConfirmed(threadKey, ReplySuggestionStatus.SENT_CONFIRMED, now)
                    now - latestForward >= FORWARD_REVERT_AFTER_MS -> replyDao.revertThreadToPending(threadKey)
                    else -> Unit // 刚转发不久:等用户去微信里发(或取消)
                }
            }
    }

    private suspend fun processCandidate(candidate: NotificationCandidateEntity) {
        val replyDao = database.replySuggestionDao()
        if (replyDao.findByCandidateId(candidate.candidateId).isNotEmpty()) return
        val threadKey = replyThreadKey(candidate.platform, candidate.conversationTitle)
        if (replyDao.countPendingForThread(threadKey) > 0) return
        val contactId = candidate.linkedContactId
            ?: candidate.suggestedContactId?.takeIf { candidate.suggestedContactConfidence >= ATTRIBUTION_THRESHOLD }
            ?: return
        if (controls.isReplyOptedOut(contactId)) return

        val now = System.currentTimeMillis()
        val thread = database.notificationCandidateDao()
            .threadMessages(candidate.platform, candidate.conversationTitle.orEmpty(), now - CANDIDATE_WINDOW_MS, THREAD_QUERY_LIMIT)
        val hasLaterOutgoing = thread.any { it.direction == "OUTGOING" && it.postedAtEpochMs > candidate.postedAtEpochMs }
        if (!ReplyWorthinessAnalyzer.evaluate(candidate.body, hasAttribution = true, hasLaterOutgoing = hasLaterOutgoing).worthy) return

        val contact = database.contactDao().findById(contactId)
        val contactName = contact?.displayName ?: candidate.senderName ?: candidate.conversationTitle ?: "对方"
        val threadContext = thread.takeLast(CONTEXT_MESSAGES)
            .map { ReplyThreadMessage(outgoing = it.direction == "OUTGOING", text = it.body.orEmpty()) }
        val drafts = generator.generateDrafts(
            ReplyDraftContext(
                requestId = "reply-${candidate.candidateId}",
                contactName = contactName,
                contactSummary = contactSummaryOf(contact?.company, contact?.title),
                incomingMessage = candidate.body.orEmpty(),
                thread = threadContext,
            ),
        )
            .filter { it.isNotBlank() && !SensitiveMessageFilter.shouldDrop(it) }
            .take(MAX_DRAFTS)
        if (drafts.isEmpty()) return

        replyDao.upsertAll(
            drafts.mapIndexed { index, draft ->
                ReplySuggestionEntity(
                    suggestionId = replySuggestionId(threadKey, candidate.candidateId, index),
                    candidateId = candidate.candidateId,
                    threadKey = threadKey,
                    contactId = contactId,
                    draft = draft,
                    draftIndex = index,
                    status = ReplySuggestionStatus.PENDING,
                    createdAtEpochMs = now,
                    contactName = contactName,
                    incomingExcerpt = candidate.body.orEmpty().replace(Regex("\\s+"), " ").trim().take(MAX_EXCERPT_CHARS),
                )
            },
        )
    }

    private fun contactSummaryOf(company: String?, title: String?): String? =
        listOfNotNull(company?.takeIf { it.isNotBlank() }, title?.takeIf { it.isNotBlank() })
            .joinToString(" / ")
            .ifBlank { null }

    private companion object {
        const val TAG = "ReplySuggestion"
        const val WECHAT_PLATFORM = "WECHAT"
        const val CANDIDATE_LIMIT = 20
        const val CONTEXT_MESSAGES = 10
        const val THREAD_QUERY_LIMIT = 50
        const val MAX_DRAFTS = 3
        const val MAX_EXCERPT_CHARS = 80
        const val ATTRIBUTION_THRESHOLD = 0.6
        const val TRIGGER_DEBOUNCE_MS = 3_000L
        const val CANDIDATE_WINDOW_MS = 24L * 60 * 60 * 1_000
        const val SUGGESTION_TTL_MS = 24L * 60 * 60 * 1_000
        const val FORWARD_REVERT_AFTER_MS = 5L * 60 * 1_000
    }
}
