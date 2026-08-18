package com.zhiban.rebuild.data.agent

import com.zhiban.rebuild.data.store.RuntimeSessionEntity

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentDatabaseEncryptionTest {
    private lateinit var context: Context
    private val name = "agent-encryption-migration-test.db"

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(name)
        AgentDatabaseEncryption.initializeLibrary()
    }

    @After fun tearDown() {
        context.deleteDatabase(name)
    }

    @Test fun plaintextDatabaseIsAtomicallyEncryptedAndReopensWithStableKeystoreKey() {
        val file = context.getDatabasePath(name)
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE private_values(id INTEGER PRIMARY KEY, value TEXT NOT NULL)")
            db.execSQL("INSERT INTO private_values(value) VALUES (?)", arrayOf("private-memory-value"))
            db.version = 16
        }
        assertTrue(AgentDatabaseEncryption.hasPlaintextHeader(file))

        val keys = AgentDatabaseKeyManager(context)
        keys.withPassphrase { AgentDatabaseEncryption.migratePlaintextIfNeeded(context, name, it) }
        assertFalse(AgentDatabaseEncryption.hasPlaintextHeader(file))
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("private-memory-value"))

        repeat(2) {
            keys.withPassphrase { passphrase ->
                SQLiteDatabase.openOrCreateDatabase(file, passphrase, null, null).use { db ->
                    db.rawQuery("SELECT value FROM private_values", emptyArray()).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals("private-memory-value", cursor.getString(0))
                    }
                    assertEquals(16, db.version)
                }
            }
        }
        assertFalse(
            context.getSharedPreferences(
                "agent_database_key",
                Context.MODE_PRIVATE,
            ).all.values.joinToString().contains("private-memory-value"),
        )
    }

    @Test fun newRoomDatabaseIsEncryptedAndReopensWithoutDataLoss() {
        val file = context.getDatabasePath(name)
        val keys = AgentDatabaseKeyManager(context)
        repeat(2) { pass ->
            keys.withPassphrase { key ->
                val database: AgentDatabase = Room.databaseBuilder(context, AgentDatabase::class.java, name)
                    .openHelperFactory(SupportOpenHelperFactory(key.copyOf(), null, true))
                    .addCallback(AgentDatabase.CALLBACK).allowMainThreadQueries().build()
                try {
                    runBlocking {
                        if (pass ==
                            0
                        ) {
                            database.runtimeSessionDao().insert(
                                RuntimeSessionEntity("encrypted-session", updatedAtEpochMs = 1),
                            )
                        } else {
                            assertEquals(
                                "encrypted-session",
                                database.runtimeSessionDao().find("encrypted-session")?.sessionId,
                            )
                        }
                    }
                } finally {
                    database.close()
                }
            }
            assertFalse(AgentDatabaseEncryption.hasPlaintextHeader(file))
        }
    }

    @Test fun plaintextMigrationIsDeferredUntilDatabaseIsFirstOpened() {
        val file = context.getDatabasePath(name)
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE private_values(id INTEGER PRIMARY KEY, value TEXT NOT NULL)")
            db.execSQL("INSERT INTO private_values(value) VALUES ('deferred-secret')")
            db.version = 16
        }
        val helper = AgentDatabaseKeyManager(context).withPassphrase { passphrase ->
            MigratingSqlCipherOpenHelperFactory(context, passphrase).create(
                SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(name)
                    .callback(object : SupportSQLiteOpenHelper.Callback(16) {
                        override fun onCreate(db: SupportSQLiteDatabase) = Unit
                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    })
                    .build(),
            )
        }

        assertTrue(
            "constructing Room's helper must not migrate on the caller thread",
            AgentDatabaseEncryption.hasPlaintextHeader(file),
        )
        helper.writableDatabase.query("SELECT value FROM private_values").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("deferred-secret", cursor.getString(0))
        }
        assertFalse(AgentDatabaseEncryption.hasPlaintextHeader(file))
        helper.close()
    }
}
