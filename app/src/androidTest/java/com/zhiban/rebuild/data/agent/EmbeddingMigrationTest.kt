package com.zhiban.rebuild.data.agent

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmbeddingMigrationTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgentDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun migration15To16CreatesManagedEmbeddingSpaceWithCascade() {
        val name = "embedding-migration"
        helper.createDatabase(name, 15).close()
        helper.runMigrationsAndValidate(name, 16, true, AgentDatabase.MIGRATION_15_16).use { db ->
            db.query("SELECT COUNT(*) FROM embedding_vectors").use { cursor -> cursor.moveToFirst() }
        }
    }
}
