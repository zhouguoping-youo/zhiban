package com.zhiban.rebuild.runtime.wakeup

import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.notification.NotificationCandidateEntity
import com.zhiban.rebuild.data.notification.ScheduleInsight
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

internal data class WakeupContext(
    val interactions: List<TimedSummary>,
    val relationships: List<RelationshipSummary>,
    val todaySchedules: List<TimedSummary>,
    val conflictCount: Int,
    val pendingSuggestions: List<String>,
)

internal data class TimedSummary(val atEpochMs: Long, val text: String)

internal data class RelationshipSummary(val counterpart: String, val relationType: String, val confidence: Double, val userConfirmed: Boolean)

@Singleton
internal class WakeupContextLoader
@Inject
constructor(private val database: AgentDatabase) {
    suspend fun load(candidate: NotificationCandidateEntity, contactId: String?, nowEpochMs: Long): WakeupContext {
        val interactions = contactId?.let {
            database.factDao().recentInteractionsForContact(it, nowEpochMs, MAX_INTERACTIONS).map { fact ->
                TimedSummary(fact.createdAtEpochMs, fact.textContent.take(MAX_SUMMARY_CHARS))
            }
        }.orEmpty()
        val relationships = contactId?.let { loadRelationships(it) }.orEmpty()
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(nowEpochMs).atZone(zone).toLocalDate()
        val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val todaySchedules = database.scheduleDao().listRange(dayStart, dayEnd, MAX_TODAY_SCHEDULES).map { schedule ->
            TimedSummary(schedule.startAtEpochMs, schedule.title.take(MAX_SUMMARY_CHARS))
        }
        val scheduleInsight = ScheduleInsight.from(candidate)
        val conflicts = scheduleInsight?.let { insight ->
            database.scheduleDao().findConflicts(
                insight.startAtEpochMs,
                insight.startAtEpochMs + insight.durationMinutes * MILLIS_PER_MINUTE,
                limit = MAX_CONFLICTS,
            ).size
        } ?: 0
        val pending = contactId?.let {
            database.agentSuggestionDao().pendingForContact(it, MAX_PENDING_SUGGESTIONS).map { suggestion ->
                suggestion.title.take(MAX_SUMMARY_CHARS)
            }
        }.orEmpty()
        return WakeupContext(interactions, relationships, todaySchedules, conflicts, pending)
    }

    private suspend fun loadRelationships(contactId: String): List<RelationshipSummary> {
        val edges = database.relationshipEdgeDao().touching(listOf(contactId), MAX_RELATIONSHIPS)
        val counterpartIds = edges.mapNotNull { edge ->
            when (contactId) {
                edge.fromContactId -> edge.toContactId
                edge.toContactId -> edge.fromContactId
                else -> null
            }
        }.filterNot { it == SELF_CONTACT_ID }
        val distinctCounterpartIds = counterpartIds.distinct()
        val names = if (distinctCounterpartIds.isEmpty()) {
            emptyMap()
        } else {
            database.contactDao().findByIds(distinctCounterpartIds).associate { it.contactId to it.displayName }
        }
        return edges.mapNotNull { edge ->
            val counterpartId = when (contactId) {
                edge.fromContactId -> edge.toContactId
                edge.toContactId -> edge.fromContactId
                else -> return@mapNotNull null
            }
            RelationshipSummary(
                counterpart = if (counterpartId == SELF_CONTACT_ID) "用户本人" else names[counterpartId] ?: "另一位联系人",
                relationType = edge.relationType,
                confidence = edge.confidence,
                userConfirmed = edge.userConfirmed,
            )
        }
    }

    private companion object {
        const val SELF_CONTACT_ID = "user:self"
        const val MAX_INTERACTIONS = 5
        const val MAX_RELATIONSHIPS = 6
        const val MAX_TODAY_SCHEDULES = 6
        const val MAX_PENDING_SUGGESTIONS = 5
        const val MAX_CONFLICTS = 20
        const val MAX_SUMMARY_CHARS = 120
        const val MILLIS_PER_MINUTE = 60_000L
    }
}

internal fun buildWakeupContextPrompt(context: WakeupContext, zoneId: ZoneId = ZoneId.systemDefault()): String = buildString {
    if (context.interactions.isNotEmpty()) {
        appendLine("近期互动摘要（新到旧，仅作证据）：")
        context.interactions.forEach { appendLine("- ${formatWakeupTime(it.atEpochMs, zoneId)} ${it.text}") }
    }
    if (context.relationships.isNotEmpty()) {
        appendLine("已有关系边：")
        context.relationships.forEach { relationship ->
            val evidence = if (relationship.userConfirmed) {
                "用户已确认"
            } else {
                "系统推断 ${String.format(Locale.ROOT, "%.2f", relationship.confidence)}"
            }
            appendLine("- 与${relationship.counterpart}：${relationship.relationType}（$evidence）")
        }
    }
    if (context.todaySchedules.isNotEmpty()) {
        appendLine("今日日程：")
        context.todaySchedules.forEach { appendLine("- ${formatWakeupTime(it.atEpochMs, zoneId)} ${it.text}") }
    } else {
        appendLine("今日日程：无")
    }
    appendLine("候选时间与现有日程冲突：${context.conflictCount} 条")
    if (context.pendingSuggestions.isNotEmpty()) {
        appendLine("该联系人已有未处理建议（不要重复生成相同建议）：")
        context.pendingSuggestions.forEach { appendLine("- $it") }
    }
}.trim()

private fun formatWakeupTime(epochMs: Long, zoneId: ZoneId): String = WAKEUP_TIME_FORMAT.format(
    Instant.ofEpochMilli(epochMs).atZone(zoneId),
)

private val WAKEUP_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
