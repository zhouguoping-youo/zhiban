package com.zhiban.rebuild.data.agent

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EventPlanningMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgentDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun migration34To35CreatesPlansWithContactAndScheduleIntegrity() {
        val name = "event-planning-migration.db"
        helper.createDatabase(name, 34).use { db ->
            db.execSQL(
                "INSERT INTO contacts (contactId, displayName, normalizedName, aliasesJson, tagsJson, source, createdAtEpochMs, updatedAtEpochMs) " +
                    "VALUES ('contact-1', '李雷', '李雷', '[]', '[]', 'USER', 1, 1)",
            )
            db.execSQL(
                "INSERT INTO schedules (id, title, startAtEpochMs, durationMinutes, createdAtEpochMs, updatedAtEpochMs) " +
                    "VALUES ('schedule-1', '聚餐', 100000, 60, 1, 1)",
            )
        }

        helper.runMigrationsAndValidate(name, 35, true, AgentDatabase.MIGRATION_34_35).use { db ->
            db.execSQL(
                "INSERT INTO event_plans VALUES ('plan-1', '聚餐', 100000, 60, NULL, NULL, 'CONFIRMED', 'schedule-1', 'USER_CREATED', 1, 1)",
            )
            db.execSQL(
                "INSERT INTO event_plan_participants VALUES ('plan-1', 'contact-1', 'PENDING', 'USER_SELECTED', 1)",
            )
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("DELETE FROM schedules WHERE id = 'schedule-1'")
            db.query("SELECT scheduleId FROM event_plans WHERE planId = 'plan-1'").use {
                check(it.moveToFirst() && it.isNull(0))
            }
            db.execSQL("DELETE FROM contacts WHERE contactId = 'contact-1'")
            db.query("SELECT COUNT(*) FROM event_plan_participants").use {
                check(it.moveToFirst() && it.getInt(0) == 0)
            }
        }
    }
}
