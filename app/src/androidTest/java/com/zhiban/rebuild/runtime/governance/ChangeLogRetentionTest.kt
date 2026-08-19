package com.zhiban.rebuild.runtime.governance

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.autowrite.ChangeLogEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChangeLogRetentionTest {
    private lateinit var database: AgentDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun oldUndoPayloadExpiresAndOnlyTerminalHistoryIsDeleted() = runBlocking {
        val dao = database.changeLogDao()
        dao.insert(change("available-old", "AVAILABLE", 10))
        dao.insert(change("available-recent", "AVAILABLE", 100))
        dao.insert(change("undone-old", "UNDONE", 10))
        dao.insert(change("unavailable-old", "UNAVAILABLE", 10))

        assertEquals(1, dao.expireUndoBefore(cutoffEpochMs = 50, limit = 256))
        val expired = dao.find("available-old")
        assertEquals("EXPIRED", expired?.undoState)
        assertEquals("{}", expired?.inversePayloadJson)
        assertEquals("AVAILABLE", dao.find("available-recent")?.undoState)

        assertEquals(3, dao.deleteTerminalBefore(cutoffEpochMs = 50, limit = 256))
        assertNull(dao.find("available-old"))
        assertNull(dao.find("undone-old"))
        assertNull(dao.find("unavailable-old"))
        assertNotNull(dao.find("available-recent"))
    }

    private fun change(id: String, state: String, createdAt: Long) = ChangeLogEntity(
        changeId = id,
        runtimeRunId = "run-$id",
        toolName = "test.tool",
        idempotencyKey = "key-$id",
        targetDomain = "TEST",
        targetId = "target-$id",
        operation = "UPDATE",
        beforeDigest = null,
        afterDigest = "digest",
        inversePayloadJson = "{\"restore\":true}",
        undoState = state,
        createdAtEpochMs = createdAt,
        undoneAtEpochMs = if (state == "UNDONE") createdAt + 1 else null,
    )
}
