package com.zhiban.rebuild.data.calllog

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CallNoteFollowUpCoordinatorTest {
    private lateinit var database: AgentDatabase
    private lateinit var coordinator: CallNoteFollowUpCoordinator

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AgentDatabase::class.java)
            .addCallback(AgentDatabase.CALLBACK)
            .allowMainThreadQueries()
            .build()
        coordinator = CallNoteFollowUpCoordinator(database)
    }

    @After fun tearDown() = database.close()

    @Test fun pendingCallIsRemindedOnceItHasWaitedTwoHours() = runBlocking {
        database.callLogDao().upsertCall(call("pending", "PENDING", updatedAtEpochMs = 1_000L))

        assertFalse(coordinator.shouldRemind("pending", 1_000L + FOLLOW_UP_DELAY_MS - 1L))
        assertTrue(coordinator.shouldRemind("pending", 1_000L + FOLLOW_UP_DELAY_MS))
    }

    @Test fun completedOrDismissedCallNoteIsNotReminded() = runBlocking {
        database.callLogDao().upsertCall(call("completed", "COMPLETED", updatedAtEpochMs = 1_000L))
        database.callLogDao().upsertCall(call("dismissed", "DISMISSED", updatedAtEpochMs = 1_000L))

        assertFalse(coordinator.shouldRemind("completed", 1_000L + FOLLOW_UP_DELAY_MS))
        assertFalse(coordinator.shouldRemind("dismissed", 1_000L + FOLLOW_UP_DELAY_MS))
        assertFalse(coordinator.shouldRemind("missing", 1_000L + FOLLOW_UP_DELAY_MS))
    }

    private fun call(id: String, state: String, updatedAtEpochMs: Long) = CallRecordEntity(
        callRecordId = id,
        source = "TEST",
        providerRowId = id.hashCode().toLong(),
        rawNumber = null,
        normalizedNumber = null,
        numberPresentation = 1,
        systemType = 1,
        direction = "INCOMING",
        startedAtEpochMs = 1L,
        durationSeconds = 60L,
        lastModifiedEpochMs = 1L,
        phoneAccountId = null,
        phoneAccountComponentName = null,
        linkedContactId = null,
        linkState = "UNMATCHED",
        linkSource = null,
        sourceStatus = "ACTIVE",
        notePromptState = state,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = updatedAtEpochMs,
    )
}
