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
class AutoWriteSummaryMigrationTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgentDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun migration46To47_addsReceiptSummaryColumn() {
        val name = "auto-write-summary-migration.db"
        helper.createDatabase(name, 46).close()
        helper.runMigrationsAndValidate(name, 47, true, AgentDatabase.MIGRATION_46_47).use { db ->
            db.execSQL(
                """INSERT INTO auto_write_receipts
                    (changeId, sourceType, sourceRefDigest, presentationType, correctionRoute, reviewState, createdAtEpochMs, summary)
                    VALUES ('c1', 'WECHAT', 'digest', 'CONTACT_COMPLETION', 'CONTACT_PROFILE', 'UNREVIEWED', 1000, '公司全称：某公司')""",
            )
            db.query("SELECT summary FROM auto_write_receipts WHERE changeId = 'c1'").use {
                it.moveToFirst()
                assertEquals("公司全称：某公司", it.getString(0))
            }
        }
    }
}
