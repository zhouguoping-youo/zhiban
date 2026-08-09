package com.zhiban.rebuild.data.agent

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CrmDomainMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgentDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migration26To27CreatesCrmDomainTables() {
        val name = "crm-domain-migration.db"
        helper.createDatabase(name, 26).use { db ->
            // Mirrors the real app's unmanaged onOpen invariant on an existing v26 install.
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_plan_runs_single_active_per_definition` " +
                    "ON `plan_runs` (`definitionId`) WHERE `runStatus` = 'ACTIVE'",
            )
        }
        helper.runMigrationsAndValidate(name, 27, true, AgentDatabase.MIGRATION_26_27).use { db ->
            val tableNames = db.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE 'crm_%' ORDER BY name",
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
            check("crm_leads" in tableNames)
            check("crm_opportunities" in tableNames)
            check("crm_activities" in tableNames)
            check("crm_next_actions" in tableNames)
            check("crm_agent_suggestions" in tableNames)
            check("crm_stage_history" in tableNames)
            val legacyIndexCount = db.query(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_plan_runs_single_active_per_definition'",
            ).use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
            check(legacyIndexCount == 0)
        }
    }
}
