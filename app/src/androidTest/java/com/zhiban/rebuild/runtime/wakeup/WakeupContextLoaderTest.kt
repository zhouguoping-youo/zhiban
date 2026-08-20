package com.zhiban.rebuild.runtime.wakeup

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.agent.ScheduleEntity
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.RelationshipEdgeEntity
import com.zhiban.rebuild.data.facts.FactEntity
import com.zhiban.rebuild.data.notification.NotificationCandidateEntity
import com.zhiban.rebuild.data.notification.NotificationInsights
import com.zhiban.rebuild.data.notification.ScheduleInsight
import com.zhiban.rebuild.data.suggestion.AgentSuggestionEntity
import com.zhiban.rebuild.data.suggestion.AgentSuggestionStatus
import com.zhiban.rebuild.data.suggestion.AgentSuggestionType
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WakeupContextLoaderTest {
    private lateinit var database: AgentDatabase

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AgentDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = database.close()

    @Test fun loadsBoundedEvidenceSchedulesConflictsAndPendingSuggestions() = runBlocking {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.of(2026, 8, 20, 9, 0).atZone(zone).toInstant().toEpochMilli()
        val meetingAt = LocalDateTime.of(2026, 8, 20, 14, 0).atZone(zone).toInstant().toEpochMilli()
        database.contactDao().insert(contact("contact-1", "李雷"))
        database.contactDao().insert(contact("contact-2", "王敏"))
        database.factDao().upsert(
            FactEntity(
                "fact-1", "INTERACTION_SUMMARY", "微信互动 · 对方发来", null, "OBSERVED_NOTIFICATION", "candidate-old",
                "contact-1", null, 1.0, "PERSONAL", "ACTIVE", 90, Long.MAX_VALUE, now - 1_000L, now - 1_000L,
            ),
        )
        database.relationshipEdgeDao().upsert(
            RelationshipEdgeEntity(
                "edge-1", "contact-1", "contact-2", "COWORKER", "digest", "[]", 0.9, true, null, "ACTIVE", now, now,
            ),
        )
        database.scheduleDao().insert(
            ScheduleEntity("schedule-1", "客户会议", meetingAt, 60, null, null, createdAtEpochMs = now, updatedAtEpochMs = now),
        )
        database.agentSuggestionDao().insert(
            AgentSuggestionEntity(
                "suggestion-1", AgentSuggestionType.WAKEUP_GENERAL, "确认客户资料", "请核对", "contact-1", "candidate-old",
                "NOTIFICATION", "wakeup-old", AgentSuggestionStatus.PENDING, now, now,
            ),
        )
        val candidate = NotificationCandidateEntity(
            "candidate-new", "source-new", "com.tencent.mm", "微信", "李雷", "明天下午开会", now,
            insightJson = NotificationInsights(ScheduleInsight("会议", meetingAt, 30, confidence = 0.95)).toJsonOrNull(),
            linkedContactId = "contact-1",
        )

        val context = WakeupContextLoader(database).load(candidate, "contact-1", now)

        assertEquals("微信互动 · 对方发来", context.interactions.single().text)
        assertEquals("王敏", context.relationships.single().counterpart)
        assertEquals("客户会议", context.todaySchedules.single().text)
        assertEquals(1, context.conflictCount)
        assertEquals(listOf("确认客户资料"), context.pendingSuggestions)
        assertTrue(buildWakeupContextPrompt(context, zone).contains("不要重复生成相同建议"))
    }

    private fun contact(id: String, name: String) = ContactEntity(
        id, name, name, null, null, null, null, null, "[]", "[]", null, null, "USER", null, 1L, 1L,
    )
}
