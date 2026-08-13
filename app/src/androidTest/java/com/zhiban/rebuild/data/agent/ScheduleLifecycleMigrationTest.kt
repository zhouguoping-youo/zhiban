package com.zhiban.rebuild.data.agent

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScheduleLifecycleMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgentDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun existingSchedulesRemainPendingAfterLifecycleMigration() {
        val name = "schedule-lifecycle-migration.db"
        helper.createDatabase(name, 37).use { db ->
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_plan_runs_single_active_per_definition` " +
                    "ON `plan_runs` (`definitionId`) WHERE `runStatus` = 'ACTIVE'",
            )
            db.execSQL(
                """INSERT INTO schedules
                    (id,title,startAtEpochMs,durationMinutes,note,createdByRunId,createdByRuntimeRunId,
                    createdByRuntimeAttemptId,createdAtEpochMs,updatedAtEpochMs,reminderMinutesBefore)
                    VALUES ('s1','旧日程',100000,60,NULL,NULL,NULL,NULL,1,1,NULL)""",
            )
        }

        helper.runMigrationsAndValidate(name, 38, true, AgentDatabase.MIGRATION_37_38).use { db ->
            db.query("SELECT status, outcomeNote, completedAtEpochMs FROM schedules WHERE id='s1'").use { cursor ->
                cursor.moveToFirst()
                assertEquals(ScheduleStatus.PENDING, cursor.getString(0))
                assertNull(cursor.getString(1))
                assertEquals(true, cursor.isNull(2))
            }
        }
    }
}
