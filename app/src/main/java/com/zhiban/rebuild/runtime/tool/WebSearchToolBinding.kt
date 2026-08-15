package com.zhiban.rebuild.runtime.tool

import com.zhiban.rebuild.runtime.provider.ProviderFailure
import com.zhiban.rebuild.runtime.provider.WebSearchGateway
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class WebSearchToolBinding(override val spec: RuntimeToolSpec, private val gateway: WebSearchGateway) : RuntimeToolBinding {
    override val aliases: Set<String> = setOf("search.web")

    override suspend fun requestApproval(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): Boolean =
        throw ToolPolicyRejectedException("read-only tools do not request approval")

    override suspend fun executeReadOnly(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): RoutedToolResult {
        val arguments = parseToolArgs(request.argumentsJson, setOf("query", "limit")) {
            ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
        }
        val query = arguments["query"]?.jsonPrimitive?.content?.trim()
            ?.takeIf { it.length in 2..MAX_QUERY_LENGTH }
            ?: throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
        val limit = arguments["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: DEFAULT_LIMIT
        if (limit !in 1..MAX_LIMIT) throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
        val results = gateway.search(query, limit)
        val safeResult = buildJsonObject {
            put("count", results.size)
            put(
                "items",
                buildJsonArray {
                    results.forEach { result ->
                        add(
                            buildJsonObject {
                                put("title", result.title)
                                put("url", result.url)
                                put("snippet", result.snippet)
                            },
                        )
                    }
                },
            )
        }.toString()
        return RoutedToolResult(spec.name, request.providerCallId, safeResult)
    }

    companion object {
        const val TOOL_NAME = "web.search"
        private const val DEFAULT_LIMIT = 5
        private const val MAX_LIMIT = 5
        private const val MAX_QUERY_LENGTH = 500
    }
}
