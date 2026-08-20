package com.zhiban.rebuild.runtime.wakeup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeupStructuredOutputCodecTest {
    @Test
    fun `decodes constrained wakeup judgment and schedule elements`() {
        val decoded = requireNotNull(
            WakeupStructuredOutputCodec.decode(
                """
                {
                  "intent":"SCHEDULE",
                  "confidence":0.92,
                  "suggestion":"建议确认后加入日历。",
                  "scheduleTitle":"与客户开会",
                  "scheduleTimeExpression":"明晚十点",
                  "scheduleDurationMinutes":30,
                  "scheduleLocation":null
                }
                """.trimIndent(),
            ),
        )

        assertEquals("SCHEDULE", decoded.intent)
        assertEquals(0.92, decoded.confidence, 0.0)
        assertEquals("明晚十点", decoded.schedule?.timeExpression)
        assertEquals(30, decoded.schedule?.durationMinutes)
    }

    @Test
    fun `rejects malformed intent and confidence instead of trusting provider prose`() {
        assertNull(WakeupStructuredOutputCodec.decode("建议马上处理"))
        assertNull(
            WakeupStructuredOutputCodec.decode(
                """{"intent":"DELETE","confidence":2,"suggestion":"处理","scheduleTitle":null,"scheduleTimeExpression":null,"scheduleDurationMinutes":null,"scheduleLocation":null}""",
            ),
        )
    }

    @Test
    fun `bounds suggestion text before persistence`() {
        val longText = "建".repeat(200)
        val decoded = requireNotNull(
            WakeupStructuredOutputCodec.decode(
                """{"intent":"FOLLOW_UP","confidence":0.6,"suggestion":"$longText","scheduleTitle":null,"scheduleTimeExpression":null,"scheduleDurationMinutes":null,"scheduleLocation":null}""",
            ),
        )

        assertEquals(150, decoded.suggestion.length)
        assertTrue(WakeupStructuredOutputCodec.RESPONSE_SCHEMA.contains("scheduleTimeExpression"))
    }
}
