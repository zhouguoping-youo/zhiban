package com.zhiban.rebuild.runtime.tool

import java.security.MessageDigest
import java.text.Normalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ToolConfirmation(val proposalId: String, val payloadRef: String, val revision: Long, val canonicalInputDigest: String)

class ToolPolicyRejectedException(message: String) : IllegalStateException(message)

data class ScheduleCreateToolCall(
    val providerCallId: String,
    val logicalStepId: String,
    val proposalId: String,
    val payloadRef: String,
    val revision: Long,
    val canonicalInputDigest: String,
    val idempotencyKey: String,
    val scheduleId: String,
    val title: String,
    val startAtEpochMs: Long,
    val durationMinutes: Int,
    val note: String?,
    val reminderMinutesBefore: Int? = null,
) {
    init {
        require(providerCallId.isNotBlank() && providerCallId.length <= 128)
        require(logicalStepId.isNotBlank() && logicalStepId.length <= 128)
        require(proposalId.isNotBlank() && payloadRef.isNotBlank())
        require(canonicalInputDigest.matches(Regex("[a-fA-F0-9]{64}")))
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= 256)
        require(scheduleId.isNotBlank() && scheduleId.length <= 128)
        require(title.isNotBlank() && title.length <= 200)
        require(startAtEpochMs > 0)
        require(durationMinutes in 1..1440)
        require(note == null || note.length <= 2_000)
        require(reminderMinutesBefore == null || reminderMinutesBefore in setOf(10, 30, 60, 1_440))
    }
}

data class ConfirmedToolExecutionContext(val runId: String, val ownerId: String, val fencingEpoch: Long, val nowEpochMs: Long)

data class SafeToolResult(val scheduleId: String, val safeResultJson: String)

data class MemoryRememberToolCall(
    val providerCallId: String,
    val logicalStepId: String,
    val proposalId: String,
    val payloadRef: String,
    val revision: Long,
    val canonicalInputDigest: String,
    val idempotencyKey: String,
    val candidateId: String,
    val content: String,
    val memoryType: String,
    val subjectKey: String,
    val predicateKey: String,
) {
    init {
        require(providerCallId.isNotBlank() && providerCallId.length <= 128)
        require(logicalStepId.isNotBlank() && logicalStepId.length <= 128)
        require(proposalId.isNotBlank() && payloadRef.isNotBlank())
        require(canonicalInputDigest.matches(Regex("[a-fA-F0-9]{64}")))
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= 256)
        require(candidateId.isNotBlank())
        require(content.isNotBlank() && content.toByteArray().size <= 8 * 1024)
        require(memoryType in setOf("PREFERENCE", "FACT", "PROJECT_RULE"))
        require(subjectKey.isNotBlank() && subjectKey.length <= 128)
        require(predicateKey.isNotBlank() && predicateKey.length <= 128)
    }
}

object MemoryRememberPlanValidator {
    const val TOOL_NAME = "memory.remember"
    fun validate(planJson: String, stagedContent: String): MemoryRememberToolCall {
        require(planJson.toByteArray().size <= 16 * 1024)
        val value = Json.parseToJsonElement(planJson).jsonObject
        require(value.keys.all { it in ALLOWED }) { "unknown memory plan field" }
        require(value.getValue("toolName").jsonPrimitive.content == TOOL_NAME)
        return MemoryRememberToolCall(
            providerCallId = value.getValue("providerCallId").jsonPrimitive.content,
            logicalStepId = value.getValue("logicalStepId").jsonPrimitive.content,
            proposalId = value.getValue("proposalId").jsonPrimitive.content,
            payloadRef = value.getValue("payloadRef").jsonPrimitive.content,
            revision = value.getValue("revision").jsonPrimitive.content.toLong(),
            canonicalInputDigest = value.getValue("canonicalInputDigest").jsonPrimitive.content,
            idempotencyKey = value.getValue("idempotencyKey").jsonPrimitive.content,
            candidateId = value.getValue("candidateId").jsonPrimitive.content,
            content = stagedContent,
            memoryType = value.getValue("memoryType").jsonPrimitive.content,
            subjectKey = value.getValue("subjectKey").jsonPrimitive.content,
            predicateKey = value.getValue("predicateKey").jsonPrimitive.content,
        )
    }
    private val ALLOWED =
        setOf(
            "toolName", "providerCallId", "logicalStepId", "proposalId", "payloadRef", "revision",
            "canonicalInputDigest", "idempotencyKey", "candidateId", "memoryType", "subjectKey", "predicateKey", "title",
        )
}

fun canonicalMemoryDigest(call: MemoryRememberToolCall): String = sha256(
    listOf(call.content.trim().replace(Regex("\\s+"), " "), call.memoryType, call.subjectKey, call.predicateKey)
        .joinToString("|") { "${it.toByteArray().size}:$it" },
)

fun canonicalMemoryIdempotencyKey(runId: String, attemptId: String, call: MemoryRememberToolCall): String = sha256(
    listOf(
        runId, attemptId, call.providerCallId, call.logicalStepId, MemoryRememberPlanValidator.TOOL_NAME, "1", call.proposalId,
        sha256(
            call.payloadRef,
        ),
        call.revision.toString(), call.canonicalInputDigest,
    ).joinToString("|"),
)

object SchedulePlanValidator {
    fun validate(planJson: String): ScheduleCreateToolCall {
        require(planJson.toByteArray().size <= 16 * 1024) { "plan exceeds limit" }
        val value = Json.parseToJsonElement(planJson).jsonObject
        require(value.keys.all { it in ALLOWED }) { "unknown plan field" }
        require(value.getValue("toolName").jsonPrimitive.content == TOOL_NAME) { "tool is not registered" }
        return ScheduleCreateToolCall(
            providerCallId = value.getValue("providerCallId").jsonPrimitive.content,
            logicalStepId = value.getValue("logicalStepId").jsonPrimitive.content,
            proposalId = value.getValue("proposalId").jsonPrimitive.content,
            payloadRef = value.getValue("payloadRef").jsonPrimitive.content,
            revision = value.getValue("revision").jsonPrimitive.content.toLong(),
            canonicalInputDigest = value.getValue("canonicalInputDigest").jsonPrimitive.content,
            idempotencyKey = value.getValue("idempotencyKey").jsonPrimitive.content,
            scheduleId = value.getValue("scheduleId").jsonPrimitive.content,
            title = value.getValue("title").jsonPrimitive.content,
            startAtEpochMs = value.getValue("startAtEpochMs").jsonPrimitive.content.toLong(),
            durationMinutes = value.getValue("durationMinutes").jsonPrimitive.content.toInt(),
            note = value["note"]?.jsonPrimitive?.content,
            reminderMinutesBefore = value["reminderMinutesBefore"]?.jsonPrimitive?.content?.toInt(),
        )
    }

    const val TOOL_NAME = "calendar.schedule.create"
    private val ALLOWED =
        setOf(
            "toolName", "providerCallId", "logicalStepId", "proposalId", "payloadRef", "revision",
            "canonicalInputDigest", "idempotencyKey", "scheduleId", "title", "startAtEpochMs", "durationMinutes",
            "note", "reminderMinutesBefore",
        )
}

fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** Deterministic change-log row id derived from a write's idempotency key. */
fun changeIdFor(idempotencyKey: String): String = "change-${sha256(idempotencyKey).take(24)}"

/** Deterministic tool-audit row id derived from a write's idempotency key. */
fun auditIdFor(idempotencyKey: String): String = "audit-${sha256(idempotencyKey).take(32)}"

fun canonicalScheduleDigest(call: ScheduleCreateToolCall): String {
    fun normalized(value: String) = Normalizer.normalize(value, Normalizer.Form.NFC)
    val fields = listOf(
        call.scheduleId,
        normalized(call.title),
        call.startAtEpochMs.toString(),
        call.durationMinutes.toString(),
        call.note?.let { "1:${normalized(it)}" } ?: "0",
        call.reminderMinutesBefore?.toString() ?: "0",
    )
    val framed = fields.joinToString(separator = "") { value -> "${value.toByteArray(Charsets.UTF_8).size}:$value" }
    return sha256(framed)
}

fun canonicalToolIdempotencyKey(runId: String, attemptId: String, call: ScheduleCreateToolCall): String = sha256(
    listOf(
        runId, attemptId, call.providerCallId, call.logicalStepId, SchedulePlanValidator.TOOL_NAME, "1",
        call.proposalId, sha256(call.payloadRef), call.revision.toString(), call.canonicalInputDigest, call.scheduleId,
    ).joinToString("|"),
)
