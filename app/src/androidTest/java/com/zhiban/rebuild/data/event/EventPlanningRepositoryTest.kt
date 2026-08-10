package com.zhiban.rebuild.data.event

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.contact.ContactEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EventPlanningRepositoryTest {
    private lateinit var database: AgentDatabase
    private lateinit var repository: EventPlanningRepository

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AgentDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = EventPlanningRepository(database)
    }

    @After fun tearDown() = database.close()

    @Test fun planParticipantAndCalendarConfirmationCommitAsOneCoherentFlow() = runBlocking {
        database.contactDao().insert(contact())
        val planId = repository.createPlan("老同事聚餐", 1_000_000L, 120, "静安寺", null, nowEpochMs = 1L)
        repository.addParticipant(planId, "contact-1", nowEpochMs = 2L)
        repository.updateResponse(planId, "contact-1", EventResponseStatus.GOING, nowEpochMs = 3L)

        val schedule = repository.confirmToCalendar(planId, nowEpochMs = 4L)
        val replayed = repository.confirmToCalendar(planId, nowEpochMs = 5L)
        val plan = repository.observePlan(planId).first()

        assertEquals(schedule.id, replayed.id)
        assertEquals(EventPlanStatus.CONFIRMED, plan?.status)
        assertEquals(schedule.id, plan?.scheduleId)
        assertEquals(1, database.scheduleDao().count())
        assertNotNull(database.scheduleDao().findById(schedule.id))
        assertEquals(EventResponseStatus.GOING, repository.observeParticipants(planId).first().single().responseStatus)
    }

    @Test fun calendarConfirmationRequiresParticipantAndRemovedParticipantCannotBeUpdated() = runBlocking {
        database.contactDao().insert(contact())
        val planId = repository.createPlan("老同事聚餐", 1_000_000L, 120, null, null, nowEpochMs = 1L)

        val emptyFailure = runCatching { repository.confirmToCalendar(planId, nowEpochMs = 2L) }
        assertTrue(emptyFailure.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, database.scheduleDao().count())

        repository.addParticipant(planId, "contact-1", nowEpochMs = 3L)
        assertTrue(repository.removeParticipant(planId, "contact-1"))
        val removedFailure = runCatching {
            repository.updateResponse(planId, "contact-1", EventResponseStatus.GOING, nowEpochMs = 4L)
        }
        assertTrue(removedFailure.exceptionOrNull() is IllegalArgumentException)
        assertFalse(repository.observeParticipants(planId).first().isNotEmpty())
    }

    private fun contact() = ContactEntity(
        "contact-1", "李雷", "李雷", null, null, null, null, null,
        "[]", "[]", null, null, "USER", null, 1, 1,
    )
}
