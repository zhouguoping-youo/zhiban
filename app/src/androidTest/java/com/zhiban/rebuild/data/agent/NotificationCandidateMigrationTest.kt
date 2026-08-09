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
class NotificationCandidateMigrationTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgentDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun migration20To21_addsLocalCandidateInbox() {
        val name = "notification-candidate-migration.db"
        helper.createDatabase(name, 20).close()
        helper.runMigrationsAndValidate(name, 21, true, AgentDatabase.MIGRATION_20_21).use { db ->
            db.execSQL(
                """INSERT INTO notification_candidates
                    (candidateId,sourceKey,packageName,appLabel,title,body,postedAtEpochMs,status,createdAtEpochMs)
                    VALUES ('n1','hash','com.example','示例','张三','明天见',1000,'PENDING',1000)""",
            )
            db.query("SELECT COUNT(*) FROM notification_candidates WHERE status='PENDING'").use {
                it.moveToFirst()
                assertEquals(1, it.getInt(0))
            }
        }
    }
}
