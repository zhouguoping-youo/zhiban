package com.zhiban.rebuild.data.export

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.agent.MemoryEntity
import com.zhiban.rebuild.data.agent.ScheduleEntity
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.crm.CrmLeadEntity
import com.zhiban.rebuild.data.store.RuntimeConversationTurnEntity
import com.zhiban.rebuild.data.store.RuntimeRunEntity
import com.zhiban.rebuild.data.store.RuntimeSessionEntity
import com.zhiban.rebuild.provider.SecretRedactor
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentDataExportServiceTest {
    private lateinit var context: Context
    private lateinit var database: AgentDatabase

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java)
            .addCallback(AgentDatabase.CALLBACK).allowMainThreadQueries().build()
    }

    @After fun tearDown() = database.close()

    @Test fun exportContainsEveryDomainSection() = runBlocking {
        database.scheduleDao().insert(schedule("sched-1"))
        database.memoryDao().insert(memory("mem-1"))
        database.contactDao().insert(contact("contact-1"))
        database.crmDao().insertLead(lead("lead-1"))

        val exported = AgentDataExportService(context, database, SecretRedactor()).create(100).readText()

        listOf("\"conversations\"", "\"memories\"", "\"contacts\"", "\"schedules\"", "\"crm\"")
            .forEach { section -> assertTrue("missing $section", exported.contains(section)) }
    }

    @Test fun exportRedactsPhoneEmailAndNeverContainsSecrets() = runBlocking {
        database.contactDao().insert(
            contact(
                "contact-1",
                phone = "13812345678",
                email = "person@example.com",
                wechatId = "wx_private_123",
            ),
        )
        database.scheduleDao().insert(schedule("sched-1", title = "和张三 13998765432 谈合作"))
        database.runtimeSessionDao().insert(RuntimeSessionEntity("session-1", updatedAtEpochMs = 10))
        database.runtimeRunDao().insert(run("run-1", "session-1"))
        database.runtimeConversationTurnDao().insert(turn("turn-1", "session-1", "run-1", "钥匙 sk-CANARY-12345678"))

        val exported = AgentDataExportService(context, database, SecretRedactor()).create(100).readText()

        assertTrue(exported.contains("REDACTED_NO_CREDENTIALS"))
        listOf("13812345678", "13998765432", "person@example.com", "wx_private_123", "sk-CANARY")
            .forEach { forbidden -> assertFalse("leaked $forbidden", exported.contains(forbidden)) }
        assertTrue(exported.contains("[REDACTED_CONTACT_ID]"))
    }

    @Test fun exportPreservesLongConversationAfterRedactingDirectIdentifiers() = runBlocking {
        val tail = "TAIL_MUST_SURVIVE"
        val content = "电话 13812345678 " + "长对话正文".repeat(180) + tail
        database.runtimeSessionDao().insert(RuntimeSessionEntity("session-long", updatedAtEpochMs = 10))
        database.runtimeRunDao().insert(run("run-long", "session-long"))
        database.runtimeConversationTurnDao().insert(turn("turn-long", "session-long", "run-long", content))

        val exported = AgentDataExportService(context, database, SecretRedactor()).create(102).readText()

        assertFalse(exported.contains("13812345678"))
        assertTrue(exported.contains("[REDACTED_PHONE]"))
        assertTrue(exported.contains(tail))
    }

    @Test fun exportIncludesConversationAndCrmContent() = runBlocking {
        database.runtimeSessionDao().insert(RuntimeSessionEntity("session-1", updatedAtEpochMs = 10))
        database.runtimeRunDao().insert(run("run-1", "session-1"))
        database.runtimeConversationTurnDao().insert(turn("turn-1", "session-1", "run-1", "今天想整理一下客户"))
        database.crmDao().insertLead(lead("lead-1", name = "王总"))

        val exported = AgentDataExportService(context, database, SecretRedactor()).create(100).readText()

        assertTrue(exported.contains("今天想整理一下客户"))
        assertTrue(exported.contains("王总"))
    }

    @Test fun exportStreamsEveryPageWithoutDroppingRows() = runBlocking {
        database.memoryDao().insert(memory("mem-1"))
        database.memoryDao().insert(memory("mem-2"))
        database.contactDao().insert(contact("contact-1", name = "甲"))
        database.contactDao().insert(contact("contact-2", name = "乙"))
        database.scheduleDao().insert(schedule("sched-1", title = "日程甲"))
        database.scheduleDao().insert(schedule("sched-2", title = "日程乙"))
        database.crmDao().insertLead(lead("lead-1", name = "线索甲"))
        database.crmDao().insertLead(lead("lead-2", name = "线索乙"))

        val exported = AgentDataExportService(context, database, SecretRedactor(), pageSize = 1).create(101)
        val json = JSONObject(exported.readText())

        assertEquals(2, json.getJSONArray("memories").length())
        assertEquals(2, json.getJSONArray("contacts").length())
        assertEquals(2, json.getJSONArray("schedules").length())
        assertEquals(2, json.getJSONObject("crm").getJSONArray("leads").length())
    }

    private fun schedule(id: String, title: String = "周会") = ScheduleEntity(
        id = id,
        title = title,
        startAtEpochMs = 1_700_000_000_000,
        durationMinutes = 30,
        note = null,
        createdByRunId = null,
        createdAtEpochMs = 10,
        updatedAtEpochMs = 10,
    )

    private fun memory(id: String) = MemoryEntity(
        id = id,
        kind = "USER_PREFERENCE",
        content = "喜欢简短回复",
        sourceRunId = null,
        createdAtEpochMs = 10,
    )

    private fun contact(id: String, phone: String? = null, email: String? = null, wechatId: String? = null, name: String = "张三") = ContactEntity(
        contactId = id,
        displayName = name,
        normalizedName = name,
        phone = phone,
        email = email,
        wechatId = wechatId,
        company = "知伴科技",
        title = "工程师",
        aliasesJson = "[]",
        tagsJson = "[]",
        note = null,
        avatarUri = null,
        source = "USER",
        deletedAtEpochMs = null,
        createdAtEpochMs = 10,
        updatedAtEpochMs = 10,
    )

    private fun lead(id: String, name: String = "张三") = CrmLeadEntity(
        leadId = id,
        contactId = null,
        displayNameSnapshot = name,
        companyNameSnapshot = null,
        status = "NEW",
        sourceType = "USER",
        sourceRef = null,
        fitSummary = null,
        confidence = 0.9,
        userConfirmed = true,
        createdAtEpochMs = 10,
        updatedAtEpochMs = 10,
    )

    private fun run(id: String, sessionId: String) = RuntimeRunEntity(
        id,
        sessionId,
        1,
        "SUCCEEDED",
        budgetJson = "{}",
        createdAtEpochMs = 10,
        updatedAtEpochMs = 30,
    )

    private fun turn(id: String, sessionId: String, runId: String, content: String) = RuntimeConversationTurnEntity(
        turnId = id,
        sessionId = sessionId,
        runId = runId,
        role = "user",
        content = content,
        contentDigest = "digest-$id",
        tokenEstimate = 4,
        createdAtEpochMs = 10,
    )
}
