package com.zhiban.rebuild.relationship

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelationshipTaxonomyTest {
    @Test
    fun `legacy relationship codes remain supported`() {
        val legacyCodes = setOf(
            "FAMILY",
            "FRIEND",
            "COLLEAGUE",
            "CUSTOMER",
            "SUPPLIER",
            "TEACHER",
            "CLASSMATE",
            "PROJECT_PARTNER",
            "OTHER",
        )

        assertTrue(RelationshipTaxonomy.selectableCodes.containsAll(legacyCodes))
    }

    @Test
    fun `historical labels describe the relationship instead of a count`() {
        assertEquals("前同事", RelationshipTaxonomy.displayName("COLLEAGUE", isHistorical = true))
        assertEquals("曾是朋友", RelationshipTaxonomy.displayName("FRIEND", isHistorical = true))
        assertEquals("父母", RelationshipTaxonomy.displayName("PARENT", isHistorical = true))
    }

    @Test
    fun `directional relationships declare their inverse`() {
        val manager = RelationshipTaxonomy.requireSupported("MANAGER")
        val parent = RelationshipTaxonomy.requireSupported("PARENT")

        assertEquals(RelationshipDirection.DIRECTED, manager.direction)
        assertEquals("SUBORDINATE", manager.inverseCode)
        assertEquals("CHILD", parent.inverseCode)
    }

    @Test
    fun `external projections expose mapping loss instead of claiming exact compatibility`() {
        val colleague = RelationshipTaxonomy.requireSupported("COLLEAGUE")
        val customer = RelationshipTaxonomy.requireSupported("CUSTOMER")
        val classmate = RelationshipTaxonomy.requireSupported("CLASSMATE")
        val sibling = RelationshipTaxonomy.requireSupported("SIBLING")
        val referrer = RelationshipTaxonomy.requireSupported("REFERRER")

        assertEquals(RelationshipMappingQuality.EXACT, colleague.vCardQuality)
        assertEquals(RelationshipMappingQuality.LOSSY, customer.vCardQuality)
        assertEquals(RelationshipMappingQuality.LOSSY, classmate.vCardQuality)
        assertEquals(RelationshipMappingQuality.LOSSY, sibling.androidQuality)
        assertEquals(RelationshipMappingQuality.LOSSY, referrer.androidQuality)
        assertTrue(classmate.extensionType.startsWith("x-zhiban-"))
    }

    @Test
    fun `unknown is an import fallback and never a selectable graph edge`() {
        assertFalse(RelationshipTaxonomy.requireSupported("UNKNOWN").isSelectable)
        assertFalse("UNKNOWN" in RelationshipTaxonomy.selectableCodes)
    }
}
