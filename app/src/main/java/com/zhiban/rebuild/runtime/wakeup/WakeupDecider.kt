package com.zhiban.rebuild.runtime.wakeup

import com.zhiban.rebuild.data.notification.MessagePlatformCapabilities
import com.zhiban.rebuild.data.notification.NotificationCandidateEntity
import java.time.LocalTime

/**
 * 唤醒决策器（纯逻辑 + 进程内节流状态）：规则流水线跑完之后，裁决"这个事件
 * 是否值得唤醒 LLM 综合判断"。规则能处理的低风险事件不唤醒；四种信号才唤醒：
 *
 * 1. 未归因 + 正文含身份自述（姓名/手机号/公司）——陌生人建档/补全场景
 * 2. 已归因 + 备注漂移——需要判断映射是否要更新
 * 3. 正文含时间/待办意图但规则层未落日程——时间模糊需推断或澄清
 * 4. 已归因 + 跨源信号（CRM 有进行中机会）——需要综合"下一步"
 *
 * 节流闸门：同联系人 30 分钟最多 1 次；全局每小时上限 8 次；夜间（23:00–07:00）
 * 完全不唤醒（保守的免打扰，未来可放宽为"只建议不执行"）。
 */
internal object WakeupDecider {

    private val PHONE_PATTERN = Regex("(?<!\\d)1[3-9]\\d{9}(?!\\d)")
    private val SELF_INTRO_PATTERN = Regex("我是[^，。,\\.\\n]{2,20}")
    private val COMPANY_HINT_PATTERN = Regex("公司|科技|集团|有限|分公司|办事处")
    private val TIME_WORDS = listOf("明天", "后天", "下周", "周一", "周二", "周三", "周四", "周五", "周六", "周日", "上午", "下午", "晚上", "点")

    /** 日期表达「5号」「28号」——必须带数字前缀，避免「信号不好回访」里的「号」误触发。 */
    private val DAY_NUMBER_PATTERN = Regex("(?<!\\d)\\d{1,2}号")
    private val ACTION_WORDS = listOf("见", "聊", "开会", "拜访", "吃饭", "安排", "提醒", "回访", "对接", "签", "报价", "方案")

    /**
     * 私人邀约/接送动词锚点：涵盖「来我家」「到我家」「来接我」「过来」「开车来」「去接」等
     * 生活场景邀约。与时间词（明天/后天/下周/X点）组合成 unscheduled_time_intent 唤醒信号，
     * 让「你明天开车来我家」这类私人日程也能触发建议（此前 ACTION_WORDS 只有商务词，私人邀约
     * 全部漏唤醒，周国平实测反馈）。
     */
    private val PRIVATE_INVITE_ANCHORS = listOf(
        "来我家", "来家里", "到我家", "到家里", "来接", "接我", "接一下", "过来", "开车来",
        "打车来", "到我这", "来我这", "来一趟", "去一趟", "碰头", "聚一下", "吃饭", "吃个饭",
        "来吃饭", "到公司", "来公司", "到我公司", "来单位", "到单位",
    )

    fun decide(candidate: NotificationCandidateEntity, hasOpenCrmOpportunity: Boolean, nowEpochMs: Long, throttle: WakeupThrottle): WakeupDecision {
        if (!candidate.incomingAndCapable()) return WakeupDecision.Skip("platform_not_wakeup_capable")
        if (isNight(nowEpochMs)) return WakeupDecision.Skip("night_quiet_hours")

        val body = candidate.body.orEmpty()
        if (body.isBlank()) return WakeupDecision.Skip("empty_body")

        val linkedContactId = candidate.linkedContactId ?: candidate.suggestedContactId
        val reason = resolveWakeReason(candidate, body, linkedContactId, hasOpenCrmOpportunity)
            ?: return WakeupDecision.Skip("rules_sufficient")

        val throttleKey = linkedContactId ?: "unlinked:${candidate.senderName ?: candidate.conversationTitle ?: "unknown"}"
        if (!throttle.tryAcquire(throttleKey, nowEpochMs)) return WakeupDecision.Skip("throttled")
        return WakeupDecision.Wake(reason, linkedContactId)
    }

    private fun resolveWakeReason(candidate: NotificationCandidateEntity, body: String, linkedContactId: String?, hasOpenCrmOpportunity: Boolean): String? =
        when {
            linkedContactId == null && looksLikeIdentitySelfDescription(body) -> "unlinked_identity_self_description"

            linkedContactId == null -> null

            candidate.identityDriftJson != null -> "identity_drift"

            hasOpenCrmOpportunity -> "cross_source_crm_opportunity"

            looksLikeUnscheduledIntent(body) && candidate.createdScheduleId == null -> "unscheduled_time_intent"

            // 已归因联系人发来身份/公司/联系方式自述，规则层无法判断是否需要补全/覆盖，
            // 唤醒 LLM 做综合判断（例如对方主动更新手机号、职位、公司等）。
            looksLikeIdentitySelfDescription(body) -> "identity_update_candidate"

            else -> null
        }

    private fun NotificationCandidateEntity.incomingAndCapable(): Boolean =
        direction == "INCOMING" && MessagePlatformCapabilities.forPlatform(platform).proactiveWakeup

    /** 身份自述：手机号、或"我是XXX"+ 公司线索、或正文出现"这是我的名片/联系方式"。 */
    internal fun looksLikeIdentitySelfDescription(body: String): Boolean {
        if (PHONE_PATTERN.containsMatchIn(body)) return true
        val intro = SELF_INTRO_PATTERN.find(body)?.value
        if (intro != null && COMPANY_HINT_PATTERN.containsMatchIn(body)) return true
        return false
    }

    /** 未落日程的待办意图：时间词 + 动作词双命中（与既有本地闸门同一设计哲学，避免误唤醒）。 */
    internal fun looksLikeUnscheduledIntent(body: String): Boolean = (TIME_WORDS.any(body::contains) || DAY_NUMBER_PATTERN.containsMatchIn(body)) &&
        (ACTION_WORDS.any(body::contains) || PRIVATE_INVITE_ANCHORS.any(body::contains))

    private fun isNight(nowEpochMs: Long): Boolean {
        val hour = LocalTime.ofInstant(java.time.Instant.ofEpochMilli(nowEpochMs), java.time.ZoneId.systemDefault()).hour
        return hour >= QUIET_HOURS_START || hour < QUIET_HOURS_END
    }

    private const val QUIET_HOURS_START = 23
    private const val QUIET_HOURS_END = 7
}

sealed interface WakeupDecision {
    data class Wake(val reason: String, val contactId: String?) : WakeupDecision
    data class Skip(val reason: String) : WakeupDecision
}

/** 进程内节流状态：同联系人 30 分钟 + 全局每小时 8 次。进程重启即清零，可接受（唤醒是尽力而为的增值路径）。 */
internal class WakeupThrottle(
    private val perContactWindowMs: Long = 30L * 60 * 1_000,
    private val globalWindowMs: Long = 60L * 60 * 1_000,
    private val globalLimit: Int = 8,
) {
    private val lastWakePerContact = HashMap<String, Long>()
    private val globalWakes = ArrayDeque<Long>()

    @Synchronized
    fun tryAcquire(contactKey: String, nowEpochMs: Long): Boolean {
        lastWakePerContact[contactKey]?.let { last ->
            if (nowEpochMs - last < perContactWindowMs) return false
        }
        while (globalWakes.isNotEmpty() && nowEpochMs - globalWakes.first() >= globalWindowMs) globalWakes.removeFirst()
        if (globalWakes.size >= globalLimit) return false
        lastWakePerContact[contactKey] = nowEpochMs
        globalWakes.addLast(nowEpochMs)
        return true
    }
}
