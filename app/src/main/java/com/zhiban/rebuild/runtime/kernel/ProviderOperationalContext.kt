package com.zhiban.rebuild.runtime.kernel

import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.notification.ScheduleInsight
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class ProviderOperationalContext(
    val notificationInsights: List<String> = emptyList(),
    val crmOpportunities: List<String> = emptyList(),
    val todaySchedules: List<String> = emptyList(),
)

/** Loads bounded, structured operational summaries; raw notification bodies never enter this channel. */
internal class ProviderOperationalContextLoader(private val database: AgentDatabase, private val zoneId: ZoneId = ZoneId.systemDefault()) {
    suspend fun load(nowEpochMs: Long): ProviderOperationalContext {
        val day = Instant.ofEpochMilli(nowEpochMs).atZone(zoneId).toLocalDate()
        val start = day.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val format = DateTimeFormatter.ofPattern("HH:mm").withZone(zoneId)
        val insights = database.notificationCandidateDao().recentWithInsights(start - DAY_MS, MAX_ITEMS).mapNotNull { candidate ->
            ScheduleInsight.from(candidate)?.let { insight ->
                "${format.format(Instant.ofEpochMilli(insight.startAtEpochMs))} ${insight.title}"
            }
        }
        val crm = database.crmDao().listOpenOpportunities(MAX_ITEMS).map { opportunity ->
            "${opportunity.title}｜${opportunity.stage}｜${opportunity.probabilityPercent}%"
        }
        val schedules = database.scheduleDao().listRange(start, end, MAX_ITEMS).map { schedule ->
            "${format.format(Instant.ofEpochMilli(schedule.startAtEpochMs))} ${schedule.title}｜${schedule.status}"
        }
        return ProviderOperationalContext(insights, crm, schedules)
    }

    private companion object {
        const val MAX_ITEMS = 6
        const val DAY_MS = 24L * 60 * 60 * 1_000
    }
}
