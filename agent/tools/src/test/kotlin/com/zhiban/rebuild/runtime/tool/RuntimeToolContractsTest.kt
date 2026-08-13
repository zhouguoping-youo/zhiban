package com.zhiban.rebuild.runtime.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the canonical-digest, idempotency-key and plan-validation contracts in [RuntimeToolContracts].
 * These are pure, deterministic derivations that tool governance relies on for de-duplication and
 * undo, so any silent change in framing, normalization or field handling is a regression.
 */
class RuntimeToolContractsTest {
    private val digest64 = "a".repeat(64)

    private fun memoryCall(content: String = "喜欢早上开会", memoryType: String = "PREFERENCE", subjectKey: String = "周国平", predicateKey: String = "meeting-time") =
        MemoryRememberToolCall(
            providerCallId = "call-1",
            logicalStepId = "step-1",
            proposalId = "prop-1",
            payloadRef = "ref-1",
            revision = 1L,
            canonicalInputDigest = digest64,
            idempotencyKey = "idem-1",
            candidateId = "cand-1",
            content = content,
            memoryType = memoryType,
            subjectKey = subjectKey,
            predicateKey = predicateKey,
        )

    private fun scheduleCall(
        scheduleId: String = "sched-1",
        title: String = "周五评审",
        note: String? = null,
        reminderMinutesBefore: Int? = null,
        crmActionId: String? = null,
    ) =
        ScheduleCreateToolCall(
            providerCallId = "call-1",
            logicalStepId = "step-1",
            proposalId = "prop-1",
            payloadRef = "ref-1",
            revision = 1L,
            canonicalInputDigest = digest64,
            idempotencyKey = "idem-1",
            scheduleId = scheduleId,
            title = title,
            startAtEpochMs = 1_000L,
            durationMinutes = 30,
            note = note,
            reminderMinutesBefore = reminderMinutesBefore,
            crmActionId = crmActionId,
        )

    private fun schedulePlan(
        scheduleId: String = "sched-1",
        title: String = "周五评审",
        toolName: String = SchedulePlanValidator.TOOL_NAME,
        note: String? = "带笔记本",
        reminder: Int? = 30,
        crmActionId: String? = null,
        extraField: String? = null,
        padNoteTo: Int = 0,
    ): String {
        val noteValue = (note ?: "").let { if (padNoteTo > 0) it + "x".repeat(padNoteTo) else it }
        val fields = buildList {
            add(""""toolName":"$toolName"""")
            add(""""providerCallId":"call-1"""")
            add(""""logicalStepId":"step-1"""")
            add(""""proposalId":"prop-1"""")
            add(""""payloadRef":"ref-1"""")
            add(""""revision":"1"""")
            add(""""canonicalInputDigest":"$digest64"""")
            add(""""idempotencyKey":"idem-1"""")
            add(""""scheduleId":"$scheduleId"""")
            add(""""title":"$title"""")
            add(""""startAtEpochMs":"1000"""")
            add(""""durationMinutes":"30"""")
            if (noteValue.isNotEmpty() || padNoteTo > 0) add(""""note":"$noteValue"""")
            reminder?.let { add(""""reminderMinutesBefore":"$it"""") }
            crmActionId?.let { add(""""crmActionId":"$it"""") }
            extraField?.let { add(it) }
        }
        return fields.joinToString(prefix = "{", postfix = "}")
    }

    @Test
    fun `sha256 is 64 lowercase hex chars and deterministic`() {
        assertEquals(64, sha256("x").length)
        assertEquals(sha256("知伴"), sha256("知伴"))
        assertNotEquals(sha256("a"), sha256("b"))
        assertTrue(sha256("x").all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `relationship tool schema follows canonical selectable taxonomy`() {
        val definition = RuntimeToolCatalog.production()
            .requireRegistered("relationship.createCandidate")
            .providerDefinitionJson
        val parameters = Json.parseToJsonElement(definition)
            .jsonObject.getValue("function")
            .jsonObject.getValue("parameters")
            .jsonObject
        val relationshipTypes = parameters.getValue("properties")
            .jsonObject
            .getValue("relationType")
            .jsonObject.getValue("enum")
            .jsonArray
            .map { it.jsonPrimitive.content }

        assertTrue("MANAGER" in relationshipTypes)
        assertTrue("CLASSMATE" in relationshipTypes)
        assertTrue("UNKNOWN" !in relationshipTypes)
        assertTrue("evidenceBasis" !in parameters.getValue("required").jsonArray.map { it.jsonPrimitive.content })
        assertTrue("evidenceBasis" !in parameters.getValue("properties").jsonObject)
    }

    @Test
    fun `memory digest collapses internal and edge whitespace to a single space`() {
        assertEquals(
            canonicalMemoryDigest(memoryCall(content = "喜欢   早上\t开会")),
            canonicalMemoryDigest(memoryCall(content = " 喜欢 早上 开会 ")),
        )
    }

    @Test
    fun `memory digest distinguishes content, type, subject and predicate`() {
        val base = canonicalMemoryDigest(memoryCall())
        assertNotEquals(base, canonicalMemoryDigest(memoryCall(content = "喜欢下午开会")))
        assertNotEquals(base, canonicalMemoryDigest(memoryCall(memoryType = "FACT")))
        assertNotEquals(base, canonicalMemoryDigest(memoryCall(subjectKey = "别人")))
        assertNotEquals(base, canonicalMemoryDigest(memoryCall(predicateKey = "other")))
    }

    @Test
    fun `schedule digest and plan bind the optional CRM action`() {
        val withoutAction = scheduleCall()
        val withAction = scheduleCall(crmActionId = "action-1")

        assertNotEquals(canonicalScheduleDigest(withoutAction), canonicalScheduleDigest(withAction))
        assertEquals("action-1", SchedulePlanValidator.validate(schedulePlan(crmActionId = "action-1")).crmActionId)
    }

    @Test
    fun `schedule digest is framing-safe and rejects naive concatenation collisions`() {
        // Without length framing, scheduleId="ab"+title="c" would collide with "a"+"bc".
        val ab = scheduleCall(scheduleId = "ab", title = "c")
        val aBc = scheduleCall(scheduleId = "a", title = "bc")
        assertNotEquals(canonicalScheduleDigest(ab), canonicalScheduleDigest(aBc))
    }

    @Test
    fun `schedule digest changes with note presence and reminder`() {
        val base = scheduleCall()
        assertNotEquals(canonicalScheduleDigest(base), canonicalScheduleDigest(base.copy(note = "带笔记本")))
        assertNotEquals(canonicalScheduleDigest(base), canonicalScheduleDigest(base.copy(reminderMinutesBefore = 30)))
    }

    @Test
    fun `schedule digest NFC-normalizes composed and decomposed forms`() {
        val composed = scheduleCall(title = "café") // U+00E9
        val decomposed = scheduleCall(title = "café") // e + combining acute
        assertEquals(canonicalScheduleDigest(composed), canonicalScheduleDigest(decomposed))
    }

    @Test
    fun `idempotency keys are deterministic and scoped by run and attempt`() {
        val call = scheduleCall()
        assertEquals(
            canonicalToolIdempotencyKey("run-1", "attempt-1", call),
            canonicalToolIdempotencyKey("run-1", "attempt-1", call),
        )
        assertNotEquals(
            canonicalToolIdempotencyKey("run-1", "attempt-1", call),
            canonicalToolIdempotencyKey("run-2", "attempt-1", call),
        )
        val mem = memoryCall()
        assertNotEquals(
            canonicalMemoryIdempotencyKey("run-1", "attempt-1", mem),
            canonicalMemoryIdempotencyKey("run-1", "attempt-2", mem),
        )
    }

    @Test
    fun `schedule plan validator accepts with and without optional fields`() {
        SchedulePlanValidator.validate(schedulePlan(note = null, reminder = null))
        SchedulePlanValidator.validate(schedulePlan(note = "提醒", reminder = 60))
    }

    @Test
    fun `schedule plan validator rejects unknown field, wrong tool and oversize payload`() {
        assertThrows(IllegalArgumentException::class.java) {
            SchedulePlanValidator.validate(schedulePlan(extraField = "\"evil\":1"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SchedulePlanValidator.validate(schedulePlan(toolName = "calendar.schedule.delete"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SchedulePlanValidator.validate(schedulePlan(padNoteTo = 17_000))
        }
    }

    @Test
    fun `schedule call invariants reject bad digest, duration, reminder and overlong title`() {
        assertThrows(IllegalArgumentException::class.java) {
            scheduleCall().copy(canonicalInputDigest = "not-a-hex")
        }
        assertThrows(IllegalArgumentException::class.java) {
            scheduleCall().copy(durationMinutes = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            scheduleCall().copy(reminderMinutesBefore = 5)
        }
        assertThrows(IllegalArgumentException::class.java) {
            scheduleCall().copy(title = "x".repeat(201))
        }
    }

    @Test
    fun `memory call invariants reject blank content, bad type and oversize content`() {
        assertThrows(IllegalArgumentException::class.java) {
            memoryCall().copy(content = "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            memoryCall().copy(memoryType = "OPINION")
        }
        assertThrows(IllegalArgumentException::class.java) {
            memoryCall().copy(content = "x".repeat(8 * 1024 + 1))
        }
    }
}
