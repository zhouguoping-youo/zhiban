package com.zhiban.rebuild.runtime.kernel

import com.zhiban.rebuild.runtime.context.IntentLabel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicToolSummaryTest {
    @Test
    fun `candidate lead summary states it is not a formal opportunity`() {
        val summary = deterministicToolSummary(
            "crm.lead.createCandidate",
            """{"targetId":"lead-1","status":"created","candidatePool":true}""",
        )

        assertTrue(summary.contains("候选线索"))
        assertTrue(summary.contains("尚未转为正式机会"))
    }

    @Test
    fun `candidate lead completes observation without exposing a follow-up opportunity write`() {
        assertTrue(
            shouldCompleteObservationDeterministically(
                "crm.lead.createCandidate",
                IntentLabel.SALES_CRM,
            ),
        )
        assertFalse(
            shouldCompleteObservationDeterministically(
                "crm.activity.append",
                IntentLabel.SALES_CRM,
            ),
        )
    }

    private val zone: ZoneId = ZoneId.systemDefault()

    @Test
    fun scheduleCreateSummaryStatesVerifiedDateTimeWhenPresent() {
        val startAt = Instant.parse("2026-08-08T09:00:00Z").toEpochMilli() // 17:00 Asia/Shanghai
        val result = buildJsonObject {
            put("scheduleId", "schedule-x")
            put("status", "created")
            put("title", "提醒我下楼拿快递")
            put("startAtEpochMs", startAt)
            put("durationMinutes", 60)
        }.toString()

        val summary = deterministicToolSummary("calendar.schedule.create", result)

        val expectedTime = DateTimeFormatter.ofPattern("M月d日 HH:mm")
            .format(Instant.ofEpochMilli(startAt).atZone(zone))
        assertEquals("已创建日程“提醒我下楼拿快递”，$expectedTime，时长 60 分钟，可在日历中查看；如有需要，可以撤销这次操作。", summary)
        assertTrue(summary.contains(expectedTime))
    }

    @Test
    fun scheduleCreateSummaryFallsBackToGenericLineWhenTimeMissing() {
        // Older persisted results (or idempotent replays) lack the echoed wall-clock fields; the
        // summary must still be honest and not invent a date.
        val result = buildJsonObject {
            put("scheduleId", "schedule-x")
            put("status", "created")
        }.toString()

        assertEquals("日程已创建，可在日历中查看；如有需要，可以撤销这次操作。", deterministicToolSummary("calendar.schedule.create", result))
    }

    @Test
    fun scheduleCreateSummaryOmitsDurationWhenAbsent() {
        val startAt = Instant.parse("2026-08-08T09:00:00Z").toEpochMilli()
        val result = buildJsonObject {
            put("title", "开会")
            put("startAtEpochMs", startAt)
        }.toString()

        val summary = deterministicToolSummary("calendar.schedule.create", result)
        assertTrue(summary.startsWith("已创建日程“开会”，"))
        assertTrue(!summary.contains("时长"))
    }
}
