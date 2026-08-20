package com.zhiban.rebuild.runtime.wakeup

import com.zhiban.rebuild.data.notification.NotificationCandidateEntity
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 唤醒决策器单测：四条件唤醒 / 跳过 / 节流边界 / 夜间免打扰。
 * 全部用固定时间戳驱动（白天正午），避免夜间边界导致的不稳定。
 */
class WakeupDeciderTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val noon: Long = LocalDateTime.of(2026, 8, 19, 12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun candidate(
        body: String?,
        linkedContactId: String? = null,
        suggestedContactId: String? = null,
        identityDriftJson: String? = null,
        createdScheduleId: String? = null,
        senderName: String? = "张三",
        direction: String = "INCOMING",
        platform: String = "WECHAT",
    ) = NotificationCandidateEntity(
        candidateId = "c-${System.nanoTime()}",
        sourceKey = "sk",
        packageName = "com.tencent.mm",
        appLabel = "微信",
        title = null,
        body = body,
        postedAtEpochMs = noon,
        platform = platform,
        conversationTitle = senderName,
        senderName = senderName,
        direction = direction,
        suggestedContactId = suggestedContactId,
        linkedContactId = linkedContactId,
        createdScheduleId = createdScheduleId,
        identityDriftJson = identityDriftJson,
    )

    private fun decide(
        candidate: NotificationCandidateEntity,
        hasOpenCrmOpportunity: Boolean = false,
        nowEpochMs: Long = noon,
        throttle: WakeupThrottle = WakeupThrottle(),
    ) = WakeupDecider.decide(candidate, hasOpenCrmOpportunity, nowEpochMs, throttle)

    // ---- 四条件唤醒 ----

    @Test
    fun `未归因加手机号自述唤醒`() {
        val d = decide(candidate(body = "我是周国平，平凯星辰（北京）科技有限公司武汉分公司 13476110061"))
        assertTrue(d is WakeupDecision.Wake)
        assertEquals("unlinked_identity_self_description", (d as WakeupDecision.Wake).reason)
    }

    @Test
    fun `未归因加我是加公司线索唤醒`() {
        val d = decide(candidate(body = "我是李四，在平凯星辰公司做销售"))
        assertTrue(d is WakeupDecision.Wake)
        assertEquals("unlinked_identity_self_description", (d as WakeupDecision.Wake).reason)
    }

    @Test
    fun `已归因加备注漂移唤醒`() {
        val d = decide(candidate(body = "晚上好", linkedContactId = "ct-1", identityDriftJson = """{"k":1}"""))
        assertTrue(d is WakeupDecision.Wake)
        assertEquals("identity_drift", (d as WakeupDecision.Wake).reason)
    }

    @Test
    fun `已归因加CRM开放机会唤醒`() {
        val d = decide(candidate(body = "在吗", linkedContactId = "ct-1"), hasOpenCrmOpportunity = true)
        assertTrue(d is WakeupDecision.Wake)
        assertEquals("cross_source_crm_opportunity", (d as WakeupDecision.Wake).reason)
    }

    @Test
    fun `时间词加动作词未落日程唤醒`() {
        val d = decide(candidate(body = "明天下午三点来公司聊聊方案吧", linkedContactId = "ct-1"))
        assertTrue(d is WakeupDecision.Wake)
        assertEquals("unscheduled_time_intent", (d as WakeupDecision.Wake).reason)
    }

    // ---- 私人邀约触发（周国平实测：来我家/开车来/接我 之前全部漏唤醒） ----

    @Test
    fun `私人邀约来我家触发日程建议`() {
        val d = decide(candidate(body = "你明天开车来我家吧", linkedContactId = "ct-1"))
        assertTrue(d is WakeupDecision.Wake)
        assertEquals("unscheduled_time_intent", (d as WakeupDecision.Wake).reason)
    }

    @Test
    fun `接送邀约到我家接我触发日程建议`() {
        val d = decide(candidate(body = "明天下午到我家接我一下", linkedContactId = "ct-1"))
        assertTrue(d is WakeupDecision.Wake)
        assertEquals("unscheduled_time_intent", (d as WakeupDecision.Wake).reason)
    }

    @Test
    fun `来公司碰头触发日程建议`() {
        val d = decide(candidate(body = "下周一过来碰个头吧", linkedContactId = "ct-1"))
        assertTrue(d is WakeupDecision.Wake)
        assertEquals("unscheduled_time_intent", (d as WakeupDecision.Wake).reason)
    }

    @Test
    fun `纯寒暄不触发私人邀约`() {
        // 有时间词但无动作词/邀约锚点 → 不唤醒
        val d = decide(candidate(body = "明天天气不错啊", linkedContactId = "ct-1"))
        assertEquals(WakeupDecision.Skip("rules_sufficient"), d)
    }

    // ---- 跳过 ----

    @Test
    fun `未开放平台跳过`() {
        val d = decide(candidate(body = "我是周国平 13476110061", platform = "SMS"))
        assertEquals(WakeupDecision.Skip("platform_not_wakeup_capable"), d)
    }

    @Test
    fun `非入站方向跳过`() {
        val d = decide(candidate(body = "我是周国平 13476110061", direction = "OUTGOING"))
        assertEquals(WakeupDecision.Skip("platform_not_wakeup_capable"), d)
    }

    @Test
    fun `QQ和企业微信可触发主动判断`() {
        listOf("QQ", "WEWORK").forEach { platform ->
            val decision = decide(candidate(body = "我是周国平 13476110061", platform = platform))
            assertTrue("$platform should wake", decision is WakeupDecision.Wake)
        }
    }

    @Test
    fun `夜间免打扰跳过`() {
        val midnight = LocalDateTime.of(2026, 8, 19, 23, 30).atZone(zone).toInstant().toEpochMilli()
        val d = decide(candidate(body = "我是周国平 13476110061"), nowEpochMs = midnight)
        assertEquals(WakeupDecision.Skip("night_quiet_hours"), d)
    }

    @Test
    fun `凌晨免打扰跳过`() {
        val dawn = LocalDateTime.of(2026, 8, 19, 6, 59).atZone(zone).toInstant().toEpochMilli()
        val d = decide(candidate(body = "我是周国平 13476110061"), nowEpochMs = dawn)
        assertEquals(WakeupDecision.Skip("night_quiet_hours"), d)
    }

    @Test
    fun `空正文跳过`() {
        val d = decide(candidate(body = null))
        assertEquals(WakeupDecision.Skip("empty_body"), d)
    }

    @Test
    fun `闲聊无信号已归因跳过`() {
        val d = decide(candidate(body = "好的，收到", linkedContactId = "ct-1"))
        assertEquals(WakeupDecision.Skip("rules_sufficient"), d)
    }

    @Test
    fun `已归因加身份自述唤醒`() {
        val d = decide(candidate(body = "我是周国平，平凯星辰（北京）科技有限公司武汉分公司 13476110061", linkedContactId = "ct-1"))
        assertTrue(d is WakeupDecision.Wake)
        assertEquals("identity_update_candidate", (d as WakeupDecision.Wake).reason)
    }

    @Test
    fun `已落日程的时间意图跳过`() {
        val d = decide(candidate(body = "明天下午三点聊聊方案", linkedContactId = "ct-1", createdScheduleId = "sch-1"))
        assertEquals(WakeupDecision.Skip("rules_sufficient"), d)
    }

    @Test
    fun `未归因无身份自述跳过`() {
        val d = decide(candidate(body = "在吗？有空吗"))
        assertEquals(WakeupDecision.Skip("rules_sufficient"), d)
    }

    // ---- 节流 ----

    @Test
    fun `同联系人30分钟内第二次跳过`() {
        val throttle = WakeupThrottle()
        val c = candidate(body = "在吗", linkedContactId = "ct-1", identityDriftJson = "{}")
        assertTrue(decide(c, throttle = throttle) is WakeupDecision.Wake)
        assertEquals(WakeupDecision.Skip("throttled"), decide(c, throttle = throttle, nowEpochMs = noon + 10 * 60 * 1_000))
        // 30 分钟后恢复
        assertTrue(decide(c, throttle = throttle, nowEpochMs = noon + 31 * 60 * 1_000) is WakeupDecision.Wake)
    }

    @Test
    fun `不同联系人互不影响`() {
        val throttle = WakeupThrottle()
        val a = candidate(body = "在吗", linkedContactId = "ct-1", identityDriftJson = "{}")
        val b = candidate(body = "在吗", linkedContactId = "ct-2", identityDriftJson = "{}")
        assertTrue(decide(a, throttle = throttle) is WakeupDecision.Wake)
        assertTrue(decide(b, throttle = throttle) is WakeupDecision.Wake)
    }

    @Test
    fun `全局每小时上限8次`() {
        val throttle = WakeupThrottle()
        var wakes = 0
        repeat(10) { i ->
            val c = candidate(body = "在吗", linkedContactId = "ct-$i", identityDriftJson = "{}")
            if (decide(c, throttle = throttle, nowEpochMs = noon + i * 1_000L) is WakeupDecision.Wake) wakes++
        }
        assertEquals(8, wakes)
        // 一小时后恢复
        val late = candidate(body = "在吗", linkedContactId = "ct-late", identityDriftJson = "{}")
        assertTrue(decide(late, throttle = throttle, nowEpochMs = noon + 61 * 60 * 1_000) is WakeupDecision.Wake)
    }

    // ---- 纯函数 ----

    @Test
    fun `手机号正则边界`() {
        assertTrue(WakeupDecider.looksLikeIdentitySelfDescription("电话 13307197061"))
        assertFalse(WakeupDecider.looksLikeIdentitySelfDescription("验证码 1133456789012"))
        assertFalse(WakeupDecider.looksLikeIdentitySelfDescription("单号 12345678901"))
    }

    @Test
    fun `我是加公司线索判定`() {
        assertTrue(WakeupDecider.looksLikeIdentitySelfDescription("我是王五，某某科技有限公司的"))
        // “我是”但无公司线索，不唤醒
        assertFalse(WakeupDecider.looksLikeIdentitySelfDescription("我是路过看热闹的"))
    }

    // ---- looksLikeUnscheduledIntent 误触发回归（#22） ----

    @Test
    fun `信号不好回访不误判为日程意图`() {
        // “号”字无数字前缀，不得与“回访”组合误触发
        assertFalse(WakeupDecider.looksLikeUnscheduledIntent("信号不好，回访一下"))
    }

    @Test
    fun `带数字的日期表达仍可唤醒`() {
        assertTrue(WakeupDecider.looksLikeUnscheduledIntent("5号下午拜访张总"))
        assertTrue(WakeupDecider.looksLikeUnscheduledIntent("28号开会"))
    }

    @Test
    fun `非数字前缀的号字不触发`() {
        assertFalse(WakeupDecider.looksLikeUnscheduledIntent("把型号发给客户"))
        assertFalse(WakeupDecider.looksLikeUnscheduledIntent("这句里12345号没动作"))
        // 无动作词，即使有时间词也不触发
        assertFalse(WakeupDecider.looksLikeUnscheduledIntent("5号下午"))
    }
}
