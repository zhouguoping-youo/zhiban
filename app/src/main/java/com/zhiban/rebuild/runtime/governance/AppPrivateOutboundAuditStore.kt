package com.zhiban.rebuild.runtime.governance

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zhiban.rebuild.runtime.provider.OutboundAuditEvent
import com.zhiban.rebuild.runtime.provider.OutboundAuditOutcome
import com.zhiban.rebuild.runtime.provider.OutboundAuditSink
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

private val Context.outboundAuditDataStore by preferencesDataStore("outbound_data_audit")

/** Metadata-only record. It never contains prompts, message bodies, contact fields or attachment bytes. */
data class OutboundAuditRecord(
    val requestDigest: String,
    val channel: String,
    val purposes: Set<String>,
    val sensitivities: Set<String>,
    val messageCount: Int,
    val attachmentCount: Int,
    val redactedMessageCount: Int,
    val omittedMessageCount: Int,
    val occurredAtEpochMs: Long,
    val outcome: String,
    val byteCount: Long,
)

@Singleton
class AppPrivateOutboundAuditStore @Inject constructor(@ApplicationContext private val context: Context) : OutboundAuditSink {
    private val eventsKey = stringPreferencesKey("events_v1")

    val records: Flow<List<OutboundAuditRecord>> = context.outboundAuditDataStore.data.map { values ->
        decode(values[eventsKey])
    }

    override suspend fun record(event: OutboundAuditEvent) {
        val record = OutboundAuditRecord(
            requestDigest = digest(event.requestId),
            channel = event.channel.name,
            purposes = event.purposes.mapTo(sortedSetOf()) { it.name },
            sensitivities = event.sensitivities.mapTo(sortedSetOf()) { it.name },
            messageCount = event.messageCount,
            attachmentCount = event.attachmentCount,
            redactedMessageCount = event.redactedMessageCount,
            omittedMessageCount = event.omittedMessageCount,
            occurredAtEpochMs = event.occurredAtEpochMs,
            outcome = event.outcome.name,
            byteCount = event.byteCount,
        )
        context.outboundAuditDataStore.edit { values ->
            values[eventsKey] = encode((decode(values[eventsKey]) + record).takeLast(MAX_RECORDS))
        }
    }

    suspend fun clear() {
        context.outboundAuditDataStore.edit { it.remove(eventsKey) }
    }

    private fun encode(records: List<OutboundAuditRecord>): String = buildJsonArray {
        records.forEach { record ->
            add(
                buildJsonObject {
                    put("requestDigest", record.requestDigest)
                    put("channel", record.channel)
                    put("purposes", buildJsonArray { record.purposes.forEach { add(JsonPrimitive(it)) } })
                    put("sensitivities", buildJsonArray { record.sensitivities.forEach { add(JsonPrimitive(it)) } })
                    put("messageCount", record.messageCount)
                    put("attachmentCount", record.attachmentCount)
                    put("redactedMessageCount", record.redactedMessageCount)
                    put("omittedMessageCount", record.omittedMessageCount)
                    put("occurredAtEpochMs", record.occurredAtEpochMs)
                    put("outcome", record.outcome)
                    put("byteCount", record.byteCount)
                },
            )
        }
    }.toString()

    private fun decode(raw: String?): List<OutboundAuditRecord> = runCatching {
        raw?.let(Json::parseToJsonElement)?.jsonArray.orEmpty().mapNotNull { element ->
            decodeRecord(element.jsonObject)
        }
    }.getOrDefault(emptyList())

    private fun decodeRecord(value: JsonObject): OutboundAuditRecord? = runCatching {
        OutboundAuditRecord(
            requestDigest = value.getValue("requestDigest").jsonPrimitive.content,
            channel = value.getValue("channel").jsonPrimitive.content,
            purposes = value.getValue("purposes").jsonArray.mapTo(sortedSetOf()) { it.jsonPrimitive.content },
            sensitivities = value.getValue("sensitivities").jsonArray.mapTo(sortedSetOf()) { it.jsonPrimitive.content },
            messageCount = value.getValue("messageCount").jsonPrimitive.int,
            attachmentCount = value.getValue("attachmentCount").jsonPrimitive.int,
            redactedMessageCount = value.getValue("redactedMessageCount").jsonPrimitive.int,
            omittedMessageCount = value.getValue("omittedMessageCount").jsonPrimitive.int,
            occurredAtEpochMs = value.getValue("occurredAtEpochMs").jsonPrimitive.long,
            outcome = value["outcome"]?.jsonPrimitive?.content
                ?: OutboundAuditOutcome.EXPORT_ATTEMPTED.name,
            byteCount = value["byteCount"]?.jsonPrimitive?.long ?: 0,
        )
    }.getOrNull()

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(24)

    private companion object {
        const val MAX_RECORDS = 200
    }
}
