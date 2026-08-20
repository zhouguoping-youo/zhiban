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
class NotificationSenderDedupMigrationTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgentDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun migration43To44_addsNormalizedSenderColumn() {
        val name = "notification-sender-dedup-migration.db"
        helper.createDatabase(name, 43).close()
        helper.runMigrationsAndValidate(name, 44, true, AgentDatabase.MIGRATION_43_44).use { db ->
            db.execSQL(
                """INSERT INTO notification_candidates
                    (candidateId,sourceKey,packageName,appLabel,title,body,postedAtEpochMs,status,createdAtEpochMs,
                     sourceType,platform,direction,isGroupChat,messageKind,suggestedContactConfidence,normalizedSender)
                    VALUES ('n1','hash','com.tencent.mm','微信','老李头','在吗',1000,'PENDING',1000,
                     'NOTIFICATION','WECHAT','INCOMING',0,'MESSAGE',0.0,'laolitou')""",
            )
            db.query("SELECT normalizedSender FROM notification_candidates WHERE candidateId = 'n1'").use {
                it.moveToFirst()
                assertEquals("laolitou", it.getString(0))
            }
        }
    }
}
