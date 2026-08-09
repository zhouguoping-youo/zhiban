package com.zhiban.rebuild.data.contact

internal data class LocalOrganizationSuggestion(val contactId: String, val company: String, val evidenceContactId: String, val confidence: Double)

/**
 * Finds explainable company-name candidates already present in the user's own relationship book.
 * A unique legal-name match may complete an abbreviation; conflicting matches produce nothing.
 */
internal fun buildLocalOrganizationSuggestions(contacts: List<ContactEntity>): List<LocalOrganizationSuggestion> {
    val canonicalCompanies = contacts.mapNotNull { contact ->
        contact.company?.cleanCompanyName()?.takeIf(::looksLikeLegalCompanyName)?.let { company ->
            CanonicalCompany(contact.contactId, company, company.companyKey())
        }
    }
    return contacts.mapNotNull { contact ->
        val current = contact.company?.cleanCompanyName() ?: return@mapNotNull null
        if (looksLikeLegalCompanyName(current)) return@mapNotNull null
        val hint = current.companyKey()
        if (hint.length < 2) return@mapNotNull null
        val matches = canonicalCompanies.filter { candidate ->
            candidate.contactId != contact.contactId && candidate.key.contains(hint)
        }.distinctBy(CanonicalCompany::key)
        val match = matches.singleOrNull() ?: return@mapNotNull null
        LocalOrganizationSuggestion(
            contactId = contact.contactId,
            company = match.name,
            evidenceContactId = match.contactId,
            confidence = 0.78,
        )
    }
}

internal fun corporateEmailDomain(email: String?): String? {
    val domain = email.orEmpty().substringAfterLast('@', missingDelimiterValue = "")
        .trim().lowercase().trimEnd('.')
    if (domain.count { it == '.' } < 1 || domain in PUBLIC_EMAIL_DOMAINS) return null
    return domain.takeIf { it.length in 4..120 }
}

private data class CanonicalCompany(val contactId: String, val name: String, val key: String)

private fun String.cleanCompanyName(): String = trim().replace(Regex("\\s+"), " ").take(160)

private fun String.companyKey(): String = lowercase().replace(Regex("[\\s（）()·._-]"), "")

private fun looksLikeLegalCompanyName(value: String): Boolean = LEGAL_COMPANY_SUFFIXES.any { value.endsWith(it) }

private val LEGAL_COMPANY_SUFFIXES = listOf("股份有限公司", "有限责任公司", "有限公司", "集团公司", "集团")

private val PUBLIC_EMAIL_DOMAINS = setOf(
    "qq.com",
    "163.com",
    "126.com",
    "sina.com",
    "sohu.com",
    "gmail.com",
    "outlook.com",
    "hotmail.com",
    "icloud.com",
    "foxmail.com",
    "yeah.net",
)
