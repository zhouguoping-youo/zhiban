package com.zhiban.rebuild.data.calendar

import com.zhiban.rebuild.data.agent.ScheduleEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleReminderValidationTest {
    private val scheduled = ScheduleEntity(
        id = "schedule-1",
        title = "更新后的标题",
        startAtEpochMs = 10_000L,
        durationMinutes = 30,
        note = null,
        createdByRunId = null,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 2L,
        reminderMinutesBefore = 15,
    )

    @Test fun deletedScheduleInvalidatesQueuedReminder() {
        assertFalse(matchesReminderSnapshot(null, 10_000L, 15))
    }

    @Test fun rescheduledOrChangedReminderInvalidatesOldWorkerSnapshot() {
        assertFalse(matchesReminderSnapshot(scheduled.copy(startAtEpochMs = 20_000L), 10_000L, 15))
        assertFalse(matchesReminderSnapshot(scheduled.copy(reminderMinutesBefore = 30), 10_000L, 15))
        assertTrue(matchesReminderSnapshot(scheduled, 10_000L, 15))
    }

    @Test fun movedEarlierScheduleWithinReminderWindowRunsImmediately() {
        assertEquals(
            0L,
            reminderDelayMillis(startAtEpochMs = 120_000L, reminderMinutesBefore = 5, nowEpochMs = 60_000L),
        )
        assertEquals(
            240_000L,
            reminderDelayMillis(startAtEpochMs = 600_000L, reminderMinutesBefore = 5, nowEpochMs = 60_000L),
        )
        assertEquals(
            null,
            reminderDelayMillis(startAtEpochMs = 60_000L, reminderMinutesBefore = 5, nowEpochMs = 60_000L),
        )
    }

    @Test fun workManagerSnapshotNeverContainsScheduleTitle() {
        val data = reminderWorkData("schedule-1", 10_000L, 15)

        assertEquals(setOf("scheduleId", "startAtEpochMs", "reminderMinutesBefore"), data.keyValueMap.keys)
        assertEquals("schedule-1", data.getString(ScheduleReminderWorker.KEY_SCHEDULE_ID))
    }

    @Test fun deletedOrRescheduledWorkerSnapshotNeverDispatchesNotification() = runTest {
        var dispatchCount = 0
        val deleted = runIfReminderCurrent("schedule-1", 10_000L, 15, { null }) { dispatchCount++ }
        val rescheduled = runIfReminderCurrent(
            "schedule-1",
            10_000L,
            15,
            { scheduled.copy(startAtEpochMs = 20_000L) },
        ) { dispatchCount++ }

        assertFalse(deleted)
        assertFalse(rescheduled)
        assertEquals(0, dispatchCount)
    }
}
