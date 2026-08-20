package com.zhiban.agent.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillActivatorTest {
    @Test fun activatesOnlyWhenRequiredToolsAreAvailable() {
        val activator = SkillActivator()
        assertTrue(activator.activate("CALENDAR_CREATE", emptySet()).isEmpty())
        assertEquals(
            "calendar_coordination",
            activator.activate(
                "CALENDAR_CREATE",
                setOf("calendar.schedule.search"),
            ).single().skillId,
        )
    }

    @Test fun contactIntelligenceActivatesForGeneralWorkWithItsGovernedToolchain() {
        val spec = BuiltInSkills.all.single { it.id == "contact_relationship" }
        val activation = SkillActivator().activate("GENERAL_WORK", spec.requiredTools)

        assertEquals("contact_relationship", activation.single().skillId)
        assertEquals(3, activation.single().version)
        assertTrue("contact.profile.proposeUpdate" in activation.single().requiredTools)
        assertTrue("contact.tag.add" in activation.single().requiredTools)
        assertTrue("relationship.createCandidate" in activation.single().requiredTools)
    }

    @Test fun salesCrmActivatesOnlyWithItsGovernedDataAndCommunicationTools() {
        val spec = BuiltInSkills.all.single { it.id == "sales_crm" }

        val activation = SkillActivator().activate("SALES_CRM", spec.requiredTools)

        assertEquals("sales_crm", activation.single().skillId)
        assertEquals(3, activation.single().version)
        assertTrue("crm.opportunity.list" in activation.single().requiredTools)
        assertTrue("crm.opportunity.get" in activation.single().requiredTools)
        assertTrue("contact.profile.proposeUpdate" in activation.single().requiredTools)
        assertTrue("contact.tag.add" in activation.single().requiredTools)
        assertTrue("communication.message.compose" in activation.single().requiredTools)
    }

    @Test fun sceneCapabilitiesExposeCrmAndPersonalLife() {
        val sceneSkills = BuiltInSkills.all.filter { it.level == SkillLevel.SCENE }
        val systemSkills = BuiltInSkills.all.filter { it.level == SkillLevel.SYSTEM }.map { it.id }.toSet()

        assertEquals(listOf("sales_crm", "personal_life", "social_planning"), sceneSkills.map { it.id })
        assertTrue("contact_relationship" in systemSkills)
        assertTrue("calendar_coordination" in systemSkills)
        assertTrue("memory_preference" in systemSkills)
    }

    @Test fun personalLifeActivatesWithRelationshipsCalendarAndConfirmedCommunication() {
        val spec = BuiltInSkills.all.single { it.id == "personal_life" }

        val activation = SkillActivator().activate("PERSONAL_LIFE", spec.requiredTools).single()

        assertEquals("personal_life", activation.skillId)
        assertTrue("relationship.getEvidence" in activation.requiredTools)
        assertTrue("calendar.schedule.create" in activation.requiredTools)
        assertTrue("communication.message.compose" in activation.requiredTools)
    }

    @Test fun socialPlanningActivatesWithRelationshipsCalendarAndConfirmedCommunication() {
        val spec = BuiltInSkills.all.single { it.id == "social_planning" }

        val activation = SkillActivator().activate("SOCIAL_PLANNING", spec.requiredTools).single()

        assertEquals("social_planning", activation.skillId)
        assertTrue("relationship.getEvidence" in activation.requiredTools)
        assertTrue("calendar.schedule.conflicts" in activation.requiredTools)
        assertTrue("communication.message.compose" in activation.requiredTools)
    }
}
