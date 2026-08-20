package com.zhiban.rebuild.runtime.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelationshipIntroductionToolContractTest {
    @Test
    fun `introduction event tool requires both participants and evidence`() {
        val definition = RuntimeToolCatalog.production()
            .requireRegistered("relationship.event.createIntroduction")
            .providerDefinitionJson
        val parameters = Json.parseToJsonElement(definition)
            .jsonObject.getValue("function")
            .jsonObject.getValue("parameters")
            .jsonObject
        val required = parameters.getValue("required").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(
            listOf("subjectContactId", "introducerContactId", "evidenceSummary"),
            required,
        )
        assertTrue(parameters.getValue("properties").jsonObject.containsKey("occurredAtEpochMs"))
    }
}
