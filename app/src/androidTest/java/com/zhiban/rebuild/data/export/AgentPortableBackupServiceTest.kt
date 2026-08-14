package com.zhiban.rebuild.data.export

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.agent.AgentDatabaseEncryption
import com.zhiban.rebuild.data.agent.AgentDatabaseKeyManager
import com.zhiban.rebuild.data.agent.AgentDatabasePortableBackup
import com.zhiban.rebuild.data.contact.ContactEntity
import java.io.File
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentPortableBackupServiceTest {
    private lateinit var context: Context
    private lateinit var database: AgentDatabase
    private lateinit var databaseName: String

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AgentDatabaseEncryption.initializeLibrary()
        databaseName = "portable-service-${System.nanoTime()}.db"
        database = AgentDatabaseKeyManager(context).withPassphrase { passphrase ->
            Room.databaseBuilder(context, AgentDatabase::class.java, databaseName)
                .openHelperFactory(SupportOpenHelperFactory(passphrase.copyOf(), null, true))
                .addCallback(AgentDatabase.CALLBACK)
                .allowMainThreadQueries()
                .build()
        }
    }

    @After fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
        File(context.getDatabasePath(databaseName).path + ".restore-pending").delete()
    }

    @Test fun serviceBacksUpAnOpenWalDatabaseWithoutDroppingCommittedRows() = runBlocking {
        database.contactDao().insert(
            ContactEntity(
                "portable-contact", "测试联系人", "测试联系人", null, null, null, null, null,
                "[]", "[]", null, null, "USER", null, 10, 10,
            ),
        )
        val service = AgentPortableBackupService(context, database, databaseName)
        val password = "portable-password".toCharArray()

        val backup = service.create(password, 1234)

        assertFalse(AgentDatabaseEncryption.hasPlaintextHeader(backup))
        val destination = File(context.cacheDir, "portable-service-target-${System.nanoTime()}.db")
        val destinationKey = "different-device-key".toByteArray()
        val summary = AgentDatabasePortableBackup.stageRestore(
            destination,
            backup,
            "portable-password".toByteArray(),
            destinationKey,
        )
        assertEquals(1, summary.contactCount)
        AgentDatabasePortableBackup.cancelPendingRestore(destination)
        destinationKey.fill(0)
        backup.delete()
        Unit
    }
}
