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
class SenderMuteMigrationTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgentDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun migration42To43_addsSenderMuteList() {
        val name = "sender-mute-migration.db"
        helper.createDatabase(name, 42).close()
        helper.runMigrationsAndValidate(name, 43, true, AgentDatabase.MIGRATION_42_43).use { db ->
            db.execSQL(
                """INSERT INTO sender_mutes (muteId, platform, normalizedHandle, visibleHandle, createdAtEpochMs)
                    VALUES ('sender-mute:WECHAT:test', 'WECHAT', 'laoli', '老李', 1000)""",
            )
            db.query("SELECT COUNT(*) FROM sender_mutes WHERE normalizedHandle = 'laoli'").use {
                it.moveToFirst()
                assertEquals(1, it.getInt(0))
            }
        }
    }
}
