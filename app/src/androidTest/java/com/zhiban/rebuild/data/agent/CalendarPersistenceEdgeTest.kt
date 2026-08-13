package com.zhiban.rebuild.data.agent

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.calendar.SystemCalendarEvent
import com.zhiban.rebuild.data.crm.CrmActionStatus
import com.zhiban.rebuild.data.crm.CrmNextActionEntity
import com.zhiban.rebuild.data.crm.CrmOpportunityEntity
import com.zhiban.rebuild.data.crm.CrmOpportunityStage
import com.zhiban.rebuild.data.crm.CrmRecordStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalendarPersistenceEdgeTest {
    private lateinit var db: AgentDatabase
    private lateinit var calendar: CalendarAgentDataRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AgentDatabase::class.java)
            .allowMainThreadQueries().build()
        calendar = CalendarAgentDataRepository(db)
    }

    @After fun tearDown() = db.close()

    @Test fun localScheduleRoundTripsEmojiAndNewlinesWithoutSystemCalendarAccess() = runBlocking {
        val title = "客户会 🤝\n第二阶段"
        val id = calendar.saveUserSchedule(null, title, 1_000_000L, 30, "备注 ✅", null, nowEpochMs = 1L)

        assertEquals(title, calendar.findSchedule(id)?.title)
        assertEquals("备注 ✅", calendar.findSchedule(id)?.note)
    }

    @Test fun deletingScheduleNullsCrmActionReferenceButPreservesAction() = runBlocking {
        val scheduleId = calendar.saveUserSchedule(null, "跟进提醒", 1_000_000L, 30, null, null, nowEpochMs = 1L)
        db.crmDao().insertOpportunity(opportunity())
        db.crmDao().insertAction(action(scheduleId))

        assertTrue(calendar.deleteSchedule(scheduleId))

        val preserved = db.crmDao().findAction(ACTION_ID)
        assertEquals("继续跟进", preserved?.title)
        assertNull(preserved?.scheduleId)
    }

    @Test fun duplicateSystemCalendarInstancesInOneImportAreStoredOnce() = runBlocking {
        val event = SystemCalendarEvent("42:1000", "重复会议", null, null, 1_000_000L, 1_060_000L, "工作")

        val summary = calendar.importConfirmedSystemCalendarEvents(
            listOf(event, event.copy(title = "重复实例")),
            nowEpochMs = 1L,
        )

        assertEquals(1, summary.created)
        assertEquals(0, summary.updated)
        assertEquals(1, db.scheduleDao().count())
        assertEquals("重复会议", db.scheduleDao().findById("system-calendar-42:1000")?.title)
    }

    @Test fun equivalentSystemEventDoesNotDuplicateAnExistingZhiBanSchedule() = runBlocking {
        val start = 2_000_000L
        calendar.saveUserSchedule(null, "向王经理发送武汉医院项目最终报价单", start, 30, null, null, nowEpochMs = 1L)

        val summary = calendar.importConfirmedSystemCalendarEvents(
            listOf(
                SystemCalendarEvent(
                    sourceId = "device-duplicate",
                    title = "向王经理发送武汉医院项目最终报价单",
                    startAtEpochMs = start,
                    endAtEpochMs = start + 30 * 60_000L,
                    location = null,
                    description = null,
                    calendarName = "My calendar",
                ),
            ),
            nowEpochMs = 2L,
        )

        assertEquals(0, summary.created)
        assertEquals(1, db.scheduleDao().count())
    }

    @Test fun completionStoresFeedbackAndReschedulingReopensTheSchedule() = runBlocking {
        val scheduleId = calendar.saveUserSchedule(null, "回访客户", 1_000_000L, 30, null, null, nowEpochMs = 1L)

        assertTrue(calendar.completeSchedule(scheduleId, "客户确认下周给答复", nowEpochMs = 2L))
        val completed = requireNotNull(calendar.findSchedule(scheduleId))
        assertEquals(ScheduleStatus.COMPLETED, completed.status)
        assertEquals("客户确认下周给答复", completed.outcomeNote)
        assertEquals(2L, completed.completedAtEpochMs)

        calendar.saveUserSchedule(scheduleId, completed.title, 2_000_000L, 30, null, null, nowEpochMs = 3L)
        val postponed = requireNotNull(calendar.findSchedule(scheduleId))
        assertEquals(ScheduleStatus.PENDING, postponed.status)
        assertNull(postponed.outcomeNote)
        assertNull(postponed.completedAtEpochMs)
    }

    @Test fun pendingFeedbackIncludesOnlyElapsedUnfinishedSchedules() = runBlocking {
        val elapsed = calendar.saveUserSchedule(null, "过期未完成", 1_000_000L, 30, null, null, nowEpochMs = 1L)
        val completed = calendar.saveUserSchedule(null, "过期已完成", 1_100_000L, 30, null, null, nowEpochMs = 1L)
        calendar.saveUserSchedule(null, "未来安排", 5_000_000L, 30, null, null, nowEpochMs = 1L)
        calendar.completeSchedule(completed, "完成", nowEpochMs = 2L)

        val pending = calendar.observePendingFeedback(beforeEpochMs = 4_000_000L, oldestEpochMs = 0L).first()

        assertEquals(listOf(elapsed), pending.map { it.id })
    }

    private fun opportunity() = CrmOpportunityEntity(
        OPPORTUNITY_ID, "历史商机", "客户公司", null, null, CrmOpportunityStage.QUALIFIED,
        CrmRecordStatus.OPEN, null, "CNY", 45, null, null, null, null, "USER_CONFIRMED", 1, 1,
    )

    private fun action(scheduleId: String) = CrmNextActionEntity(
        ACTION_ID, OPPORTUNITY_ID, null, "FOLLOW_UP", "继续跟进", 1_000_000L,
        CrmActionStatus.PENDING, 1, null, "USER_CONFIRMED", scheduleId, 1, 1,
    )

    private companion object {
        const val OPPORTUNITY_ID = "opportunity-calendar"
        const val ACTION_ID = "action-calendar"
    }
}
