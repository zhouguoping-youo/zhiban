package com.zhiban.rebuild.ui.tabs

import com.zhiban.rebuild.data.notification.NotificationCandidateEntity
import com.zhiban.rebuild.data.notification.ScheduleInsight

internal data class NotificationCandidateReview(
    val headline: String,
    val reason: String,
    val contactLine: String?,
    val scheduleLine: String?,
    val evidence: String?,
)

/** Converts raw perception output into the one unresolved decision a person needs to review. */
internal fun buildNotificationCandidateReview(candidate: NotificationCandidateEntity, matchedContactName: String?): NotificationCandidateReview {
    val schedule = ScheduleInsight.from(candidate)
    val needsContact = candidate.linkedContactId == null && !candidate.senderName.isNullOrBlank()
    val needsSchedule = schedule != null && candidate.createdScheduleId == null
    return NotificationCandidateReview(
        headline = reviewHeadline(needsContact, needsSchedule, matchedContactName),
        reason = reviewReason(candidate, schedule, needsContact, needsSchedule),
        contactLine = reviewContactLine(candidate, matchedContactName),
        scheduleLine = schedule?.takeIf { needsSchedule }?.let { "安排 · ${formatMessageSchedule(it.startAtEpochMs)}" },
        evidence = candidate.body?.replace(Regex("\\s+"), " ")?.trim()?.take(160)?.takeIf(String::isNotBlank),
    )
}

private fun reviewHeadline(needsContact: Boolean, needsSchedule: Boolean, matchedContactName: String?): String = when {
    needsContact && needsSchedule && matchedContactName != null -> "可能是$matchedContactName，并识别到一项安排"
    needsContact && needsSchedule -> "识别到一项安排，还不能确定联系人"
    needsContact && matchedContactName != null -> "可能是$matchedContactName"
    needsContact -> "识别到一位联系人，但身份还不唯一"
    needsSchedule -> "联系人已确定，安排仍需确认"
    else -> "还有一处信息需要确认"
}

private fun reviewReason(candidate: NotificationCandidateEntity, schedule: ScheduleInsight?, needsContact: Boolean, needsSchedule: Boolean): String = when {
    candidate.isGroupChat && needsContact -> "群聊显示名可能重名，知伴没有直接写入联系人"
    candidate.suggestedContactId != null && needsContact -> "现有资料只支持一个可能匹配，还不足以安全自动关联"
    needsContact -> "联系人库中没有找到可唯一验证的身份"
    needsSchedule && schedule?.confidence?.let { it < 0.98 } == true -> "时间表达仍有歧义，知伴没有直接写入日历"
    needsSchedule -> "检测到时间冲突或上下文仍需核对，知伴没有直接写入日历"
    else -> "知伴无法从现有证据得到唯一结论"
}

private fun reviewContactLine(candidate: NotificationCandidateEntity, matchedContactName: String?): String? = when {
    candidate.linkedContactId != null && matchedContactName != null -> "已关联 · $matchedContactName"
    matchedContactName != null -> "可能联系人 · $matchedContactName"
    !candidate.senderName.isNullOrBlank() -> "消息中的人 · ${candidate.senderName}"
    else -> null
}
