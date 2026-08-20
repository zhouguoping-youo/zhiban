package com.zhiban.rebuild.data.contact

import androidx.sqlite.db.SimpleSQLiteQuery
import java.text.Normalizer

/**
 * 联系人自然语言检索。多词一次检索(P1-性能2):过去每 term 各执行一次 search()(FTS MATCH +
 * instr 全表扫 + canonical 解析 + 6 个 fill COALESCE),最多 16 次;现在合并为一条 SQL——
 * FTS 整词 MATCH 独立成 IN 子查询限定候选集(MATCH 不能与普通谓词同 OR,见 buildMultiTermSql)
 * + OR'd instr 子串链(中文 bigram/词内子串无法由 FTS simple tokenizer 命中,instr 链不可去),
 * 候选行附带 termMask(每位一个 term,instr 命中=1)供 Kotlin 排序,
 * canonical 解析与 fill-only COALESCE 与原 SQL 一字不差。
 *
 * 排序语义与原实现一致:显示名精确匹配优先、命中词数降序、首个命中词序升序,取前 [limit]。
 */
internal suspend fun ContactDao.searchNatural(query: String, limit: Int): List<ContactSearchProjection> {
    val normalized = normalizeContactSearchText(query)
    if (normalized.isBlank()) return emptyList()
    val terms = (listOfNotNull(normalized.takeIf(SAFE_WHOLE_QUERY::matches)).asSequence() + contactSearchTerms(normalized))
        .distinct()
        .take(MAX_CONTACT_SEARCH_TERMS)
        .toList()
    if (terms.isEmpty()) return emptyList()
    val ftsQuery = terms.joinToString(" OR ") { ftsLiteral(it) }
    // 绑定顺序与 SQL 中的 ? 出现顺序一致：termMask 各 term（N 个）→ WHERE 的 MATCH ftsQuery（1 个）→ instr 链各 term（N 个）。
    val args = terms.toTypedArray() + arrayOf(ftsQuery) + terms.toTypedArray()
    val rows = searchNaturalMultiTermRaw(SimpleSQLiteQuery(buildMultiTermSql(terms.size), args))
    val matches = linkedMapOf<String, ScoredContact>()
    rows.forEach { row ->
        val hitCount = row.termMask.count { it == '1' }
        val firstTermIndex = row.termMask.indexOfFirst { it == '1' }.coerceAtLeast(0)
        val current = matches[row.contactId]
        matches[row.contactId] = if (current == null) {
            ScoredContact(row.toProjection(), hitCount, firstTermIndex)
        } else {
            // 多个合并源解析到同一 canonical 时按行累计命中。
            current.copy(hitCount = current.hitCount + hitCount)
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

private fun ContactSearchProjectionWithMask.toProjection() = ContactSearchProjection(
    contactId = contactId,
    displayName = displayName,
    phone = phone,
    email = email,
    wechatId = wechatId,
    company = company,
    title = title,
    note = note,
)

/** 与 ContactDao.search 相同的 canonical 解析 + fill-only COALESCE,加 termMask;经 @RawQuery 执行。
 *
 * 注意:f 子查询必须提升到 JOIN,原 IN(...) 写法会让外层 SELECT 看不到 f.termMask。
 */
private fun buildMultiTermSql(termCount: Int): String {
    val instrOrs = List(termCount) { "instr(lower(content), lower(?)) > 0" }.joinToString(" OR ")
    val maskCases = List(termCount) { "CASE WHEN instr(lower(content), lower(?)) > 0 THEN '1' ELSE '0' END" }
        .joinToString(" || ")
    return """
        SELECT canonical.contactId, canonical.displayName,
          COALESCE(canonical.phone, (SELECT source.phone FROM contact_merge_links link INNER JOIN contacts source ON source.contactId = link.sourceContactId WHERE link.canonicalContactId = canonical.contactId AND link.undoneAtEpochMs IS NULL AND source.phone IS NOT NULL LIMIT 1)) AS phone,
          COALESCE(canonical.email, (SELECT source.email FROM contact_merge_links link INNER JOIN contacts source ON source.contactId = link.sourceContactId WHERE link.canonicalContactId = canonical.contactId AND link.undoneAtEpochMs IS NULL AND source.email IS NOT NULL LIMIT 1)) AS email,
          COALESCE(canonical.wechatId, (SELECT source.wechatId FROM contact_merge_links link INNER JOIN contacts source ON source.contactId = link.sourceContactId WHERE link.canonicalContactId = canonical.contactId AND link.undoneAtEpochMs IS NULL AND source.wechatId IS NOT NULL LIMIT 1)) AS wechatId,
          COALESCE(canonical.company, (SELECT source.company FROM contact_merge_links link INNER JOIN contacts source ON source.contactId = link.sourceContactId WHERE link.canonicalContactId = canonical.contactId AND link.undoneAtEpochMs IS NULL AND source.company IS NOT NULL LIMIT 1)) AS company,
          COALESCE(canonical.title, (SELECT source.title FROM contact_merge_links link INNER JOIN contacts source ON source.contactId = link.sourceContactId WHERE link.canonicalContactId = canonical.contactId AND link.undoneAtEpochMs IS NULL AND source.title IS NOT NULL LIMIT 1)) AS title,
          COALESCE(canonical.note, (SELECT source.note FROM contact_merge_links link INNER JOIN contacts source ON source.contactId = link.sourceContactId WHERE link.canonicalContactId = canonical.contactId AND link.undoneAtEpochMs IS NULL AND source.note IS NOT NULL LIMIT 1)) AS note,
          f.termMask AS termMask
        FROM contacts canonical
        JOIN (
          SELECT contactId, $maskCases AS termMask
          FROM contact_search_fts
          -- SQLite 限制:MATCH 不能与普通谓词(instr)混在同一个 OR 里,否则报
          -- "unable to use function MATCH in the requested context"。因此 MATCH
          -- 必须独立成 IN 子查询,FTS 整词命中只用于缩小候选集;中文 bigram/子串
          -- 仍由外层 instr OR 链兜底(termMask 也仅由 instr 判定,与 FTS 命中一致)。
          WHERE contactId IN (SELECT contactId FROM contact_search_fts WHERE content MATCH ?)
             OR $instrOrs
        ) f ON canonical.contactId = COALESCE(
          (SELECT m.canonicalContactId FROM contact_merge_links m
           WHERE m.sourceContactId = f.contactId AND m.undoneAtEpochMs IS NULL),
          f.contactId
        )
        WHERE canonical.deletedAtEpochMs IS NULL
          AND canonical.contactId NOT IN (SELECT sourceContactId FROM contact_merge_links WHERE undoneAtEpochMs IS NULL)
        LIMIT 800
    """.trimIndent()
}

private data class ScoredContact(val contact: ContactSearchProjection, val hitCount: Int, val firstTermIndex: Int)

private const val MIN_CONTACT_SEARCH_TERM_LENGTH = 2
private const val HAN_BIGRAM_LENGTH = 2
private const val MAX_CONTACT_SEARCH_TERMS = 16
private val SAFE_WHOLE_QUERY = Regex("[\\p{L}\\p{N}_]+")
private val CHINESE_QUERY_FILLERS = Regex("(?:请|帮|我|你|他|她|它|做|找|查|的|是|有|在|那|这|哪|谁|什么|怎么|如何|一个|和|与|跟|了|过)+")
