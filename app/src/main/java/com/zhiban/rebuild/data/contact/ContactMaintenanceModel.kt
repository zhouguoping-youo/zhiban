package com.zhiban.rebuild.data.contact

import kotlin.math.roundToInt

enum class ContactMaintenanceIssue {
    NO_REACHABLE_METHOD,
    STALE_PROFILE,
}

data class ContactQualityVector(val identity: Double, val reachability: Double, val freshness: Double, val temporal: Double, val provenance: Double) {
    val score: Int = ((identity + reachability + freshness + temporal + provenance) * 20).roundToInt()
        .coerceIn(0, 100)
}

data class ContactMaintenanceItem(val contact: ContactEntity, val quality: ContactQualityVector, val issues: Set<ContactMaintenanceIssue>)

data class ContactMaintenanceOverview(val items: List<ContactMaintenanceItem>, val duplicateReviewCount: Int, val enrichmentReviewCount: Int) {
    val needsAttentionCount: Int = items.count { it.issues.isNotEmpty() } + duplicateReviewCount + enrichmentReviewCount
}

object ContactMaintenanceEvaluator {
    fun evaluate(
        contacts: List<ContactEntity>,
        employments: List<PersonEmploymentEpisodeEntity>,
        platformIdentities: List<ContactPlatformIdentityEntity>,
        duplicateReviewCount: Int,
        enrichmentReviewCount: Int,
        nowEpochMs: Long,
    ): ContactMaintenanceOverview {
        val employmentByPerson = employments.filter { it.status == "ACTIVE" }.groupBy(PersonEmploymentEpisodeEntity::personId)
        val platformsByContact = platformIdentities.groupBy(ContactPlatformIdentityEntity::contactId)
        val items = contacts.map { contact ->
            val contactEmployments = employmentByPerson[contact.contactId].orEmpty()
            val contactPlatforms = platformsByContact[contact.contactId].orEmpty()
            ContactMaintenanceItem(
                contact = contact,
                quality = quality(contact, contactEmployments, contactPlatforms, nowEpochMs),
                issues = issues(contact, contactEmployments, nowEpochMs),
            )
        }.sortedWith(compareBy<ContactMaintenanceItem> { it.issues.isEmpty() }.thenBy { it.quality.score })
        return ContactMaintenanceOverview(items, duplicateReviewCount, enrichmentReviewCount)
    }

    private fun quality(
        contact: ContactEntity,
        employments: List<PersonEmploymentEpisodeEntity>,
        platforms: List<ContactPlatformIdentityEntity>,
        nowEpochMs: Long,
    ) = ContactQualityVector(
        identity = when {
            platforms.any { !it.platformUserId.isNullOrBlank() } -> 1.0
            !contact.phone.isNullOrBlank() || !contact.email.isNullOrBlank() || !contact.wechatId.isNullOrBlank() -> 0.8
            else -> 0.4
        },
        reachability = listOf(contact.phone, contact.email, contact.wechatId).count { !it.isNullOrBlank() }
            .let { count ->
                if (count >= 2) {
                    1.0
                } else if (count == 1) {
                    0.7
                } else {
                    0.2
                }
            },
        freshness = freshness(contact.updatedAtEpochMs, nowEpochMs),
        temporal = when {
            employments.isEmpty() && contact.company.isNullOrBlank() -> 0.7
            employments.any { it.validFromEpochMs != null || it.validToEpochMs != null || it.currentState == "CURRENT" } -> 1.0
            employments.isNotEmpty() -> 0.4
            else -> 0.2
        },
        provenance = when {
            contact.source == "USER" -> 1.0
            contact.source.startsWith("SYSTEM_CONTACT") -> 0.7
            else -> 0.5
        },
    )

    private fun issues(contact: ContactEntity, employments: List<PersonEmploymentEpisodeEntity>, nowEpochMs: Long): Set<ContactMaintenanceIssue> = buildSet {
        if (contact.phone.isNullOrBlank() && contact.email.isNullOrBlank() && contact.wechatId.isNullOrBlank()) {
            add(ContactMaintenanceIssue.NO_REACHABLE_METHOD)
        }
        if (nowEpochMs - contact.updatedAtEpochMs > STALE_AFTER_MS) add(ContactMaintenanceIssue.STALE_PROFILE)
    }

    private fun freshness(updatedAtEpochMs: Long, nowEpochMs: Long): Double = when (nowEpochMs - updatedAtEpochMs) {
        in Long.MIN_VALUE..FRESH_MS -> 1.0
        in (FRESH_MS + 1)..STALE_AFTER_MS -> 0.7
        in (STALE_AFTER_MS + 1)..VERY_STALE_AFTER_MS -> 0.4
        else -> 0.2
    }

    private const val DAY_MS = 24L * 60 * 60 * 1_000
    private const val FRESH_MS = 180L * DAY_MS
    private const val STALE_AFTER_MS = 365L * DAY_MS
    private const val VERY_STALE_AFTER_MS = 730L * DAY_MS
}
