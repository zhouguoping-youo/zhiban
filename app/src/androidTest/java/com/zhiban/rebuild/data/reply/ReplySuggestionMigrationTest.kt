package com.zhiban.rebuild.data.reply

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiban.rebuild.data.agent.AgentDatabase
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReplySuggestionMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgentDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun migration39To40CreatesReplySuggestions() {
        val name = "reply-suggestion-migration.db"
        helper.createDatabase(name, 39)
        helper.runMigrationsAndValidate(name, 40, true, AgentDatabase.MIGRATION_39_40).use { db ->
            db.execSQL(
                "INSERT INTO reply_suggestions (suggestionId, candidateId, threadKey, contactId, draft, draftIndex, status, createdAtEpochMs, forwardedAtEpochMs, confirmedAtEpochMs, contactName, incomingExcerpt) " +
                    "VALUES ('rs-1', 'cand-1', 'WECHAT|张三', 'contact-1', '好的', 0, 'PENDING', 1000, NULL, NULL, '张三', '合同能发我一份吗')",
            )
            // Row omitting the denormalized display columns exercises the incomingExcerpt default.
            db.execSQL(
                "INSERT INTO reply_suggestions (suggestionId, candidateId, threadKey, contactId, draft, draftIndex, status, createdAtEpochMs) " +
                    "VALUES ('rs-2', 'cand-2', 'WECHAT|李四', NULL, '收到', 0, 'PENDING', 1001)",
            )
            db.query("SELECT draft, draftIndex, status, contactId, contactName, incomingExcerpt FROM reply_suggestions WHERE suggestionId = 'rs-1'").use {
                check(it.moveToFirst())
                check(it.getString(0) == "好的")
                check(it.getInt(1) == 0)
                check(it.getString(2) == "PENDING")
                check(it.getString(3) == "contact-1")
                check(it.getString(4) == "张三")
                check(it.getString(5) == "合同能发我一份吗")
            }
            db.query("SELECT contactName, incomingExcerpt FROM reply_suggestions WHERE suggestionId = 'rs-2'").use {
                check(it.moveToFirst())
                check(it.isNull(0))
                check(it.getString(1) == "")
            }
        }
    }
}
