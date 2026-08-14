package com.zhiban.rebuild.runtime.kernel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationContinuationRequirementsTest {
    @Test
    fun `calendar result cannot finish a request that also asks for contact count`() {
        val instruction = remainingObservationRequirements(
            "统计联系人总数，并查看今天日程",
            setOf("calendar.schedule.search"),
        )

        assertTrue(instruction.contains("contact.maintenance.list"))
        assertFalse(instruction.contains("calendar.schedule.search"))
    }

    @Test
    fun `contact result cannot finish a request that also asks for today's schedule`() {
        val instruction = remainingObservationRequirements(
            "Count my contacts and check today's schedule.",
            setOf("contact.maintenance.list"),
        )

        assertTrue(instruction.contains("calendar.schedule.search"))
        assertFalse(instruction.contains("contact.maintenance.list"))
    }

    @Test
    fun `no continuation remains after both requested domains completed`() {
        val instruction = remainingObservationRequirements(
            "联系人总数和今天安排",
            setOf("contact.maintenance.list", "calendar.schedule.search"),
        )

        assertTrue(instruction.isEmpty())
    }

    @Test
    fun `runtime chooses the missing explicit read instead of trusting early model final`() {
        assertEquals(
            "contact.maintenance.list",
            nextRequiredReadTool("联系人总数和今天安排", setOf("calendar.schedule.search")),
        )
        assertEquals(
            "calendar.schedule.search",
            nextRequiredReadTool("联系人总数和今天安排", setOf("contact.maintenance.list")),
        )
        assertEquals(
            null,
            nextRequiredReadTool(
                "联系人总数和今天安排",
                setOf("contact.maintenance.list", "calendar.schedule.search"),
            ),
        )
    }

    @Test
    fun `completed multi-domain read summarizes every verified result`() {
        val summary = requiredReadCompletionSummary(
            "联系人总数和今天安排",
            listOf(
                "contact.maintenance.list" to "{\"totalContactCount\":12}",
                "calendar.schedule.search" to "{\"count\":0}",
            ),
        )

        assertTrue(summary.orEmpty().contains("联系人总数：12 人"))
        assertTrue(summary.orEmpty().contains("没有日程安排"))
    }

    @Test
    fun `completed summary accepts canonical calendar alias`() {
        val summary = requiredReadCompletionSummary(
            "Count my contacts and check today's schedule.",
            listOf(
                "contact.maintenance.list" to "{\"totalContactCount\":3}",
                "calendar.search" to "{\"count\":2}",
            ),
        )

        assertTrue(summary.orEmpty().contains("联系人总数：3 人"))
        assertTrue(summary.orEmpty().contains("已查到 2 条日程"))
    }

    @Test
    fun `calendar conflict summary names every occupied source instead of claiming no conflict`() {
        val summary = deterministicToolSummary(
            "calendar.schedule.conflicts",
            """{"hasConflict":true,"count":2,"items":[{"title":"客户视频会议","startAtEpochMs":1786802400000,"source":"ZHI_BAN"},{"title":"机场接人","startAtEpochMs":1786802400000,"source":"SYSTEM_CALENDAR"}]}""",
        )

        assertTrue(summary.contains("客户视频会议"))
        assertTrue(summary.contains("机场接人"))
        assertTrue(summary.contains("知伴日历"))
        assertTrue(summary.contains("手机日历"))
        assertTrue(summary.contains("会发生冲突"))
    }
}
