package com.zhiban.rebuild.data.agent

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScheduleReminderMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgentDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migration19To20_preservesScheduleAndAddsOptionalReminder() {
        val name = "schedule-reminder-migration.db"
        helper.createDatabase(name, 19).use { db ->
            db.execSQL(
                """INSERT INTO schedules
                    (id,title,startAtEpochMs,durationMinutes,note,createdByRunId,createdByRuntimeRunId,
                    createdByRuntimeAttemptId,createdAtEpochMs,updatedAtEpochMs)
                    VALUES ('s1','体检',100000,60,NULL,NULL,NULL,NULL,1,1)""",
            )
        }
        helper.runMigrationsAndValidate(name, 20, true, AgentDatabase.MIGRATION_19_20).use { db ->
            db.query("SELECT title, reminderMinutesBefore FROM schedules WHERE id='s1'").use { cursor ->
                cursor.moveToFirst()
                assertEquals("体检", cursor.getString(0))
                assertEquals(true, cursor.isNull(1))
            }
        }
    }
}
