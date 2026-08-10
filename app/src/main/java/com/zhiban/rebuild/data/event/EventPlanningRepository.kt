package com.zhiban.rebuild.data.event

import androidx.room.withTransaction
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.agent.ScheduleEntity
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

class EventPlanningRepository @Inject internal constructor(private val database: AgentDatabase) {
    private val plans = database.eventPlanningDao()

    fun observePlans(): Flow<List<EventPlanEntity>> = plans.observePlans()

    fun observePlan(planId: String): Flow<EventPlanEntity?> = plans.observePlan(planId)

    fun observeAllParticipants(): Flow<List<EventPlanParticipantEntity>> = plans.observeAllParticipants()

    fun observeParticipants(planId: String): Flow<List<EventPlanParticipantEntity>> = plans.observeParticipants(planId)

    suspend fun createPlan(
        title: String,
        startAtEpochMs: Long,
        durationMinutes: Int,
        location: String?,
        note: String?,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): String {
        require(title.isNotBlank()) { "安排名称不能为空" }
        require(startAtEpochMs >= nowEpochMs - PAST_EVENT_GRACE_MS) { "这个时间已经过去" }
        require(durationMinutes in 15..1_440) { "时长应为 15–1440 分钟" }
        val id = "event-${UUID.randomUUID()}"
        plans.insertPlan(
            EventPlanEntity(
                planId = id,
                title = title.trim().take(120),
                proposedStartAtEpochMs = startAtEpochMs,
                durationMinutes = durationMinutes,
                location = location.cleaned(160),
                note = note.cleaned(500),
                status = EventPlanStatus.DRAFT,
                scheduleId = null,
                sourceType = "USER_CREATED",
                createdAtEpochMs = nowEpochMs,
                updatedAtEpochMs = nowEpochMs,
            ),
        )
        return id
    }

    suspend fun addParticipant(planId: String, contactId: String, nowEpochMs: Long = System.currentTimeMillis()) {
        require(database.contactDao().findById(contactId) != null) { "联系人不存在" }
        val plan = plans.findPlan(planId) ?: error("安排不存在")
        require(plan.status != EventPlanStatus.COMPLETED) { "已结束的安排不能添加联系人" }
        database.withTransaction {
            plans.upsertParticipant(
                EventPlanParticipantEntity(
                    planId = planId,
                    contactId = contactId,
                    responseStatus = EventResponseStatus.PENDING,
                    responseSource = "USER_SELECTED",
                    updatedAtEpochMs = nowEpochMs,
                ),
            )
            if (plan.status == EventPlanStatus.DRAFT) {
                check(plans.updatePlan(plan.copy(status = EventPlanStatus.COORDINATING, updatedAtEpochMs = nowEpochMs)) == 1)
            }
        }
    }

    suspend fun removeParticipant(planId: String, contactId: String): Boolean = plans.removeParticipant(planId, contactId) == 1

    suspend fun updateResponse(planId: String, contactId: String, responseStatus: String, nowEpochMs: Long = System.currentTimeMillis()) {
        require(responseStatus in EventResponseStatus.ALL) { "回复状态无效" }
        require(plans.findPlan(planId) != null) { "安排不存在" }
        require(database.contactDao().findById(contactId) != null) { "联系人不存在" }
        require(plans.findParticipant(planId, contactId) != null) { "联系人不在这项安排中" }
        plans.upsertParticipant(
            EventPlanParticipantEntity(
                planId = planId,
                contactId = contactId,
                responseStatus = responseStatus,
                responseSource = "USER_CONFIRMED",
                updatedAtEpochMs = nowEpochMs,
            ),
        )
    }

    suspend fun confirmToCalendar(planId: String, nowEpochMs: Long = System.currentTimeMillis()): ScheduleEntity = database.withTransaction {
        val plan = plans.findPlan(planId) ?: error("安排不存在")
        require(plan.status != EventPlanStatus.COMPLETED) { "安排已经结束" }
        val participantRows = plans.participantsForPlan(planId)
        require(participantRows.isNotEmpty()) { "请先选择参与人" }
        plan.scheduleId?.let { existingId ->
            database.scheduleDao().findById(existingId)?.let { return@withTransaction it }
        }
        check(
            database.scheduleDao().findConflicts(
                plan.proposedStartAtEpochMs,
                plan.proposedStartAtEpochMs + plan.durationMinutes * 60_000L,
            ).isEmpty(),
        ) { "这段时间已有其他日程" }
        val contacts = database.contactDao().listActiveForIntelligence().associateBy { it.contactId }
        val participantNames = participantRows.mapNotNull { contacts[it.contactId]?.displayName }
        val schedule = ScheduleEntity(
            id = "event-schedule-$planId",
            title = plan.title,
            startAtEpochMs = plan.proposedStartAtEpochMs,
            durationMinutes = plan.durationMinutes,
            note = buildScheduleNote(plan, participantNames),
            createdByRunId = null,
            createdByRuntimeRunId = null,
            createdByRuntimeAttemptId = null,
            createdAtEpochMs = nowEpochMs,
            updatedAtEpochMs = nowEpochMs,
            reminderMinutesBefore = 60,
        )
        database.scheduleDao().insert(schedule)
        check(plans.updatePlan(plan.copy(status = EventPlanStatus.CONFIRMED, scheduleId = schedule.id, updatedAtEpochMs = nowEpochMs)) == 1)
        schedule
    }

    private fun buildScheduleNote(plan: EventPlanEntity, participantNames: List<String>): String? = listOfNotNull(
        plan.location?.let { "地点：$it" },
        participantNames.takeIf(List<String>::isNotEmpty)?.joinToString(prefix = "参与人："),
        plan.note,
        "来自知伴 · 一起安排",
    ).joinToString("\n").takeIf(String::isNotBlank)

    private fun String?.cleaned(maxLength: Int): String? = this?.trim()?.take(maxLength)?.takeIf(String::isNotBlank)

    private companion object {
        const val PAST_EVENT_GRACE_MS = 5 * 60_000L
    }
}
