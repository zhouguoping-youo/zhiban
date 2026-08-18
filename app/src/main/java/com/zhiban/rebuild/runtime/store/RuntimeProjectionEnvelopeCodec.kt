package com.zhiban.rebuild.runtime.store

import com.zhiban.rebuild.data.store.RuntimeProjectionEntity
import com.zhiban.rebuild.runtime.spi.RUNTIME_SCHEMA_VERSION
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class RuntimeProjectionEnvelope(val schemaVersion: Int, val producerVersion: String, val payloadJson: String)

internal object RuntimeProjectionEnvelopeCodec {
    fun encode(payloadJson: String, producerVersion: String): String = buildJsonObject {
        put("snapshotSchemaVersion", RUNTIME_SCHEMA_VERSION)
        put("snapshotProducerVersion", producerVersion)
        put("payloadJson", payloadJson)
    }.toString()

    fun decode(value: String): RuntimeProjectionEnvelope? = runCatching {
        val objectValue = Json.parseToJsonElement(value).jsonObject
        RuntimeProjectionEnvelope(
            schemaVersion = objectValue.getValue("snapshotSchemaVersion").jsonPrimitive.intOrNull
                ?: return@runCatching null,
            producerVersion = objectValue.getValue("snapshotProducerVersion").jsonPrimitive.content,
            payloadJson = objectValue.getValue("payloadJson").jsonPrimitive.content,
        )
    }.getOrNull()
}

internal fun RuntimeProjectionEntity.decodedForRuntime(): RuntimeProjectionEntity =
    RuntimeProjectionEnvelopeCodec.decode(snapshotJson)?.let { copy(snapshotJson = it.payloadJson) } ?: this
