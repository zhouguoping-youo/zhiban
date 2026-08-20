package com.zhiban.rebuild.ui.tabs

import com.zhiban.rebuild.data.contact.ContactImportantDateProjection
import com.zhiban.rebuild.data.contact.importantDateDisplayLabel
import com.zhiban.rebuild.data.contact.nextImportantDateOccurrence
import com.zhiban.rebuild.data.notification.NotificationCandidateEntity
import com.zhiban.rebuild.data.notification.ScheduleInsight
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class LifeAssistantItemKind { COMMITMENT, IMPORTANT_DATE }

data class LifeAssistantItem(
    val id: String,
    val kind: LifeAssistantItemKind,
    val title: String,
    val contactId: String?,
    val contactName: String?,
    val eventAtEpochMs: Long,
    val durationMinutes: Int,
    val sourceLabel: String,
    val evidence: String?,
    val confidence: Double,
    val candidateId: String? = null,
)

data class LifeAssistantState(val items: List<LifeAssistantItem> = emptyList(), val isLoading: Boolean = true, val actionMessage: String? = null) {
    val pendingCommitments: List<LifeAssistantItem>
        get() = items.filter { it.kind == LifeAssistantItemKind.COMMITMENT }
    val importantDates: List<LifeAssistantItem>
        get() = items.filter { it.kind == LifeAssistantItemKind.IMPORTANT_DATE }
    val spotlight: LifeAssistantItem?
        get() = pendingCommitments.firstOrNull() ?: items.firstOrNull()
}

internal fun buildLifeAssistantItems(
    importantDates: List<ContactImportantDateProjection>,
    candidates: List<NotificationCandidateEntity>,
    nowEpochMs: Long,
    zoneId: ZoneId,
): List<LifeAssistantItem> {
    val today = Instant.ofEpochMilli(nowEpochMs).atZone(zoneId).toLocalDate()
    val dateItems = importantDates.distinctBy { it.contactId to it.kind }.mapNotNull { date ->
        val occurrence = nextImportantDateOccurrence(date.month, date.day, today) ?: return@mapNotNull null
        LifeAssistantItem(
            id = "date:${date.dateId}:${occurrence.year}",
            kind = LifeAssistantItemKind.IMPORTANT_DATE,
            title = "${date.displayName}的${importantDateDisplayLabel(date.kind)}",
            contactId = date.contactId,
            contactName = date.displayName,
            eventAtEpochMs = occurrence.atStartOfDay(zoneId).toInstant().toEpochMilli(),
            durationMinutes = 0,
            sourceLabel = if (date.userConfirmed) "联系人资料" else "知伴整理",
            evidence = null,
            confidence = if (date.userConfirmed) 1.0 else 0.75,
        )
    }
    val commitmentItems = candidates.mapNotNull { candidate ->
        val insight = ScheduleInsight.from(candidate) ?: return@mapNotNull null
        if (candidate.createdScheduleId != null || insight.startAtEpochMs < nowEpochMs - FIVE_MINUTES_MS) {
            return@mapNotNull null
        }
        val contactName = candidate.senderName?.trim()?.takeIf(String::isNotBlank)
            ?: candidate.conversationTitle?.trim()?.takeIf(String::isNotBlank)
        LifeAssistantItem(
            id = "commitment:${candidate.candidateId}",
            kind = LifeAssistantItemKind.COMMITMENT,
            title = insight.title.trim().ifBlank { "待确认的约定" },
            contactId = candidate.linkedContactId ?: candidate.suggestedContactId,
            contactName = contactName,
            eventAtEpochMs = insight.startAtEpochMs,
            durationMinutes = insight.durationMinutes,
            sourceLabel = candidate.appLabel,
            evidence = candidate.body?.trim()?.take(180)?.takeIf(String::isNotBlank),
            confidence = insight.confidence.coerceIn(0.0, 1.0),
            candidateId = candidate.candidateId,
        )
    }
    return (commitmentItems + dateItems).sortedBy(LifeAssistantItem::eventAtEpochMs)
}

private const val FIVE_MINUTES_MS = 5 * 60_000L
