package com.zhiban.rebuild.data.agent

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CallLogMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgentDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migration29To30CreatesCanonicalCallTablesAndForeignKeys() {
        val name = "call-log-migration.db"
        helper.createDatabase(name, 29).close()

        helper.runMigrationsAndValidate(name, 30, true, AgentDatabase.MIGRATION_29_30).use { db ->
            db.execSQL(
                "INSERT INTO contacts (contactId, displayName, normalizedName, aliasesJson, tagsJson, source, createdAtEpochMs, updatedAtEpochMs) VALUES ('contact-1', '联系人', '联系人', '[]', '[]', 'MANUAL', 1, 1)",
            )
            db.execSQL(
                "INSERT INTO call_records VALUES ('call-1', 'ANDROID_CALL_LOG', 9, '13800138000', '13800138000', 1, 1, 'INCOMING', 1000, 20, 1001, NULL, NULL, 'contact-1', 'MATCHED', 'NORMALIZED_PHONE', 'ACTIVE', 'NONE', 1, 1)",
            )
            db.execSQL("INSERT INTO call_notes VALUES ('note-1', 'call-1', '谈过报价', 'TYPED', NULL, 1, 1)")
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("DELETE FROM contacts WHERE contactId = 'contact-1'")
            db.query("SELECT linkedContactId FROM call_records WHERE callRecordId = 'call-1'").use {
                check(it.moveToFirst() && it.isNull(0))
            }
            db.execSQL("DELETE FROM call_records WHERE callRecordId = 'call-1'")
            db.query("SELECT COUNT(*) FROM call_notes").use {
                check(it.moveToFirst() && it.getInt(0) == 0)
            }
        }
    }
}
