package com.zhiban.rebuild.data.suggestion

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventIntentExtractorTest {

    // 固定"现在"：2026-08-19（周三）12:00 +08:00，避免夜间语义干扰
    private val nowEpochMs: Long =
        ZonedDateTime.of(2026, 8, 19, 12, 0, 0, 0, ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val knownCompanies = listOf(
        "平凯星辰（北京）科技有限公司",
        "九州通医药集团股份有限公司",
    )

    @Test
    fun `真实场景_接人加拜访_双意图完整解析`() {
        val body = "你明天开车来我家万科云城接我，我们一去见九州通的领导。明天早上9点30分到就行"
        val intent = EventIntentExtractor.extract(body, "周国平", knownCompanies, nowEpochMs, zone)

        assertTrue(intent.hasScheduleIntent)
        assertTrue(intent.canCreateSchedule)
        // 2026-08-20 09:30 +08:00
        val expected = ZonedDateTime.of(2026, 8, 20, 9, 30, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, intent.startAtEpochMs)
        assertEquals("明天 09:30", intent.timeDescription)
        assertEquals("万科云城", intent.location)
        assertEquals("周国平", intent.pickupPerson)
        assertEquals("九州通", intent.company)
        assertEquals("九州通医药集团股份有限公司", intent.companyFull)
        assertTrue(intent.title!!.contains("接周国平"))
        assertTrue(intent.title!!.contains("拜访九州通"))
        assertTrue(intent.note!!.contains("客户：九州通医药集团股份有限公司"))
        assertTrue(intent.needsConfirmation.any { it.contains("拜访对象未提及") })
    }

    @Test
    fun `公司全称查不到时_保持简称并提示确认`() {
        val body = "明天去拜访云图智能的领导"
        val intent = EventIntentExtractor.extract(body, "周国平", emptyList(), nowEpochMs, zone)
        assertEquals("云图智能", intent.company)
        assertEquals("云图智能", intent.companyFull)
        assertTrue(intent.needsConfirmation.any { it.contains("未在联系人库中检索到公司全称") })
    }

    @Test
    fun `拜访对象有人名时_不需要确认`() {
        val body = "明天拜访九州通张总，下午2点"
        val intent = EventIntentExtractor.extract(body, "周国平", knownCompanies, nowEpochMs, zone)
        assertEquals("九州通", intent.company)
        assertTrue(intent.needsConfirmation.none { it.contains("拜访对象未提及") })
    }

    @Test
    fun `仅时间无地点无公司_也能建日程`() {
        val body = "明天下午3点开会"
        val intent = EventIntentExtractor.extract(body, "周国平", knownCompanies, nowEpochMs, zone)
        assertTrue(intent.canCreateSchedule)
        val expected = ZonedDateTime.of(2026, 8, 20, 15, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, intent.startAtEpochMs)
        assertNull(intent.location)
        assertNull(intent.company)
    }

    @Test
    fun `下午晚上时段自动加12小时`() {
        val body = "后天晚上8点半见天马科技的陈经理"
        val intent = EventIntentExtractor.extract(body, "周国平", knownCompanies, nowEpochMs, zone)
        val expected = ZonedDateTime.of(2026, 8, 21, 20, 30, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, intent.startAtEpochMs)
        assertEquals("天马科技", intent.company)
        assertTrue(intent.needsConfirmation.none { it.contains("拜访对象未提及") })
    }

    @Test
    fun `下周一相对日期解析`() {
        val body = "下周一早上9点拜访天马科技"
        val intent = EventIntentExtractor.extract(body, "周国平", knownCompanies, nowEpochMs, zone)
        // 2026-08-19 是周三，下周一 = 2026-08-24
        val expected = ZonedDateTime.of(2026, 8, 24, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, intent.startAtEpochMs)
    }

    @Test
    fun `当天说周几_应指今天而非七天后的同一天`() {
        // now=2026-08-19（周三），说「周三」必须解析到今天，不得用 next() 偏移到 08-26
        val body = "周三下午3点开会"
        val intent = EventIntentExtractor.extract(body, "周国平", knownCompanies, nowEpochMs, zone)
        assertTrue(intent.hasScheduleIntent)
        val expected = ZonedDateTime.of(2026, 8, 19, 15, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, intent.startAtEpochMs)
    }

    @Test
    fun `大后天必须优先于后天解析`() {
        val intent = EventIntentExtractor.extract("大后天晚上8点开会", "周国平", knownCompanies, nowEpochMs, zone)
        val expected = ZonedDateTime.of(2026, 8, 22, 20, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, intent.startAtEpochMs)
    }

    @Test
    fun `明晚必须解析为明天晚上`() {
        val intent = EventIntentExtractor.extract("明晚8点见面", "周国平", knownCompanies, nowEpochMs, zone)
        val expected = ZonedDateTime.of(2026, 8, 20, 20, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, intent.startAtEpochMs)
    }

    @Test
    fun `日号和月日使用同一日期出口`() {
        val dayOnly = EventIntentExtractor.extract("28号下午3点开会", "周国平", knownCompanies, nowEpochMs, zone)
        val monthDay = EventIntentExtractor.extract("9月2日晚上7点见面", "周国平", knownCompanies, nowEpochMs, zone)
        val expectedDayOnly = ZonedDateTime.of(2026, 8, 28, 15, 0, 0, 0, zone).toInstant().toEpochMilli()
        val expectedMonthDay = ZonedDateTime.of(2026, 9, 2, 19, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expectedDayOnly, dayOnly.startAtEpochMs)
        assertEquals(expectedMonthDay, monthDay.startAtEpochMs)
    }

    @Test
    fun `裸十二点保持正午而非午夜`() {
        val eleven = ZonedDateTime.of(2026, 8, 19, 11, 0, 0, 0, zone).toInstant().toEpochMilli()
        val intent = EventIntentExtractor.extract("12点开会", "周国平", knownCompanies, eleven, zone)
        val expected = ZonedDateTime.of(2026, 8, 19, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, intent.startAtEpochMs)
    }

    @Test
    fun `非法时分明确失败而不强压为合法时间`() {
        val invalidHour = EventIntentExtractor.extract("明天25点开会", "周国平", knownCompanies, nowEpochMs, zone)
        val invalidMinute = EventIntentExtractor.extract("明天99:99开会", "周国平", knownCompanies, nowEpochMs, zone)
        assertFalse(invalidHour.hasScheduleIntent)
        assertNull(invalidHour.startAtEpochMs)
        assertFalse(invalidMinute.hasScheduleIntent)
        assertNull(invalidMinute.startAtEpochMs)
    }

    @Test
    fun `已有日程洞察时间优先于正文再次解析`() {
        val authoritative = ZonedDateTime.of(2026, 8, 25, 18, 30, 0, 0, zone).toInstant().toEpochMilli()
        val intent = EventIntentExtractor.extract(
            body = "28号99点开会",
            contactName = "周国平",
            knownCompanies = knownCompanies,
            nowEpochMs = nowEpochMs,
            zoneId = zone,
            authoritativeStartAtEpochMs = authoritative,
            authoritativeDurationMinutes = 45,
            authoritativeTitle = "项目复盘会",
        )
        assertEquals(authoritative, intent.startAtEpochMs)
        assertEquals(45, intent.durationMinutes)
        assertEquals("项目复盘会", intent.title)
    }

    @Test
    fun `仅时间词加动作词_默认今天`() {
        // now=12:00，14:00 未过 → 今天 14:00
        val body = "下午2点见个面"
        val intent = EventIntentExtractor.extract(body, "周国平", knownCompanies, nowEpochMs, zone)
        assertTrue(intent.hasScheduleIntent)
        val expected = ZonedDateTime.of(2026, 8, 19, 14, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, intent.startAtEpochMs)
    }

    @Test
    fun `仅时间词加动作词_时间已过_顺延明天`() {
        // now=12:00，10:00 已过 → 明天 10:00
        val body = "上午10点开会"
        val intent = EventIntentExtractor.extract(body, "周国平", knownCompanies, nowEpochMs, zone)
        assertTrue(intent.hasScheduleIntent)
        val expected = ZonedDateTime.of(2026, 8, 20, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, intent.startAtEpochMs)
    }

    @Test
    fun `有3点_不误解析为时间`() {
        // 「3点」前有「有」字 → HOUR_FULL_PATTERN 前后瞻必须拒绝，不得产生时间意图
        val body = "有3点要开会讨论"
        val intent = EventIntentExtractor.extract(body, "周国平", knownCompanies, nowEpochMs, zone)
        assertFalse(intent.hasScheduleIntent)
        assertNull(intent.startAtEpochMs)
    }

    @Test
    fun `早上晚上带分钟_分钟不得丢失`() {
        // 回归：否定后瞻曾含「上」字，「早上9点30分」「晚上8点10分」的数字前是"上"被误伤成数量词，
        // 导致 parseTimeOfDay 失败又静默兜底 9:00——分钟被吃、时刻被改。两个表达都必须精确到分。
        val morning = EventIntentExtractor.extract("明天早上9点30分到就行", "周国平", knownCompanies, nowEpochMs, zone)
        val expectedMorning = ZonedDateTime.of(2026, 8, 20, 9, 30, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expectedMorning, morning.startAtEpochMs)

        val evening = EventIntentExtractor.extract("后天晚上8点10分见", "周国平", knownCompanies, nowEpochMs, zone)
        val expectedEvening = ZonedDateTime.of(2026, 8, 21, 20, 10, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expectedEvening, evening.startAtEpochMs)
    }

    @Test
    fun `只说了日期_默认上午9点`() {
        val body = "明天到公司"
        val intent = EventIntentExtractor.extract(body, "周国平", knownCompanies, nowEpochMs, zone)
        val expected = ZonedDateTime.of(2026, 8, 20, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, intent.startAtEpochMs)
        assertEquals("明天 09:00", intent.timeDescription)
    }

    @Test
    fun `无时间意图的闲聊_不建日程`() {
        val body = "在吗，最近怎么样"
        val intent = EventIntentExtractor.extract(body, "周国平", knownCompanies, nowEpochMs, zone)
        assertFalse(intent.hasScheduleIntent)
        assertFalse(intent.canCreateSchedule)
        assertNull(intent.startAtEpochMs)
    }

    @Test
    fun `地点后缀兜底_无接人动词也识别`() {
        val body = "明天晚上8点到万科云城吃饭"
        val intent = EventIntentExtractor.extract(body, "周国平", knownCompanies, nowEpochMs, zone)
        assertEquals("万科云城", intent.location)
        val expected = ZonedDateTime.of(2026, 8, 20, 20, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, intent.startAtEpochMs)
    }

    @Test
    fun `空正文不解析`() {
        val intent = EventIntentExtractor.extract("  ", "周国平", knownCompanies, nowEpochMs, zone)
        assertFalse(intent.hasScheduleIntent)
    }

    // ---- 双地点分离 / 拜访地点检索 / 对接人候选 / 行程估算 ----

    private val jiuzhoutongAddress = EventIntentExtractor.CompanyAddress(
        company = "九州通医药集团股份有限公司",
        address = "湖北省武汉市汉阳区龙阳大道特8号",
        latitude = 30.5498,
        longitude = 114.2345,
        source = "CONTACT",
    )

    @Test
    fun `双地点分离_拜访地点来自联系人库地址`() {
        val body = "你明天开车来我家万科云城接我，我们一去见九州通的领导。明天早上9点30分到就行"
        val intent = EventIntentExtractor.extract(
            body,
            "周国平",
            knownCompanies,
            nowEpochMs,
            zone,
            knownCompanyAddresses = listOf(jiuzhoutongAddress),
        )
        assertEquals("万科云城", intent.pickupLocation)
        assertEquals("湖北省武汉市汉阳区龙阳大道特8号", intent.visitLocation)
        assertEquals("CONTACT", intent.visitLocationSource)
        // 日程主地点=拜访地点优先
        assertEquals("湖北省武汉市汉阳区龙阳大道特8号", intent.location)
        // 拜访地点已知 → 不再要求确认地址
        assertTrue(intent.needsConfirmation.none { it.contains("拜访地点未知") })
        // 标题区分两地点
        assertTrue(intent.title!!.contains("接周国平（万科云城）"))
        assertTrue(intent.title!!.contains("拜访九州通"))
        assertTrue(intent.note!!.contains("接人：万科云城"))
        assertTrue(intent.note!!.contains("拜访：湖北省武汉市汉阳区龙阳大道特8号"))
    }

    @Test
    fun `拜访地点未知_进待确认`() {
        val body = "明天去见九州通的领导，下午2点"
        val intent = EventIntentExtractor.extract(body, "周国平", knownCompanies, nowEpochMs, zone)
        assertEquals("九州通", intent.company)
        assertNull(intent.visitLocation)
        assertTrue(intent.needsConfirmation.any { it.contains("拜访地点未知") })
    }

    @Test
    fun `对接人候选_按公司匹配联系人库`() {
        val body = "明天去见九州通的领导，下午2点"
        val contacts = listOf(
            EventIntentExtractor.ContactCandidate("c1", "张三", "华中区负责人", "武汉九州通"),
            EventIntentExtractor.ContactCandidate("c2", "李四", "采购总监", "武汉九州通"),
            EventIntentExtractor.ContactCandidate("c3", "王五", "销售", "平凯星辰"),
        )
        val intent = EventIntentExtractor.extract(
            body,
            "周国平",
            knownCompanies,
            nowEpochMs,
            zone,
            contacts = contacts,
        )
        assertEquals(listOf("张三", "李四"), intent.contactCandidates.map { it.name })
        // 有候选时提示从候选中确认
        assertTrue(intent.needsConfirmation.any { it.contains("候选：张三、李四") })
        assertTrue(intent.note!!.contains("对接人候选：张三（华中区负责人）、李四（采购总监）"))
    }

    @Test
    fun `行程估算_有坐标时按距离推出发时间`() {
        val body = "你明天开车来我家万科云城接我，我们一去见九州通的领导。明天早上9点30分到就行"
        // 出发地(光谷) → 接人点(万科云城) → 拜访点(汉阳九州通)，三点连线约 15km 直线
        val departure = EventIntentExtractor.DepartureContext("光谷", 30.5074, 114.3981)
        val pickup = EventIntentExtractor.PickupContext("万科云城", 30.5074, 114.3981)
        val intent = EventIntentExtractor.extract(
            body,
            "周国平",
            knownCompanies,
            nowEpochMs,
            zone,
            knownCompanyAddresses = listOf(jiuzhoutongAddress),
            departure = departure,
            pickupCoordinate = pickup,
        )
        assertNotNull(intent.departAtEpochMs)
        // 出发时间早于 09:30
        assertTrue(intent.departAtEpochMs!! < intent.startAtEpochMs!!)
        assertTrue(intent.travelNote!!.contains("建议"))
        assertTrue(intent.travelNote!!.contains("出发"))
    }

    @Test
    fun `无坐标_按经验值估算出发时间`() {
        val body = "明天早上9点30分到万科云城接我，然后去见九州通领导"
        val intent = EventIntentExtractor.extract(
            body,
            "周国平",
            knownCompanies,
            nowEpochMs,
            zone,
            knownCompanyAddresses = listOf(jiuzhoutongAddress),
        )
        assertNotNull(intent.departAtEpochMs)
        // 09:30 - 75 分钟（30+30+15） = 08:15
        val expected = ZonedDateTime.of(2026, 8, 20, 8, 15, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, intent.departAtEpochMs)
        assertTrue(intent.travelNote!!.contains("经验估算"))
    }

    @Test
    fun `出发位置未知_进待确认`() {
        val body = "明天去见九州通的领导，下午2点"
        val intent = EventIntentExtractor.extract(body, "周国平", knownCompanies, nowEpochMs, zone)
        assertTrue(intent.needsConfirmation.any { it.contains("出发位置未知") })
    }
}
