package com.zhiban.rebuild.provider

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class SecretRedactor {
    private val bearer = Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]{8,}")
    private val credentialLike = Regex("(?i)(api[_-]?key|token|secret)\\s*[:=]\\s*[^\\s,;}]{4,}")
    private val mainlandPhone = Regex("(?<!\\d)(?:\\+?86[- ]?)?1[3-9]\\d{9}(?!\\d)")
    private val email = Regex("(?i)(?<![A-Z0-9._%+-])[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}(?![A-Z0-9._%+-])")
    private val mainlandId = Regex("(?<![0-9A-Za-z])\\d{17}[0-9Xx](?![0-9A-Za-z])")

    fun redact(value: String?, knownSecrets: Collection<String> = emptyList()): String {
        val safe = redactWithoutTruncation(value, knownSecrets)
        return safe.take(DIAGNOSTIC_MAX_LENGTH)
    }

    /** Redacts direct identifiers without applying the diagnostic-log length cap. */
    fun redactWithoutTruncation(value: String?, knownSecrets: Collection<String> = emptyList()): String {
        if (value.isNullOrEmpty()) return ""
        var safe: String = value
        knownSecrets.filter { it.isNotEmpty() }.forEach { safe = safe.replace(it, "[REDACTED]") }
        safe = bearer.replace(safe, "Bearer [REDACTED]")
        safe =
            credentialLike.replace(safe) { match ->
                match.value.substringBefore(':').substringBefore('=') +
                    "=[REDACTED]"
            }
        safe = mainlandPhone.replace(safe, "[REDACTED_PHONE]")
        safe = email.replace(safe, "[REDACTED_EMAIL]")
        safe = mainlandId.replace(safe, "[REDACTED_ID]")
        return if (looksLikeUnredactedSecret(safe)) "[REDACTED]" else safe
    }

    fun safeRequestId(value: String?): String? = value
        ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }

    fun redactHeaders(headers: Map<String, String>): Map<String, String> = headers.mapValues { (name, value) ->
        if (isSensitiveName(name)) "[REDACTED]" else redact(value)
    }

    fun redactUrl(value: String): String = runCatching {
        val uri = URI(value)
        val query = uri.rawQuery?.split('&')?.joinToString("&") { pair ->
            val name = pair.substringBefore('=')
            val decodedName = URLDecoder.decode(name, Charsets.UTF_8.name())
            val rawValue = pair.substringAfter('=', "")
            val safeValue = if (isSensitiveName(
                    decodedName,
                )
            ) {
                "[REDACTED]"
            } else {
                redact(URLDecoder.decode(rawValue, Charsets.UTF_8.name()))
            }
            "$name=${URLEncoder.encode(safeValue, Charsets.UTF_8.name())}"
        }
        URI(uri.scheme, uri.authority, uri.path, query, uri.fragment).toASCIIString()
    }.getOrElse { "[REDACTED_URL]" }

    fun redactJson(value: String): String = runCatching {
        redactElement(Json.parseToJsonElement(value)).toString()
    }.getOrElse { redact(value) }

    fun redactThrowable(value: Throwable): String {
        val chain = generateSequence(value) { it.cause }.take(8)
            .joinToString(" <- ") { "${it::class.java.simpleName}:${redact(it.message)}" }
        return if (looksLikeUnredactedSecret(chain)) "[REDACTED_EXCEPTION]" else chain.take(512)
    }

    private fun redactElement(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.mapValues { (key, child) ->
                if (isSensitiveName(key)) JsonPrimitive("[REDACTED]") else redactElement(child)
            },
        )

        is JsonArray -> JsonArray(element.map(::redactElement))

        is JsonPrimitive -> if (element.isString) JsonPrimitive(redact(element.content)) else element
    }

    private fun isSensitiveName(name: String): Boolean = name.lowercase().replace("-", "_") in setOf(
        "authorization",
        "api_key",
        "apikey",
        "token",
        "access_token",
        "secret",
        "credential",
        "credential_ref",
    )

    private fun looksLikeUnredactedSecret(value: String): Boolean = value.contains("sk-") && Regex("sk-[A-Za-z0-9_-]{8,}").containsMatchIn(value)

    private companion object {
        const val DIAGNOSTIC_MAX_LENGTH = 512
    }
}
