package com.zhiban.rebuild.ui.tabs

import com.zhiban.rebuild.data.agent.ScheduleProjection
import com.zhiban.rebuild.data.agent.ScheduleStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleLifecycleLabelTest {
    private val schedule = ScheduleProjection("s1", "跟进客户", 10_000L, 30, null)

    @Test fun pendingFutureScheduleOffersProgressUpdateWithoutCrowdingTimeColumn() {
        assertEquals("更新进展", scheduleLifecycleLabel(schedule, 1_000L))
    }

    @Test fun elapsedPendingScheduleAsksForFeedbackInsteadOfAssumingCompletion() {
        assertEquals("补充结果", scheduleLifecycleLabel(schedule, 2_000_000L))
    }

    @Test fun completedScheduleShowsCompletedState() {
        assertEquals("查看结果", scheduleLifecycleLabel(schedule.copy(status = ScheduleStatus.COMPLETED), 2_000_000L))
    }
}
