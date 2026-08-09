package com.zhiban.rebuild.runtime.tool

import com.zhiban.rebuild.data.contact.ContactDao
import java.text.Normalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class ContactSearchToolBinding(override val spec: RuntimeToolSpec, private val contacts: ContactDao) : RuntimeToolBinding {
    override suspend fun requestApproval(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext) =
        throw ToolPolicyRejectedException("contact.search is read-only")

    override suspend fun executeReadOnly(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): RoutedToolResult {
        val args = parseContactArgs(request.argumentsJson)
        val query = args["query"]?.jsonPrimitive?.content?.trim().orEmpty()
        require(query.isNotBlank() && query.length <= 100) { "INVALID_TOOL_ARGUMENTS" }
        val limit = (args["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 20).coerceIn(1, 50)
        val results = contacts.search(query, normalizeContactQuery(query), limit)
        val safe = buildJsonObject {
            put("query", query)
            put("count", results.size)
            put(
                "contacts",
                buildJsonArray {
                    results.forEach { item ->
                        add(
                            buildJsonObject {
                                put("contactId", item.contactId)
                                put("displayName", item.displayName)
                                item.phone?.let { put("phone", it) }
                                item.email?.let { put("email", it) }
                                item.wechatId?.let { put("wechatId", it) }
                                item.company?.let { put("company", it) }
                                item.title?.let { put("title", it) }
                                item.note?.let { put("note", it.take(500)) }
                            },
                        )
                    }
                },
            )
        }.toString()
        return RoutedToolResult(spec.name, request.providerCallId, safe)
    }
}

internal class ContactDetailToolBinding(override val spec: RuntimeToolSpec, private val contacts: ContactDao) : RuntimeToolBinding {
    override suspend fun requestApproval(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext) =
        throw ToolPolicyRejectedException("contact.getDetail is read-only")

    override suspend fun executeReadOnly(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): RoutedToolResult {
        val args = parseContactArgs(request.argumentsJson)
        val id = args["contactId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it.length <= 128 }
            ?: throw IllegalArgumentException("INVALID_TOOL_ARGUMENTS")
        val contact = contacts.findById(id)
        val safe = if (contact == null) {
            buildJsonObject {
                put("found", false)
                put("contactId", id)
            }
        } else {
            buildJsonObject {
                put("found", true)
                put("contactId", contact.contactId)
                put("displayName", contact.displayName)
                contact.phone?.let { put("phone", it) }
                contact.email?.let { put("email", it) }
                contact.wechatId?.let { put("wechatId", it) }
                contact.company?.let { put("company", it) }
                contact.title?.let { put("title", it) }
                contact.note?.let { put("note", it.take(1000)) }
                put(
                    "roles",
                    buildJsonArray {
                        contacts.roles(id).forEach { role ->
                            add(
                                buildJsonObject {
                                    put("skillId", role.skillId)
                                    put("roleType", role.roleType)
                                    put("confidence", role.confidence)
                                    put("userConfirmed", role.userConfirmed)
                                },
                            )
                        }
                    },
                )
            }
        }
        return RoutedToolResult(spec.name, request.providerCallId, safe.toString())
    }
}

internal fun normalizeContactQuery(value: String): String = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC).lowercase()

private fun parseContactArgs(value: String) = runCatching {
    Json.parseToJsonElement(value).jsonObject
}.getOrElse { throw IllegalArgumentException("INVALID_TOOL_ARGUMENTS") }
