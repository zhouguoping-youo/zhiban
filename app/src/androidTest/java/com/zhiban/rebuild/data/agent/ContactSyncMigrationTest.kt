package com.zhiban.rebuild.data.agent

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactSyncMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgentDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migration36To37AddsEncryptedContactSyncReceiptTable() {
        val name = "contact-sync-migration.db"
        helper.createDatabase(name, 36).close()

        helper.runMigrationsAndValidate(name, 37, true, AgentDatabase.MIGRATION_36_37).use { db ->
            db.query("SELECT COUNT(*) FROM contact_sync_operations").use { cursor ->
                check(cursor.moveToFirst())
                check(cursor.getInt(0) == 0)
            }
        }
    }
}
