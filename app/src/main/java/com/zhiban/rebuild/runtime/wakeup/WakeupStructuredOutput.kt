package com.zhiban.rebuild.runtime.wakeup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Bounded, provider-generated judgment. Schedule timing remains authoritative only after local normalization. */
internal data class WakeupStructuredOutput(val intent: String, val confidence: Double, val suggestion: String, val schedule: WakeupScheduleElements?)

internal data class WakeupScheduleElements(val title: String?, val timeExpression: String?, val durationMinutes: Int?, val location: String?)

internal object WakeupStructuredOutputCodec {
    private val json = Json { ignoreUnknownKeys = false }

    fun decode(raw: String): WakeupStructuredOutput? {
        val firstBrace = raw.indexOf('{')
        val lastBrace = raw.lastIndexOf('}')
        if (firstBrace < 0 || lastBrace < firstBrace) return null
        val objectText = raw.substring(firstBrace, lastBrace + 1)
        val value = runCatching { json.parseToJsonElement(objectText).jsonObject }.getOrNull() ?: return null
        if (value.keys != EXPECTED_KEYS) return null
        val intent = value["intent"]?.jsonPrimitive?.contentOrNull?.takeIf(ALLOWED_INTENTS::contains) ?: return null
        val confidence = value["confidence"]?.jsonPrimitive?.doubleOrNull?.takeIf { it in 0.0..1.0 } ?: return null
        val suggestion = value["suggestion"]?.jsonPrimitive?.contentOrNull?.trim()?.take(MAX_SUGGESTION_CHARS)
            ?.takeIf(String::isNotBlank) ?: return null
        val schedule = WakeupScheduleElements(
            title = value.nullableString("scheduleTitle", MAX_SCHEDULE_TITLE_CHARS),
            timeExpression = value.nullableString("scheduleTimeExpression", MAX_TIME_EXPRESSION_CHARS),
            durationMinutes = value["scheduleDurationMinutes"]?.jsonPrimitive?.intOrNull?.takeIf { it in 1..1_440 },
            location = value.nullableString("scheduleLocation", MAX_LOCATION_CHARS),
        ).takeUnless { it.title == null && it.timeExpression == null && it.durationMinutes == null && it.location == null }
        return WakeupStructuredOutput(intent, confidence, suggestion, schedule)
    }

    private fun kotlinx.serialization.json.JsonObject.nullableString(key: String, maxLength: Int): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.trim()?.take(maxLength)?.takeIf(String::isNotBlank)

    const val RESPONSE_SCHEMA = """
{
  "name": "zhiban_wakeup_judgment",
  "strict": true,
  "schema": {
    "type": "object",
    "properties": {
      "intent": {"type": "string", "enum": ["FOLLOW_UP", "SCHEDULE", "CONTACT_UPDATE", "CRM", "NONE"]},
      "confidence": {"type": "number", "minimum": 0, "maximum": 1},
      "suggestion": {"type": "string", "maxLength": 150},
      "scheduleTitle": {"type": ["string", "null"]},
      "scheduleTimeExpression": {"type": ["string", "null"]},
      "scheduleDurationMinutes": {"type": ["integer", "null"], "minimum": 1, "maximum": 1440},
      "scheduleLocation": {"type": ["string", "null"]}
    },
    "required": [
      "intent", "confidence", "suggestion", "scheduleTitle", "scheduleTimeExpression",
      "scheduleDurationMinutes", "scheduleLocation"
    ],
    "additionalProperties": false
  }
}
"""

    private val ALLOWED_INTENTS = setOf("FOLLOW_UP", "SCHEDULE", "CONTACT_UPDATE", "CRM", "NONE")
    private val EXPECTED_KEYS = setOf(
        "intent",
        "confidence",
        "suggestion",
        "scheduleTitle",
        "scheduleTimeExpression",
        "scheduleDurationMinutes",
        "scheduleLocation",
    )
    private const val MAX_SUGGESTION_CHARS = 150
    private const val MAX_SCHEDULE_TITLE_CHARS = 80
    private const val MAX_TIME_EXPRESSION_CHARS = 80
    private const val MAX_LOCATION_CHARS = 120
}
