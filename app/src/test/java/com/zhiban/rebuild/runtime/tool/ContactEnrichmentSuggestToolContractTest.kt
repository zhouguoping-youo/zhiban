package com.zhiban.rebuild.runtime.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactEnrichmentSuggestToolContractTest {
    private val spec = RuntimeToolCatalog.production().requireRegistered("contact.enrichment.suggest")

    @Test
    fun `enrichment suggest tool is registered and confirmation gated`() {
        assertEquals(RuntimeToolRisk.WRITE_CONFIRMATION_REQUIRED, spec.risk)
        assertEquals(1, spec.version)
    }

    @Test
    fun `provider definition declares the four enrichment fields and required args`() {
        val json = spec.providerDefinitionJson
        listOf("ORGANIZATION", "EMPLOYMENT", "ADDRESS", "COMMUNICATION_METHOD").forEach {
            assertTrue("missing field $it", json.contains(it))
        }
        assertTrue(json.contains("\"contactId\""))
        assertTrue(json.contains("\"proposedValueJson\""))
        // Suggestion only — must never promise a direct profile write.
        assertTrue(json.contains("绝不直接写入联系人资料"))
    }
}
