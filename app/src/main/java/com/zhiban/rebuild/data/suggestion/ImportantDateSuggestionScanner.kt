package com.zhiban.rebuild.data.suggestion

import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.contact.importantDateDisplayLabel
import com.zhiban.rebuild.data.contact.nextImportantDateOccurrence
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class ImportantDateSuggestionScanner @Inject constructor(private val database: AgentDatabase, private val suggestions: AgentSuggestionRepository) {
    suspend fun scan(nowEpochMs: Long = System.currentTimeMillis(), zoneId: ZoneId = ZoneId.systemDefault()): Int {
        val today = Instant.ofEpochMilli(nowEpochMs).atZone(zoneId).toLocalDate()
        var created = 0
        database.contactKnowledgeDao().listAllImportantDates().forEach { date ->
            val occurrence = nextImportantDateOccurrence(date.month, date.day, today) ?: return@forEach
            val daysUntil = ChronoUnit.DAYS.between(today, occurrence).toInt()
            if (daysUntil !in 0..REMINDER_WINDOW_DAYS) return@forEach
            val label = importantDateDisplayLabel(date.kind)
            val timing = when (daysUntil) {
                0 -> "就是今天"
                1 -> "是明天"
                else -> "还有${daysUntil}天"
            }
            val inserted = suggestions.insert(
                AgentSuggestionEntity(
                    suggestionId = "important-date:${date.dateId}:${occurrence.year}",
                    type = AgentSuggestionType.IMPORTANT_DATE_REMINDER,
                    title = "${date.displayName}的$label$timing",
                    body = "可以提前准备问候或安排。",
                    contactId = date.contactId,
                    candidateId = null,
                    sourceEvent = "MAINTENANCE",
                    dedupeKey = "important-date:${date.dateId}:${occurrence.year}",
                    status = AgentSuggestionStatus.PENDING,
                    createdAtEpochMs = nowEpochMs,
                    updatedAtEpochMs = nowEpochMs,
                    priorityScore = if (daysUntil <= 1) 80 else 65,
                ),
            )
            if (inserted) created++
        }
        return created
    }

    private companion object {
        const val REMINDER_WINDOW_DAYS = 7
    }
}
