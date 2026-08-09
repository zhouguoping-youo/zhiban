package com.zhiban.rebuild.runtime.plan

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.agent.PlanDefinitionEntity
import com.zhiban.rebuild.data.agent.PlanNodeEntity
import com.zhiban.rebuild.data.agent.PlanVersionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Per-ADR-006 §3.1, plan_runs has a partial UNIQUE index that allows at
 * most one ACTIVE run per definitionId. This test freezes the four
 * legitimate boundaries the architect called out:
 *   1. fenceBlocksSecondActiveInsert
 *   2. pauseFreesSlotForFreshActive
 *   3. longRunningAndPausedCanCoexist
 *   4. supersedeReplacesActiveAndKeepsAuditTrail
 * and one defensive boundary (resume refuses when another ACTIVE exists).
 */
@RunWith(AndroidJUnit4::class)
class PlanLifecycleFenceTest {
    private lateinit var database: AgentDatabase
    private lateinit var lifecycle: PlanLifecycle
    private var nowMs: Long = 1_000L

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(AgentDatabase.CALLBACK)
            .build()
        lifecycle = PlanLifecycle(database, database.planDao(), clock = { nowMs })
        // Seed a version + definition used by all tests
        lifecycle.insertVersion(PlanVersionEntity("v1", schemaVersion = 1, createdAtEpochMs = 1, note = "seed"))
        lifecycle.insertDefinition(
            PlanDefinitionEntity(
                definitionId = DEF_ID,
                versionId = "v1",
                ownerNamespace = "USER",
                fingerprint = "fp-$DEF_ID",
                payloadJson = "{}",
                createdAtEpochMs = 1,
            ),
        )
    }

    @After fun tearDown() = database.close()

    @Test
    fun fenceBlocksSecondActiveInsertForSameDefinition() = runBlocking {
        val first = lifecycle.startActiveRun(DEF_ID, "run-1")
        assertEquals("ACTIVE", first.runStatus)

        try {
            lifecycle.startActiveRun(DEF_ID, "run-2")
            fail("expected partial UNIQUE fence to reject a second ACTIVE run")
        } catch (_: SQLiteConstraintException) {
            // expected
        }

        val runs = database.planDao().runsForDefinition(DEF_ID)
        assertEquals(1, runs.size)
        assertEquals("run-1", runs.single().runId)
    }

    @Test
    fun pauseFreesSlotForFreshActive() = runBlocking {
        val first = lifecycle.startActiveRun(DEF_ID, "run-1")
        nowMs = 2_000L
        assertTrue(lifecycle.pauseRun(first.runId, nowMs = nowMs))

        // Pausing transitions ACTIVE -> PAUSED; partial UNIQUE no longer
        // applies, so a fresh ACTIVE insert must succeed.
        val second = lifecycle.startActiveRun(DEF_ID, "run-2")
        assertEquals("ACTIVE", second.runStatus)

        val runs = database.planDao().runsForDefinition(DEF_ID)
        assertEquals(2, runs.size)
        val byId = runs.associateBy { it.runId }
        assertEquals("PAUSED", byId.getValue("run-1").runStatus)
        assertEquals(2_000L, byId.getValue("run-1").completedAtEpochMs)
        assertEquals("ACTIVE", byId.getValue("run-2").runStatus)
        assertEquals(null, byId.getValue("run-2").completedAtEpochMs)
    }

    @Test
    fun longRunningAndPausedCanCoexist() = runBlocking {
        // Long-running ACTIVE
        val longRun = lifecycle.startActiveRun(DEF_ID, "long-run")
        // Pre-existing PAUSED run for the same definition (inserted as PAUSED
        // directly to represent history that arrived before this run)
        val paused = com.zhiban.rebuild.data.agent.PlanRunEntity(
            runId = "paused-1",
            definitionId = DEF_ID,
            runStatus = PlanLifecycle.RUN_STATUS_PAUSED,
            activeAttemptId = null,
            startedAtEpochMs = 500L,
            completedAtEpochMs = 500L,
        )
        database.planDao().insertRun(paused)

        val runs = database.planDao().runsForDefinition(DEF_ID)
        assertEquals(2, runs.size)
        val byId = runs.associateBy { it.runId }
        assertEquals("ACTIVE", byId.getValue("long-run").runStatus)
        assertEquals(PlanLifecycle.RUN_STATUS_PAUSED, byId.getValue("paused-1").runStatus)
        // Pausing the long-run should still work
        nowMs = 3_000L
        assertTrue(lifecycle.pauseRun(longRun.runId, nowMs = nowMs))
    }

    @Test
    fun supersedeReplacesActiveAndKeepsAuditTrail() = runBlocking {
        val first = lifecycle.startActiveRun(DEF_ID, "run-1")
        nowMs = 4_000L

        val second = lifecycle.supersedeAndStart(DEF_ID, "run-2", nowMs = nowMs)
        assertEquals("run-2", second.runId)
        assertEquals("ACTIVE", second.runStatus)

        val runs = database.planDao().runsForDefinition(DEF_ID)
        assertEquals(2, runs.size)
        val byId = runs.associateBy { it.runId }
        assertEquals(com.zhiban.rebuild.data.agent.PLAN_STATUS_SUPERSEDED, byId.getValue("run-1").runStatus)
        assertEquals(4_000L, byId.getValue("run-1").completedAtEpochMs)
        assertEquals("ACTIVE", byId.getValue("run-2").runStatus)
    }

    @Test
    fun resumeRefusedWhenAnotherActiveExists() = runBlocking {
        val first = lifecycle.startActiveRun(DEF_ID, "run-1")
        assertTrue(lifecycle.pauseRun(first.runId, nowMs = 2_000L))
        // After pause we have a PAUSED run; insert a second ACTIVE for
        // same definition. The schema allows it (only one ACTIVE).
        lifecycle.startActiveRun(DEF_ID, "run-2")

        // Trying to resume the PAUSED run must fail at the partial UNIQUE
        // fence (run-2 is ACTIVE for the same definition).
        try {
            lifecycle.resumeRun(first.runId)
            fail("expected resume to fail when another ACTIVE run exists")
        } catch (_: SQLiteConstraintException) {
            // expected
        }

        val runs = database.planDao().runsForDefinition(DEF_ID)
        val byId = runs.associateBy { it.runId }
        assertEquals(PlanLifecycle.RUN_STATUS_PAUSED, byId.getValue("run-1").runStatus)
        assertEquals("ACTIVE", byId.getValue("run-2").runStatus)
    }

    @Test
    fun supersedeOnNoActiveIsJustAnInsert() = runBlocking {
        // No ACTIVE run yet; supersedeAndStart should not require a
        // pre-existing run, just insert the new ACTIVE.
        val first = lifecycle.supersedeAndStart(DEF_ID, "run-1", nowMs = 5_000L)
        assertEquals("run-1", first.runId)
        val runs = database.planDao().runsForDefinition(DEF_ID)
        assertEquals(1, runs.size)
        assertEquals("ACTIVE", runs.single().runStatus)
    }

    private companion object {
        const val DEF_ID: String = "def-test"
    }
}
