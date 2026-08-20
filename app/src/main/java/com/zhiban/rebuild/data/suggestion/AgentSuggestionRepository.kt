package com.zhiban.rebuild.data.suggestion

import android.util.Log
import androidx.room.withTransaction
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.agent.ScheduleEntity
import com.zhiban.rebuild.data.autowrite.recordWriteVerificationFailure
import com.zhiban.rebuild.data.calendar.ScheduleReminderRegistrar
import com.zhiban.rebuild.data.completion.ContactCompletionDraft
import com.zhiban.rebuild.data.completion.ContactCompletionRepository
import com.zhiban.rebuild.data.event.EventPlanEntity
import com.zhiban.rebuild.data.event.EventPlanParticipantEntity
import com.zhiban.rebuild.data.event.EventPlanStatus
import com.zhiban.rebuild.data.event.EventResponseStatus
import com.zhiban.rebuild.foundation.sha256
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

/**
 * 建议中心数据面：薄转发 + 状态迁移事务，模式对齐 [com.zhiban.rebuild.data.autowrite.AutoWriteRepository]。
 *
 * "接受"不仅是意图确认：若建议携带结构化执行负载（execActionType=SCHEDULE），
 * 接受时会把日程要素、参与人、日历事件和建议状态作为一笔原子写入；冲突、缺参与人
 * 或过期等失败会完整回滚并保留待处理建议，绝不显示没有真实结果的成功状态。
 */
class AgentSuggestionRepository @Inject internal constructor(
    private val database: AgentDatabase,
    private val contactCompletion: ContactCompletionRepository,
    private val reminderRegistrar: ScheduleReminderRegistrar,
    private val notifier: AgentSuggestionNotifier,
) {
    fun observeSuggestions(limit: Int = 50, offset: Int = 0) = database.agentSuggestionDao().observeRecent(limit, offset)

    fun observePendingCount() = database.agentSuggestionDao().observePendingCount()

    /**
     * 新增一条建议。真实语义是「insert-if-absent」：主键冲突时静默忽略（返回 false），
     * 幂等由 dedupeKey unique 索引保证（同一候选只产一条建议）。不建议改成 REPLACE——
     * 那会整行覆盖，破坏已流转的 PENDING→ACCEPTED/DISMISSED 状态。
     */
    suspend fun insert(suggestion: AgentSuggestionEntity): Boolean {
        val dao = database.agentSuggestionDao()
        val feedback = dao.feedbackStats(
            suggestion.type,
            suggestion.contactId,
            suggestion.createdAtEpochMs - SUGGESTION_FEEDBACK_WINDOW_MS,
        )
        val adjustment = SuggestionFeedbackPolicy.adjustment(feedback)
        val prepared = suggestion.copy(priorityScore = adjustment.adjustedPriority(suggestion.priorityScore))
        val inserted = dao.insert(prepared) != -1L
        if (inserted) {
            notifier.publish(dao.pendingCount(), prepared.contactId)
        }
        return inserted
    }

    /**
     * 用户认可建议：PENDING → ACCEPTED（乐观锁，重复点击返回 false）。日程建议只有在
     * 计划、参与人、日历事件和建议状态同一事务全部写成后才返回 true；任何校验或写入
     * 失败都会整体回滚并保持 PENDING，绝不出现「已采纳但日历里没有事件」的假成功。
     *
     * @param chosenContactId 确认面板里选定的真实联系人 id；姓名始终从联系人库读取。
     */
    suspend fun accept(suggestionId: String, chosenContactId: String? = null, nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        // 一键转发补全：先拉起微信成功、再转 ACCEPTED（失败保持 PENDING，用户可重试）。
        val pending = database.agentSuggestionDao().find(suggestionId)
        if (pending?.status == AgentSuggestionStatus.PENDING && pending.execActionType == EXEC_COMPLETION) {
            return completeAndHandoff(suggestionId, pending.forwardMessage.orEmpty(), nowEpochMs)
        }
        if (pending?.status == AgentSuggestionStatus.PENDING && pending.execActionType == EXEC_SCHEDULE) {
            return createScheduleAndAccept(suggestionId, chosenContactId, nowEpochMs)
        }
        return database.withTransaction {
            val suggestion = database.agentSuggestionDao().find(suggestionId) ?: return@withTransaction false
            if (suggestion.status != AgentSuggestionStatus.PENDING) return@withTransaction false
            database.agentSuggestionDao().transitionStatus(
                suggestionId,
                AgentSuggestionStatus.PENDING,
                AgentSuggestionStatus.ACCEPTED,
                nowEpochMs,
            ) == 1
        }
    }

    /** 忽略建议：PENDING → DISMISSED。补全建议会先撤掉关联的 DRAFTED 请求（不留活跃请求挡下次起草）。 */
    suspend fun dismiss(suggestionId: String, nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        val suggestion = database.agentSuggestionDao().find(suggestionId)
        suggestion?.completionRequestId?.let { requestId ->
            try {
                contactCompletion.cancel(requestId)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                Log.w(TAG, "completion:cancel_request_failed suggestion=$suggestionId", failure)
            }
        }
        return database.agentSuggestionDao().transitionStatus(suggestionId, AgentSuggestionStatus.PENDING, AgentSuggestionStatus.DISMISSED, nowEpochMs) == 1
    }

    /**
     * 补全建议的一键转发：把 [finalText]（卡片里可编辑过的最终稿）交给微信预填，
     * 用户亲选联系人亲发。handoff 成功才转 ACCEPTED；微信未装/请求状态已变 → 保持 PENDING 返回 false。
     */
    suspend fun completeAndHandoff(suggestionId: String, finalText: String, nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        val suggestion = database.agentSuggestionDao().find(suggestionId) ?: return false
        if (suggestion.status != AgentSuggestionStatus.PENDING) return false
        if (suggestion.execActionType != EXEC_COMPLETION) return false
        val requestId = suggestion.completionRequestId ?: return false
        val handedOff = try {
            contactCompletion.confirmAndHandoff(requestId, finalText)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            Log.w(TAG, "completion:handoff_failed suggestion=$suggestionId", failure)
            false
        }
        if (!handedOff) return false
        return database.agentSuggestionDao().transitionStatus(
            suggestionId,
            AgentSuggestionStatus.PENDING,
            AgentSuggestionStatus.ACCEPTED,
            nowEpochMs,
        ) == 1
    }

    /** 补全建议卡所需的渲染视图（请求 id + 联系人 + 字段 + 草稿），供「一键转发」对话框复用。 */
    suspend fun completionDraft(suggestionId: String): ContactCompletionDraft? {
        val suggestion = database.agentSuggestionDao().find(suggestionId) ?: return null
        val contact = suggestion.contactId?.let { database.contactDao().findRawById(it) } ?: return null
        val requestId = suggestion.completionRequestId ?: return null
        val draftText = suggestion.forwardMessage?.takeIf(String::isNotBlank) ?: return null
        return ContactCompletionDraft(
            requestId = requestId,
            contactId = contact.contactId,
            contactName = contact.displayName,
            fields = AgentSuggestionCodecs.decodeMissingFields(suggestion.missingFieldsJson),
            draftText = draftText,
        )
    }

    /** 清理已处置的历史建议（保留 30 天），由维护协调器调用。 */
    suspend fun pruneSettled(olderThanDays: Int = 30, nowEpochMs: Long = System.currentTimeMillis()): Int =
        database.agentSuggestionDao().pruneSettledBefore(nowEpochMs - olderThanDays * 24L * 60 * 60 * 1_000)

    private suspend fun createScheduleAndAccept(suggestionId: String, chosenContactId: String?, nowEpochMs: Long): Boolean = try {
        val schedule = database.withTransaction {
            val creation = resolveScheduleCreation(suggestionId, chosenContactId, nowEpochMs)
                ?: return@withTransaction null
            persistScheduleCreation(creation, nowEpochMs)
        } ?: return false
        reminderRegistrar.replace(schedule.id, schedule.startAtEpochMs, schedule.reminderMinutesBefore)
        true
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        if (failure.message == "SCHEDULE_WRITE_VERIFY_FAILED") {
            database.recordWriteVerificationFailure(
                toolName = "calendar.schedule.create",
                targetId = suggestionId,
                idempotencyKey = "schedule-suggestion:$suggestionId",
                reasonCode = failure.message.orEmpty(),
                nowEpochMs = nowEpochMs,
            )
        }
        Log.w(TAG, "schedule:create_atomic_failed suggestion=$suggestionId", failure)
        false
    }

    private suspend fun resolveScheduleCreation(suggestionId: String, chosenContactId: String?, nowEpochMs: Long): ScheduleCreation? {
        val suggestion = database.agentSuggestionDao().find(suggestionId) ?: return null
        if (suggestion.status != AgentSuggestionStatus.PENDING || suggestion.execActionType != EXEC_SCHEDULE) return null
        val startAtEpochMs = suggestion.startAtEpochMs ?: return null
        if (startAtEpochMs < nowEpochMs - PAST_EVENT_GRACE_MS) return null
        val durationMinutes = suggestion.durationMinutes?.coerceIn(15, 1_440) ?: DEFAULT_DURATION_MINUTES
        val selectedContactId = chosenContactId ?: suggestion.contactId ?: return null
        if (!isAllowedParticipant(suggestion, selectedContactId)) return null
        val contact = database.contactDao().findById(selectedContactId) ?: return null
        if (hasConflict(startAtEpochMs, durationMinutes)) return null
        val title = (suggestion.scheduleTitle?.takeIf(String::isNotBlank) ?: suggestion.title).trim().take(120)
        if (title.isBlank()) return null
        return ScheduleCreation(
            suggestion = suggestion,
            startAtEpochMs = startAtEpochMs,
            durationMinutes = durationMinutes,
            contactId = selectedContactId,
            title = title,
            note = buildScheduleNote(suggestion, contact.displayName),
        )
    }

    private suspend fun hasConflict(startAtEpochMs: Long, durationMinutes: Int): Boolean = database.scheduleDao().findConflicts(
        startAtEpochMs,
        startAtEpochMs + durationMinutes * 60_000L,
    ).isNotEmpty()

    private suspend fun persistScheduleCreation(creation: ScheduleCreation, nowEpochMs: Long): ScheduleEntity {
        val planId = "event-suggestion-${sha256(creation.suggestion.suggestionId).take(24)}"
        val scheduleId = "event-schedule-$planId"
        val schedule = ScheduleEntity(
            id = scheduleId,
            title = creation.title,
            startAtEpochMs = creation.startAtEpochMs,
            durationMinutes = creation.durationMinutes,
            note = creation.note,
            createdByRunId = null,
            createdAtEpochMs = nowEpochMs,
            updatedAtEpochMs = nowEpochMs,
            reminderMinutesBefore = DEFAULT_REMINDER_MINUTES,
        )
        database.scheduleDao().insert(schedule)
        database.eventPlanningDao().insertPlan(
            EventPlanEntity(
                planId = planId,
                title = creation.title,
                proposedStartAtEpochMs = creation.startAtEpochMs,
                durationMinutes = creation.durationMinutes,
                location = creation.suggestion.location?.trim()?.take(160)?.takeIf(String::isNotBlank),
                note = creation.note,
                status = EventPlanStatus.CONFIRMED,
                scheduleId = scheduleId,
                sourceType = "AGENT_SUGGESTION",
                createdAtEpochMs = nowEpochMs,
                updatedAtEpochMs = nowEpochMs,
            ),
        )
        database.eventPlanningDao().upsertParticipant(
            EventPlanParticipantEntity(
                planId = planId,
                contactId = creation.contactId,
                responseStatus = EventResponseStatus.PENDING,
                responseSource = "USER_SELECTED",
                updatedAtEpochMs = nowEpochMs,
            ),
        )
        val persisted = database.scheduleDao().findById(scheduleId)
        check(
            persisted != null &&
                persisted.title == creation.title &&
                persisted.startAtEpochMs == creation.startAtEpochMs &&
                persisted.reminderMinutesBefore == DEFAULT_REMINDER_MINUTES,
        ) { "SCHEDULE_WRITE_VERIFY_FAILED" }
        check(database.agentSuggestionDao().markScheduleCreated(creation.suggestion.suggestionId, planId, nowEpochMs) == 1)
        return schedule
    }

    private fun isAllowedParticipant(suggestion: AgentSuggestionEntity, contactId: String): Boolean = contactId == suggestion.contactId ||
        AgentSuggestionCodecs.decodeCandidates(suggestion.contactCandidatesJson).any { it.contactId == contactId }

    private fun buildScheduleNote(suggestion: AgentSuggestionEntity, chosenContactName: String?): String? = buildList {
        suggestion.location?.let { add("地点：$it") }
        suggestion.pickupLocation?.let { add("接人：$it") }
        suggestion.visitLocation?.takeIf { it != suggestion.location }?.let { add("拜访：$it") }
        suggestion.companyFull?.takeIf(String::isNotBlank)?.let { add("客户：$it") }
        chosenContactName?.takeIf(String::isNotBlank)?.let { add("对接人：$it") }
        suggestion.travelNote?.takeIf(String::isNotBlank)?.let { add(it) }
        suggestion.confirmNotes?.takeIf(String::isNotBlank)?.let { add("待确认：$it") }
        add("来自知伴 · 智能建议")
    }.joinToString("\n").takeIf(String::isNotBlank)

    private data class ScheduleCreation(
        val suggestion: AgentSuggestionEntity,
        val startAtEpochMs: Long,
        val durationMinutes: Int,
        val contactId: String,
        val title: String,
        val note: String?,
    )

    private companion object {
        const val TAG = "AgentSuggestion"
        const val EXEC_SCHEDULE = "SCHEDULE"
        const val EXEC_COMPLETION = "CONTACT_COMPLETION"
        const val DEFAULT_DURATION_MINUTES = 90
        const val DEFAULT_REMINDER_MINUTES = 60
        const val PAST_EVENT_GRACE_MS = 5 * 60_000L
    }
}
