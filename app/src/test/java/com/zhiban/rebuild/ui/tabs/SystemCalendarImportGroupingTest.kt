package com.zhiban.rebuild.ui.tabs

import com.zhiban.rebuild.data.calendar.SystemCalendarEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class SystemCalendarImportGroupingTest {
    @Test
    fun `events are grouped by stable calendar id even when names match`() {
        val events = listOf(
            event("1", "工作", 10L),
            event("2", "工作", 10L),
            event("3", "工作", 20L),
        )

        val sources = groupSystemCalendarEvents(events)

        assertEquals(listOf("calendar:10", "calendar:20"), sources.map { it.key })
        assertEquals(listOf(2, 1), sources.map { it.events.size })
    }

    @Test
    fun `missing calendar metadata is shown as one fallback source`() {
        val sources = groupSystemCalendarEvents(
            listOf(event("1", null, null), event("2", "", null)),
        )

        assertEquals(1, sources.size)
        assertEquals("其他日历", sources.single().name)
        assertEquals(2, sources.single().events.size)
    }

    private fun event(sourceId: String, calendarName: String?, calendarId: Long?) = SystemCalendarEvent(
        sourceId = sourceId,
        title = "日程 $sourceId",
        description = null,
        location = null,
        startAtEpochMs = 1_000L,
        endAtEpochMs = 2_000L,
        calendarName = calendarName,
        calendarId = calendarId,
    )
}
