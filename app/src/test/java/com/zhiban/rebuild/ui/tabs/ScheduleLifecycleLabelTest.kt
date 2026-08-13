package com.zhiban.rebuild.ui.tabs

import com.zhiban.rebuild.data.agent.ScheduleProjection
import com.zhiban.rebuild.data.agent.ScheduleStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleLifecycleLabelTest {
    private val schedule = ScheduleProjection("s1", "跟进客户", 10_000L, 30, null)

    @Test fun pendingFutureScheduleOffersCompletionOrPostpone() {
        assertEquals("完成或延期", scheduleLifecycleLabel(schedule, 1_000L))
    }

    @Test fun elapsedPendingScheduleAsksForFeedbackInsteadOfAssumingCompletion() {
        assertEquals("待反馈", scheduleLifecycleLabel(schedule, 2_000_000L))
    }

    @Test fun completedScheduleShowsCompletedState() {
        assertEquals("已完成", scheduleLifecycleLabel(schedule.copy(status = ScheduleStatus.COMPLETED), 2_000_000L))
    }
}
