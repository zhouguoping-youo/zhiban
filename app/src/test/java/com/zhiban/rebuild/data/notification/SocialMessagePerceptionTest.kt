package com.zhiban.rebuild.data.notification

import com.zhiban.rebuild.data.notification.NotificationCandidateEntity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialMessagePerceptionTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = LocalDate.of(2026, 7, 29)
        .atTime(10, 0)
        .atZone(zone)
        .toInstant()
        .toEpochMilli()

    @Test
    fun wechatSingleChatProducesIncomingPersonEvidence() {
        val candidate = SocialNotificationParser.parse(
            snapshot(
                packageName = "com.tencent.mm",
                title = "张三",
                text = "项目材料我已经发给你了",
            ),
        )

        assertNotNull(candidate)
        assertEquals("WECHAT", candidate!!.platform)
        assertEquals("张三", candidate.senderName)
        assertEquals("张三", candidate.conversationTitle)
        assertFalse(candidate.isGroupChat)
        assertEquals("项目材料我已经发给你了", candidate.body)
    }

    @Test
    fun notificationTextIsBoundedAndControlCharactersAreRemovedWithoutLanguageWhitelist() {
        val candidate = SocialNotificationParser.parse(
            snapshot(
                packageName = "com.tencent.mm",
                title = "مرحبا\u0000🙂",
                text = "བཀྲ་ཤིས་\u0000" + "文".repeat(1_500),
            ),
        )!!

        assertFalse(candidate.senderName!!.contains('\u0000'))
        assertTrue(candidate.senderName!!.contains("مرحبا"))
        assertTrue(candidate.senderName!!.contains("🙂"))
        assertFalse(candidate.body!!.contains('\u0000'))
        assertTrue(candidate.body!!.startsWith("བཀྲ་ཤིས་"))
        assertEquals(1_000, candidate.body!!.length)
        assertEquals(64, candidate.sourceKey.length)
    }

    @Test
    fun wechatStackedUnreadCountPrefixInTextIsNotMistakenForGroupChat() {
        // Real-device format (dumpsys notification): a 1:1 chat whose messages stack reports
        // title="周国平" and text="[3条]周国平: ...". The stacked prefix must not turn this into a
        // group chat — group candidates are excluded from auto-linking, so the unique-name match
        // would surface a confirmation card instead of silently binding to the contact.
        val candidate = SocialNotificationParser.parse(
            snapshot(
                packageName = "com.tencent.mm",
                title = "周国平",
                text = "[3条]周国平: 我是周国平，平凯星辰（北京）科技有限公司武汉分公司，13476110061",
            ),
        )

        assertNotNull(candidate)
        assertEquals("周国平", candidate!!.senderName)
        assertEquals("周国平", candidate.conversationTitle)
        assertFalse(candidate.isGroupChat)
        assertEquals("我是周国平，平凯星辰（北京）科技有限公司武汉分公司，13476110061", candidate.body)
    }

    @Test
    fun wechatGroupSeparatesConversationAndActualSender() {
        val candidate = SocialNotificationParser.parse(
            snapshot(
                packageName = "com.tencent.mm",
                title = "产品项目群 (3)",
                text = "李雷：明天下午3点开会",
            ),
        )

        assertNotNull(candidate)
        assertEquals("产品项目群", candidate!!.conversationTitle)
        assertEquals("李雷", candidate.senderName)
        assertEquals("明天下午3点开会", candidate.body)
        assertTrue(candidate.isGroupChat)
        assertEquals("SCHEDULE_CANDIDATE", candidate.messageKind)
        assertNotNull(ScheduleInsight.from(candidate))
    }

    @Test
    fun wechatStackedGroupStillParsesAsGroupAfterPrefixStrip() {
        // The unread-count strip must not flatten a genuinely stacked GROUP message: stripping "[5条]"
        // still leaves "李雷：…", which keeps the group classification and the real sender attribution.
        val candidate = SocialNotificationParser.parse(
            snapshot(
                packageName = "com.tencent.mm",
                title = "产品项目群 (3)",
                text = "[5条]李雷：明天下午3点开会",
            ),
        )

        assertNotNull(candidate)
        assertEquals("产品项目群", candidate!!.conversationTitle)
        assertEquals("李雷", candidate.senderName)
        assertEquals("明天下午3点开会", candidate.body)
        assertTrue(candidate.isGroupChat)
    }

    @Test
    fun wechatStackedSenderUnreadCountPrefixIsStrippedForAttribution() {
        // When WeChat messages stack, the MessagingStyle sender arrives as "[3条]周国平". The unread-count tag is
        // not part of the name; it must be stripped or name-based attribution/auto-linking silently misses.
        val candidate = SocialNotificationParser.parse(
            snapshot(
                packageName = "com.tencent.mm",
                title = "周国平",
                text = "合同明天能发我吗",
            ).copy(
                messages = listOf(NotificationMessageSnapshot("[3条]周国平", "合同明天能发我吗", now)),
            ),
        )

        assertNotNull(candidate)
        assertEquals("周国平", candidate!!.senderName)
        assertEquals("周国平", candidate.conversationTitle)
    }

    @Test
    fun explicitChineseScheduleGetsDeterministicLocalTime() {
        val insights = NotificationInsightAnalyzer.analyze(
            text = "明天下午三点在公司开会",
            senderName = "张三",
            conversationTitle = "张三",
            postedAtEpochMs = now,
            zoneId = zone,
        )

        val schedule = insights.schedule
        assertNotNull(schedule)
        val expected = LocalDate.of(2026, 7, 30)
            .atTime(15, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        assertEquals(expected, schedule!!.startAtEpochMs)
        assertEquals(10, schedule.reminderMinutesBefore)
    }

    @Test
    fun contractDeadlineWithoutSchedulingIntentIsNotMistakenForAnAppointment() {
        val insights = NotificationInsightAnalyzer.analyze(
            text = "明天下午3点这个合约到期",
            senderName = "张三",
            conversationTitle = "张三",
            postedAtEpochMs = now,
            zoneId = zone,
        )

        assertNull(insights.schedule)
    }

    @Test
    fun explicitAppointmentStillProducesASchedule() {
        val insights = NotificationInsightAnalyzer.analyze(
            text = "明天下午3点约王经理吃饭",
            senderName = "张三",
            conversationTitle = "张三",
            postedAtEpochMs = now,
            zoneId = zone,
        )

        assertNotNull(insights.schedule)
    }

    @Test
    fun tentativeScheduleQuestionRemainsConfirmableButIsNotHighConfidence() {
        val insights = NotificationInsightAnalyzer.analyze(
            text = "明天下午三点开武汉项目复盘会吗？",
            senderName = "张总",
            conversationTitle = "张总",
            postedAtEpochMs = now,
            zoneId = zone,
        )

        val schedule = requireNotNull(insights.schedule)
        assertTrue(schedule.confidence >= 0.85)
        assertTrue(schedule.confidence < 0.98)
        assertEquals("开武汉项目复盘会", schedule.title)
    }

    @Test
    fun confirmedCompoundMeetingTitleIsRecognizedWithHighConfidence() {
        val insights = NotificationInsightAnalyzer.analyze(
            text = "明天下午三点开武汉项目复盘会，请准时参加",
            senderName = "张总",
            conversationTitle = "张总",
            postedAtEpochMs = now,
            zoneId = zone,
        )

        val schedule = requireNotNull(insights.schedule)
        assertTrue(schedule.confidence >= 0.98)
        assertEquals("开武汉项目复盘会，请准时参加", schedule.title)
    }

    @Test
    fun unrelatedMembershipTextIsNotMistakenForACompoundMeeting() {
        val insights = NotificationInsightAnalyzer.analyze(
            text = "明天下午三点开会员续费服务",
            senderName = "服务通知",
            conversationTitle = "服务通知",
            postedAtEpochMs = now,
            zoneId = zone,
        )

        assertNull(insights.schedule)
    }

    @Test
    fun weekdayPhrasesKeepTheNearestOccurrenceMatrix() {
        val weekdayText = mapOf(
            DayOfWeek.MONDAY to "一",
            DayOfWeek.TUESDAY to "二",
            DayOfWeek.WEDNESDAY to "三",
            DayOfWeek.THURSDAY to "四",
            DayOfWeek.FRIDAY to "五",
            DayOfWeek.SATURDAY to "六",
            DayOfWeek.SUNDAY to "日",
        )
        val today = LocalDate.of(2026, 7, 29)
        weekdayText.forEach { (weekday, text) ->
            val nearest = today.with(TemporalAdjusters.nextOrSame(weekday))
            listOf("周", "星期").forEach { prefix ->
                assertScheduleDate("下午3点 $prefix$text 开会", nearest)
            }
            val nearestDownWeek = if (nearest == today) nearest.plusWeeks(1) else nearest
            listOf("下周", "下星期").forEach { prefix ->
                assertScheduleDate("下午3点 $prefix$text 开会", nearestDownWeek)
            }
        }
    }

    @Test
    fun resolveWeekdayPhrasesKeepNextOrSameWhenNextWeekPrefixAppearsInContext() {
        val today = LocalDate.of(2026, 7, 29).atTime(11, 0).atZone(zone).toInstant().toEpochMilli()
        assertScheduleDate("周一说下周三开会", LocalDate.of(2026, 8, 3), today)
        assertScheduleDate("周四说下周周二开会", LocalDate.of(2026, 7, 30), today)
    }

    @Test
    fun resolveWeekdayPhrasesShiftToNextWeekOnlyWhenTargetDayIsToday() {
        val today = LocalDate.of(2026, 8, 3).atTime(11, 0).atZone(zone).toInstant().toEpochMilli()
        assertScheduleDate("今天说下周今天下午3点开会", LocalDate.of(2026, 8, 3), today)
    }

    @Test
    fun likelyReplySignalRecognizesCommonReplyWordsAndShortCodes() {
        assertTrue(SocialNotificationParser.likelyReplySignal("收到，我看看"))
        assertTrue(SocialNotificationParser.likelyReplySignal("好的"))
        assertTrue(SocialNotificationParser.likelyReplySignal("收到，好的，确认了"))
        assertFalse(SocialNotificationParser.likelyReplySignal("周三有空吗"))
    }

    @Test
    fun hasLikelyRecentOutboundContextUsesDirectionAndIndicator() {
        assertFalse(
            SocialNotificationParser.hasLikelyRecentOutboundContext(
                NotificationCandidateEntity(
                    candidateId = "c1",
                    sourceKey = "k1",
                    packageName = "com.tencent.mm",
                    appLabel = "微信",
                    title = "张三",
                    body = "收到",
                    postedAtEpochMs = 1L,
                    direction = "OUTGOING",
                ),
            ),
        )
        assertTrue(
            SocialNotificationParser.hasLikelyRecentOutboundContext(
                NotificationCandidateEntity(
                    candidateId = "c2",
                    sourceKey = "k2",
                    packageName = "com.tencent.mm",
                    appLabel = "微信",
                    title = "张三",
                    body = "收到",
                    postedAtEpochMs = 1L,
                    direction = "INCOMING",
                ),
            ),
        )
    }

    @Test
    fun scheduleTitleWillStripTimePrefixEvenWithNoWhitespace() {
        val insights = NotificationInsightAnalyzer.analyze(
            text = "老周，明天下下午3点和我开会。",
            senderName = "老周",
            conversationTitle = "老周",
            postedAtEpochMs = now,
            zoneId = zone,
        )
        val schedule = requireNotNull(insights.schedule)
        assertEquals("开会", schedule.title)
    }

    @Test
    fun scheduleTitleAlsoStripsTimeConnectiveForDifferentWhitespaceStyles() {
        val insights = NotificationInsightAnalyzer.analyze(
            text = "老周，明天 下午3点 和我见面",
            senderName = "老周",
            conversationTitle = "老周",
            postedAtEpochMs = now,
            zoneId = zone,
        )
        val schedule = requireNotNull(insights.schedule)
        assertEquals("见面", schedule.title)
    }

    @Test
    fun qqFeishuAndCommonSmsPackagesAreRecognized() {
        assertEquals("QQ", SocialAppCatalog.platformForPackage("com.tencent.mobileqq")?.code)
        assertEquals("FEISHU", SocialAppCatalog.platformForPackage("com.ss.android.lark")?.code)
        assertEquals("LARK", SocialAppCatalog.platformForPackage("com.larksuite.suite")?.code)
        assertEquals("SMS", SocialAppCatalog.platformForPackage("com.samsung.android.messaging")?.code)
    }

    @Test
    fun messagingStyleSelfSenderIsMarkedOutgoingButTargetsConversationPerson() {
        val candidate = SocialNotificationParser.parse(
            snapshot(
                packageName = "com.ss.android.lark",
                title = "王敏",
                text = "收到",
            ).copy(
                selfDisplayName = "老周",
                messages = listOf(NotificationMessageSnapshot("老周", "我下午发给你", now)),
            ),
        )

        assertNotNull(candidate)
        assertEquals("OUTGOING", candidate!!.direction)
        assertEquals("王敏", candidate.senderName)
        assertEquals("我下午发给你", candidate.body)
    }

    @Test
    fun verificationCodesAndUnsupportedAppsAreNeverStaged() {
        assertNull(
            SocialNotificationParser.parse(
                snapshot(
                    packageName = "com.samsung.android.messaging",
                    title = "张三",
                    text = "动态口令 482913，请勿转发",
                ),
            ),
        )
        assertNull(
            SocialNotificationParser.parse(
                snapshot(
                    packageName = "com.samsung.android.messaging",
                    title = "张三",
                    text = "交易密码 482913",
                ),
            ),
        )
        assertNull(
            SocialNotificationParser.parse(
                snapshot(
                    packageName = "com.samsung.android.messaging",
                    title = "张三",
                    text = "密钥 sk_test_1234567890",
                ),
            ),
        )
        assertNotNull(
            SocialNotificationParser.parse(
                snapshot(
                    packageName = "com.tencent.mm",
                    title = "张三",
                    text = "项目密钥管理方案已经更新",
                ),
            ),
        )
        assertNull(
            SocialNotificationParser.parse(
                snapshot(
                    packageName = "com.samsung.android.messaging",
                    title = "张三",
                    text = "Your code is 482913. Do not share it.",
                ),
            ),
        )
        assertNull(
            SocialNotificationParser.parse(
                snapshot(
                    packageName = "com.samsung.android.messaging",
                    title = "张三",
                    text = "Use 482913 as your security code",
                ),
            ),
        )
        assertNull(
            SocialNotificationParser.parse(
                snapshot(
                    packageName = "com.samsung.android.messaging",
                    title = "10690000",
                    text = "验证码 482913，5分钟内有效",
                ),
            ),
        )
        listOf(
            "这是我的银行卡号 6222 0212 3456 7890 123",
            "信用卡尾号 **** 4829，请核对",
            "please use card number 4111-1111-1111-1111",
        ).forEach { sensitiveText ->
            assertNull(
                SocialNotificationParser.parse(
                    snapshot(packageName = "com.tencent.mm", title = "张三", text = sensitiveText),
                ),
            )
        }
        assertNotNull(
            SocialNotificationParser.parse(
                snapshot(
                    packageName = "com.tencent.mm",
                    title = "张三",
                    text = "会议编号 1234 5678 9012 3456，请查收",
                ),
            ),
        )
        assertNull(
            SocialNotificationParser.parse(
                snapshot(
                    packageName = "com.example.shopping",
                    title = "促销通知",
                    text = "今晚八点开抢",
                ),
            ),
        )
    }

    private fun assertScheduleDate(text: String, expectedDate: LocalDate) {
        assertScheduleDate(text, expectedDate, now)
    }

    private fun assertScheduleDate(text: String, expectedDate: LocalDate, postedAtEpochMs: Long) {
        val insight = NotificationInsightAnalyzer.analyze(
            text = text,
            senderName = "张三",
            conversationTitle = "张三",
            postedAtEpochMs = postedAtEpochMs,
            zoneId = zone,
        ).schedule
        assertNotNull(text, insight)
        val actualDate = java.time.Instant.ofEpochMilli(requireNotNull(insight).startAtEpochMs)
            .atZone(zone)
            .toLocalDate()
        assertEquals(text, expectedDate, actualDate)
    }

    @Test
    fun smsRequiresExplicitOptInButRemainsSupported() {
        assertFalse("SMS" in MessageCollectionPreferences.DEFAULT_PLATFORMS)
        assertTrue("SMS" in MessageCollectionPreferences.SUPPORTED_PLATFORMS)
    }

    @Test
    fun genericOrBroadcastSmsIsNotAddedToRelationshipInbox() {
        assertNull(
            SocialNotificationParser.parse(
                snapshot(
                    packageName = "com.samsung.android.messaging",
                    title = "信息",
                    text = "查看信息",
                ),
            ),
        )
        assertNull(
            SocialNotificationParser.parse(
                snapshot(
                    packageName = "com.samsung.android.messaging",
                    title = "106900001234",
                    text = "【某服务】业务通知，请注意查收",
                ),
            ),
        )
        assertNull(
            SocialNotificationParser.parse(
                snapshot(
                    packageName = "com.samsung.android.messaging",
                    title = "信息",
                    text = "【公共服务】天气预警通知",
                ),
            ),
        )
    }

    @Test
    fun namedPersonalSmsRemainsAvailableForUserConfirmation() {
        val candidate = SocialNotificationParser.parse(
            snapshot(
                packageName = "com.samsung.android.messaging",
                title = "张三",
                text = "明天下午三点见面",
            ),
        )
        assertNotNull(candidate)
        assertEquals("SMS", candidate!!.platform)
        assertEquals("张三", candidate.senderName)
        assertEquals("SCHEDULE_CANDIDATE", candidate.messageKind)
    }

    @Test
    fun repeatedNotificationHasStableSourceIdentity() {
        val first = SocialNotificationParser.parse(snapshot("com.tencent.mm", "张三", "收到"))
        val second = SocialNotificationParser.parse(snapshot("com.tencent.mm", "张三", "收到"))
        assertEquals(first?.sourceKey, second?.sourceKey)
        assertEquals(first?.candidateId, second?.candidateId)
    }

    private fun snapshot(packageName: String, title: String, text: String) = SocialNotificationSnapshot(
        packageName = packageName,
        notificationKey = "key-1",
        postTimeEpochMs = now,
        appLabel = packageName,
        title = title,
        text = text,
        bigText = null,
        conversationTitle = null,
        selfDisplayName = null,
        messages = emptyList(),
        category = "msg",
        isOngoing = false,
        userHandle = "UserHandle{0}",
    )
}
