package com.zhiban.rebuild.runtime.context

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityExtractionTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = LocalDateTime.of(2026, 7, 21, 12, 0).atZone(zone).toInstant().toEpochMilli()
    private val extractor = LocalEntityExtractor(zone)

    @Test fun linksKnownContactRoleAndResolvesRecentRange() {
        val result = extractor.extract(
            "帮我看看上次和张三聊的项目", now,
            listOf(EntityDictionaryEntry("张三", "c-001", listOf("老张"), "CUSTOMER", "crm")),
        )
        val person = result.entities.single { it.type == ExtractedEntityType.PERSON }
        assertEquals("c-001", person.linkedId)
        assertEquals("CUSTOMER", person.roleType)
        assertEquals("crm", person.skillId)
        assertEquals("上次", result.timeRange?.expression)
        assertEquals(IntentLabel.GENERAL_WORK, result.intentLabel)
    }

    @Test fun extractsPhoneEmailDateAndExplicitContactCreateIntent() {
        val result = extractor.extract("新增联系人李雷，电话 13900139000，邮箱 li@example.com，2026-07-25 跟进", now)
        assertEquals(IntentLabel.CONTACT_CREATE, result.intentLabel)
        assertTrue(result.entities.any { it.type == ExtractedEntityType.PHONE && it.value == "13900139000" })
        assertTrue(result.entities.any { it.type == ExtractedEntityType.EMAIL && it.value == "li@example.com" })
        assertEquals("2026-07-25", result.timeRange?.expression)
    }

    @Test fun preservesUnlinkedPersonInsteadOfDroppingEntity() {
        val result = extractor.extract("帮我找张三的联系方式", now)
        val person = result.entities.single { it.type == ExtractedEntityType.PERSON }
        assertEquals("张三", person.value)
        assertNull(person.linkedId)
        assertEquals(IntentLabel.CONTACT_QUERY, result.intentLabel)
    }

    @Test fun generalConversationFallsBackWithoutInventingEntities() {
        val result = extractor.extract("你好", now)
        assertEquals(IntentLabel.GENERAL_WORK, result.intentLabel)
        assertEquals(.65, result.intentConfidence, 0.0)
        assertTrue(result.entities.isEmpty())
        assertNull(result.timeRange)
    }

    @Test fun recognizesRelationshipReadAndWriteIntents() {
        assertEquals(IntentLabel.RELATIONSHIP_QUERY, extractor.extract("张三和李四是什么关系", now).intentLabel)
        assertEquals(IntentLabel.RELATIONSHIP_WRITE, extractor.extract("记下张三和李四是朋友", now).intentLabel)
    }

    @Test fun recognizesSalesCrmIntentWithoutStealingExplicitContactCreation() {
        assertEquals(IntentLabel.SALES_CRM, extractor.extract("帮我整理本周的客户跟进", now).intentLabel)
        assertEquals(IntentLabel.SALES_CRM, extractor.extract("看看 CRM 里有哪些销售机会", now).intentLabel)
        assertEquals(IntentLabel.CONTACT_CREATE, extractor.extract("把张三加为客户", now).intentLabel)
    }

    @Test fun recognizesPersonalLifeWithoutStealingOrdinaryCalendarCreation() {
        assertEquals(IntentLabel.PERSONAL_LIFE, extractor.extract("用生活助理整理重要的人与事", now).intentLabel)
        assertEquals(IntentLabel.PERSONAL_LIFE, extractor.extract("帮我做一个生日安排", now).intentLabel)
        assertEquals(IntentLabel.CALENDAR_CREATE, extractor.extract("明天下午三点提醒我买礼物", now).intentLabel)
    }

    @Test fun recognizesMultiPersonPlanningWithoutStealingPersonalLife() {
        assertEquals(IntentLabel.SOCIAL_PLANNING, extractor.extract("帮我约几个老同事周末吃饭", now).intentLabel)
        assertEquals(IntentLabel.SOCIAL_PLANNING, extractor.extract("用一起安排组织聚会", now).intentLabel)
        assertEquals(IntentLabel.PERSONAL_LIFE, extractor.extract("帮我做一个生日安排", now).intentLabel)
    }

    @Test fun resolvesEnglishTomorrowAndNormalizesEnglishWallClockInDeviceZone() {
        val result = extractor.extract(
            "Create a calendar event tomorrow at 8 PM, remind me 10 minutes before.", now,
        )
        assertEquals(IntentLabel.CALENDAR_CREATE, result.intentLabel)
        assertEquals("tomorrow", result.timeRange?.expression)
        val expected = LocalDateTime.of(2026, 7, 22, 20, 0)
            .atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, resolveCalendarStartEpochMs("tomorrow at 8 PM", result.timeRange, zone))
    }

    @Test fun normalizesChineseWallClockAndDoesNotMistakeReminderMinutesForTime() {
        val result = extractor.extract("明天下午 3 点提醒我开会，提前 10 分钟", now)
        val expected = LocalDateTime.of(2026, 7, 22, 15, 0)
            .atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, resolveCalendarStartEpochMs("明天下午 3 点提醒我开会，提前 10 分钟", result.timeRange, zone))
        assertNull(resolveCalendarStartEpochMs("明天提醒我开会，提前 10 分钟", result.timeRange, zone))
    }

    // Casual schedule requests with a future day + clock time but no explicit verb must still route to
    // CALENDAR_CREATE, so the deterministic confirmation path runs instead of a fabricated free-text
    // success reply (the "明晚8点健身" hallucination).
    @Test fun casualFutureTimePlusActivityIsCalendarCreateInWorkMode() {
        assertEquals(IntentLabel.CALENDAR_CREATE, extractor.extract("明晚8点健身", now).intentLabel)
        assertEquals(IntentLabel.CALENDAR_CREATE, extractor.extract("后天上午10点复诊", now).intentLabel)
        assertEquals(IntentLabel.CALENDAR_CREATE, extractor.extract("今晚7点跟客户吃饭", now).intentLabel)
    }

    // A message-send whose *content* carries a time ("就说周五下午三点老地方见") must not be hijacked into
    // CALENDAR_CREATE by the casual future-time signal — it is a communication task (GENERAL_WORK) so the
    // model picks wechat.send / communication.message.compose instead of force-creating a schedule.
    @Test fun messageSendWithTimeInContentIsNotCalendarCreate() {
        assertEquals(
            IntentLabel.GENERAL_WORK,
            extractor.extract("给汪戈发条微信，就说周五下午三点老地方见", now).intentLabel,
        )
        assertEquals(
            IntentLabel.GENERAL_WORK,
            extractor.extract("发短信给张三，明天下午三点见", now).intentLabel,
        )
        assertEquals(
            IntentLabel.GENERAL_WORK,
            extractor.extract("帮我发条消息：明晚8点老地方见", now).intentLabel,
        )
        // An explicit reminder/schedule verb still wins over an embedded send — "提醒我…发微信" is a
        // reminder, not a message-send.
        assertEquals(
            IntentLabel.CALENDAR_CREATE,
            extractor.extract("提醒我周五下午三点给汪戈发微信", now).intentLabel,
        )
        // No send verb: a bare future time + activity stays a calendar write (no regression).
        assertEquals(IntentLabel.CALENDAR_CREATE, extractor.extract("明晚8点健身", now).intentLabel)
    }

    @Test fun compactDayPeriodAnchorsResolveDateAndClockTogether() {
        val cases = listOf(
            "今晚7点跟客户吃饭" to LocalDateTime.of(2026, 7, 21, 19, 0),
            "明晚8点健身" to LocalDateTime.of(2026, 7, 22, 20, 0),
            "明早8点出发" to LocalDateTime.of(2026, 7, 22, 8, 0),
            "明晚8:30接孩子" to LocalDateTime.of(2026, 7, 22, 20, 30),
        )

        cases.forEach { (text, expected) ->
            val context = extractor.extract(text, now)
            assertEquals(expected.atZone(zone).toInstant().toEpochMilli(), resolveCalendarStartEpochMs(text, context.timeRange, zone))
        }
    }

    @Test fun bigDayAfterTomorrowIsNotCollapsedIntoDayAfterTomorrow() {
        val text = "大后天晚上9点复盘"
        val context = extractor.extract(text, now)

        assertEquals("大后天", context.timeRange?.expression)
        assertEquals(
            LocalDateTime.of(2026, 7, 24, 21, 0).atZone(zone).toInstant().toEpochMilli(),
            resolveCalendarStartEpochMs(text, context.timeRange, zone),
        )
    }

    // The same future-day + time shape must NOT be hijacked when the user is clearly asking a question.
    @Test fun futureTimeQuestionsAreNotCalendarCreate() {
        assertEquals(IntentLabel.CALENDAR_QUERY, extractor.extract("明晚8点我有事吗", now).intentLabel)
        assertEquals(IntentLabel.CALENDAR_QUERY, extractor.extract("明天几点有空", now).intentLabel)
        assertEquals(IntentLabel.CALENDAR_QUERY, extractor.extract("我明天下午3点有安排吗", now).intentLabel)
        assertEquals(IntentLabel.CALENDAR_QUERY, extractor.extract("明天有什么日程", now).intentLabel)
        // A polite creation request remains a write intent despite ending in a question particle.
        assertEquals(IntentLabel.CALENDAR_CREATE, extractor.extract("可以帮我安排明天下午3点的会议吗", now).intentLabel)
        // The single Coworker mode recognizes a future activity as actionable.
        assertEquals(IntentLabel.CALENDAR_CREATE, extractor.extract("明晚8点健身", now).intentLabel)
    }

    @Test fun colloquialCalendarQuestionsNeverBecomeCreateIntents() {
        listOf(
            "明天有啥安排",
            "明天有安排么",
            "明天有安排嘛",
            "明天有安排吧？",
            "我明天的安排是什么",
            "明天安排了哪些事",
        ).forEach { input ->
            assertEquals(input, IntentLabel.CALENDAR_QUERY, extractor.extract(input, now).intentLabel)
        }
        assertEquals(
            IntentLabel.CALENDAR_CREATE,
            extractor.extract("帮我安排明天下午3点开会吧", now).intentLabel,
        )
    }

    // now = 2026-07-21 (Tuesday). Current-week Monday = 2026-07-20.
    @Test fun resolvesNextWeekWeekdayInsteadOfTrustingProviderRelativeDateMath() {
        val result = extractor.extract("下周三下午3点和张总开会", now)
        val expected = LocalDateTime.of(2026, 7, 29, 15, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals("下周三", result.timeRange?.expression)
        assertEquals(expected, resolveCalendarStartEpochMs("下周三下午3点和张总开会", result.timeRange, zone))
    }

    @Test fun resolvesWeekAfterNextWithoutCollapsingItToNextWeek() {
        val result = extractor.extract("下下周三下午3点和张总开会", now)
        val expected = LocalDateTime.of(2026, 8, 5, 15, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals("下下周三", result.timeRange?.expression)
        assertEquals(expected, resolveCalendarStartEpochMs("下下周三下午3点和张总开会", result.timeRange, zone))

        val synonym = extractor.extract("下下星期二上午9点复盘", now)
        assertEquals(
            LocalDateTime.of(2026, 8, 4, 9, 0).atZone(zone).toInstant().toEpochMilli(),
            resolveCalendarStartEpochMs("下下星期二上午9点复盘", synonym.timeRange, zone),
        )
    }

    @Test fun mixedWeekdayReferencesUseTheMatchedWeekPrefix() {
        val result = extractor.extract("下周三前把下下周五的方案准备好", now)
        val expected = LocalDateTime.of(2026, 7, 29, 0, 0).atZone(zone).toInstant().toEpochMilli()

        assertEquals("下周三", result.timeRange?.expression)
        assertEquals(expected, result.timeRange?.startEpochMs)
    }

    @Test fun resolvesThisWeekAndBareWeekdayToUpcomingLocalDay() {
        val thisWeek = extractor.extract("本周三上午10点开会", now)
        assertEquals(
            LocalDateTime.of(2026, 7, 22, 10, 0).atZone(zone).toInstant().toEpochMilli(),
            resolveCalendarStartEpochMs("本周三上午10点开会", thisWeek.timeRange, zone),
        )
        val bare = extractor.extract("周三下午3点开个会", now)
        assertEquals(
            LocalDateTime.of(2026, 7, 22, 15, 0).atZone(zone).toInstant().toEpochMilli(),
            resolveCalendarStartEpochMs("周三下午3点开个会", bare.timeRange, zone),
        )
    }

    @Test fun resolvesDayAfterTomorrowAndLateTodayWallClocks() {
        val dayAfter = extractor.extract("后天下午3点提醒我", now)
        assertEquals(
            LocalDateTime.of(2026, 7, 23, 15, 0).atZone(zone).toInstant().toEpochMilli(),
            resolveCalendarStartEpochMs("后天下午3点提醒我", dayAfter.timeRange, zone),
        )
        val lateToday = extractor.extract("今天23:30开会", now)
        assertEquals(
            LocalDateTime.of(2026, 7, 21, 23, 30).atZone(zone).toInstant().toEpochMilli(),
            resolveCalendarStartEpochMs("今天23:30开会", lateToday.timeRange, zone),
        )
    }

    // Bug: "中午12点" was mapped to 24:00 (rejected → null) and "中午1点" to 13:00, because 中午
    // was lumped in with 下午/晚上 (+12). 中午 should pin to the 12:00 hour block.
    @Test fun resolvesNoonMarkerToTwelveOClockNotMidnight() {
        val noon = extractor.extract("明天中午12点吃饭", now)
        assertEquals(
            LocalDateTime.of(2026, 7, 22, 12, 0).atZone(zone).toInstant().toEpochMilli(),
            resolveCalendarStartEpochMs("明天中午12点吃饭", noon.timeRange, zone),
        )
        val halfPastNoon = extractor.extract("明天中午12点半开会", now)
        assertEquals(
            LocalDateTime.of(2026, 7, 22, 12, 30).atZone(zone).toInstant().toEpochMilli(),
            resolveCalendarStartEpochMs("明天中午12点半开会", halfPastNoon.timeRange, zone),
        )
    }

    @Test fun resolvesNightTwelveToTheFollowingMidnight() {
        val tonight = extractor.extract("今晚12点关灯", now)
        assertEquals(
            LocalDateTime.of(2026, 7, 22, 0, 0).atZone(zone).toInstant().toEpochMilli(),
            resolveCalendarStartEpochMs("今晚12点关灯", tonight.timeRange, zone),
        )
        val tomorrowNight = extractor.extract("明晚12点开跨国会议", now)
        assertEquals(
            LocalDateTime.of(2026, 7, 23, 0, 0).atZone(zone).toInstant().toEpochMilli(),
            resolveCalendarStartEpochMs("明晚12点开跨国会议", tomorrowNight.timeRange, zone),
        )
        val tomorrowMidnight = extractor.extract("明天凌晨12点出发", now)
        assertEquals(
            LocalDateTime.of(2026, 7, 22, 0, 0).atZone(zone).toInstant().toEpochMilli(),
            resolveCalendarStartEpochMs("明天凌晨12点出发", tomorrowMidnight.timeRange, zone),
        )
    }

    // Bug: a bare "下午3点" (no 今天/明天) yields a null timeRange, so the local resolver returns
    // null and the wrong provider epoch is trusted. It should anchor to today (or tomorrow if past).
    @Test fun resolvesBareAfternoonClockToUpcomingDayWhenNoDayWordPresent() {
        // now = 2026-07-21 12:00, so 下午3点 (15:00) today is still ahead.
        val resolved = resolveCalendarStartEpochMs("下午3点提醒我开会", null, zone, now)
        assertEquals(
            LocalDateTime.of(2026, 7, 21, 15, 0).atZone(zone).toInstant().toEpochMilli(),
            resolved,
        )
    }

    @Test fun rollsBareClockToTomorrowWhenThatTimeAlreadyPassedToday() {
        // now = 2026-07-21 12:00, so 早上8点 today has passed → tomorrow 08:00.
        val resolved = resolveCalendarStartEpochMs("早上8点提醒我", null, zone, now)
        assertEquals(
            LocalDateTime.of(2026, 7, 22, 8, 0).atZone(zone).toInstant().toEpochMilli(),
            resolved,
        )
    }
}
