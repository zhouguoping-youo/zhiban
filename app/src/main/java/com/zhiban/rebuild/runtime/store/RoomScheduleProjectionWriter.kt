package com.zhiban.rebuild.runtime.store

import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.agent.ScheduleEntity
import com.zhiban.rebuild.data.facts.FactEntity
import com.zhiban.rebuild.data.facts.FactIndex
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class RoomScheduleProjectionWriter(private val database: AgentDatabase) {
    fun scheduleJson(value: ScheduleEntity): String = buildJsonObject {
        put("id", value.id)
        put("title", value.title)
        put("startAtEpochMs", value.startAtEpochMs)
        put("durationMinutes", value.durationMinutes)
        value.reminderMinutesBefore?.let { put("reminderMinutesBefore", it) }
        value.note?.let { put("note", it) }
        value.createdByRunId?.let { put("createdByRunId", it) }
        value.createdByRuntimeRunId?.let { put("createdByRuntimeRunId", it) }
        value.createdByRuntimeAttemptId?.let { put("createdByRuntimeAttemptId", it) }
        put("createdAtEpochMs", value.createdAtEpochMs)
        put("updatedAtEpochMs", value.updatedAtEpochMs)
        put("status", value.status)
        value.outcomeNote?.let { put("outcomeNote", it) }
        value.completedAtEpochMs?.let { put("completedAtEpochMs", it) }
    }.toString()

    suspend fun putScheduleFact(value: ScheduleEntity, runId: String, nowEpochMs: Long) {
        FactIndex(database).upsert(
            FactEntity(
                factId = "schedule:${value.id}",
                factType = "CALENDAR_EVENT",
                textContent = buildString {
                    append(value.title).append("，开始时间=").append(value.startAtEpochMs)
                    append("，时长=").append(value.durationMinutes).append("分钟")
                    value.note?.takeIf(String::isNotBlank)?.let { append("，备注=").append(it) }
                },
                structuredDataJson = buildJsonObject {
                    put("startAtEpochMs", value.startAtEpochMs)
                    put("durationMinutes", value.durationMinutes)
                }.toString(),
                sourceType = "AGENT_DOMAIN_WRITE",
                sourceRef = runId,
                contactId = null,
                skillId = null,
                confidence = 1.0,
                sensitivity = "PERSONAL",
                status = "ACTIVE",
                ttlDays = 0,
                expiresAtEpochMs = null,
                createdAtEpochMs = value.createdAtEpochMs,
                updatedAtEpochMs = nowEpochMs,
            ),
        )
    }
}
