package com.zhiban.rebuild.data.contact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactMaintenanceEvaluatorTest {
    @Test
    fun unknownEmploymentAndMissingReachabilityBecomeExplicitMaintenanceIssues() {
        val contact = contact("a", updatedAt = 1L)
        val overview = ContactMaintenanceEvaluator.evaluate(
            contacts = listOf(contact),
            employments = listOf(employment("a")),
            platformIdentities = emptyList(),
            duplicateReviewCount = 2,
            enrichmentReviewCount = 3,
            nowEpochMs = 800L * 24 * 60 * 60 * 1_000,
        )

        val issues = overview.items.single().issues
        assertTrue(ContactMaintenanceIssue.NO_REACHABLE_METHOD in issues)
        assertTrue(ContactMaintenanceIssue.EMPLOYMENT_TIME_UNKNOWN in issues)
        assertTrue(ContactMaintenanceIssue.STALE_PROFILE in issues)
        assertEquals(6, overview.needsAttentionCount)
    }

    @Test
    fun qualityScoreIsDiagnosticAndDoesNotPromoteUnknownTimeToCurrent() {
        val item = ContactMaintenanceEvaluator.evaluate(
            contacts = listOf(contact("a", phone = "13800138000", company = "知伴", updatedAt = 100L)),
            employments = listOf(employment("a")),
            platformIdentities = emptyList(),
            duplicateReviewCount = 0,
            enrichmentReviewCount = 0,
            nowEpochMs = 100L,
        ).items.single()

        assertEquals(0.4, item.quality.temporal, 0.0)
        assertTrue(ContactMaintenanceIssue.EMPLOYMENT_TIME_UNKNOWN in item.issues)
    }

    private fun contact(id: String, phone: String? = null, company: String? = null, updatedAt: Long) = ContactEntity(
        contactId = id,
        displayName = "联系人$id",
        normalizedName = "联系人$id",
        phone = phone,
        email = null,
        wechatId = null,
        company = company,
        title = null,
        aliasesJson = "[]",
        tagsJson = "[]",
        note = null,
        avatarUri = null,
        source = "SYSTEM_CONTACT:test",
        deletedAtEpochMs = null,
        createdAtEpochMs = 1,
        updatedAtEpochMs = updatedAt,
    )

    private fun employment(personId: String) = PersonEmploymentEpisodeEntity(
        episodeId = "employment-$personId",
        personId = personId,
        organizationId = null,
        companyNameSnapshot = "知伴",
        department = null,
        title = null,
        validFromEpochMs = null,
        validToEpochMs = null,
        temporalPrecision = "UNKNOWN",
        currentState = "UNKNOWN",
        sourceRef = "test",
        confidence = 0.6,
        verificationState = "OBSERVED",
        status = "ACTIVE",
        recordedAtEpochMs = 1,
        updatedAtEpochMs = 1,
    )
}
