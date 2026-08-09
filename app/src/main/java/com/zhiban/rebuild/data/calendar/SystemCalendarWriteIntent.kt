package com.zhiban.rebuild.data.calendar

import android.content.Intent
import android.provider.CalendarContract
import com.zhiban.rebuild.data.agent.ScheduleProjection

/**
 * Opens the Android-owned calendar editor for explicit user confirmation.
 */
object SystemCalendarWriteIntent {
    fun create(schedule: ScheduleProjection): Intent = Intent(Intent.ACTION_INSERT).apply {
        data = CalendarContract.Events.CONTENT_URI
        putExtra(CalendarContract.Events.TITLE, schedule.title)
        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, schedule.startAtEpochMs)
        putExtra(
            CalendarContract.EXTRA_EVENT_END_TIME,
            schedule.startAtEpochMs + schedule.durationMinutes * 60_000L,
        )
        schedule.note?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) }
    }
}
