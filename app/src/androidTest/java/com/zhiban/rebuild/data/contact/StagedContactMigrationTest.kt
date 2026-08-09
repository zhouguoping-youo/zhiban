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
class StagedContactMigrationTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgentDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun migration11To12CreatesDedicatedExpiringContactStagingTable() {
        val name = "staged-contact-migration-${System.nanoTime()}"
        helper.createDatabase(name, 11).close()
        val db = helper.runMigrationsAndValidate(name, 12, true, AgentDatabase.MIGRATION_11_12)
        db.execSQL("INSERT INTO staged_contact_candidates VALUES ('candidate-1','{}','digest','PENDING',1,999,1)")
        db.query("SELECT state, payloadDigest FROM staged_contact_candidates WHERE candidateId='candidate-1'").use {
            it.moveToFirst()
            assertEquals("PENDING", it.getString(0))
            assertEquals("digest", it.getString(1))
        }
        db.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(name)
    }
}
