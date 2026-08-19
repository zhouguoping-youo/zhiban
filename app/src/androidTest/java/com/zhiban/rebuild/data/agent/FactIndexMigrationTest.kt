package com.zhiban.rebuild.data.agent

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zhiban.rebuild.data.facts.FactEntity
import com.zhiban.rebuild.data.facts.FactIndex
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FactIndexMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "fact-index-migration.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgentDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @After fun clean() = context.deleteDatabase(name).let { Unit }

    @Test fun migrationCreatesFactStoreAndFullTextProjection() = runBlocking {
        helper.createDatabase(name, 13).close()
        helper.runMigrationsAndValidate(name, 14, true, AgentDatabase.MIGRATION_13_14).close()
        val db = androidx.room.Room.databaseBuilder(context, AgentDatabase::class.java, name)
            .addMigrations(
                AgentDatabase.MIGRATION_13_14,
                AgentDatabase.MIGRATION_14_15,
                AgentDatabase.MIGRATION_15_16,
                AgentDatabase.MIGRATION_16_17,
                AgentDatabase.MIGRATION_17_18,
                AgentDatabase.MIGRATION_18_19,
                AgentDatabase.MIGRATION_19_20,
                AgentDatabase.MIGRATION_20_21,
                AgentDatabase.MIGRATION_21_22,
                AgentDatabase.MIGRATION_22_23,
                AgentDatabase.MIGRATION_23_24,
                AgentDatabase.MIGRATION_24_25,
                AgentDatabase.MIGRATION_25_26,
                AgentDatabase.MIGRATION_26_27,
                AgentDatabase.MIGRATION_27_28,
                AgentDatabase.MIGRATION_28_29,
                AgentDatabase.MIGRATION_29_30,
                AgentDatabase.MIGRATION_30_31,
                AgentDatabase.MIGRATION_31_32,
                AgentDatabase.MIGRATION_32_33,
                AgentDatabase.MIGRATION_33_34,
                AgentDatabase.MIGRATION_34_35,
                AgentDatabase.MIGRATION_35_36,
                AgentDatabase.MIGRATION_36_37,
                AgentDatabase.MIGRATION_37_38,
                AgentDatabase.MIGRATION_38_39,
                AgentDatabase.MIGRATION_39_40,
                AgentDatabase.MIGRATION_40_41,
                AgentDatabase.MIGRATION_41_42,
                AgentDatabase.MIGRATION_42_43,
                AgentDatabase.MIGRATION_43_44,
                AgentDatabase.MIGRATION_44_45,
            )
            .addCallback(AgentDatabase.CALLBACK)
            .allowMainThreadQueries().build()
        val index = FactIndex(db)
        index.upsert(
            FactEntity(
                "contact:c1", "CONTACT", "张三在星河科技负责采购", null, "TEST", "run-1", "c1", "crm",
                1.0, "NORMAL", "ACTIVE", 0, null, 1, 1,
            ),
        )
        assertEquals(
            1,
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM facts").use {
                it.moveToFirst()
                it.getInt(0)
            },
        )
        assertEquals(
            1,
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM fact_fts").use {
                it.moveToFirst()
                it.getInt(0)
            },
        )
        assertEquals(
            1,
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM fact_fts WHERE fact_fts MATCH '星'").use {
                it.moveToFirst()
                it.getInt(0)
            },
        )
        assertEquals(
            1,
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM fact_fts WHERE fact_fts MATCH '星 河'").use {
                it.moveToFirst()
                it.getInt(0)
            },
        )
        assertEquals(
            1,
            db.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM facts WHERE factId IN (SELECT factId FROM fact_fts WHERE fact_fts MATCH '星 河')",
            ).use {
                it.moveToFirst()
                it.getInt(0)
            },
        )
        val rows = index.search("星河", 2, 10)
        assertEquals(listOf("contact:c1"), rows.map { it.factId })
        db.close()
    }
}
