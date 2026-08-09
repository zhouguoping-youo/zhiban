package com.zhiban.rebuild.data.agent

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactKnowledgeMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgentDatabase::class.java,
    )

    @Test
    fun migration24To26CreatesKnowledgeTablesAndBackfillsLegacyFields() {
        val name = "contact-knowledge-v24-v26"
        helper.createDatabase(name, 24).apply {
            execSQL(
                """INSERT INTO contacts(
                    contactId,displayName,normalizedName,phone,email,wechatId,company,title,
                    aliasesJson,tagsJson,note,avatarUri,source,deletedAtEpochMs,createdAtEpochMs,updatedAtEpochMs
                ) VALUES('c1','小李','小李','13800138000','li@example.com','liwx','示例科技','产品经理',
                    '[]','[]',NULL,NULL,'USER',NULL,100,200)""",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            name,
            26,
            true,
            AgentDatabase.MIGRATION_24_25,
            AgentDatabase.MIGRATION_25_26,
        )
        assertEquals(
            3,
            db.query("SELECT COUNT(*) FROM contact_methods WHERE contactId='c1'").use {
                it.moveToFirst()
                it.getInt(0)
            },
        )
        assertEquals(
            "示例科技",
            db.query("SELECT companyNameSnapshot FROM contact_employments WHERE contactId='c1'").use {
                it.moveToFirst()
                it.getString(0)
            },
        )
        assertEquals(
            1,
            db.query("SELECT COUNT(*) FROM organizations").use {
                it.moveToFirst()
                it.getInt(0)
            },
        )
        assertEquals(
            0,
            db.query("SELECT COUNT(*) FROM owner_contact_links").use {
                it.moveToFirst()
                it.getInt(0)
            },
        )
        db.close()
    }
}
