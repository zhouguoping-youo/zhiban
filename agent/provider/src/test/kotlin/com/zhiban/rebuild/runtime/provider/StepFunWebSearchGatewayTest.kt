package com.zhiban.rebuild.runtime.provider

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StepFunWebSearchGatewayTest {
    @Test fun parserKeepsOnlyUniqueHttpSourcesAndCapsUntrustedText() {
        val oversized = "x".repeat(1_200)
        val body =
            """{"results":[
                {"url":"https://example.com/weather#hourly","title":"Weather\nToday","snippet":"$oversized"},
                {"url":"https://example.com/weather","title":"duplicate","snippet":"ignored"},
                {"url":"javascript:alert(1)","title":"bad","snippet":"bad"}
            ]}""".trimIndent()

        val results = parseStepFunSearchResponse(body, Json, limit = 5)

        assertEquals(1, results.size)
        assertEquals("https://example.com/weather", results.single().url)
        assertEquals("Weather Today", results.single().title)
        assertEquals(1_000, results.single().snippet.length)
    }

    @Test fun parserRejectsResponsesWithoutResults() {
        assertTrue(
            runCatching { parseStepFunSearchResponse("{}", Json, limit = 5) }
                .exceptionOrNull() is ProviderFailure,
        )
    }
}
