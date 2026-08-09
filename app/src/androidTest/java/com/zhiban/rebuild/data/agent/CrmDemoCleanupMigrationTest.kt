package com.zhiban.rebuild.data.agent

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CrmDemoCleanupMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgentDatabase::class.java,
    )

    @Test
    fun migration_removes_only_marked_demo_rows_and_records_counts() {
        val name = "crm-demo-cleanup-migration"
        helper.createDatabase(name, 27).use { db ->
            db.execSQL(contactSql("crm-demo-contact-wang", "王建国", "CRM_DEMO"))
            db.execSQL(contactSql("real-contact", "真实联系人", "MANUAL"))
            db.execSQL(scheduleSql("crm-demo-schedule-private", "销售 CRM 演示日程"))
            db.execSQL(scheduleSql("real-schedule", "销售 CRM 真实日程"))
            db.execSQL(
                "INSERT INTO crm_leads VALUES ('crm-demo-lead', 'crm-demo-contact-wang', '王建国', NULL, 'CONVERTED', 'DEMO', NULL, NULL, 1.0, 1, 1, 1)",
            )
            db.execSQL(
                "INSERT INTO crm_leads VALUES ('real-lead', 'real-contact', '真实联系人', NULL, 'NEW', 'USER_CONFIRMED', NULL, NULL, 1.0, 1, 1, 1)",
            )
            db.execSQL(
                "INSERT INTO crm_opportunities VALUES ('crm-demo-opp', '演示机会', '演示公司', 'crm-demo-contact-wang', 'crm-demo-lead', 'LEAD', 'OPEN', NULL, 'CNY', 10, NULL, NULL, NULL, NULL, 'DEMO', 1, 1)",
            )
            db.execSQL(
                "INSERT INTO crm_opportunities VALUES ('real-opp', '真实机会', '真实公司', 'real-contact', 'real-lead', 'LEAD', 'OPEN', NULL, 'CNY', 10, NULL, NULL, NULL, NULL, 'USER_CONFIRMED', 1, 1)",
            )
        }

        helper.runMigrationsAndValidate(name, 28, true, AgentDatabase.MIGRATION_27_28).use { db ->
            assertEquals(0, db.count("SELECT COUNT(*) FROM contacts WHERE source='CRM_DEMO'"))
            assertEquals(1, db.count("SELECT COUNT(*) FROM contacts WHERE contactId='real-contact'"))
            assertEquals(0, db.count("SELECT COUNT(*) FROM crm_opportunities WHERE sourceType='DEMO'"))
            assertEquals(1, db.count("SELECT COUNT(*) FROM crm_opportunities WHERE opportunityId='real-opp'"))
            assertEquals(0, db.count("SELECT COUNT(*) FROM schedules WHERE id LIKE 'crm-demo-%'"))
            assertEquals("个人 CRM 真实日程", db.text("SELECT title FROM schedules WHERE id='real-schedule'"))
            assertEquals(1, db.count("SELECT COUNT(*) FROM crm_demo_cleanup_audits WHERE status='COMPLETED'"))
            assertFalse(db.text("SELECT plannedCountsJson FROM crm_demo_cleanup_audits").isBlank())
            assertFalse(db.text("SELECT deletedCountsJson FROM crm_demo_cleanup_audits").isBlank())
        }
    }

    private fun contactSql(id: String, name: String, source: String) =
        "INSERT INTO contacts VALUES ('$id', '$name', '$name', NULL, NULL, NULL, NULL, NULL, '[]', '[]', NULL, NULL, '$source', NULL, 1, 1)"

    private fun scheduleSql(id: String, title: String) = "INSERT INTO schedules VALUES ('$id', '$title', 1000, 30, '销售 CRM', NULL, NULL, NULL, 1, 1, NULL)"

    private fun SupportSQLiteDatabase.count(sql: String): Int = query(sql).use {
        it.moveToFirst()
        it.getInt(0)
    }
    private fun SupportSQLiteDatabase.text(sql: String): String = query(sql).use {
        it.moveToFirst()
        it.getString(0)
    }
}
