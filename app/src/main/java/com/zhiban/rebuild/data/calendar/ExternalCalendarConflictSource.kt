package com.zhiban.rebuild.data.calendar

data class ExternalCalendarConflict(val sourceId: String, val title: String, val startAtEpochMs: Long, val endAtEpochMs: Long)

/** Read-only boundary used when local calendar writes need to account for device calendar events. */
fun interface ExternalCalendarConflictSource {
    suspend fun findConflicts(startAtEpochMs: Long, endAtEpochMs: Long, excludeScheduleId: String?, limit: Int): List<ExternalCalendarConflict>
}
