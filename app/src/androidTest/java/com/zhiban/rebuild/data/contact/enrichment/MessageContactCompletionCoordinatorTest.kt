package com.zhiban.rebuild.data.contact.enrichment

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.autowrite.ChangeUndoApplier
import com.zhiban.rebuild.data.completion.buildCompletionRepository
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.notification.NotificationCandidateEntity
import com.zhiban.rebuild.data.suggestion.AgentSuggestionRepository
import com.zhiban.rebuild.runtime.governance.ChangeUndoApplierImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageContactCompletionCoordinatorTest {
    private lateinit var context: Context
    private lateinit var database: AgentDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java)
            .addCallback(AgentDatabase.CALLBACK)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = database.close()

    private fun coordinator(vararg fields: ExtractedContactField): MessageContactCompletionCoordinator {
        // TASK 74：构造函数新增 completion + suggestions（主动补全建议卡）；用共享替身组装，
        // 测试消息无 wechatId/WECHAT 身份 → 主动补全闸门（微信可达）不过，不会真的起草。
        val completion = buildCompletionRepository(database)
        return MessageContactCompletionCoordinator(
            database,
            MessageContactFieldExtraction { _, _, _ -> fields.toList() },
            completion,
            AgentSuggestionRepository(
                database,
                completion,
                com.zhiban.rebuild.data.calendar.ScheduleReminderRegistrar { _, _, _ -> },
                testSuggestionNotifier(),
            ),
        )
    }

    private fun linkedCandidate(id: String, body: String) = NotificationCandidateEntity(
        candidateId = id,
        sourceKey = "source-$id",
        packageName = "com.tencent.mm",
        appLabel = "微信",
        title = "周国平",
        body = body,
        postedAtEpochMs = System.currentTimeMillis(),
        platform = "WECHAT",
        conversationTitle = "周国平",
        senderName = "周国平",
        linkedContactId = "contact-1",
    )

    private fun contact(company: String? = null, title: String? = null, phone: String? = null) = ContactEntity(
        contactId = "contact-1",
        displayName = "周国平",
        normalizedName = "周国平",
        phone = phone,
        email = null,
        wechatId = null,
        company = company,
        title = title,
        aliasesJson = "[]",
        tagsJson = "[]",
        note = null,
        avatarUri = null,
        source = "MANUAL",
        deletedAtEpochMs = null,
        createdAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_000L,
    )

    @Test
    fun highConfidenceFieldsAreAutoAppliedWithReversibleReceipt() = runBlocking {
        database.contactDao().insert(contact())
        database.notificationCandidateDao().upsert(
            linkedCandidate(
                "msg-1",
                "我是周国平，平凯星辰（北京）科技有限公司武汉分公司 13476110061",
            ),
        )
        coordinator(
            ExtractedContactField(MessageContactFieldKinds.COMPANY, "平凯星辰（北京）科技有限公司武汉分公司", 0.96),
            ExtractedContactField(MessageContactFieldKinds.PHONE, "13476110061", 0.99),
        ).processOnce()

        val updated = requireNotNull(database.contactDao().findRawById("contact-1"))
        assertEquals("平凯星辰（北京）科技有限公司武汉分公司", updated.company)
        assertEquals("13476110061", updated.phone)

        val receipts = database.changeLogDao().observeAutoWriteReceipts().first()
        assertEquals(1, receipts.size)
        assertEquals("CONTACT_COMPLETION", receipts[0].presentationType)
        assertEquals("AVAILABLE", receipts[0].undoState)
        assertEquals("公司全称：平凯星辰（北京）科技有限公司武汉分公司 · 电话：13476110061", receipts[0].contentPreview)
        assertEquals("周国平", receipts[0].contactName)

        // 幂等:同一条消息不再重复处理/重复落收据。
        coordinator(
            ExtractedContactField(MessageContactFieldKinds.COMPANY, "另一个公司", 0.96),
        ).processOnce()
        assertEquals(1, database.changeLogDao().observeAutoWriteReceipts().first().size)
        assertEquals("平凯星辰（北京）科技有限公司武汉分公司", database.contactDao().findRawById("contact-1")?.company)
    }

    @Test
    fun uncertainFieldsBecomeEnrichmentSuggestionCardsAndExistingValuesAreNeverOverwritten() = runBlocking {
        database.contactDao().insert(contact(company = "已有公司"))
        database.notificationCandidateDao().upsert(
            linkedCandidate("msg-2", "我是周国平，这是我的电话 13476110061"),
        )
        coordinator(
            ExtractedContactField(MessageContactFieldKinds.COMPANY, "新公司", 0.99),
            ExtractedContactField(MessageContactFieldKinds.PHONE, "13476110061", 0.7),
        ).processOnce()

        // 公司已有值:哪怕高置信也不覆盖,且不产生任何写入。
        val unchanged = requireNotNull(database.contactDao().findRawById("contact-1"))
        assertEquals("已有公司", unchanged.company)
        assertNull(unchanged.phone)
        assertTrue(database.changeLogDao().observeAutoWriteReceipts().first().isEmpty())

        // 低置信手机号 → 智能完善候选卡,确认后由既有闭环写入。
        val suggestions = database.contactKnowledgeDao().observePendingEnrichment("contact-1").first()
        assertEquals(1, suggestions.size)
        assertEquals("COMMUNICATION_METHOD", suggestions[0].fieldKind)
        assertTrue(suggestions[0].proposedValueJson.contains("13476110061"))
        assertEquals("微信消息", suggestions[0].sourceRef)
        assertEquals(0.7, suggestions[0].confidence, 0.000_001)
    }

    @Test
    fun casualMessagesWithoutContactInfoSignalSkipTheModelCall() = runBlocking {
        database.contactDao().insert(contact())
        database.notificationCandidateDao().upsert(linkedCandidate("msg-3", "收到，明天见"))
        var extractCalls = 0
        val completion = buildCompletionRepository(database)
        MessageContactCompletionCoordinator(
            database,
            MessageContactFieldExtraction { _, _, _ ->
                extractCalls += 1
                emptyList()
            },
            completion,
            AgentSuggestionRepository(
                database,
                completion,
                com.zhiban.rebuild.data.calendar.ScheduleReminderRegistrar { _, _, _ -> },
                testSuggestionNotifier(),
            ),
        ).processOnce()

        assertEquals(0, extractCalls)
    }

    private fun testSuggestionNotifier() = com.zhiban.rebuild.data.suggestion.AgentSuggestionNotifier(
        context,
        com.zhiban.rebuild.data.config.AgentControlStore(context, "suggestion_notification_test_${System.nanoTime()}"),
    )

    @Test
    fun undoRestoresPreviousFieldValues() = runBlocking {
        database.contactDao().insert(contact(title = "销售"))
        database.notificationCandidateDao().upsert(
            linkedCandidate("msg-4", "我是周国平，平凯星辰（北京）科技有限公司武汉分公司 13476110061"),
        )
        coordinator(
            ExtractedContactField(MessageContactFieldKinds.COMPANY, "平凯星辰（北京）科技有限公司武汉分公司", 0.96),
            ExtractedContactField(MessageContactFieldKinds.PHONE, "13476110061", 0.99),
        ).processOnce()

        val receipt = database.changeLogDao().observeAutoWriteReceipts().first().single()
        val undoApplier: ChangeUndoApplier = ChangeUndoApplierImpl(database)
        assertTrue(undoApplier.undoVisible(receipt.changeId, System.currentTimeMillis()))

        val restored = requireNotNull(database.contactDao().findRawById("contact-1"))
        assertNull(restored.company)
        assertEquals("销售", restored.title)
        assertNull(restored.phone)
    }

    @Test
    fun autoWriteIsSkippedForUnlinkedOrGroupCandidates() = runBlocking {
        database.contactDao().insert(contact())
        database.notificationCandidateDao().upsert(
            linkedCandidate("msg-5", "我是周国平 13476110061").copy(linkedContactId = null),
        )
        coordinator(
            ExtractedContactField(MessageContactFieldKinds.PHONE, "13476110061", 0.99),
        ).processOnce()

        assertNull(database.contactDao().findRawById("contact-1")?.phone)
        assertTrue(database.changeLogDao().observeAutoWriteReceipts().first().isEmpty())
        assertTrue(database.contactKnowledgeDao().observePendingEnrichment("contact-1").first().isEmpty())
    }
}
