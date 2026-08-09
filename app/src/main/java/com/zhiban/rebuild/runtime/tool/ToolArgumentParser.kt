package com.zhiban.rebuild.runtime.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal fun parseToolArgs(
    argumentsJson: String,
    allowedKeys: Set<String>?,
    failure: () -> RuntimeException = { IllegalArgumentException("INVALID_TOOL_ARGUMENTS") },
): JsonObject {
    val value = runCatching { Json.parseToJsonElement(argumentsJson).jsonObject }
        .getOrElse { throw failure() }
    if (allowedKeys != null && value.keys.any { it !in allowedKeys }) throw failure()
    return value
}
