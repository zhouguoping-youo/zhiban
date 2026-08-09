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
class ContactIdentityMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "contact-identity-migration"
    private val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgentDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @After
    fun clean() {
        context.deleteDatabase(name)
    }

    @Test
    fun migrationCreatesStructuredIdentityTables() {
        helper.createDatabase(name, 17).close()
        helper.runMigrationsAndValidate(name, 18, true, AgentDatabase.MIGRATION_17_18).close()
    }
}
