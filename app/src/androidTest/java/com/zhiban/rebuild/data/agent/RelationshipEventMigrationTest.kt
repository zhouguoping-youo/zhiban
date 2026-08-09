package com.zhiban.rebuild.data.agent

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RelationshipEventMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgentDatabase::class.java,
    )

    @Test
    fun migration16To17CreatesEventAndParticipantTablesWithCascade() {
        val name = "relationship-event-v16-v17"
        helper.createDatabase(name, 16).close()
        val db = helper.runMigrationsAndValidate(name, 17, true, AgentDatabase.MIGRATION_16_17)
        // MigrationTestHelper exposes the raw SQLite connection. Mirror Room's
        // runtime configuration so foreign-key cascade behavior is exercised.
        db.execSQL("PRAGMA foreign_keys=ON")
        db.execSQL(
            """INSERT INTO relationship_events(
                eventId,eventType,title,note,occurredAtEpochMs,evidenceDigest,evidenceRefsJson,
                userConfirmed,status,createdAtEpochMs,updatedAtEpochMs
            ) VALUES('event-1','INTRODUCTION','小周介绍我认识小李',NULL,NULL,'USER_CONFIRMED',
                '["USER_PROFILE"]',1,'ACTIVE',1,1)""",
        )
        db.execSQL(
            """INSERT INTO relationship_event_participants(
                participantId,eventId,participantKind,contactId,participantRole,displayNameSnapshot,createdAtEpochMs
            ) VALUES('participant-1','event-1','CONTACT','contact-1','SUBJECT','小李',1)""",
        )
        assertEquals(
            1,
            db.query("SELECT COUNT(*) FROM relationship_events").use {
                it.moveToFirst()
                it.getInt(0)
            },
        )
        assertEquals(
            1,
            db.query("SELECT COUNT(*) FROM relationship_event_participants").use {
                it.moveToFirst()
                it.getInt(0)
            },
        )
        db.execSQL("DELETE FROM relationship_events WHERE eventId='event-1'")
        assertEquals(
            0,
            db.query("SELECT COUNT(*) FROM relationship_event_participants").use {
                it.moveToFirst()
                it.getInt(0)
            },
        )
        db.close()
    }
}
