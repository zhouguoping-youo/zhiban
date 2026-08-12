package com.zhiban.rebuild.data.agent

import com.zhiban.rebuild.data.contact.OrganizationEntity

/** Normalizes spacing only; it never expands an abbreviation into a guessed legal name. */
internal fun normalizeOrganizationFullName(value: String): String = value.trim().replace(ORGANIZATION_WHITESPACE, " ")

internal suspend fun AgentDatabase.upsertUserConfirmedOrganization(fullName: String, sourceRef: String, nowEpochMs: Long): OrganizationEntity {
    val canonicalName = normalizeOrganizationFullName(fullName)
    require(canonicalName.isNotBlank()) { "公司全称不能为空" }
    val organizationId = stableContactKnowledgeId("organization", "NAME", canonicalName.lowercase())
    val knowledge = contactKnowledgeDao()
    val existing = knowledge.findOrganization(organizationId)
    return OrganizationEntity(
        organizationId = organizationId,
        canonicalName = canonicalName,
        normalizedName = canonicalName.lowercase().filterNot(Char::isWhitespace),
        creditCode = existing?.creditCode,
        status = existing?.status,
        registeredAddress = existing?.registeredAddress,
        longitude = existing?.longitude,
        latitude = existing?.latitude,
        source = "USER",
        sourceRef = sourceRef,
        userConfirmed = true,
        verifiedAtEpochMs = nowEpochMs,
        createdAtEpochMs = existing?.createdAtEpochMs ?: nowEpochMs,
        updatedAtEpochMs = nowEpochMs,
    ).also { knowledge.upsertOrganization(it) }
}

private val ORGANIZATION_WHITESPACE = Regex("\\s+")
