package com.zhiban.rebuild.runtime.memory

import com.zhiban.rebuild.data.memory.MemoryCurrentVersionEntity

import com.zhiban.rebuild.data.memory.MemoryEvidenceEntity
import com.zhiban.rebuild.data.memory.MemoryNamespaceEntity
import com.zhiban.rebuild.data.memory.MemoryRecordEntity

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.facts.FactEntity
import com.zhiban.rebuild.data.facts.EmbeddingVectorEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression for the foreign-key enforcement gap: the production database ran with
 * `PRAGMA foreign_keys` OFF, so the declared `ON DELETE CASCADE` on `memory_records` / `facts`
 * never fired. Deleting a memory left an orphan `memory_current_versions` row that made
 * `dao.current()` keep returning a stale entry, blocking any re-commit of the same
 * logicalMemoryId (CURRENT_MEMORY_EXISTS / a failed supersede on re-upsert), and
 * `embedding_vectors` leaked after their fact was removed. The open callback now enables FK.
 */
@RunWith(AndroidJUnit4::class)
class MemoryForeignKeyCascadeTest {
    private lateinit var database: AgentDatabase

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AgentDatabase::class.java,
        ).addCallback(AgentDatabase.CALLBACK).allowMainThreadQueries().build()
    }

    @After fun tearDown() = database.close()

    @Test fun foreignKeysAreEnforcedWhenOpenedThroughTheCallback() {
        database.openHelper.writableDatabase.query("PRAGMA foreign_keys").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
    }

    @Test fun deleteLeavesNoOrphanCurrentVersionThatWouldBlockRecommit() = runBlocking {
        val store = MemoryAtomicStore(database) { 1_000L }
        store.ensureNamespace(namespace())
        store.upsertReversible(upsert())
        assertNotNull(database.memoryPersistenceDao().current(NS, LOGICAL))

        store.delete(NS, LOGICAL, "delete-digest")

        // FK cascade removed the current_version row with its record, so this logical id is free again.
        assertNull(database.memoryPersistenceDao().current(NS, LOGICAL))
    }

    @Test fun deletingMemoryRecordCascadesCurrentVersionAndEvidence() = runBlocking {
        val dao = database.memoryPersistenceDao()
        dao.insertNamespace(namespace())
        dao.insertRecord(record(version = 1))
        dao.insertCurrent(MemoryCurrentVersionEntity(NS, LOGICAL, 1, MEMORY_ID, 0))
        dao.insertEvidence(listOf(evidence(version = 1)))
        assertEquals(1, countRows("memory_evidence"))

        assertEquals(1, dao.deleteRecord(NS, MEMORY_ID, 1))

        assertNull(dao.current(NS, LOGICAL))
        assertEquals(0, countRows("memory_current_versions"))
        assertEquals(0, countRows("memory_evidence"))
    }

    @Test fun deletingFactCascadesEmbeddingVectors() = runBlocking {
        database.factDao().upsert(fact())
        database.embeddingVectorDao().upsert(vector())
        assertEquals(1, countRows("embedding_vectors"))

        assertEquals(1, database.factDao().delete(FACT_ID))

        assertEquals(0, countRows("embedding_vectors"))
    }

    private fun countRows(table: String): Int =
        database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun namespace() = MemoryNamespaceEntity(NS, "owner", "profile", "SCOPE", "scope-id", "ACTIVE", 0, 0, 0)

    private fun upsert() = MemoryUpsertRequest(NS, LOGICAL, "FACT", "user", "likes", "喜欢喝咖啡", "PERSONAL", 0.99, "user-chat")

    private fun record(version: Long) = MemoryRecordEntity(
        NS, MEMORY_ID, version, LOGICAL, "FACT", "user", "likes", "喜欢喝咖啡", "喜欢喝咖啡",
        "digest-$version", "PERSONAL", 0.99, 1.0, "ACTIVE", null, null, 1_000L, 1_000L, null,
        1_000L, null, 1, "source-digest",
    )

    private fun evidence(version: Long) = MemoryEvidenceEntity(
        NS, MEMORY_ID, version, "evidence-1", "SOURCE", "user-chat", "src-digest", 1_000L,
        "excerpt-digest", "USER", "PERSONAL",
    )

    private fun fact() = FactEntity(
        FACT_ID, "AGENT_MEMORY", "喜欢喝咖啡", null, "USER_CONFIRMED_MEMORY", NS, null, null,
        1.0, "PERSONAL", "ACTIVE", 0, null, 1_000L, 1_000L,
    )

    private fun vector() = EmbeddingVectorEntity(
        "embedding-1", FACT_ID, "ark", "embed-model", 3, byteArrayOf(1, 2, 3), 1_000L, null,
    )

    private companion object {
        const val NS = "namespace-1"
        const val LOGICAL = "logical-1"
        const val MEMORY_ID = "memory-1"
        const val FACT_ID = "fact-1"
    }
}
