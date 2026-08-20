package com.zhiban.rebuild.ui.tabs

import com.zhiban.rebuild.data.contact.ContactImportantDateProjection
import com.zhiban.rebuild.data.contact.nextImportantDateOccurrence
import com.zhiban.rebuild.data.notification.NotificationCandidateEntity
import com.zhiban.rebuild.data.notification.NotificationInsights
import com.zhiban.rebuild.data.notification.ScheduleInsight
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LifeAssistantModelsTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test fun nextOccurrenceKeepsTodayAndMovesPastDatesToNextYear() {
        val today = LocalDate.of(2026, 8, 10)

        assertEquals(today, nextImportantDateOccurrence(8, 10, today))
        assertEquals(LocalDate.of(2027, 8, 9), nextImportantDateOccurrence(8, 9, today))
        assertNull(nextImportantDateOccurrence(13, 1, today))
    }

    @Test fun leapDayUsesLastValidDayInNonLeapYear() {
        assertEquals(
            LocalDate.of(2027, 2, 28),
            nextImportantDateOccurrence(2, 29, LocalDate.of(2026, 3, 1)),
        )
    }

    @Test fun commitmentsStayCandidatesAndTakeSpotlightPriority() {
        val now = LocalDateTime.of(2026, 8, 10, 9, 0).atZone(zone).toInstant().toEpochMilli()
        val eventAt = LocalDateTime.of(2026, 8, 11, 15, 0).atZone(zone).toInstant().toEpochMilli()
        val items = buildLifeAssistantItems(
            importantDates = listOf(importantDate(month = 8, day = 10)),
            candidates = listOf(commitmentCandidate(eventAt)),
            nowEpochMs = now,
            zoneId = zone,
        )
        val state = LifeAssistantState(items = items, isLoading = false)

        assertEquals(LifeAssistantItemKind.COMMITMENT, state.spotlight?.kind)
        assertEquals("candidate-1", state.spotlight?.candidateId)
        assertEquals(1, state.pendingCommitments.size)
        assertEquals(1, state.importantDates.size)
    }

    private fun importantDate(month: Int, day: Int) = ContactImportantDateProjection(
        dateId = "date-1",
        contactId = "contact-1",
        displayName = "李雷",
        kind = "BIRTHDAY",
        year = 1990,
        month = month,
        day = day,
        source = "USER",
        evidenceRef = null,
        userConfirmed = true,
        updatedAtEpochMs = 1L,
    )

    private fun commitmentCandidate(eventAt: Long) = NotificationCandidateEntity(
        candidateId = "candidate-1",
        sourceKey = "source-1",
        packageName = "com.tencent.mm",
        appLabel = "微信",
        title = "李雷",
        body = "明天下午三点见面",
        postedAtEpochMs = eventAt - 24 * 60 * 60 * 1_000L,
        senderName = "李雷",
        linkedContactId = "contact-1",
        insightJson = NotificationInsights(
            ScheduleInsight("见面", eventAt, confidence = 0.92),
        ).toJsonOrNull(),
    )
}
