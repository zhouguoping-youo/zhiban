package com.zhiban.rebuild.runtime.context

import com.zhiban.rebuild.data.facts.EmbeddingVectorEntity
import com.zhiban.rebuild.data.facts.FactEntity
import com.zhiban.rebuild.data.facts.FactIndex

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FactLifecycleIntegrationTest {
    private lateinit var database: AgentDatabase

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AgentDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() = database.close()

    @Test fun expiredFactDeletesCanonicalFtsAndEmbeddingProjection() = runBlocking {
        val index = FactIndex(database)
        index.upsert(
            FactEntity(
                factId = "fact:expired", factType = "TEST", textContent = "过期事实",
                structuredDataJson = null, sourceType = "TEST", sourceRef = null,
                contactId = null, skillId = null, confidence = 1.0, sensitivity = "LOW",
                status = "ACTIVE", ttlDays = 1, expiresAtEpochMs = 100,
                createdAtEpochMs = 1, updatedAtEpochMs = 1,
            ),
        )
        database.embeddingVectorDao().upsert(
            EmbeddingVectorEntity(
                embeddingId = "embedding:expired",
                factId = "fact:expired",
                providerId = "fake",
                modelId = "fake-model",
                dimensions = 2,
                vectorBlob = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0),
                generatedAtEpochMs = 2,
                modelVersion = null,
            ),
        )

        assertEquals(1, index.deleteExpired(now = 101))
        assertTrue(index.search("过期事实", now = 101, limit = 10).isEmpty())
        assertEquals(
            0,
            database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM embedding_vectors").use {
                it.moveToFirst()
                it.getInt(0)
            },
        )
        assertEquals(
            0,
            database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM facts").use {
                it.moveToFirst()
                it.getInt(0)
            },
        )
    }

    @Test fun inconsistentFtsProjectionCanBeRebuiltFromCanonicalFacts() = runBlocking {
        val index = FactIndex(database)
        index.upsert(
            FactEntity(
                factId = "fact:repair", factType = "TEST", textContent = "客户交付计划",
                structuredDataJson = null, sourceType = "TEST", sourceRef = null,
                contactId = null, skillId = null, confidence = 1.0, sensitivity = "LOW",
                status = "ACTIVE", ttlDays = 0, expiresAtEpochMs = null,
                createdAtEpochMs = 1, updatedAtEpochMs = 1,
            ),
        )
        database.openHelper.writableDatabase.execSQL("DELETE FROM fact_fts")
        assertTrue(index.search("客户交付计划", now = 2, limit = 10).isEmpty())

        assertTrue(index.repairIfInconsistent())

        assertEquals(listOf("fact:repair"), index.search("客户交付计划", now = 2, limit = 10).map { it.factId })
        assertTrue(!index.repairIfInconsistent())
    }
}
