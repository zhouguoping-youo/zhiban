package com.zhiban.rebuild.data.agent

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactInteractionMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgentDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migration51To52BackfillsMetadataOnlyInteractionLedger() {
        val name = "contact-interaction-migration.db"
        helper.createDatabase(name, 51).use { db ->
            db.execSQL(
                """INSERT INTO contacts
                (contactId, displayName, normalizedName, aliasesJson, tagsJson, source, createdAtEpochMs, updatedAtEpochMs)
                VALUES ('contact-1', '联系人', '联系人', '[]', '[]', 'MANUAL', 1, 1)""",
            )
            db.execSQL(
                """INSERT INTO facts VALUES
                ('fact-1','INTERACTION_SUMMARY','不应进入账本的正文',NULL,'CRM_ACTIVITY','activity-1',
                 'contact-1',NULL,1.0,'PERSONAL','ACTIVE',90,NULL,1000,1000)""",
            )
            db.execSQL(
                """INSERT INTO call_records VALUES
                ('call-1','ANDROID_CALL_LOG',9,NULL,NULL,1,1,'INCOMING',2000,20,2001,NULL,NULL,
                 'contact-1','MATCHED','NORMALIZED_PHONE','ACTIVE','NONE',2,2)""",
            )
            db.execSQL(
                """INSERT INTO notification_candidates
                (candidateId,sourceKey,packageName,appLabel,title,body,postedAtEpochMs,status,createdAtEpochMs,
                 sourceType,platform,direction,isGroupChat,messageKind,suggestedContactConfidence,linkedContactId)
                VALUES ('n1','key','com.tencent.mm','微信','标题','不应进入账本的消息',3000,'CONFIRMED',3,
                 'NOTIFICATION','WECHAT','OUTGOING',0,'MESSAGE',1.0,'contact-1')""",
            )
        }

        helper.runMigrationsAndValidate(name, 52, true, AgentDatabase.MIGRATION_51_52).use { db ->
            db.query("SELECT sourceType, occurredAtEpochMs, channel, direction FROM contact_interactions ORDER BY occurredAtEpochMs").use {
                assertEquals(3, it.count)
                it.moveToFirst()
                assertEquals("FACT", it.getString(0))
                it.moveToNext()
                assertEquals("CALL", it.getString(0))
                it.moveToNext()
                assertEquals("NOTIFICATION", it.getString(0))
                assertEquals("WECHAT", it.getString(2))
                assertEquals("OUTGOING", it.getString(3))
            }
            db.query("PRAGMA table_info(contact_interactions)").use { cursor ->
                val columns = buildSet {
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                }
                assertFalse(columns.any { it in setOf("body", "textContent", "note", "rawNumber") })
            }
        }
    }
}
