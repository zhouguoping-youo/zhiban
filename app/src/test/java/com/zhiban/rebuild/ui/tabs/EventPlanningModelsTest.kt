package com.zhiban.rebuild.ui.tabs

import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.event.EventPlanEntity
import com.zhiban.rebuild.data.event.EventPlanParticipantEntity
import com.zhiban.rebuild.data.event.EventPlanStatus
import com.zhiban.rebuild.data.event.EventResponseStatus
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventPlanningModelsTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test fun joinsParticipantsAndCountsOnlyPendingReplies() {
        val item = buildEventPlanUi(
            plans = listOf(plan()),
            participants = listOf(
                participant("contact-1", EventResponseStatus.PENDING),
                participant("contact-2", EventResponseStatus.GOING),
            ),
            contacts = listOf(contact("contact-1", "李雷"), contact("contact-2", "韩梅梅")),
        ).single()

        assertEquals(listOf("李雷", "韩梅梅"), item.participants.map { it.contact.displayName })
        assertEquals(1, item.pendingReplies)
        assertEquals("2 人 · 1 人待回复", eventProgressLabel(item))
    }

    @Test fun invitePromptUsesKnownFactsAndNeverClaimsMessageWasSent() {
        val item = buildEventPlanUi(
            listOf(plan()),
            listOf(participant("contact-1", EventResponseStatus.PENDING)),
            listOf(contact("contact-1", "李雷")),
        ).single()

        val prompt = eventInvitePrompt(item)

        assertTrue(prompt.contains("李雷"))
        assertTrue(prompt.contains("发送前必须让我确认"))
        assertFalse(prompt.contains("已发送给"))
    }

    private fun plan() = EventPlanEntity(
        planId = "plan-1",
        title = "老同事聚餐",
        proposedStartAtEpochMs = LocalDateTime.of(2026, 8, 15, 18, 30).atZone(zone).toInstant().toEpochMilli(),
        durationMinutes = 120,
        location = "静安寺",
        note = null,
        status = EventPlanStatus.COORDINATING,
        scheduleId = null,
        sourceType = "USER_CREATED",
        createdAtEpochMs = 1,
        updatedAtEpochMs = 1,
    )

    private fun participant(contactId: String, status: String) = EventPlanParticipantEntity("plan-1", contactId, status, "USER_SELECTED", 1)

    private fun contact(id: String, name: String) = ContactEntity(
        id, name, name, null, null, null, null, null, "[]", "[]", null, null, "USER", null, 1, 1,
    )
}
