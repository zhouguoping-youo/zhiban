package com.zhiban.rebuild.data.calendar

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.agent.ScheduleEntity
import com.zhiban.rebuild.data.config.AgentControlStore
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.event.EventPlanEntity
import com.zhiban.rebuild.data.event.EventPlanParticipantEntity
import com.zhiban.rebuild.data.event.EventPlanStatus
import com.zhiban.rebuild.data.event.EventResponseStatus
import com.zhiban.rebuild.data.suggestion.AgentSuggestionNotifier
import com.zhiban.rebuild.data.suggestion.AgentSuggestionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScheduleAdvanceSuggestionCoordinatorTest {
    private lateinit var database: AgentDatabase
    private lateinit var coordinator: ScheduleAdvanceSuggestionCoordinator

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val controls = AgentControlStore(context, "schedule_advance_${System.nanoTime()}")
        coordinator = ScheduleAdvanceSuggestionCoordinator(database, AgentSuggestionNotifier(context, controls))
    }

    @After fun close() = database.close()

    @Test fun pendingParticipantOneToThreeDaysAheadCreatesSuggestionOnce() = runTest {
        val now = 10L * DAY_MS
        insertPlanningRows("pending", EventResponseStatus.PENDING, now + 2L * DAY_MS)

        assertTrue(coordinator.evaluate("schedule-pending", now + 2L * DAY_MS, now))
        assertFalse(coordinator.evaluate("schedule-pending", now + 2L * DAY_MS, now))

        val suggestion = database.agentSuggestionDao().observeRecent().first().single()
        assertEquals(AgentSuggestionType.SCHEDULE_ADVANCE_CONFIRMATION, suggestion.type)
        assertTrue(suggestion.body.contains("要不要提前联系确认"))
        assertTrue(suggestion.body.contains("张三"))
    }

    @Test fun respondedRescheduledAndOutsideWindowSchedulesAreIgnored() = runTest {
        val now = 10L * DAY_MS
        insertPlanningRows("responded", EventResponseStatus.GOING, now + 2L * DAY_MS)
        insertPlanningRows("far", EventResponseStatus.PENDING, now + 4L * DAY_MS)
        insertPlanningRows("near", EventResponseStatus.PENDING, now + DAY_MS - 1)

        assertFalse(coordinator.evaluate("schedule-responded", now + 2L * DAY_MS, now))
        assertFalse(coordinator.evaluate("schedule-far", now + 4L * DAY_MS, now))
        assertFalse(coordinator.evaluate("schedule-near", now + DAY_MS - 1, now))
        assertFalse(coordinator.evaluate("schedule-far", now + 5L * DAY_MS, now))
        assertTrue(database.agentSuggestionDao().observeRecent().first().isEmpty())
    }

    private suspend fun insertPlanningRows(suffix: String, responseStatus: String, startAt: Long) {
        val contactId = "contact-$suffix"
        val scheduleId = "schedule-$suffix"
        val planId = "plan-$suffix"
        database.contactDao().insert(
            ContactEntity(
                contactId, "张三", "张三", null, null, null, null, null,
                "[]", "[]", null, null, "TEST", null, 1, 1,
            ),
        )
        database.scheduleDao().insert(
            ScheduleEntity(scheduleId, "项目沟通", startAt, 60, null, null, createdAtEpochMs = 1, updatedAtEpochMs = 1),
        )
        database.eventPlanningDao().insertPlan(
            EventPlanEntity(
                planId, "项目沟通", startAt, 60, null, null, EventPlanStatus.CONFIRMED,
                scheduleId, "TEST", 1, 1,
            ),
        )
        database.eventPlanningDao().upsertParticipant(
            EventPlanParticipantEntity(planId, contactId, responseStatus, "TEST", 1),
        )
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1_000
    }
}
