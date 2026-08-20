package com.zhiban.rebuild.data.interaction

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.config.AgentControlStore
import com.zhiban.rebuild.data.config.SilenceContactThresholds
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.RelationshipEdgeEntity
import com.zhiban.rebuild.data.contact.RelationshipPersonIds
import com.zhiban.rebuild.data.suggestion.AgentSuggestionNotifier
import com.zhiban.rebuild.data.suggestion.AgentSuggestionStatus
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
class SilentContactSuggestionScannerTest {
    private lateinit var database: AgentDatabase
    private lateinit var controls: AgentControlStore
    private lateinit var scanner: SilentContactSuggestionScanner

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        controls = AgentControlStore(context, "silence_scanner_${System.nanoTime()}")
        val notifier = AgentSuggestionNotifier(context, controls)
        scanner = SilentContactSuggestionScanner(database, controls, notifier)
    }

    @After fun close() = database.close()

    @Test fun scansRelationshipThresholdsIntoOneDailyAggregate() = runTest {
        val now = 100L * DAY_MS
        insertContact("family", "家人")
        insertContact("customer", "客户")
        insertContact("general", "朋友")
        insertContact("recent", "刚联系")
        insertInteraction("family", now - 14L * DAY_MS)
        insertInteraction("customer", now - 30L * DAY_MS)
        insertInteraction("general", now - 60L * DAY_MS)
        insertInteraction("recent", now - 5L * DAY_MS)
        insertRelationship("family", "PARENT")
        insertRelationship("customer", "CUSTOMER")

        assertTrue(scanner.scan(now))
        assertFalse(scanner.scan(now + 1_000))

        val suggestion = database.agentSuggestionDao().observeRecent().first().single()
        assertEquals(AgentSuggestionType.SILENT_CONTACTS, suggestion.type)
        assertEquals(AgentSuggestionStatus.PENDING, suggestion.status)
        assertEquals("MAINTENANCE_SILENCE_SCAN", suggestion.sourceEvent)
        assertTrue(suggestion.body.contains("家人"))
        assertTrue(suggestion.body.contains("客户"))
        assertTrue(suggestion.body.contains("朋友"))
        assertFalse(suggestion.body.contains("刚联系"))
    }

    @Test fun configurableThresholdsAreApplied() = runTest {
        val now = 20L * DAY_MS
        controls.saveSilenceContactThresholds(SilenceContactThresholds(5, 5, 5))
        insertContact("contact", "小王")
        insertInteraction("contact", now - 6L * DAY_MS)

        assertTrue(scanner.scan(now))
        assertTrue(database.agentSuggestionDao().observeRecent().first().single().body.contains("小王"))
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

    private suspend fun insertInteraction(contactId: String, occurredAt: Long) {
        database.contactInteractionDao().insertIgnore(
            ContactInteractionEntity(
                interactionId = "interaction:$contactId",
                contactId = contactId,
                occurredAtEpochMs = occurredAt,
                channel = "TEST",
                direction = InteractionDirection.UNKNOWN,
                sourceType = InteractionSourceType.FACT,
                sourceId = "source:$contactId",
                createdAtEpochMs = occurredAt,
            ),
        )
    }

    private suspend fun insertRelationship(contactId: String, relationType: String) {
        database.relationshipEdgeDao().upsert(
            RelationshipEdgeEntity(
                edgeId = "edge:$contactId",
                fromContactId = RelationshipPersonIds.SELF,
                toContactId = contactId,
                relationType = relationType,
                evidenceDigest = "test",
                evidenceRefsJson = "[]",
                confidence = 1.0,
                userConfirmed = true,
                skillId = null,
                status = "ACTIVE",
                createdAtEpochMs = 1,
                updatedAtEpochMs = 1,
            ),
        )
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1_000
    }
}
