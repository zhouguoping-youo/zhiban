package com.zhiban.rebuild.data.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentDatabasePortableBackupTest {
    private lateinit var directory: File
    private val sourceKey = "source-device-key".toByteArray()
    private val destinationKey = "destination-device-key".toByteArray()
    private val backupPassword = "portable-password".toByteArray()

    @Before fun setUp() {
        AgentDatabaseEncryption.initializeLibrary()
        val context = ApplicationProvider.getApplicationContext<Context>()
        directory = File(context.cacheDir, "portable-backup-test-${System.nanoTime()}").apply { check(mkdirs()) }
    }

    @After fun tearDown() {
        directory.deleteRecursively()
        sourceKey.fill(0)
        destinationKey.fill(0)
        backupPassword.fill(0)
    }

    @Test fun portableBackupReencryptsAndRestoresTheCompleteDatabaseOnColdStart() {
        val source = File(directory, "source.db")
        createDatabase(source, sourceKey, "portable-contact")
        val backup = File(directory, "backup.zhibanbackup")

        val created = AgentDatabasePortableBackup.create(source, sourceKey, backup, backupPassword)

        assertEquals(AGENT_DATABASE_SCHEMA_VERSION, created.schemaVersion)
        assertEquals(1, created.contactCount)
        assertFalse(AgentDatabaseEncryption.hasPlaintextHeader(backup))

        val destination = File(directory, "destination.db")
        createDatabase(destination, destinationKey, "old-contact")
        val staged = AgentDatabasePortableBackup.stageRestore(
            destination,
            backup,
            backupPassword,
            destinationKey,
        )
        assertEquals(1, staged.contactCount)
        assertTrue(AgentDatabasePortableBackup.hasPendingRestore(destination))
        assertEquals("old-contact", contactName(destination, destinationKey))

        AgentDatabasePortableBackup.installPendingRestoreIfPresent(destination, destinationKey)

        assertFalse(AgentDatabasePortableBackup.hasPendingRestore(destination))
        assertEquals("portable-contact", contactName(destination, destinationKey))
        assertFalse(File(directory, "destination.db.pre-restore").exists())
    }

    @Test fun wrongPasswordCannotStageRestore() {
        val source = File(directory, "source.db")
        createDatabase(source, sourceKey, "portable-contact")
        val backup = File(directory, "backup.zhibanbackup")
        AgentDatabasePortableBackup.create(source, sourceKey, backup, backupPassword)

        assertTrue(
            runCatching {
                AgentDatabasePortableBackup.stageRestore(
                    File(directory, "wrong-target.db"),
                    backup,
                    "wrong-password".toByteArray(),
                    destinationKey,
                )
            }.isFailure,
        )
    }

    @Test fun coldStartRecoversAnInterruptedReplacementBeforeApplyingPendingRestore() {
        val source = File(directory, "source.db")
        createDatabase(source, sourceKey, "portable-contact")
        val backup = File(directory, "backup.zhibanbackup")
        AgentDatabasePortableBackup.create(source, sourceKey, backup, backupPassword)
        val destination = File(directory, "destination.db")
        createDatabase(destination, destinationKey, "old-contact")
        AgentDatabasePortableBackup.stageRestore(destination, backup, backupPassword, destinationKey)
        val previous = File(directory, "destination.db.pre-restore")
        assertTrue(destination.renameTo(previous))

        AgentDatabasePortableBackup.installPendingRestoreIfPresent(destination, destinationKey)

        assertEquals("portable-contact", contactName(destination, destinationKey))
        assertFalse(previous.exists())
        assertFalse(AgentDatabasePortableBackup.hasPendingRestore(destination))
    }

    private fun createDatabase(file: File, key: ByteArray, contactName: String) {
        SQLiteDatabase.openOrCreateDatabase(file, key, null, null).use { database ->
            database.rawExecSQL("CREATE TABLE contacts (contactId TEXT PRIMARY KEY, displayName TEXT NOT NULL)")
            database.rawExecSQL("CREATE TABLE relationship_edges (edgeId TEXT PRIMARY KEY)")
            database.rawExecSQL("CREATE TABLE schedules (scheduleId TEXT PRIMARY KEY)")
            database.rawExecSQL("CREATE TABLE memories (memoryId TEXT PRIMARY KEY)")
            database.rawExecSQL("CREATE TABLE crm_opportunities (opportunityId TEXT PRIMARY KEY)")
            database.rawExecSQL("INSERT INTO contacts(contactId, displayName) VALUES ('contact-1', ?)", contactName)
            database.rawExecSQL("PRAGMA user_version = $AGENT_DATABASE_SCHEMA_VERSION")
        }
    }

    private fun contactName(file: File, key: ByteArray): String = SQLiteDatabase.openOrCreateDatabase(file, key, null, null).use { database ->
        database.rawQuery("SELECT displayName FROM contacts WHERE contactId='contact-1'", emptyArray()).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }
    }
}
