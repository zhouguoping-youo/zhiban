package com.zhiban.rebuild.data.agent

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RelationshipGraphMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AgentDatabase::class.java)

    @Test fun migration14To15CreatesRelationshipEdges() {
        val name = "relationship-v14-v15"
        helper.createDatabase(name, 14).close()
        val db = helper.runMigrationsAndValidate(name, 15, true, AgentDatabase.MIGRATION_14_15)
        db.execSQL(
            "INSERT INTO relationship_edges(edgeId,fromContactId,toContactId,relationType,evidenceDigest,evidenceRefsJson,confidence,userConfirmed,skillId,status,createdAtEpochMs,updatedAtEpochMs) VALUES('e','a','b','FRIEND','d','[]',1,1,NULL,'ACTIVE',1,1)",
        )
        assertEquals(
            1,
            db.query("SELECT COUNT(*) FROM relationship_edges").use {
                it.moveToFirst()
                it.getInt(0)
            },
        )
        db.close()
    }
}
