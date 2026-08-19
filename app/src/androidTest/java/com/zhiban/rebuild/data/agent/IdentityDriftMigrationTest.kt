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
class IdentityDriftMigrationTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgentDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun migration44To45_addsIdentityDriftColumn() {
        val name = "identity-drift-migration.db"
        helper.createDatabase(name, 44).close()
        helper.runMigrationsAndValidate(name, 45, true, AgentDatabase.MIGRATION_44_45).use { db ->
            db.execSQL(
                """INSERT INTO notification_candidates
                    (candidateId,sourceKey,packageName,appLabel,title,body,postedAtEpochMs,status,createdAtEpochMs,
                     identityDriftJson)
                    VALUES ('n1','hash','com.tencent.mm','微信','李建国','在吗',1000,'PENDING',1000,
                     '{"platform":"WECHAT","newHandle":"lijiangguo","oldHandle":"老李头","oldIdentityId":"i1"}')""",
            )
            db.query("SELECT identityDriftJson FROM notification_candidates WHERE candidateId = 'n1'").use {
                it.moveToFirst()
                assertEquals("老李头", it.getString(0)?.substringAfter("\"oldHandle\":\"")?.substringBefore('"'))
            }
        }
    }
}
