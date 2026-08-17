package com.zhiban.rebuild.data.contact.enrichment

/**
 * Classifies a free-text company field before any network lookup so that only plausible short names are
 * expanded: already-complete registered names (FULL_NAME) and obvious non-companies (NOT_COMPANY) never
 * trigger a search. Pure and deterministic, so it is safe to run on-device for every imported contact.
 */
internal object CompanyShortNameDetector {
    enum class Classification { SUSPECTED_SHORT, FULL_NAME, NOT_COMPANY }

    fun classify(rawCompany: String?): Classification {
        val value = rawCompany?.trim().orEmpty()
        if (value.length < MIN_COMPANY_CHARS || value.length > MAX_COMPANY_CHARS) return Classification.NOT_COMPANY
        if (value.any(Char::isDigit) && value.none(::isCjk)) return Classification.NOT_COMPANY
        if (value.none(::isCjk)) return Classification.NOT_COMPANY
        if (value in NON_COMPANY_EXACT) return Classification.NOT_COMPANY
        if (FULL_NAME_SUFFIXES.any(value::endsWith)) return Classification.FULL_NAME
        return Classification.SUSPECTED_SHORT
    }

    private fun isCjk(char: Char): Boolean = char in '\u4e00'..'\u9fa5'

    private const val MIN_COMPANY_CHARS = 2
    private const val MAX_COMPANY_CHARS = 40

    /** Registered-name organisation suffixes: a value ending in one of these is already a full name. */
    private val FULL_NAME_SUFFIXES = listOf(
        "有限责任公司",
        "股份有限公司",
        "集团有限公司",
        "有限公司",
        "股份公司",
        "有限合伙",
        "普通合伙",
        "事务所",
        "研究院",
        "研究所",
    )

    /** Whole-field values people enter to mean "no company". Matched exactly so city names like 无锡 survive. */
    private val NON_COMPANY_EXACT = setOf(
        "无", "暂无", "没有", "无业", "个体", "个体户", "自由职业", "待业", "退休", "在家", "个人", "全职妈妈", "保密",
    )
}
