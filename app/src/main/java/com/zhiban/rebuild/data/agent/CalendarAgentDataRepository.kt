package com.zhiban.rebuild.data.agent

import androidx.room.withTransaction
import com.zhiban.rebuild.data.calendar.SystemCalendarEvent
import com.zhiban.rebuild.data.contact.ContactAddressEntity
import com.zhiban.rebuild.data.contact.ContactAliasEntity
import com.zhiban.rebuild.data.contact.ContactEmploymentEntity
import com.zhiban.rebuild.data.contact.ContactEnrichmentCandidateEntity
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.ContactFacetEntity
import com.zhiban.rebuild.data.contact.ContactImportantDateEntity
import com.zhiban.rebuild.data.contact.ContactMergeLinkEntity
import com.zhiban.rebuild.data.contact.ContactMethodEntity
import com.zhiban.rebuild.data.contact.ContactPlatformIdentityEntity
import com.zhiban.rebuild.data.contact.ContactRoleEntity
import com.zhiban.rebuild.data.contact.OrganizationEntity
import com.zhiban.rebuild.data.contact.OwnerContactLinkEntity
import com.zhiban.rebuild.data.contact.RelationshipEdgeEntity
import com.zhiban.rebuild.data.contact.RelationshipEventEntity
import com.zhiban.rebuild.data.contact.RelationshipEventParticipantEntity
import com.zhiban.rebuild.data.contact.RelationshipEventWithParticipants
import com.zhiban.rebuild.data.contact.RelationshipPersonIds
import com.zhiban.rebuild.data.contact.SystemContactCandidate
import com.zhiban.rebuild.data.contact.normalizeContactPhone
import com.zhiban.rebuild.data.crm.CrmActionStatus
import com.zhiban.rebuild.data.crm.CrmActivityEntity
import com.zhiban.rebuild.data.crm.CrmAgentSuggestionEntity
import com.zhiban.rebuild.data.crm.CrmDemoCleanupAuditEntity
import com.zhiban.rebuild.data.crm.CrmLeadEntity
import com.zhiban.rebuild.data.crm.CrmLeadStatus
import com.zhiban.rebuild.data.crm.CrmNextActionEntity
import com.zhiban.rebuild.data.crm.CrmOpportunityEntity
import com.zhiban.rebuild.data.crm.CrmOpportunityStage
import com.zhiban.rebuild.data.crm.CrmOpportunityStakeholderEntity
import com.zhiban.rebuild.data.crm.CrmRecordStatus
import com.zhiban.rebuild.data.crm.CrmStageHistoryEntity
import com.zhiban.rebuild.data.crm.CrmSuggestionStatus
import com.zhiban.rebuild.data.notification.MessageCollectionPreferences
import com.zhiban.rebuild.data.notification.NotificationCandidateEntity
import com.zhiban.rebuild.data.notification.NotificationInsightAnalyzer
import com.zhiban.rebuild.data.notification.ScheduleInsight
import com.zhiban.rebuild.runtime.context.FactEntity
import com.zhiban.rebuild.runtime.context.FactIndex
import com.zhiban.rebuild.runtime.governance.ActionDecision
import com.zhiban.rebuild.runtime.governance.ActionPolicy
import com.zhiban.rebuild.runtime.governance.AutoWriteAuditDraft
import com.zhiban.rebuild.runtime.governance.AutoWriteToolNames
import com.zhiban.rebuild.runtime.governance.ReversibleWriteReadiness
import com.zhiban.rebuild.runtime.governance.canonicalChangeDigest
import com.zhiban.rebuild.runtime.governance.insertVisibleAutoWrite
import com.zhiban.rebuild.runtime.tool.RuntimeToolRisk
import com.zhiban.rebuild.runtime.tool.RuntimeToolSpec
import com.zhiban.rebuild.runtime.tool.sha256
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import org.json.JSONObject

data class CalendarImportSummary(val created: Int, val updated: Int)

internal class CalendarAgentDataRepository(private val database: AgentDatabase) {
    private val schedules = database.scheduleDao()
    fun observeSchedules(fromEpochMs: Long, toEpochMs: Long): Flow<List<ScheduleProjection>> = schedules.observeRange(fromEpochMs, toEpochMs)

    fun observePendingFeedback(beforeEpochMs: Long, oldestEpochMs: Long, limit: Int = 20): Flow<List<ScheduleProjection>> =
        schedules.observePendingFeedback(beforeEpochMs, oldestEpochMs, limit)

    suspend fun findSchedule(scheduleId: String): ScheduleEntity? = schedules.findById(scheduleId)

    suspend fun saveUserSchedule(
        scheduleId: String?,
        title: String,
        startAtEpochMs: Long,
        durationMinutes: Int,
        note: String?,
        reminderMinutesBefore: Int? = null,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): String {
        require(title.isNotBlank()) { "日程名称不能为空" }
        require(durationMinutes in 1..1_440) { "日程时长无效" }
        require(startAtEpochMs >= nowEpochMs - PAST_SCHEDULE_GRACE_MS) { "这个时间已经过去" }
        require(reminderMinutesBefore == null || reminderMinutesBefore in 1..1_440) {
            "提醒时间无效"
        }
        val existing = scheduleId?.let { schedules.findById(it) }
        val id = existing?.id ?: scheduleId ?: "user-${UUID.randomUUID()}"
        val value = ScheduleEntity(
            id = id,
            title = title.trim(),
            startAtEpochMs = startAtEpochMs,
            durationMinutes = durationMinutes,
            note = note?.trim()?.takeIf(String::isNotEmpty),
            reminderMinutesBefore = reminderMinutesBefore,
            createdByRunId = existing?.createdByRunId,
            createdByRuntimeRunId = existing?.createdByRuntimeRunId,
            createdByRuntimeAttemptId = existing?.createdByRuntimeAttemptId,
            createdAtEpochMs = existing?.createdAtEpochMs ?: nowEpochMs,
            updatedAtEpochMs = nowEpochMs,
            status = if (existing != null && existing.startAtEpochMs != startAtEpochMs) {
                ScheduleStatus.PENDING
            } else {
                existing?.status ?: ScheduleStatus.PENDING
            },
            outcomeNote = if (existing != null && existing.startAtEpochMs != startAtEpochMs) null else existing?.outcomeNote,
            completedAtEpochMs = if (existing != null && existing.startAtEpochMs != startAtEpochMs) null else existing?.completedAtEpochMs,
        )
        if (existing == null) schedules.insert(value) else check(schedules.update(value) == 1)
        return id
    }

    suspend fun deleteSchedule(scheduleId: String): Boolean = schedules.deleteById(scheduleId) == 1

    suspend fun completeSchedule(scheduleId: String, outcomeNote: String?, nowEpochMs: Long = System.currentTimeMillis()): Boolean = schedules.updateCompletion(
        id = scheduleId,
        status = ScheduleStatus.COMPLETED,
        outcomeNote = outcomeNote?.trim()?.take(1_000)?.takeIf(String::isNotBlank),
        completedAtEpochMs = nowEpochMs,
        nowEpochMs = nowEpochMs,
    ) == 1

    suspend fun reopenSchedule(scheduleId: String, nowEpochMs: Long = System.currentTimeMillis()): Boolean = schedules.updateCompletion(
        id = scheduleId,
        status = ScheduleStatus.PENDING,
        outcomeNote = null,
        completedAtEpochMs = null,
        nowEpochMs = nowEpochMs,
    ) == 1

    suspend fun importConfirmedSystemCalendarEvents(events: List<SystemCalendarEvent>, nowEpochMs: Long = System.currentTimeMillis()): CalendarImportSummary =
        database.withTransaction {
            var created = 0
            var updated = 0
            events.distinctBy(SystemCalendarEvent::sourceId).forEach { event ->
                require(event.title.isNotBlank()) { "系统日程标题不能为空" }
                val id = "system-calendar-${event.sourceId}".take(220)
                val existing = schedules.findById(id)
                val duration = (
                    ((event.endAtEpochMs ?: event.startAtEpochMs + 60 * 60_000L) - event.startAtEpochMs) /
                        60_000L
                    )
                    .coerceIn(1L, 1_440L).toInt()
                val sourceNote = listOfNotNull(
                    event.location?.let { "地点：$it" },
                    event.description,
                    event.calendarName?.let { "来自系统日历：$it" },
                ).joinToString("\n").takeIf(String::isNotBlank)
                if (existing == null && findEquivalentSchedule(event.title, event.startAtEpochMs, duration) != null) {
                    updated++
                    return@forEach
                }
                val value = ScheduleEntity(
                    id = id,
                    title = event.title.trim().take(200),
                    startAtEpochMs = event.startAtEpochMs,
                    durationMinutes = duration,
                    note = sourceNote,
                    reminderMinutesBefore = existing?.reminderMinutesBefore,
                    createdByRunId = null,
                    createdByRuntimeRunId = null,
                    createdByRuntimeAttemptId = null,
                    createdAtEpochMs = existing?.createdAtEpochMs ?: nowEpochMs,
                    updatedAtEpochMs = nowEpochMs,
                    status = existing?.status ?: ScheduleStatus.PENDING,
                    outcomeNote = existing?.outcomeNote,
                    completedAtEpochMs = existing?.completedAtEpochMs,
                )
                if (existing == null) {
                    schedules.insert(value)
                    created++
                } else {
                    check(schedules.update(value) == 1)
                    updated++
                }
            }
            CalendarImportSummary(created, updated)
        }

    suspend fun findScheduleConflicts(startAtEpochMs: Long, durationMinutes: Int, excludeScheduleId: String? = null): List<ScheduleProjection> =
        schedules.findConflicts(
            startEpochMs = startAtEpochMs,
            endEpochMs = startAtEpochMs + durationMinutes * 60_000L,
            excludeId = excludeScheduleId,
        )

    suspend fun findEquivalentSchedule(title: String, startAtEpochMs: Long, durationMinutes: Int, excludeScheduleId: String? = null): ScheduleProjection? {
        val normalizedTitle = normalizeScheduleIdentityTitle(title)
        if (normalizedTitle.isBlank()) return null
        return schedules.listRange(
            fromEpochMs = startAtEpochMs - EQUIVALENT_START_TOLERANCE_MS,
            toEpochMs = startAtEpochMs + durationMinutes * 60_000L + EQUIVALENT_START_TOLERANCE_MS,
            limit = 100,
        ).firstOrNull { candidate ->
            candidate.id != excludeScheduleId &&
                kotlin.math.abs(candidate.startAtEpochMs - startAtEpochMs) <= EQUIVALENT_START_TOLERANCE_MS &&
                kotlin.math.abs(candidate.durationMinutes - durationMinutes) <= EQUIVALENT_DURATION_TOLERANCE_MINUTES &&
                normalizeScheduleIdentityTitle(candidate.title) == normalizedTitle
        }
    }

    private companion object {
        const val PAST_SCHEDULE_GRACE_MS = 5 * 60_000L
        const val EQUIVALENT_START_TOLERANCE_MS = 5 * 60_000L
        const val EQUIVALENT_DURATION_TOLERANCE_MINUTES = 5
    }
}

internal fun normalizeScheduleIdentityTitle(value: String): String = stripConcreteParticipantPrefix(value.trim())
    .lowercase()
    .filter { it.isLetterOrDigit() }

private fun stripConcreteParticipantPrefix(value: String): String {
    val match = PARTICIPANT_ACTION_PREFIX.matchEntire(value) ?: return value
    val participant = match.groupValues[1].trim()
    val subject = match.groupValues[2].trim()
    return if (participant.length in 1..8 && subject.length >= 4 && MEETING_SUBJECT_MARKERS.any(subject::contains)) {
        subject
    } else {
        value
    }
}

private val PARTICIPANT_ACTION_PREFIX = Regex(
    """^(?:和|与)([\p{L}\p{N}·_-]{1,8})(?:一起)?(?:开|召开|参加|进行)(.+)$""",
)
private val MEETING_SUBJECT_MARKERS = listOf("会", "会议", "复盘", "评审", "沟通", "碰头")
