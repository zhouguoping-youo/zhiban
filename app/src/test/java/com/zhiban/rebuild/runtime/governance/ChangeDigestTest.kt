package com.zhiban.rebuild.runtime.governance

import com.zhiban.rebuild.data.autowrite.canonicalChangeDigest
import com.zhiban.rebuild.data.autowrite.changeDigestMatches
import com.zhiban.rebuild.data.facts.FactEntity
import com.zhiban.rebuild.foundation.sha256
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeDigestTest {
    private val fact = FactEntity(
        factId = "fact-1",
        factType = "INTERACTION_SUMMARY",
        textContent = "已联系",
        structuredDataJson = "{\"channel\":\"WECHAT\"}",
        sourceType = "OBSERVED_NOTIFICATION",
        sourceRef = "source-1",
        contactId = "contact-1",
        skillId = null,
        confidence = 1.0,
        sensitivity = "PERSONAL",
        status = "ACTIVE",
        ttlDays = 90,
        expiresAtEpochMs = 100,
        createdAtEpochMs = 1,
        updatedAtEpochMs = 2,
    )

    @Test
    fun canonicalDigestIsNotTheDataClassPresentationDigest() {
        assertFalse(canonicalChangeDigest(fact) == sha256(fact.toString()))
    }

    @Test
    fun legacyPresentationDigestRemainsAcceptedForExistingUndoRows() {
        assertTrue(changeDigestMatches(sha256(fact.toString()), canonicalChangeDigest(fact), fact))
        assertTrue(changeDigestMatches(canonicalChangeDigest(fact), canonicalChangeDigest(fact), fact))
    }
}
