package com.zhiban.rebuild.data.interaction

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.config.AgentControlStore
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.suggestion.AgentSuggestionNotifier
import com.zhiban.rebuild.data.suggestion.AgentSuggestionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UnobservedReplySuggestionScannerTest {
    private lateinit var database: AgentDatabase
    private lateinit var controls: AgentControlStore
    private lateinit var scanner: UnobservedReplySuggestionScanner

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        controls = AgentControlStore(context, "unobserved_reply_${System.nanoTime()}")
        scanner = UnobservedReplySuggestionScanner(
            database,
            controls,
            AgentSuggestionNotifier(context, controls),
        )
    }

    @After fun close() = database.close()

    @Test fun oldOutgoingWithoutObservedIncomingCreatesOneSuggestion() = runTest {
        val now = 10L * DAY_MS
        insertContact("pending", "张三")
        insertInteraction("pending", "out-1", now - 4L * DAY_MS, InteractionDirection.OUTGOING)

        assertEquals(1, scanner.scan(now))
        assertEquals(0, scanner.scan(now))

        val suggestion = database.agentSuggestionDao().observeRecent().first().single()
        assertEquals(AgentSuggestionType.UNOBSERVED_REPLY, suggestion.type)
        assertEquals("pending", suggestion.contactId)
        assertTrue(suggestion.body.contains("未观察到回复"))
        assertFalse(suggestion.body.contains("没有回复"))
    }

    @Test fun laterIncomingAndRecentOutgoingDoNotCreateSuggestions() = runTest {
        val now = 10L * DAY_MS
        insertContact("answered", "李四")
        insertContact("recent", "王五")
        insertInteraction("answered", "out-answered", now - 5L * DAY_MS, InteractionDirection.OUTGOING)
        insertInteraction("answered", "in-answered", now - 4L * DAY_MS, InteractionDirection.INCOMING)
        insertInteraction("recent", "out-recent", now - DAY_MS, InteractionDirection.OUTGOING)

        assertEquals(0, scanner.scan(now))
    }

    @Test fun configurableDelayAndContactOptOutAreRespected() = runTest {
        val now = 10L * DAY_MS
        controls.saveUnobservedReplyDays(1)
        controls.setReplyOptOut("opted-out", true)
        insertContact("eligible", "赵六")
        insertContact("opted-out", "钱七")
        insertInteraction("eligible", "out-eligible", now - 2L * DAY_MS, InteractionDirection.OUTGOING)
        insertInteraction("opted-out", "out-opted-out", now - 2L * DAY_MS, InteractionDirection.OUTGOING)

        assertEquals(1, scanner.scan(now))
        assertEquals("eligible", database.agentSuggestionDao().observeRecent().first().single().contactId)
    }

    private suspend fun insertContact(id: String, name: String) {
        database.contactDao().insert(
            ContactEntity(
                contactId = id,
                displayName = name,
                normalizedName = name,
                phone = null,
                email = null,
                wechatId = null,
                company = null,
                title = null,
                aliasesJson = "[]",
                tagsJson = "[]",
                note = null,
                avatarUri = null,
                source = "TEST",
                deletedAtEpochMs = null,
                createdAtEpochMs = 1,
                updatedAtEpochMs = 1,
            ),
        )
    }

    private suspend fun insertInteraction(contactId: String, sourceId: String, occurredAt: Long, direction: String) {
        database.contactInteractionDao().insertIgnore(
            ContactInteractionEntity(
                interactionId = "interaction:$sourceId",
                contactId = contactId,
                occurredAtEpochMs = occurredAt,
                channel = "WECHAT",
                direction = direction,
                sourceType = InteractionSourceType.NOTIFICATION,
                sourceId = sourceId,
                createdAtEpochMs = occurredAt,
            ),
        )
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1_000
    }
}
