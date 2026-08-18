package com.zhiban.rebuild.data.contact.enrichment

import com.zhiban.rebuild.runtime.provider.WebSearchGateway
import com.zhiban.rebuild.runtime.provider.WebSearchHit
import com.zhiban.rebuild.runtime.runSuspendCatching

/**
 * Resolves a company's registered full name from public web search instead of a private registry proxy.
 * Only the short-name hint leaves the device (contact names, phones and emails are forbidden here), and
 * every result stays PENDING evidence carrying the source URL the user can inspect. The gateway never
 * throws: a non-StepFun provider, an offline radio or a search failure all degrade to "no candidates".
 *
 * Web search is not an authoritative registry, so confidence is capped at [MAX_CONFIDENCE] and only full
 * names that themselves contain the short-name hint are ever returned — a corroborated but hint-free name
 * is treated as a different company, never as a completion.
 */
internal class WebSearchCompanyRegistryGateway(private val webSearch: WebSearchGateway, private val clock: () -> Long = System::currentTimeMillis) :
    CompanyRegistryGateway {
    // Always wired; StepFun availability is resolved per search and degrades silently rather than
    // disabling the whole enrichment pass (a synchronous isConfigured cannot await the active profile).
    override val isConfigured: Boolean = true
    override val providerId: String = PROVIDER_ID
    override val sourceLabel: String = SOURCE_LABEL

    private val cache = object : LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean = size > MAX_CACHE_ENTRIES
    }

    override suspend fun search(companyHint: String): List<CompanyRegistryMatch> {
        val hint = companyHint.trim()
        if (CompanyShortNameDetector.classify(hint) != CompanyShortNameDetector.Classification.SUSPECTED_SHORT) return emptyList()
        val cacheKey = normalize(hint)
        cached(cacheKey)?.let { return it }
        val hits = runSuspendCatching { webSearch.search(buildQuery(hint), SEARCH_RESULT_LIMIT) }
            .getOrElse {
                // 瞬态失败(超时/离线/限流)绝不写缓存:把空结果当"真没搜到"缓存会以 30 天 TTL 阻塞
                // 该公司的后续重试(P1-6)。只有真实搜索结果(含成功的空结果)才入缓存。
                return emptyList()
            }
        val matches = score(normalize(hint), hits)
        store(cacheKey, matches)
        return matches
    }

    private fun buildQuery(hint: String): String = "$hint 全称 工商注册信息".take(MAX_QUERY_CHARS)

    private fun score(hint: String, hits: List<WebSearchHit>): List<CompanyRegistryMatch> {
        val aggregates = linkedMapOf<String, CandidateAggregate>()
        hits.forEach { hit ->
            extractFullNames("${hit.title} ${hit.snippet}")
                .map { it.filterNot(Char::isWhitespace) }
                .filter { it.length in hint.length..MAX_COMPANY_NAME_CHARS }
                .filter { it.contains(hint, ignoreCase = true) }
                .forEach { fullName -> aggregates.getOrPut(fullName) { CandidateAggregate(fullName) }.addHit(hit) }
        }
        return aggregates.values
            .map { it.toMatch(hint) }
            .sortedByDescending(CompanyRegistryMatch::confidence)
            .take(MAX_MATCHES)
    }

    private fun extractFullNames(text: String): List<String> = ORG_NAME.findAll(text).mapNotNull { it.groupValues.getOrNull(1)?.trim() }.toList()

    private fun cached(key: String): List<CompanyRegistryMatch>? = synchronized(cache) {
        val entry = cache[key] ?: return@synchronized null
        if (clock() - entry.storedAtEpochMs > CACHE_TTL_MS) {
            cache.remove(key)
            null
        } else {
            entry.matches
        }
    }

    private fun store(key: String, matches: List<CompanyRegistryMatch>) = synchronized(cache) {
        cache[key] = CacheEntry(clock(), matches)
    }

    private fun normalize(value: String): String = value.trim().lowercase().filterNot(Char::isWhitespace)

    private class CandidateAggregate(private val fullName: String) {
        private val hosts = linkedSetOf<String>()
        private var sourceUrl: String? = null
        private var sourceName: String? = null
        private var authoritative = false

        fun addHit(hit: WebSearchHit) {
            val host = hit.url.hostLabel()
            hosts += host
            val authority = authoritativeSource(host)
            if (authority != null && !authoritative) {
                authoritative = true
                sourceUrl = hit.url
                sourceName = authority
            } else if (sourceUrl == null) {
                sourceUrl = hit.url
            }
        }

        fun toMatch(hint: String): CompanyRegistryMatch {
            var confidence = CONTAINS_BASE_CONFIDENCE
            if (authoritative) confidence += AUTHORITATIVE_BONUS
            if (hosts.size >= MIN_SOURCES_FOR_CORROBORATION) confidence += CROSS_DOMAIN_BONUS
            val reasons = mutableListOf("全称包含简称「$hint」")
            sourceName?.let { reasons += "来源：$it" }
            if (hosts.size >= MIN_SOURCES_FOR_CORROBORATION) reasons += "多来源一致"
            return CompanyRegistryMatch(
                providerRecordId = sourceUrl ?: fullName,
                canonicalName = fullName,
                creditCode = null,
                registrationStatus = null,
                registeredAddress = null,
                confidence = confidence.coerceAtMost(MAX_CONFIDENCE),
                matchReasons = reasons,
                sourceUrl = sourceUrl,
            )
        }
    }

    private companion object {
        const val PROVIDER_ID = "company-registry:websearch"
        const val SOURCE_LABEL = "网络公开信息"
        const val SEARCH_RESULT_LIMIT = 5
        const val MAX_QUERY_CHARS = 120
        const val MAX_COMPANY_NAME_CHARS = 30
        const val MAX_MATCHES = 3
        const val MAX_CACHE_ENTRIES = 200
        const val MIN_SOURCES_FOR_CORROBORATION = 2
        const val CONTAINS_BASE_CONFIDENCE = 0.65
        const val AUTHORITATIVE_BONUS = 0.15
        const val CROSS_DOMAIN_BONUS = 0.15
        const val MAX_CONFIDENCE = 0.80
        const val CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1_000

        /** Anchored at a name boundary so a sentence ending in a suffix does not swallow its lead-in. */
        val ORG_NAME = Regex(
            """(?:^|[\s，。、；：:""'【】《》〈〉!?,.\-—…·/\\|<>])""" +
                """([一-龥][一-龥A-Za-z0-9（）()]{1,28}?""" +
                """(?:有限责任公司|股份有限公司|集团有限公司|有限公司|股份公司|有限合伙|事务所|研究院|研究所))""",
        )

        val AUTHORITATIVE_SOURCES = listOf(
            "qcc.com" to "企查查",
            "tianyancha.com" to "天眼查",
            "aiqicha.baidu.com" to "爱企查",
            "qixin.com" to "启信宝",
            "gsxt.gov.cn" to "国家企业信用信息公示系统",
        )

        fun authoritativeSource(host: String): String? = AUTHORITATIVE_SOURCES
            .firstOrNull { (domain, _) -> host == domain || host.endsWith(".$domain") }
            ?.second

        fun String.hostLabel(): String = substringAfter("://", this)
            .substringBefore("/")
            .substringBefore("?")
            .substringAfter("@")
            .substringBefore(":")
            .removePrefix("www.")
            .lowercase()
    }

    private data class CacheEntry(val storedAtEpochMs: Long, val matches: List<CompanyRegistryMatch>)
}
