package com.zhiban.rebuild.data.agent

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactMergeMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "contact-merge-migration"
    private val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgentDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @After fun clean() {
        context.deleteDatabase(name)
    }

    @Test fun migrationCreatesReversibleMergeLinks() {
        helper.createDatabase(name, 18).close()
        helper.runMigrationsAndValidate(name, 19, true, AgentDatabase.MIGRATION_18_19).close()
    }
}
