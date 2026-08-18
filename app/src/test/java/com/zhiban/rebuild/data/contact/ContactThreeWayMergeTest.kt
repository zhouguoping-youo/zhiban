package com.zhiban.rebuild.data.contact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactThreeWayMergeTest {
    @Test
    fun unchangedDeviceAcceptsDesiredScalarUpdatesAndDeduplicatesMethods() {
        val base = projection("丁波", listOf("13800138000"), listOf("ding@example.com"), "旧公司")
        val desired = base.copy(
            phones = listOf("138-0013-8000", "13900139000"),
            emails = listOf("DING@example.com", "new@example.com"),
            company = "新公司",
        )

        val plan = ContactThreeWayMerge.plan(base, base, desired)

        assertTrue(plan.canApply)
        assertEquals(mapOf("company" to "新公司"), plan.scalarUpdates)
        assertEquals(listOf("13900139000"), plan.phoneAdditions)
        assertEquals(listOf("new@example.com"), plan.emailAdditions)
    }

    @Test
    fun concurrentDeviceAndAgentEditsBecomeConflictInsteadOfOverwrite() {
        val base = projection("丁波", emptyList(), emptyList(), "旧公司")
        val device = base.copy(company = "用户在手机改的公司")
        val desired = base.copy(company = "知伴核实的新公司")

        val plan = ContactThreeWayMerge.plan(base, device, desired)

        assertFalse(plan.canApply)
        assertEquals("company", plan.conflicts.single().field)
        assertTrue(plan.scalarUpdates.isEmpty())
    }

    @Test
    fun externalAdditionsArePreservedBecauseAutomaticDeletionIsNeverPlanned() {
        val base = projection("丁波", listOf("13800138000"), emptyList(), null)
        val device = base.copy(phones = listOf("13800138000", "13900139000"))
        val desired = base.copy(phones = listOf("13800138000"))

        val plan = ContactThreeWayMerge.plan(base, device, desired)

        assertTrue(plan.canApply)
        assertTrue(plan.phoneAdditions.isEmpty())
        assertTrue(plan.isNoOp)
    }

    @Test
    fun projectionJsonIsCanonicalAndRoundTrips() {
        val value = projection(" 丁波 ", listOf("138-0013-8000", "13800138000"), listOf("DING@example.com"), " 知伴 ")

        val decoded = ContactSyncProjection.decode(value.encode())

        assertEquals("丁波", decoded.displayName)
        assertEquals(listOf("13800138000"), decoded.phones)
        assertEquals(listOf("ding@example.com"), decoded.emails)
        assertEquals("知伴", decoded.company)
    }

    @Test
    fun pendingDesiredStateSurvivesUnchangedObservation() {
        val base = projection("丁波", emptyList(), emptyList(), "旧公司")
        val desired = base.copy(company = "新公司")
        val seeded = ContactSyncSnapshotState.observe(null, "link-1", base, 10)
            .copy(desiredProjectionJson = desired.encode(), desiredDigest = digest(desired), syncState = "PENDING")

        val refreshed = ContactSyncSnapshotState.observe(seeded, "link-1", base, 20)

        assertEquals("PENDING", refreshed.syncState)
        assertEquals(desired.encode(), refreshed.desiredProjectionJson)
        assertEquals(base.encode(), refreshed.baseProjectionJson)
    }

    @Test
    fun externalEditDoesNotReplaceMergeBase() {
        val base = projection("丁波", emptyList(), emptyList(), "旧公司")
        val desired = base.copy(company = "新公司")
        val device = base.copy(company = "用户的新公司")
        val seeded = ContactSyncSnapshotState.observe(null, "link-1", base, 10)
            .copy(desiredProjectionJson = desired.encode(), desiredDigest = digest(desired), syncState = "PENDING")

        val refreshed = ContactSyncSnapshotState.observe(seeded, "link-1", device, 20)

        assertEquals("EXTERNAL_CHANGED", refreshed.syncState)
        assertEquals(base.encode(), refreshed.baseProjectionJson)
        assertEquals(desired.encode(), refreshed.desiredProjectionJson)
    }

    @Test
    fun observedDesiredPromotesToNewMergeBase() {
        val base = projection("丁波", emptyList(), emptyList(), "旧公司")
        val desired = base.copy(company = "新公司")
        val seeded = ContactSyncSnapshotState.observe(null, "link-1", base, 10)
            .copy(desiredProjectionJson = desired.encode(), desiredDigest = digest(desired), syncState = "PENDING")

        val refreshed = ContactSyncSnapshotState.observe(seeded, "link-1", desired, 20)

        assertEquals("IN_SYNC", refreshed.syncState)
        assertEquals(desired.encode(), refreshed.baseProjectionJson)
        assertEquals(null, refreshed.desiredProjectionJson)
    }

    private fun projection(name: String, phones: List<String>, emails: List<String>, company: String?) =
        ContactSyncProjection(name, phones, emails, company, null, null)

    private fun digest(value: ContactSyncProjection) = com.zhiban.rebuild.foundation.sha256(value.encode())
}
