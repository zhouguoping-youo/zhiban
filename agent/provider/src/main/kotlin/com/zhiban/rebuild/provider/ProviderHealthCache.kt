package com.zhiban.rebuild.provider

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

interface ProviderHealthCache {
    fun load(profileDigest: String, nowEpochMs: Long): ProviderHealth?
    fun save(profileDigest: String, health: ProviderHealth)
    fun clear()
}

object NoopProviderHealthCache : ProviderHealthCache {
    override fun load(profileDigest: String, nowEpochMs: Long) = null
    override fun save(profileDigest: String, health: ProviderHealth) = Unit
    override fun clear() = Unit
}

/** Non-secret one-hour health snapshot. API keys and request payloads are never stored here. */
class AndroidProviderHealthCache(context: Context) : ProviderHealthCache {
    private val prefs = context.getSharedPreferences("agent_provider_health_v1", Context.MODE_PRIVATE)

    override fun load(profileDigest: String, nowEpochMs: Long): ProviderHealth? = runCatching {
        val root = Json.parseToJsonElement(prefs.getString(profileDigest, null) ?: return null).jsonObject
        val checkedAt = root.getValue("checkedAt").jsonPrimitive.content.toLong()
        if (nowEpochMs - checkedAt !in 0..TTL_MS) return null
        val available = root.getValue("available").jsonPrimitive.content.toBooleanStrict()
        val capability = if (!available) {
            null
        } else {
            CapabilitySnapshot(
                profileDigest,
                root.getValue("modalities").jsonPrimitive.content.split(',').filter(String::isNotBlank).toSet(),
                root.getValue("features").jsonPrimitive.content.split(',').filter(String::isNotBlank).toSet(),
                root.getValue("maxContext").jsonPrimitive.content.toInt(),
                root.getValue("maxOutput").jsonPrimitive.content.toInt(),
                checkedAt,
                checkedAt + TTL_MS,
            )
        }
        ProviderHealth(available, checkedAt, capability, root["failure"]?.jsonPrimitive?.content)
    }.getOrNull()

    override fun save(profileDigest: String, health: ProviderHealth) {
        val value = buildJsonObject {
            put("checkedAt", health.checkedAtEpochMs)
            put("available", health.available)
            health.safeFailureCode?.let { put("failure", it) }
            health.capability?.let {
                put("modalities", it.modalities.sorted().joinToString(","))
                put("features", it.features.sorted().joinToString(","))
                put("maxContext", it.maxContextTokens)
                put("maxOutput", it.maxOutputTokens)
            }
        }.toString()
        prefs.edit().putString(profileDigest, value).apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        const val TTL_MS = 60 * 60_000L
    }
}
