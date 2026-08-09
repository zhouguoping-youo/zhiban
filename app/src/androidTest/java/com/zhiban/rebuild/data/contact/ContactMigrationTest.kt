package com.zhiban.rebuild.data.contact

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiban.rebuild.data.agent.AgentDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgentDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun migration9To10CreatesContactAndRoleTables() {
        val name = "contact-migration-${System.nanoTime()}"
        helper.createDatabase(name, 9).close()

        val db = helper.runMigrationsAndValidate(name, 10, true, AgentDatabase.MIGRATION_9_10)
        db.execSQL(
            "INSERT INTO contacts VALUES ('c1','张三','张三','13800138000',NULL,NULL,'知伴科技','经理','[]','[]',NULL,NULL,'USER',NULL,1,1)",
        )
        db.execSQL("INSERT INTO contact_roles VALUES ('c1','crm','CUSTOMER',0.9,1,NULL,1,1)")
        db.query("SELECT COUNT(*) FROM contact_roles WHERE contactId='c1'").use {
            it.moveToFirst()
            assertEquals(1, it.getInt(0))
        }
        db.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(name)
    }
}
