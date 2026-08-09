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
class MessagePerceptionMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgentDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationAddsStructuredMessagePerceptionWithoutChangingOldRows() {
        val name = "message-perception-migration"
        helper.createDatabase(name, 22).apply {
            execSQL(
                """INSERT INTO notification_candidates
                   (candidateId,sourceKey,packageName,appLabel,title,body,postedAtEpochMs,status,createdAtEpochMs)
                   VALUES ('c1','s1','com.tencent.mm','微信','张三','明天下午三点见面',1,'PENDING',1)""",
            )
            close()
        }
        helper.runMigrationsAndValidate(
            name,
            24,
            true,
            AgentDatabase.MIGRATION_22_23,
            AgentDatabase.MIGRATION_23_24,
        ).use { db ->
            db.query(
                "SELECT platform, sourceType, direction, isGroupChat, messageKind, suggestedContactConfidence FROM notification_candidates WHERE candidateId='c1'",
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals("OTHER", cursor.getString(0))
                assertEquals("NOTIFICATION", cursor.getString(1))
                assertEquals("INCOMING", cursor.getString(2))
                assertEquals(0, cursor.getInt(3))
                assertEquals("MESSAGE", cursor.getString(4))
                assertEquals(0.0, cursor.getDouble(5), 0.0)
            }
        }
    }

    @Test
    fun repairMigrationPreservesCompleteVersion23Rows() {
        val name = "message-perception-v23-v24"
        helper.createDatabase(name, 23).apply {
            execSQL(
                """INSERT INTO notification_candidates
                   (candidateId,sourceKey,packageName,appLabel,title,body,postedAtEpochMs,status,createdAtEpochMs,
                    sourceType,platform,conversationTitle,senderName,direction,isGroupChat,messageKind,
                    suggestedContactConfidence)
                   VALUES ('c23','s23','com.ss.android.lark','飞书','项目群','明天下午三点开会',1,'PENDING',1,
                    'NOTIFICATION','FEISHU','项目群','李雷','INCOMING',1,'MESSAGE',0.9)""",
            )
            close()
        }
        helper.runMigrationsAndValidate(name, 24, true, AgentDatabase.MIGRATION_23_24).use { db ->
            db.query(
                "SELECT platform, senderName, direction, isGroupChat FROM notification_candidates WHERE candidateId='c23'",
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals("FEISHU", cursor.getString(0))
                assertEquals("李雷", cursor.getString(1))
                assertEquals("INCOMING", cursor.getString(2))
                assertEquals(1, cursor.getInt(3))
            }
        }
    }
}
