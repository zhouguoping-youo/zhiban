package com.zhiban.rebuild.data.contact

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiban.rebuild.data.agent.AgentDatabase
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 41→42 索引补齐:contacts(normalizedName, deletedAtEpochMs) 与 notification_candidates 的
 * (status, postedAtEpochMs)/(platform, direction) 复合索引在迁移后落库;contacts 的活动部分索引
 * 由 CALLBACK 托管(迁移验证后 onOpen 重建)。
 */
@RunWith(AndroidJUnit4::class)
class ContactIndexesMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgentDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun migration41To42AddsContactAndNotificationIndexes() {
        val name = "contact-indexes-migration.db"
        helper.createDatabase(name, 41)
        helper.runMigrationsAndValidate(name, 42, true, AgentDatabase.MIGRATION_41_42).use { db ->
            listOf(
                "index_contacts_normalizedName_deletedAtEpochMs",
                "index_notification_candidates_status_postedAtEpochMs",
                "index_notification_candidates_platform_direction",
            ).forEach { index ->
                db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name = ?", arrayOf(index)).use {
                    check(it.moveToFirst()) { "$index missing after migration" }
                }
            }
        }
    }
}
