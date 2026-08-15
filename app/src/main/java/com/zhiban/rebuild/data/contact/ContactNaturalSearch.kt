package com.zhiban.rebuild.data.contact

import java.text.Normalizer

internal suspend fun ContactDao.searchNatural(query: String, limit: Int): List<ContactSearchProjection> {
    val normalized = normalizeContactSearchText(query)
    if (normalized.isBlank()) return emptyList()
    val matches = linkedMapOf<String, ScoredContact>()
    (listOfNotNull(normalized.takeIf(SAFE_WHOLE_QUERY::matches)).asSequence() + contactSearchTerms(normalized))
        .distinct()
        .take(MAX_CONTACT_SEARCH_TERMS)
        .forEachIndexed { termIndex, term ->
            search(ftsLiteral(term), term, MAX_CANDIDATES_PER_TERM).forEach { contact ->
                val current = matches[contact.contactId]
                matches[contact.contactId] = if (current == null) {
                    ScoredContact(contact, hitCount = 1, firstTermIndex = termIndex)
                } else {
                    current.copy(hitCount = current.hitCount + 1)
                }
            }
        }
    return matches.values
        .sortedWith(
            compareByDescending<ScoredContact> {
                normalizeContactSearchText(it.contact.displayName) == normalized
            }.thenByDescending { it.hitCount }
                .thenBy { it.firstTermIndex },
        )
        .map(ScoredContact::contact)
        .take(limit)
}

internal fun normalizeContactSearchText(value: String): String = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC).lowercase()

private fun contactSearchTerms(query: String): Sequence<String> {
    val latinTerms = Regex("[a-z0-9_]+")
        .findAll(query)
        .map { it.value }
    val hanTerms = Regex("[\\u3400-\\u4DBF\\u4E00-\\u9FFF]+")
        .findAll(query)
        .flatMap { match ->
            match.value.split(CHINESE_QUERY_FILLERS).asSequence()
        }
        .filter { it.length >= MIN_CONTACT_SEARCH_TERM_LENGTH }
        .flatMap { value ->
            sequenceOf(value) + value.windowedSequence(size = HAN_BIGRAM_LENGTH)
        }
    return (latinTerms + hanTerms)
        .filter { it.length >= MIN_CONTACT_SEARCH_TERM_LENGTH }
        .distinct()
}

private fun ftsLiteral(value: String): String = "\"${value.replace("\"", "\"\"")}\""

private data class ScoredContact(val contact: ContactSearchProjection, val hitCount: Int, val firstTermIndex: Int)

private const val MIN_CONTACT_SEARCH_TERM_LENGTH = 2
private const val HAN_BIGRAM_LENGTH = 2
private const val MAX_CONTACT_SEARCH_TERMS = 16
private const val MAX_CANDIDATES_PER_TERM = 50
private val SAFE_WHOLE_QUERY = Regex("[\\p{L}\\p{N}_]+")
private val CHINESE_QUERY_FILLERS = Regex("(?:请|帮|我|你|他|她|它|做|找|查|的|是|有|在|那|这|哪|谁|什么|怎么|如何|一个|和|与|跟|了|过)+")
