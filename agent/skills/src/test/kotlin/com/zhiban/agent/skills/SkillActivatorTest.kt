package com.zhiban.agent.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillActivatorTest {
    @Test fun activatesOnlyInWorkWhenRequiredToolsAreAvailable() {
        val activator = SkillActivator()
        assertTrue(activator.activate("CALENDAR_CREATE", "Chat", setOf("calendar.schedule.search")).isEmpty())
        assertTrue(activator.activate("CALENDAR_CREATE", "Work", emptySet()).isEmpty())
        assertEquals(
            "calendar_coordination",
            activator.activate(
                "CALENDAR_CREATE",
                "Work",
                setOf("calendar.schedule.search"),
            ).single().skillId,
        )
    }

    @Test fun contactIntelligenceActivatesForGeneralWorkWithItsGovernedToolchain() {
        val spec = BuiltInSkills.all.single { it.id == "contact_relationship" }
        val activation = SkillActivator().activate("GENERAL_WORK", "Work", spec.requiredTools)

        assertEquals("contact_relationship", activation.single().skillId)
        assertEquals(3, activation.single().version)
        assertTrue("contact.profile.proposeUpdate" in activation.single().requiredTools)
        assertTrue("contact.tag.add" in activation.single().requiredTools)
        assertTrue("relationship.createCandidate" in activation.single().requiredTools)
    }

    @Test fun salesCrmActivatesOnlyWithItsGovernedDataAndCommunicationTools() {
        val spec = BuiltInSkills.all.single { it.id == "sales_crm" }

        val activation = SkillActivator().activate("SALES_CRM", "Work", spec.requiredTools)

        assertEquals("sales_crm", activation.single().skillId)
        assertEquals(3, activation.single().version)
        assertTrue("crm.opportunity.list" in activation.single().requiredTools)
        assertTrue("crm.opportunity.get" in activation.single().requiredTools)
        assertTrue("contact.profile.proposeUpdate" in activation.single().requiredTools)
        assertTrue("contact.tag.add" in activation.single().requiredTools)
        assertTrue("communication.message.compose" in activation.single().requiredTools)
    }

    @Test fun onlySalesCrmIsExposedAsABuiltInSceneCapability() {
        val sceneSkills = BuiltInSkills.all.filter { it.level == SkillLevel.SCENE }
        val systemSkills = BuiltInSkills.all.filter { it.level == SkillLevel.SYSTEM }.map { it.id }.toSet()

        assertEquals(listOf("sales_crm"), sceneSkills.map { it.id })
        assertTrue("contact_relationship" in systemSkills)
        assertTrue("calendar_coordination" in systemSkills)
        assertTrue("memory_preference" in systemSkills)
    }
}
