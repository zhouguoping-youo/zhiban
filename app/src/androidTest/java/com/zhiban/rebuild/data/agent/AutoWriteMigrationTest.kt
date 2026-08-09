package com.zhiban.rebuild.data.agent

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutoWriteMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgentDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migration30To31PreservesChangesAndAllowsRuntimeLessVisibleAutoWrites() {
        val name = "auto-write-migration.db"
        helper.createDatabase(name, 30).use { db ->
            // Production v30 installs have this callback-managed partial index even though it is
            // intentionally absent from Room's entity schema.
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_plan_runs_single_active_per_definition " +
                    "ON plan_runs(definitionId) WHERE runStatus IN ('RUNNING', 'PAUSED')",
            )
            db.execSQL(
                "INSERT INTO change_log VALUES ('old-change', 'run-1', 'calendar.schedule.create', 'old-key', 'CALENDAR', 'schedule-1', 'CREATE', NULL, 'after', '{\"deleteScheduleId\":\"schedule-1\"}', 'AVAILABLE', 1, NULL)",
            )
        }

        helper.runMigrationsAndValidate(name, 31, true, AgentDatabase.MIGRATION_30_31).use { db ->
            db.query("SELECT runtimeRunId, originType FROM change_log WHERE changeId='old-change'").use {
                check(it.moveToFirst())
                check(it.getString(0) == "run-1")
                check(it.getString(1) == "RUNTIME_TOOL")
            }
            db.execSQL(
                "INSERT INTO change_log VALUES ('auto-change', NULL, 'contact.interactionSummary.record', 'auto-key', 'FACT', 'fact-1', 'CREATE', NULL, 'after', '{\"deleteFactId\":\"fact-1\"}', 'AVAILABLE', 2, NULL, 'SYSTEM_PERCEPTION')",
            )
            db.execSQL(
                "INSERT INTO auto_write_receipts VALUES ('auto-change', NULL, 'WECHAT', 'digest', 1.0, 'INTERACTION_SUMMARY', 'CONTACT_PICKER', 'UNREVIEWED', 2)",
            )
            db.query("SELECT COUNT(*) FROM auto_write_receipts WHERE changeId='auto-change'").use {
                check(it.moveToFirst() && it.getInt(0) == 1)
            }
        }
    }
}
