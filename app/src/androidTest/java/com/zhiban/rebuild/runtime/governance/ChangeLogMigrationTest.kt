package com.zhiban.rebuild.runtime.governance

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
class ChangeLogMigrationTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgentDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun migration10To11CreatesDurableUndoLedgerWithoutPiiPayloadRequirement() {
        val name = "change-log-migration-${System.nanoTime()}"
        helper.createDatabase(name, 10).close()
        val db = helper.runMigrationsAndValidate(name, 11, true, AgentDatabase.MIGRATION_10_11)
        db.execSQL(
            "INSERT INTO change_log VALUES ('change-1','run-1','contact.createCandidate','key-1','CONTACT','c1','CREATE',NULL,'digest','{\"deleteContactId\":\"c1\"}','AVAILABLE',1,NULL)",
        )
        db.query("SELECT inversePayloadJson, undoState FROM change_log WHERE changeId='change-1'").use {
            it.moveToFirst()
            assertEquals("{\"deleteContactId\":\"c1\"}", it.getString(0))
            assertEquals("AVAILABLE", it.getString(1))
        }
        db.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(name)
    }
}
