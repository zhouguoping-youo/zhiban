package com.zhiban.rebuild.data.completion

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiban.rebuild.data.agent.AgentDatabase
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactCompletionMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgentDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun migration40To41AddsResponsibilitiesAndCompletionRequests() {
        val name = "contact-completion-migration.db"
        helper.createDatabase(name, 40)
        helper.runMigrationsAndValidate(name, 41, true, AgentDatabase.MIGRATION_40_41).use { db ->
            // contacts gains the responsibilities column.
            db.query("PRAGMA table_info(contacts)").use { cursor ->
                var found = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "responsibilities") found = true
                }
                check(found) { "contacts.responsibilities column missing after migration" }
            }
            // A contact row satisfies the FK for a completion request.
            db.execSQL(
                "INSERT INTO contacts (contactId, displayName, normalizedName, aliasesJson, tagsJson, source, createdAtEpochMs, updatedAtEpochMs) " +
                    "VALUES ('c-1', '张三', '张三', '[]', '[]', 'USER', 1000, 1000)",
            )
            db.execSQL(
                "INSERT INTO contact_completion_requests " +
                    "(requestId, contactId, requestedFieldsJson, draftText, status, threadKey, responseCandidateId, " +
                    "sentAtEpochMs, respondedAtEpochMs, createdAtEpochMs, expiresAtEpochMs, updatedAtEpochMs) " +
                    "VALUES ('ccr-1', 'c-1', '[\"PHONE\",\"EMAIL\"]', '方便发我下你的手机号和邮箱吗？', 'AWAITING_REPLY', " +
                    "'WECHAT|张三', NULL, 2000, NULL, 1000, 9000, 2000)",
            )
            db.query("SELECT status, requestedFieldsJson, contactId FROM contact_completion_requests WHERE requestId = 'ccr-1'").use {
                check(it.moveToFirst())
                check(it.getString(0) == "AWAITING_REPLY")
                check(it.getString(1) == "[\"PHONE\",\"EMAIL\"]")
                check(it.getString(2) == "c-1")
            }
        }
    }
}
