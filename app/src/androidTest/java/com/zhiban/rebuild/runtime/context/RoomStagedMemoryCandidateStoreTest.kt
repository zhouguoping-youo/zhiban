package com.zhiban.rebuild.runtime.context
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class RoomStagedMemoryCandidateStoreTest {
    private lateinit var db: AgentDatabase
    private lateinit var store: RoomStagedMemoryCandidateStore

    @Before fun setup() {
        db =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                AgentDatabase::class.java,
            ).allowMainThreadQueries().build()
        store =
            RoomStagedMemoryCandidateStore(db)
    }

    @After fun close() = db.close()

    @Test fun stageIsPendingScopedTraceableAndBounded() = runBlocking {
        val x = store.stage(MemoryScope.PERSON, "p1", "偏好清淡", listOf("e1"), Sensitivity.PERSONAL, 1000, 60000)
        Assert.assertEquals(32, x.id.length)
        Assert.assertEquals("PENDING", x.state)
        Assert.assertEquals(listOf("e1"), x.sourceIds)
        Assert.assertEquals(1, store.listPending(MemoryScope.PERSON, "p1", 1001).size)
        Assert.assertEquals(0, store.listPending(MemoryScope.PERSON, "p2", 1001).size)
        Assert.assertTrue(
            runCatching {
                store.stage(
                    MemoryScope.SESSION,
                    "s",
                    "x".repeat(65 * 1024),
                    listOf("e"),
                    Sensitivity.PERSONAL,
                    1000,
                    60000,
                )
            }.isFailure,
        )
        Assert.assertTrue(
            runCatching {
                store.stage(MemoryScope.SESSION, "s", "x", listOf("e"), Sensitivity.PERSONAL, 1000, 86400001)
            }.isFailure,
        )
        Assert.assertTrue(
            runCatching {
                store.stage(MemoryScope.SESSION, "s".repeat(257), "x", listOf("e"), Sensitivity.PERSONAL, 1000, 10)
            }.isFailure,
        )
        Assert.assertTrue(
            runCatching {
                store.stage(MemoryScope.SESSION, "s", "x", List(65) { "e$it" }, Sensitivity.PERSONAL, 1000, 10)
            }.isFailure,
        )
    }

    @Test fun approvalUsesRevisionCasAndDoesNotPromoteToPromptOrLongTerm() {
        runBlocking {
            val x = store.stage(MemoryScope.PERSON, "p", "candidate", listOf("e"), Sensitivity.PERSONAL, 1000, 1000)
            Assert.assertEquals(ApprovalWriteResult.APPROVED, store.approve(x.id, "approval-1", 0, 1010))
            Assert.assertEquals(ApprovalWriteResult.DUPLICATE, store.approve(x.id, "approval-1", 0, 1011))
            Assert.assertEquals(ApprovalWriteResult.CONFLICT, store.approve(x.id, "other", 0, 1012))
            val approved = store.find(x.id, 1012)!!
            Assert.assertEquals("APPROVED", approved.state)
            Assert.assertEquals("candidate", approved.content)
            Assert.assertEquals(0, store.listPending(MemoryScope.PERSON, "p", 1012).size)
        }
    }

    @Test fun directApprovalAfterExpiryFailsAndHardClearsContent() {
        runBlocking {
            val x = store.stage(MemoryScope.PERSON, "p", "expired", listOf("e"), Sensitivity.PERSONAL, 1000, 10)
            Assert.assertEquals(ApprovalWriteResult.CONFLICT, store.approve(x.id, "approval", 0, 1011))
            val expired = store.find(x.id, 1011)!!
            Assert.assertEquals("EXPIRED", expired.state)
            Assert.assertNull(expired.content)
        }
    }

    @Test fun rejectDeleteAndExpiryHardClearContent() = runBlocking {
        val a = store.stage(MemoryScope.SESSION, "s", "secret", listOf("e1"), Sensitivity.SENSITIVE, 1000, 100)
        Assert.assertTrue(store.reject(a.id, 1010))
        Assert.assertNull(store.find(a.id, 1010)?.content)
        Assert.assertEquals("REJECTED", store.find(a.id, 1010)?.state)
        val b = store.stage(MemoryScope.WORKING, "r", "delete", listOf("e2"), Sensitivity.PERSONAL, 1000, 100)
        Assert.assertTrue(store.delete(b.id, 1010))
        Assert.assertNull(store.find(b.id, 1010)?.content)
        val c = store.stage(MemoryScope.SESSION, "s", "expire", listOf("e3"), Sensitivity.PERSONAL, 1000, 10)
        Assert.assertEquals(1, store.purgeExpired(1011))
        Assert.assertNull(store.find(c.id, 1011)?.content)
        Assert.assertEquals("EXPIRED", store.find(c.id, 1011)?.state)
    }

    @Test fun appDisablesBackupAndExcludesDatabaseFromExtractionRules() {
        val c = ApplicationProvider.getApplicationContext<Context>()
        Assert.assertEquals(
            0,
            c.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP,
        )
        listOf(com.zhiban.rebuild.R.xml.backup_rules, com.zhiban.rebuild.R.xml.data_extraction_rules).forEach { res ->
            val parser = c.resources.getXml(res)
            var databaseExcluded = false
            while (parser.eventType !=
                org.xmlpull.v1.XmlPullParser.END_DOCUMENT
            ) {
                if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG &&
                    parser.name == "exclude" &&
                    parser.getAttributeValue(null, "domain") == "database"
                ) {
                    databaseExcluded = true
                }
                parser.next()
            }
            Assert.assertTrue(databaseExcluded)
        }
    }

    @Test fun pendingSurvivesReopenWithoutPromotion() = runBlocking<Unit> {
        db.close()
        val c = ApplicationProvider.getApplicationContext<Context>()
        val n = "staged-memory.db"
        c.deleteDatabase(n)
        db =
            Room.databaseBuilder(c, AgentDatabase::class.java, n).allowMainThreadQueries().build()
        store =
            RoomStagedMemoryCandidateStore(db)
        val x = store.stage(MemoryScope.SESSION, "s", "pending", listOf("e"), Sensitivity.PERSONAL, 1000, 60000)
        db.close()
        db =
            Room.databaseBuilder(c, AgentDatabase::class.java, n).allowMainThreadQueries().build()
        store =
            RoomStagedMemoryCandidateStore(db)
        Assert.assertEquals("pending", store.find(x.id, 1001)?.content)
        Assert.assertEquals("PENDING", store.find(x.id, 1001)?.state)
        c.deleteDatabase(n)
    }
}
