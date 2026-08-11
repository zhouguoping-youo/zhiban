package com.zhiban.rebuild.data.contact

/**
 * Explainable identity resolution. A name or company is never sufficient for automatic linking.
 * Automatic decisions require either one stable platform user id or two independent exact
 * communication identifiers; every other plausible match remains a review candidate.
 */
object ContactIdentityResolver {
    fun resolve(
        contacts: List<ContactEntity>,
        aliases: List<ContactAliasEntity>,
        platformIdentities: List<ContactPlatformIdentityEntity>,
    ): List<ContactIdentityResolution> {
        val active = contacts.filter { it.deletedAtEpochMs == null }
        val profiles = active.associate { it.contactId to MutableIdentityProfile(it) }
        aliases.filter(ContactAliasEntity::userConfirmed).forEach { alias ->
            profiles[alias.contactId]?.aliases?.add(alias.normalizedAlias)
        }
        platformIdentities.forEach { identity ->
            identity.toEvidenceKey()?.let { profiles[identity.contactId]?.platforms?.add(it) }
        }
        return buildCandidatePairs(profiles, aliases).mapNotNull { pair ->
            resolvePair(profiles.getValue(pair.first), profiles.getValue(pair.second))
        }.sortedWith(
            compareByDescending<ContactIdentityResolution> { it.decision.priority }
                .thenByDescending { it.confidence },
        ).toList()
    }

    private fun buildCandidatePairs(profiles: Map<String, MutableIdentityProfile>, aliases: List<ContactAliasEntity>): Set<IdentityPair> = buildSet {
        fun <T> addIndexed(values: (MutableIdentityProfile) -> Set<T>) {
            profiles.values.flatMap { profile -> values(profile).map { it to profile.contact.contactId } }
                .groupBy({ it.first }, { it.second }).values
                .filter { it.size in 2..MAX_PAIRABLE_GROUP_SIZE }
                .forEach { ids -> ids.forEachPair { first, second -> add(IdentityPair.of(first, second)) } }
        }
        addIndexed(MutableIdentityProfile::phones)
        addIndexed(MutableIdentityProfile::emails)
        addIndexed(MutableIdentityProfile::wechatIds)
        addIndexed(MutableIdentityProfile::platforms)
        addIndexed { profile ->
            val name = profile.contact.normalizedName
            val company = profile.contact.company.normalizedText()
            if (name.isBlank() || company == null) emptySet() else setOf("$name\u001f$company")
        }
        profiles.values.groupBy { it.contact.normalizedName }.values
            .filter { group -> group.size in 2..MAX_PAIRABLE_GROUP_SIZE && group.any { it.contact.isAgentStub() } }
            .forEach { group ->
                group.map { it.contact.contactId }
                    .forEachPair { first, second -> add(IdentityPair.of(first, second)) }
            }
        val contactsByName = profiles.values.groupBy { it.contact.normalizedName }
        aliases.filter(ContactAliasEntity::userConfirmed).forEach { alias ->
            contactsByName[alias.normalizedAlias].orEmpty().forEach { named ->
                if (alias.contactId != named.contact.contactId) {
                    add(IdentityPair.of(alias.contactId, named.contact.contactId))
                }
            }
        }
    }

    private fun resolvePair(first: MutableIdentityProfile, second: MutableIdentityProfile): ContactIdentityResolution? {
        val stablePlatforms = first.platforms.intersect(second.platforms).filterTo(linkedSetOf()) { it.stable }
        val sharedStrong = buildList {
            if (first.phones.intersect(second.phones).isNotEmpty()) add("手机号")
            if (first.emails.intersect(second.emails).isNotEmpty()) add("邮箱")
            if (first.wechatIds.intersect(second.wechatIds).isNotEmpty()) add("微信号")
            if (first.platforms.intersect(second.platforms).isNotEmpty()) add("社交账号")
        }.distinct()
        val contradictions = first.contradictionsWith(second)
        val weakReason = first.weakReasonWith(second)
        val decision = when {
            stablePlatforms.isNotEmpty() && contradictions < 2 -> IdentityResolutionDecision.AUTO_LINK
            sharedStrong.size >= 2 && contradictions == 0 -> IdentityResolutionDecision.AUTO_LINK
            sharedStrong.isNotEmpty() -> IdentityResolutionDecision.REVIEW
            contradictions >= 2 -> IdentityResolutionDecision.BLOCKED
            weakReason != null -> IdentityResolutionDecision.REVIEW
            else -> return null
        }
        val reason = when {
            stablePlatforms.isNotEmpty() -> "平台稳定账号相同"
            sharedStrong.isNotEmpty() -> sharedStrong.joinToString("、", postfix = "相同")
            else -> weakReason ?: return null
        }
        val confidence = when (decision) {
            IdentityResolutionDecision.AUTO_LINK -> if (stablePlatforms.isNotEmpty()) 0.995 else 0.99
            IdentityResolutionDecision.REVIEW -> if (sharedStrong.isNotEmpty()) 0.96 else first.weakConfidence(second)
            IdentityResolutionDecision.BLOCKED -> 0.0
        }
        return ContactIdentityResolution(
            first = first.contact,
            second = second.contact,
            decision = decision,
            reason = reason,
            confidence = confidence,
            contradictions = contradictions,
        )
    }

    private const val MAX_PAIRABLE_GROUP_SIZE = 20
}

enum class IdentityResolutionDecision(internal val priority: Int) {
    AUTO_LINK(3),
    REVIEW(2),
    BLOCKED(1),
}

data class ContactIdentityResolution(
    val first: ContactEntity,
    val second: ContactEntity,
    val decision: IdentityResolutionDecision,
    val reason: String,
    val confidence: Double,
    val contradictions: Int,
)

private data class PlatformEvidenceKey(val platform: String, val value: String, val stable: Boolean)

private data class IdentityPair(val first: String, val second: String) {
    companion object {
        fun of(first: String, second: String) = if (first < second) IdentityPair(first, second) else IdentityPair(second, first)
    }
}

private data class MutableIdentityProfile(
    val contact: ContactEntity,
    val phones: Set<String> = setOfNotNull(normalizeContactPhone(contact.phone)),
    val emails: Set<String> = setOfNotNull(contact.email.normalizedEmail()),
    val wechatIds: Set<String> = setOfNotNull(contact.wechatId.normalizedHandle()),
    val aliases: MutableSet<String> = linkedSetOf(),
    val platforms: MutableSet<PlatformEvidenceKey> = linkedSetOf(),
) {
    fun contradictionsWith(other: MutableIdentityProfile): Int = listOf(
        phones contradict other.phones,
        emails contradict other.emails,
        wechatIds contradict other.wechatIds,
    ).count(Boolean::not)

    fun weakReasonWith(other: MutableIdentityProfile): String? = when {
        contact.normalizedName.isNotBlank() &&
            contact.normalizedName == other.contact.normalizedName &&
            contact.company.normalizedText() != null &&
            contact.company.normalizedText() == other.contact.company.normalizedText() -> "姓名和公司相同"

        contact.isAgentStub() && contact.normalizedName == other.contact.normalizedName -> "同名且一方是待确认联系人"

        other.contact.isAgentStub() && contact.normalizedName == other.contact.normalizedName -> "同名且一方是待确认联系人"

        aliases.contains(other.contact.normalizedName) || other.aliases.contains(contact.normalizedName) -> "常用称呼与姓名吻合"

        else -> null
    }

    fun weakConfidence(other: MutableIdentityProfile): Double = when (weakReasonWith(other)) {
        "常用称呼与姓名吻合" -> 0.9
        "姓名和公司相同" -> 0.82
        else -> 0.6
    }
}

private infix fun Set<String>.contradict(other: Set<String>): Boolean = isEmpty() || other.isEmpty() || intersect(other).isNotEmpty()

private fun ContactPlatformIdentityEntity.toEvidenceKey(): PlatformEvidenceKey? {
    val stableId = platformUserId?.trim()?.takeIf(String::isNotEmpty)
    val value = stableId ?: normalizedHandle.trim().takeIf(String::isNotEmpty) ?: return null
    return PlatformEvidenceKey(platform.uppercase(), value, stableId != null)
}

private fun ContactEntity.isAgentStub(): Boolean = source == "AGENT_CANDIDATE"

private fun String?.normalizedEmail(): String? = this?.trim()?.lowercase()?.takeIf { '@' in it }

private fun String?.normalizedHandle(): String? = this?.trim()?.trimStart('@')?.lowercase()?.takeIf(String::isNotEmpty)

private fun String?.normalizedText(): String? = this?.trim()?.lowercase()?.takeIf(String::isNotEmpty)

private inline fun <T> List<T>.forEachPair(block: (T, T) -> Unit) {
    for (firstIndex in 0 until lastIndex) {
        for (secondIndex in firstIndex + 1 until size) block(this[firstIndex], this[secondIndex])
    }
}
