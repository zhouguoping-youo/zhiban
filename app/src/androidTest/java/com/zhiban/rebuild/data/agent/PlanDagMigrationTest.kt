package com.zhiban.rebuild.data.agent

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ADR-006 §3.1 + ADR-005 §10.1: v8->v9 migration must add the 11 Plan
 * DAG tables without disturbing the v8 foundation (memory layer, runtime
 * store, agent_runs, schedules, tool_audits). The partial UNIQUE fence
 * index is created by the onCreate / onOpen callback (Room cannot model
 * partial UNIQUE in @Index); the migration test freezes only what the
 * migration itself does, and the fence behavior is covered by
 * PlanLifecycleFenceTest.
 */
@RunWith(AndroidJUnit4::class)
class PlanDagMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgentDatabase::class.java,
    )

    @Test
    fun migrate8To9AddsPlanDagTablesAndPreservesLegacyRows() {
        val name = "plan-dag-v8-v9"
        helper.createDatabase(name, 8).apply {
            execSQL(
                "INSERT INTO agent_runs(id,userInput,status,pendingToolCallJson,schemaVersion,expiresAtEpochMs,errorCode,createdAtEpochMs,updatedAtEpochMs) " +
                    "VALUES ('legacy-run','hi','SUCCEEDED',NULL,1,NULL,NULL,100,100)",
            )
            execSQL(
                "INSERT INTO memories(id,kind,content,sourceRunId,schemaVersion,createdAtEpochMs) " +
                    "VALUES ('legacy-mem','USER_PREFERENCE','legacy-pref','legacy-run',1,100)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(name, 9, true, AgentDatabase.MIGRATION_8_9)

        // Legacy rows preserved
        assertEquals(1, scalar(db, "SELECT COUNT(*) FROM agent_runs WHERE id='legacy-run'"))
        assertEquals(1, scalar(db, "SELECT COUNT(*) FROM memories WHERE id='legacy-mem' AND content='legacy-pref'"))

        // All 11 Plan DAG tables created
        listOf(
            "plan_versions",
            "plan_definitions",
            "plan_nodes",
            "plan_edges",
            "plan_runs",
            "node_attempts",
            "approval_grants",
            "dispatch_outbox",
            "result_ledger",
            "resource_leases",
            "session_leases",
        ).forEach { table ->
            assertEquals("missing $table", 1, scalar(db, "SELECT COUNT(*) FROM sqlite_master WHERE name='$table'"))
        }
    }

    @Test
    fun migrate8To9SchemaIsAcceptedByRoomValidator() {
        // runMigrationsAndValidate would throw IllegalStateException with
        // "Migration didn't properly handle" if the migration's actual
        // table schema diverged from the Room-generated v9.json. Reaching
        // the assert below means the migration passed Room's strict check.
        val name = "plan-dag-validator-v8-v9"
        helper.createDatabase(name, 8).apply { close() }
        val db = helper.runMigrationsAndValidate(name, 9, true, AgentDatabase.MIGRATION_8_9)
        // The 11 new tables are queryable
        val tables = listOf(
            "plan_versions", "plan_definitions", "plan_nodes", "plan_edges",
            "plan_runs", "node_attempts", "approval_grants",
            "dispatch_outbox", "result_ledger", "resource_leases", "session_leases",
        )
        for (table in tables) {
            val count = scalar(db, "SELECT COUNT(*) FROM $table")
            assertTrue("$table must be empty after migration (count=$count)", count == 0)
        }
    }

    private fun scalar(db: SupportSQLiteDatabase, sql: String): Int {
        db.query(sql).use { c ->
            check(c.moveToFirst())
            return c.getInt(0)
        }
    }
}
