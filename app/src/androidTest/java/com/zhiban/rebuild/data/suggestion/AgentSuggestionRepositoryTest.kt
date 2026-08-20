package com.zhiban.rebuild.data.suggestion

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.calendar.ScheduleReminderRegistrar
import com.zhiban.rebuild.data.completion.CompletionHandoff
import com.zhiban.rebuild.data.completion.ContactCompletionRepository
import com.zhiban.rebuild.data.completion.ContactCompletionRequestEntity
import com.zhiban.rebuild.data.completion.ContactCompletionStatus
import com.zhiban.rebuild.data.completion.FakeOutreachGenerator
import com.zhiban.rebuild.data.config.AgentControlStore
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.ContactImportantDateEntity
import com.zhiban.rebuild.data.contact.ContactProfileField
import com.zhiban.rebuild.data.event.EventPlanStatus
import java.time.LocalDateTime
import java.time.ZoneId
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

/**
 * 建议中心数据面回归：insert 幂等、状态迁移乐观锁、SCHEDULE 执行链路、
 * confirm 失败不回写 planId（计划留 DRAFT）、CONTACT_COMPLETION 一键转发链路
 * （handoff 成功才 ACCEPTED、失败保持 PENDING；dismiss 联动撤掉 DRAFTED 请求）。
 *
 * 模式对齐 [com.zhiban.rebuild.data.event.EventPlanningRepositoryTest]：真实内存 Room 库。
 * androidTest 无 mockk（仅在 testImplementation），补全仓库的协作对象用真实/fake 组合：
 * AgentControlStore（隔离 prefs 名）+ 共享替身（FakeOutreachGenerator 等，见
 * com.zhiban.rebuild.data.completion.CompletionTestDoubles，不真正调 LLM）。
 */
@RunWith(AndroidJUnit4::class)
class AgentSuggestionRepositoryTest {
    private lateinit var database: AgentDatabase
    private lateinit var repository: AgentSuggestionRepository
    private var handoffSucceeds = true
    private val registeredReminders = mutableListOf<Triple<String, Long, Int?>>()

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AgentDatabase::class.java)
            .allowMainThreadQueries().build()
        val controls = AgentControlStore(ApplicationProvider.getApplicationContext(), "agent_controls_test_${System.currentTimeMillis()}")
        val completion = ContactCompletionRepository(
            database,
            CompletionHandoff { _, _, _ -> handoffSucceeds },
            FakeOutreachGenerator(),
            controls,
        )
        registeredReminders.clear()
        repository = AgentSuggestionRepository(
            database,
            completion,
            ScheduleReminderRegistrar { scheduleId, startAtEpochMs, reminderMinutesBefore ->
                registeredReminders += Triple(scheduleId, startAtEpochMs, reminderMinutesBefore)
            },
            AgentSuggestionNotifier(ApplicationProvider.getApplicationContext(), controls),
        )
    }

    @After fun tearDown() = database.close()

    // ---- insert 幂等 ----

    @Test fun insertIgnoresDuplicateDedupeKey() = runBlocking {
        val dao = database.agentSuggestionDao()
        assertTrue(repository.insert(suggestion("s-1", dedupeKey = "wakeup-dup")))
        // 同一 dedupeKey（unique 索引）再次插入 → IGNORE → 返回 false，不得整行覆盖。
        assertFalse(repository.insert(suggestion("s-2", dedupeKey = "wakeup-dup", title = "被覆盖的标题")))
        val all = dao.observeRecent(100).first()
        assertEquals(1, all.size)
        assertEquals("s-1", all.single().suggestionId)
        assertEquals("明天拜访九州通", all.single().title)
    }

    // ---- 状态迁移乐观锁 ----

    @Test fun acceptMovesPendingToAcceptedExactlyOnce() = runBlocking {
        repository.insert(suggestion("s-1"))
        assertTrue(repository.accept("s-1", nowEpochMs = 2L))
        assertEquals(AgentSuggestionStatus.ACCEPTED, database.agentSuggestionDao().find("s-1")?.status)
        // 乐观锁：已 ACCEPTED 的建议再次 accept 不得重复流转。
        assertFalse(repository.accept("s-1", nowEpochMs = 3L))
        assertEquals(AgentSuggestionStatus.ACCEPTED, database.agentSuggestionDao().find("s-1")?.status)
    }

    @Test fun dismissMovesPendingToDismissedExactlyOnce() = runBlocking {
        repository.insert(suggestion("s-1"))
        assertTrue(repository.dismiss("s-1", nowEpochMs = 2L))
        assertEquals(AgentSuggestionStatus.DISMISSED, database.agentSuggestionDao().find("s-1")?.status)
        // 已 DISMISSED：dismiss / accept 都必须返回 false。
        assertFalse(repository.dismiss("s-1", nowEpochMs = 3L))
        assertFalse(repository.accept("s-1", nowEpochMs = 3L))
        assertEquals(AgentSuggestionStatus.DISMISSED, database.agentSuggestionDao().find("s-1")?.status)
    }

    @Test fun acceptReturnsFalseForMissingSuggestion() = runBlocking {
        assertFalse(repository.accept("ghost", nowEpochMs = 2L))
        assertFalse(repository.dismiss("ghost", nowEpochMs = 2L))
    }

    @Test fun pendingCountOnlyIncludesPendingSuggestions() = runBlocking {
        repository.insert(suggestion("s-1"))
        repository.insert(suggestion("s-2"))
        repository.accept("s-1", nowEpochMs = 2L)
        assertEquals(1, database.agentSuggestionDao().observePendingCount().first())
    }

    @Test fun repeatedDismissalsDownrankNextSuggestionForSameTypeAndContact() = runBlocking {
        repeat(3) { index ->
            val id = "dismissed-$index"
            repository.insert(suggestion(id, contactId = "contact-1", createdAt = 1_000L + index))
            assertTrue(repository.dismiss(id, nowEpochMs = 2_000L + index))
        }

        repository.insert(suggestion("next", contactId = "contact-1", createdAt = 3_000L))

        assertEquals(30, database.agentSuggestionDao().find("next")?.priorityScore)
        assertEquals(
            AgentSuggestionFeedbackStats(0, 3),
            database.agentSuggestionDao().feedbackStats(AgentSuggestionType.WAKEUP_SCHEDULE, "contact-1", 0L),
        )
    }

    @Test fun importantDateWithinSevenDaysCreatesOneAnnualReminder() = runBlocking {
        database.contactDao().insert(contact())
        database.contactKnowledgeDao().upsertImportantDate(
            ContactImportantDateEntity(
                dateId = "birthday-1",
                contactId = "contact-1",
                kind = "BIRTHDAY",
                year = 1990,
                month = 8,
                day = 25,
                source = "USER",
                evidenceRef = null,
                userConfirmed = true,
                createdAtEpochMs = 1L,
                updatedAtEpochMs = 1L,
            ),
        )
        val zone = ZoneId.of("Asia/Shanghai")
        val now = LocalDateTime.of(2026, 8, 20, 9, 0).atZone(zone).toInstant().toEpochMilli()
        val scanner = ImportantDateSuggestionScanner(database, repository)

        assertEquals(1, scanner.scan(now, zone))
        assertEquals(0, scanner.scan(now, zone))
        val reminder = database.agentSuggestionDao().find("important-date:birthday-1:2026")
        assertEquals(AgentSuggestionType.IMPORTANT_DATE_REMINDER, reminder?.type)
        assertEquals("李雷的生日还有5天", reminder?.title)
        assertEquals(65, reminder?.priorityScore)
    }

    @Test fun pendingSuggestionsExpireWithScheduleAwareLifecycle() = runBlocking {
        val now = 10L * 24 * 60 * 60 * 1_000
        val old = now - 8L * 24 * 60 * 60 * 1_000
        database.agentSuggestionDao().insert(suggestion("general-old").copy(createdAtEpochMs = old, updatedAtEpochMs = old))
        database.agentSuggestionDao().insert(
            suggestion("schedule-future").copy(
                createdAtEpochMs = old,
                updatedAtEpochMs = old,
                execActionType = "SCHEDULE",
                startAtEpochMs = now + 60 * 60_000L,
            ),
        )
        database.agentSuggestionDao().insert(
            suggestion("schedule-past").copy(
                execActionType = "SCHEDULE",
                startAtEpochMs = now - 1L,
            ),
        )

        assertEquals(2, database.agentSuggestionDao().expirePending(now - 7L * 24 * 60 * 60 * 1_000, now))
        assertEquals(AgentSuggestionStatus.DISMISSED, database.agentSuggestionDao().find("general-old")?.status)
        assertEquals(AgentSuggestionStatus.PENDING, database.agentSuggestionDao().find("schedule-future")?.status)
        assertEquals(AgentSuggestionStatus.DISMISSED, database.agentSuggestionDao().find("schedule-past")?.status)
        assertEquals(
            listOf("schedule-future"),
            database.agentSuggestionDao().imminentSchedules(now, now + 24L * 60 * 60 * 1_000).map { it.suggestionId },
        )
    }

    @Test fun suggestionsCanBeReadInStablePages() = runBlocking {
        repeat(5) { index ->
            database.agentSuggestionDao().insert(
                suggestion("page-$index", dedupeKey = "page-$index").copy(createdAtEpochMs = index.toLong()),
            )
        }

        assertEquals(listOf("page-4", "page-3"), database.agentSuggestionDao().observeRecent(2, 0).first().map { it.suggestionId })
        assertEquals(listOf("page-2", "page-1"), database.agentSuggestionDao().observeRecent(2, 2).first().map { it.suggestionId })
    }

    // ---- pruneSettled：只清「非 PENDING 且超期」 ----

    @Test fun pruneKeepsPendingAndRecentSuggestions() = runBlocking {
        val dao = database.agentSuggestionDao()
        val now = 1_000_000_000L
        val older = now - 40L * 24 * 60 * 60 * 1_000
        // PENDING + 超期 → 保留（未处置不得清）
        repository.insert(suggestion("s-old-pending", createdAt = older))
        // ACCEPTED + 超期 → 清理
        repository.insert(suggestion("s-old-accepted", status = AgentSuggestionStatus.ACCEPTED, createdAt = older))
        // ACCEPTED + 未超期 → 保留
        repository.insert(suggestion("s-new-accepted", status = AgentSuggestionStatus.ACCEPTED, createdAt = now - 1_000))

        val deleted = repository.pruneSettled(olderThanDays = 30, nowEpochMs = now)

        assertEquals(1, deleted)
        assertNotNull(dao.find("s-old-pending"))
        assertNull(dao.find("s-old-accepted"))
        assertNotNull(dao.find("s-new-accepted"))
    }

    // ---- SCHEDULE 执行链路（真实 EventPlanningRepository）----

    @Test fun acceptScheduleCreatesConfirmedPlanAndCalendarEvent() = runBlocking {
        database.contactDao().insert(contact())
        val dao = database.agentSuggestionDao()
        repository.insert(
            suggestion(
                "s-sched",
                contactId = "contact-1",
                execActionType = "SCHEDULE",
                startAtEpochMs = 1_000_000_000L,
                location = "武汉市汉阳区龙阳大道特8号",
                companyFull = "九州通医药集团股份有限公司",
            ),
        )

        assertTrue(repository.accept("s-sched", chosenContactId = "contact-1", nowEpochMs = 1L))

        val after = dao.find("s-sched")!!
        assertEquals(AgentSuggestionStatus.ACCEPTED, after.status)
        // 只有 confirmToCalendar 成功才回写 planId（UI「已创建日程」依据）。
        assertNotNull(after.planId)
        val plan = database.eventPlanningDao().findPlan(after.planId!!)
        assertNotNull(plan)
        assertEquals(EventPlanStatus.CONFIRMED, plan?.status)
        val schedule = database.scheduleDao().findById(plan!!.scheduleId!!)
        assertNotNull(schedule)
        assertTrue(schedule!!.title.contains("九州通"))
        assertTrue(schedule.note!!.contains("地点：武汉市汉阳区龙阳大道特8号"))
        assertTrue(schedule.note!!.contains("对接人：李雷"))
        assertTrue(schedule.note!!.contains("来自知伴 · 智能建议"))
        assertEquals(1, database.scheduleDao().count())
        assertEquals(schedule.id, registeredReminders.single().first)
        assertEquals(schedule.startAtEpochMs, registeredReminders.single().second)
        assertEquals(60, registeredReminders.single().third)
    }

    @Test fun scheduleWithoutParticipantRemainsPendingAndWritesNothing() = runBlocking {
        val dao = database.agentSuggestionDao()
        repository.insert(
            suggestion(
                "s-no-contact",
                contactId = null,
                execActionType = "SCHEDULE",
                startAtEpochMs = 1_000_000_000L,
            ),
        )

        assertFalse(repository.accept("s-no-contact", nowEpochMs = 1L))

        val after = dao.find("s-no-contact")!!
        assertEquals(AgentSuggestionStatus.PENDING, after.status)
        assertNull(after.planId)
        assertTrue(database.eventPlanningDao().observePlans().first().isEmpty())
        assertEquals(0, database.scheduleDao().count())
    }

    @Test fun scheduleWithMissingContactRemainsPendingAndWritesNothing() = runBlocking {
        val dao = database.agentSuggestionDao()
        repository.insert(
            suggestion(
                "s-ghost-contact",
                contactId = "ghost-contact",
                execActionType = "SCHEDULE",
                startAtEpochMs = 1_000_000_000L,
            ),
        )

        assertFalse(repository.accept("s-ghost-contact", nowEpochMs = 1L))

        val after = dao.find("s-ghost-contact")!!
        assertEquals(AgentSuggestionStatus.PENDING, after.status)
        assertNull(after.planId)
        assertTrue(database.eventPlanningDao().observePlans().first().isEmpty())
        assertEquals(0, database.scheduleDao().count())
    }

    @Test fun expiredScheduleRemainsPendingAndWritesNothing() = runBlocking {
        val dao = database.agentSuggestionDao()
        repository.insert(
            suggestion(
                "s-past",
                contactId = null,
                execActionType = "SCHEDULE",
                startAtEpochMs = 100_000L,
            ),
        )

        assertFalse(repository.accept("s-past", nowEpochMs = 1_000_000L))

        val after = dao.find("s-past")!!
        assertEquals(AgentSuggestionStatus.PENDING, after.status)
        assertNull(after.planId)
        assertTrue(database.eventPlanningDao().observePlans().first().isEmpty())
        assertEquals(0, database.scheduleDao().count())
    }

    @Test fun selectedCandidateContactIsWrittenAsParticipant() = runBlocking {
        database.contactDao().insert(contact())
        repository.insert(
            suggestion(
                "s-candidate",
                contactId = null,
                execActionType = "SCHEDULE",
                startAtEpochMs = 1_000_000_000L,
                contactCandidatesJson = """[{"contactId":"contact-1","name":"李雷"}]""",
            ),
        )

        assertTrue(repository.accept("s-candidate", chosenContactId = "contact-1", nowEpochMs = 1L))

        val planId = database.agentSuggestionDao().find("s-candidate")!!.planId!!
        assertNotNull(database.eventPlanningDao().findParticipant(planId, "contact-1"))
        assertEquals(1, database.scheduleDao().count())
    }

    @Test fun acceptScheduleDefaultsDurationToNinetyMinutes() = runBlocking {
        database.contactDao().insert(contact())
        repository.insert(
            suggestion(
                "s-dur",
                contactId = "contact-1",
                execActionType = "SCHEDULE",
                startAtEpochMs = 1_000_000_000L,
                durationMinutes = null,
            ),
        )
        repository.accept("s-dur", nowEpochMs = 1L)
        val planId = database.agentSuggestionDao().find("s-dur")!!.planId!!
        assertEquals(90, database.eventPlanningDao().findPlan(planId)?.durationMinutes)
    }

    // ---- CONTACT_COMPLETION 一键转发链路 ----

    @Test fun acceptCompletionSettlesOnlyAfterSuccessfulHandoff() = runBlocking {
        database.contactDao().insert(contact())
        insertCompletionRequest("ccr-1", "contact-1")
        repository.insert(
            suggestion(
                "s-comp",
                type = AgentSuggestionType.WAKEUP_COMPLETION,
                contactId = "contact-1",
                execActionType = "CONTACT_COMPLETION",
                completionRequestId = "ccr-1",
                forwardMessage = "您好，我是周国平本人的知伴AI助手……",
            ),
        )

        assertTrue(repository.accept("s-comp", nowEpochMs = 2L))

        val after = database.agentSuggestionDao().find("s-comp")!!
        assertEquals(AgentSuggestionStatus.ACCEPTED, after.status)
        // 请求随 handoff 成功进入等待回复。
        assertEquals(
            ContactCompletionStatus.AWAITING_REPLY,
            database.contactCompletionRequestDao().findById("ccr-1")?.status,
        )
    }

    @Test fun failedCompletionHandoffRemainsPendingAndDrafted() = runBlocking {
        handoffSucceeds = false // 模拟微信未装/拉不起预填
        database.contactDao().insert(contact())
        insertCompletionRequest("ccr-1", "contact-1")
        repository.insert(
            suggestion(
                "s-comp",
                type = AgentSuggestionType.WAKEUP_COMPLETION,
                contactId = "contact-1",
                execActionType = "CONTACT_COMPLETION",
                completionRequestId = "ccr-1",
                forwardMessage = "您好，我是周国平本人的知伴AI助手……",
            ),
        )

        assertFalse(repository.accept("s-comp", nowEpochMs = 2L))

        // 半自动纪律：没拉起微信绝不谎报已发——建议保持 PENDING 可重试，请求保持 DRAFTED。
        assertEquals(AgentSuggestionStatus.PENDING, database.agentSuggestionDao().find("s-comp")?.status)
        assertEquals(ContactCompletionStatus.DRAFTED, database.contactCompletionRequestDao().findById("ccr-1")?.status)
    }

    @Test fun completionWithoutRequestIdCannotBeAccepted() = runBlocking {
        repository.insert(
            suggestion(
                "s-comp",
                type = AgentSuggestionType.WAKEUP_COMPLETION,
                contactId = "contact-1",
                execActionType = "CONTACT_COMPLETION",
                completionRequestId = null,
            ),
        )
        assertFalse(repository.accept("s-comp", nowEpochMs = 2L))
        assertEquals(AgentSuggestionStatus.PENDING, database.agentSuggestionDao().find("s-comp")?.status)
    }

    @Test fun dismissCompletionCancelsItsActiveRequest() = runBlocking {
        database.contactDao().insert(contact())
        insertCompletionRequest("ccr-1", "contact-1")
        repository.insert(
            suggestion(
                "s-comp",
                type = AgentSuggestionType.WAKEUP_COMPLETION,
                contactId = "contact-1",
                execActionType = "CONTACT_COMPLETION",
                completionRequestId = "ccr-1",
                forwardMessage = "您好，我是周国平本人的知伴AI助手……",
            ),
        )

        assertTrue(repository.dismiss("s-comp", nowEpochMs = 2L))

        assertEquals(AgentSuggestionStatus.DISMISSED, database.agentSuggestionDao().find("s-comp")?.status)
        assertEquals(ContactCompletionStatus.CANCELLED, database.contactCompletionRequestDao().findById("ccr-1")?.status)
    }

    @Test fun completionDraftExposesDialogRenderingData() = runBlocking {
        database.contactDao().insert(contact())
        insertCompletionRequest("ccr-1", "contact-1")
        repository.insert(
            suggestion(
                "s-comp",
                type = AgentSuggestionType.WAKEUP_COMPLETION,
                contactId = "contact-1",
                execActionType = "CONTACT_COMPLETION",
                completionRequestId = "ccr-1",
                forwardMessage = "您好，我是周国平本人的知伴AI助手，发现您的资料还不全，方便补充手机号吗？",
                missingFieldsJson = """["PHONE","EMAIL"]""",
            ),
        )

        val draft = repository.completionDraft("s-comp")

        assertNotNull(draft)
        assertEquals("ccr-1", draft?.requestId)
        assertEquals("contact-1", draft?.contactId)
        assertEquals("李雷", draft?.contactName)
        assertEquals(listOf(ContactProfileField.PHONE, ContactProfileField.EMAIL), draft?.fields)
        assertTrue(draft!!.draftText.contains("知伴AI助手"))
    }

    @Test fun completionDraftRequiresForwardMessage() = runBlocking {
        database.contactDao().insert(contact())
        insertCompletionRequest("ccr-1", "contact-1")
        repository.insert(
            suggestion(
                "s-comp",
                type = AgentSuggestionType.WAKEUP_COMPLETION,
                contactId = "contact-1",
                execActionType = "CONTACT_COMPLETION",
                completionRequestId = "ccr-1",
                forwardMessage = null,
            ),
        )
        assertNull(repository.completionDraft("s-comp"))
    }

    // ---- helpers ----

    private suspend fun insertCompletionRequest(requestId: String, contactId: String) {
        database.contactCompletionRequestDao().upsert(
            ContactCompletionRequestEntity(
                requestId = requestId,
                contactId = contactId,
                requestedFieldsJson = """["PHONE","EMAIL"]""",
                draftText = "您好，我是周国平本人的知伴AI助手，发现您的资料还不全，方便补充手机号吗？",
                status = ContactCompletionStatus.DRAFTED,
                createdAtEpochMs = 1L,
                expiresAtEpochMs = Long.MAX_VALUE,
                updatedAtEpochMs = 1L,
            ),
        )
    }

    private fun suggestion(
        id: String,
        dedupeKey: String = "wakeup-$id",
        status: String = AgentSuggestionStatus.PENDING,
        contactId: String? = null,
        execActionType: String? = null,
        startAtEpochMs: Long? = null,
        durationMinutes: Int? = 90,
        location: String? = null,
        companyFull: String? = null,
        createdAt: Long = 1L,
        title: String = "明天拜访九州通",
        type: String = AgentSuggestionType.WAKEUP_SCHEDULE,
        completionRequestId: String? = null,
        forwardMessage: String? = null,
        missingFieldsJson: String? = null,
        contactCandidatesJson: String? = null,
    ) = AgentSuggestionEntity(
        suggestionId = id,
        type = type,
        title = title,
        body = "建议明天上午拜访九州通领导",
        contactId = contactId,
        candidateId = "cand-$id",
        sourceEvent = "NOTIFICATION",
        dedupeKey = dedupeKey,
        status = status,
        createdAtEpochMs = createdAt,
        updatedAtEpochMs = createdAt,
        execActionType = execActionType,
        startAtEpochMs = startAtEpochMs,
        durationMinutes = durationMinutes,
        location = location,
        companyFull = companyFull,
        completionRequestId = completionRequestId,
        forwardMessage = forwardMessage,
        missingFieldsJson = missingFieldsJson,
        contactCandidatesJson = contactCandidatesJson,
    )

    private fun contact() = ContactEntity(
        "contact-1", "李雷", "李雷", null, null, null, null, null,
        "[]", "[]", null, null, "USER", null, 1, 1,
    )
}
