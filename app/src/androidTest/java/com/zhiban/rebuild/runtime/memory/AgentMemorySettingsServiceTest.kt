package com.zhiban.rebuild.runtime.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentMemorySettingsServiceTest {
    private lateinit var database: AgentDatabase
    private lateinit var service: AgentMemorySettingsService

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AgentDatabase::class.java,
        ).allowMainThreadQueries().build()
        service = AgentMemorySettingsService(database)
    }

    @After
    fun close() = database.close()

    @Test
    fun changingOnlyCategoryKeepsTheMemory() = runBlocking {
        service.add("我不吃香菜", "PREFERENCE")
        val original = service.list().single()

        service.update(original, original.text, "PROFILE")

        val updated = service.list().single()
        assertEquals("我不吃香菜", updated.text)
        assertEquals("PROFILE", updated.type)
        assertEquals("关于我", updated.categoryLabel)
    }
}
