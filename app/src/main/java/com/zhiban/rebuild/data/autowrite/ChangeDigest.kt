package com.zhiban.rebuild.data.autowrite

import com.zhiban.rebuild.data.agent.ScheduleEntity
import com.zhiban.rebuild.data.autowrite.canonicalChangeDigest
import com.zhiban.rebuild.data.autowrite.changeDigestMatches
import com.zhiban.rebuild.data.crm.CrmActivityEntity
import com.zhiban.rebuild.data.crm.CrmLeadEntity
import com.zhiban.rebuild.data.crm.CrmNextActionEntity
import com.zhiban.rebuild.data.crm.CrmOpportunityEntity
import com.zhiban.rebuild.data.facts.FactEntity
import com.zhiban.rebuild.foundation.sha256
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val changeDigestJson = Json {
    encodeDefaults = true
    explicitNulls = true
}

internal fun canonicalChangeDigest(value: FactEntity): String = sha256(changeDigestJson.encodeToString(value))

internal fun canonicalChangeDigest(value: CrmLeadEntity): String = sha256(changeDigestJson.encodeToString(value))

internal fun canonicalChangeDigest(value: CrmActivityEntity): String = sha256(changeDigestJson.encodeToString(value))

internal fun canonicalChangeDigest(value: CrmNextActionEntity): String = sha256(changeDigestJson.encodeToString(value))

internal fun canonicalChangeDigest(value: CrmOpportunityEntity): String = sha256(changeDigestJson.encodeToString(value))

internal fun canonicalChangeDigest(value: ScheduleEntity): String = sha256(
    buildJsonObject {
        put("id", value.id)
        put("title", value.title)
        put("startAtEpochMs", value.startAtEpochMs)
        put("durationMinutes", value.durationMinutes)
        put("note", value.note)
        put("reminderMinutesBefore", value.reminderMinutesBefore)
        put("createdByRunId", value.createdByRunId)
        put("createdByRuntimeRunId", value.createdByRuntimeRunId)
        put("createdByRuntimeAttemptId", value.createdByRuntimeAttemptId)
        put("createdAtEpochMs", value.createdAtEpochMs)
        put("updatedAtEpochMs", value.updatedAtEpochMs)
    }.toString(),
)

/**
 * Rows written before the canonical JSON migration used data-class `toString()`.
 * Keep that digest readable so the migration never takes away a user's existing undo.
 */
internal fun changeDigestMatches(stored: String?, canonical: String, legacyValue: Any): Boolean =
    stored == canonical || stored == sha256(legacyValue.toString())
