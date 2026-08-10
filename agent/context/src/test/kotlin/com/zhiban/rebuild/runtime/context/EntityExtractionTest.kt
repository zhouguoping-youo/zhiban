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
            "帮我看看上次和张三聊的项目",
            "Work",
            now,
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
        val result = extractor.extract("新增联系人李雷，电话 13900139000，邮箱 li@example.com，2026-07-25 跟进", "Work", now)
        assertEquals(IntentLabel.CONTACT_CREATE, result.intentLabel)
        assertTrue(result.entities.any { it.type == ExtractedEntityType.PHONE && it.value == "13900139000" })
        assertTrue(result.entities.any { it.type == ExtractedEntityType.EMAIL && it.value == "li@example.com" })
        assertEquals("2026-07-25", result.timeRange?.expression)
    }

    @Test fun preservesUnlinkedPersonInsteadOfDroppingEntity() {
        val result = extractor.extract("帮我找张三的联系方式", "Work", now)
        val person = result.entities.single { it.type == ExtractedEntityType.PERSON }
        assertEquals("张三", person.value)
        assertNull(person.linkedId)
        assertEquals(IntentLabel.CONTACT_QUERY, result.intentLabel)
    }

    @Test fun chatFallsBackWithoutInventingEntities() {
        val result = extractor.extract("你好", "Chat", now)
        assertEquals(IntentLabel.GENERAL_CHAT, result.intentLabel)
        assertEquals(.75, result.intentConfidence, 0.0)
        assertTrue(result.entities.isEmpty())
        assertNull(result.timeRange)
    }

    @Test fun recognizesRelationshipReadAndWriteIntents() {
        assertEquals(IntentLabel.RELATIONSHIP_QUERY, extractor.extract("张三和李四是什么关系", "Work", now).intentLabel)
        assertEquals(IntentLabel.RELATIONSHIP_WRITE, extractor.extract("记下张三和李四是朋友", "Work", now).intentLabel)
    }

    @Test fun recognizesSalesCrmIntentWithoutStealingExplicitContactCreation() {
        assertEquals(IntentLabel.SALES_CRM, extractor.extract("帮我整理本周的客户跟进", "Work", now).intentLabel)
        assertEquals(IntentLabel.SALES_CRM, extractor.extract("看看 CRM 里有哪些销售机会", "Work", now).intentLabel)
        assertEquals(IntentLabel.CONTACT_CREATE, extractor.extract("把张三加为客户", "Work", now).intentLabel)
    }

    @Test fun recognizesPersonalLifeWithoutStealingOrdinaryCalendarCreation() {
        assertEquals(IntentLabel.PERSONAL_LIFE, extractor.extract("用生活助理整理重要的人与事", "Work", now).intentLabel)
        assertEquals(IntentLabel.PERSONAL_LIFE, extractor.extract("帮我做一个生日安排", "Work", now).intentLabel)
        assertEquals(IntentLabel.CALENDAR_CREATE, extractor.extract("明天下午三点提醒我买礼物", "Work", now).intentLabel)
    }

    @Test fun resolvesEnglishTomorrowAndNormalizesEnglishWallClockInDeviceZone() {
        val result = extractor.extract(
            "Create a calendar event tomorrow at 8 PM, remind me 10 minutes before.",
            "Work",
            now,
        )
        assertEquals(IntentLabel.CALENDAR_CREATE, result.intentLabel)
        assertEquals("tomorrow", result.timeRange?.expression)
        val expected = LocalDateTime.of(2026, 7, 22, 20, 0)
            .atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, resolveCalendarStartEpochMs("tomorrow at 8 PM", result.timeRange, zone))
    }

    @Test fun normalizesChineseWallClockAndDoesNotMistakeReminderMinutesForTime() {
        val result = extractor.extract("明天下午 3 点提醒我开会，提前 10 分钟", "Work", now)
        val expected = LocalDateTime.of(2026, 7, 22, 15, 0)
            .atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, resolveCalendarStartEpochMs("明天下午 3 点提醒我开会，提前 10 分钟", result.timeRange, zone))
        assertNull(resolveCalendarStartEpochMs("明天提醒我开会，提前 10 分钟", result.timeRange, zone))
    }

    // Casual schedule requests with a future day + clock time but no explicit verb must still route to
    // CALENDAR_CREATE, so the deterministic confirmation path runs instead of a fabricated free-text
    // success reply (the "明晚8点健身" hallucination).
    @Test fun casualFutureTimePlusActivityIsCalendarCreateInWorkMode() {
        assertEquals(IntentLabel.CALENDAR_CREATE, extractor.extract("明晚8点健身", "Work", now).intentLabel)
        assertEquals(IntentLabel.CALENDAR_CREATE, extractor.extract("后天上午10点复诊", "Work", now).intentLabel)
        assertEquals(IntentLabel.CALENDAR_CREATE, extractor.extract("今晚7点跟客户吃饭", "Work", now).intentLabel)
    }

    // The same future-day + time shape must NOT be hijacked when the user is clearly asking a question.
    @Test fun futureTimeQuestionsAreNotCalendarCreate() {
        // Query phrasing excludes the casual-create signal; without create keywords it stays GENERAL_WORK.
        assertEquals(IntentLabel.GENERAL_WORK, extractor.extract("明晚8点我有事吗", "Work", now).intentLabel)
        assertEquals(IntentLabel.GENERAL_WORK, extractor.extract("明天几点有空", "Work", now).intentLabel)
        // Chat mode never widens into CALENDAR_CREATE via the casual signal.
        assertEquals(IntentLabel.GENERAL_CHAT, extractor.extract("明晚8点健身", "Chat", now).intentLabel)
    }

    // now = 2026-07-21 (Tuesday). Current-week Monday = 2026-07-20.
    @Test fun resolvesNextWeekWeekdayInsteadOfTrustingProviderRelativeDateMath() {
        val result = extractor.extract("下周三下午3点和张总开会", "Work", now)
        val expected = LocalDateTime.of(2026, 7, 29, 15, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals("下周三", result.timeRange?.expression)
        assertEquals(expected, resolveCalendarStartEpochMs("下周三下午3点和张总开会", result.timeRange, zone))
    }

    @Test fun resolvesThisWeekAndBareWeekdayToUpcomingLocalDay() {
        val thisWeek = extractor.extract("本周三上午10点开会", "Work", now)
        assertEquals(
            LocalDateTime.of(2026, 7, 22, 10, 0).atZone(zone).toInstant().toEpochMilli(),
            resolveCalendarStartEpochMs("本周三上午10点开会", thisWeek.timeRange, zone),
        )
        val bare = extractor.extract("周三下午3点开个会", "Work", now)
        assertEquals(
            LocalDateTime.of(2026, 7, 22, 15, 0).atZone(zone).toInstant().toEpochMilli(),
            resolveCalendarStartEpochMs("周三下午3点开个会", bare.timeRange, zone),
        )
    }

    @Test fun resolvesDayAfterTomorrowAndLateTodayWallClocks() {
        val dayAfter = extractor.extract("后天下午3点提醒我", "Work", now)
        assertEquals(
            LocalDateTime.of(2026, 7, 23, 15, 0).atZone(zone).toInstant().toEpochMilli(),
            resolveCalendarStartEpochMs("后天下午3点提醒我", dayAfter.timeRange, zone),
        )
        val lateToday = extractor.extract("今天23:30开会", "Work", now)
        assertEquals(
            LocalDateTime.of(2026, 7, 21, 23, 30).atZone(zone).toInstant().toEpochMilli(),
            resolveCalendarStartEpochMs("今天23:30开会", lateToday.timeRange, zone),
        )
    }

    // Bug: "中午12点" was mapped to 24:00 (rejected → null) and "中午1点" to 13:00, because 中午
    // was lumped in with 下午/晚上 (+12). 中午 should pin to the 12:00 hour block.
    @Test fun resolvesNoonMarkerToTwelveOClockNotMidnight() {
        val noon = extractor.extract("明天中午12点吃饭", "Work", now)
        assertEquals(
            LocalDateTime.of(2026, 7, 22, 12, 0).atZone(zone).toInstant().toEpochMilli(),
            resolveCalendarStartEpochMs("明天中午12点吃饭", noon.timeRange, zone),
        )
        val halfPastNoon = extractor.extract("明天中午12点半开会", "Work", now)
        assertEquals(
            LocalDateTime.of(2026, 7, 22, 12, 30).atZone(zone).toInstant().toEpochMilli(),
            resolveCalendarStartEpochMs("明天中午12点半开会", halfPastNoon.timeRange, zone),
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
