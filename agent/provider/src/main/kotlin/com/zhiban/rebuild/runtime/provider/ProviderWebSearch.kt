package com.zhiban.rebuild.runtime.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun providerTools(json: Json, toolsJson: String?, allowWebSearch: Boolean): JsonArray? {
    val runtimeTools = toolsJson?.let { encoded ->
        json.parseToJsonElement(encoded) as? JsonArray
            ?: throw ProviderFailure("INVALID_TOOL_SCHEMA", retryable = false)
    }.orEmpty()
    if (runtimeTools.isEmpty() && !allowWebSearch) return null
    return buildJsonArray {
        runtimeTools.forEach(::add)
        if (allowWebSearch) {
            add(
                buildJsonObject {
                    put("type", "web_search")
                    put(
                        "function",
                        buildJsonObject {
                            put(
                                "description",
                                "仅在回答需要最新公开信息时搜索互联网；不得用联系人、日历、记忆或消息中的私密数据发起搜索。",
                            )
                        },
                    )
                },
            )
        }
    }
}

internal class WebSearchEvidenceCollector {
    private val sourcesByUrl = linkedMapOf<String, String>()

    /** Returns true when [item] is a provider-owned web search call rather than a runtime tool call. */
    fun accept(item: JsonObject): Boolean {
        val type = (item["type"] as? JsonPrimitive)?.contentOrNull
        if (type != WEB_SEARCH_TYPE) return false
        val function = item["function"] as? JsonObject ?: return true
        val results = function["results"] as? JsonArray ?: return true
        results.forEach { result ->
            if (sourcesByUrl.size >= MAX_WEB_SOURCES) return@forEach
            val source = result as? JsonObject ?: return@forEach
            val url = safeUrl((source["url"] as? JsonPrimitive)?.contentOrNull) ?: return@forEach
            val title = safeTitle((source["title"] as? JsonPrimitive)?.contentOrNull, url)
            sourcesByUrl.putIfAbsent(url, title)
        }
        return true
    }

    fun takeMarkdown(): String? {
        if (sourcesByUrl.isEmpty()) return null
        return sourcesByUrl.entries.joinToString(
            separator = "\n",
            prefix = "\n\n来源：\n",
        ) { (url, title) -> "- [$title]($url)" }.also { sourcesByUrl.clear() }
    }

    private fun safeUrl(raw: String?): String? {
        if (raw == null || raw.length > MAX_WEB_SOURCE_URL_LENGTH) return null
        val parsed = raw.toHttpUrlOrNull() ?: return null
        if (parsed.scheme !in setOf("http", "https")) return null
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
        return parsed.newBuilder().fragment(null).build().toString()
    }

    private fun safeTitle(raw: String?, fallbackUrl: String): String {
        val normalized = raw.orEmpty()
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .replace("[", "")
            .replace("]", "")
            .trim()
            .take(MAX_WEB_SOURCE_TITLE_LENGTH)
        return normalized.ifBlank { fallbackUrl.toHttpUrlOrNull()?.host ?: "网页来源" }
    }

    private companion object {
        const val WEB_SEARCH_TYPE = "web_search"
        const val MAX_WEB_SOURCES = 5
        const val MAX_WEB_SOURCE_URL_LENGTH = 2_048
        const val MAX_WEB_SOURCE_TITLE_LENGTH = 120
    }
}
