package com.zhiban.rebuild.data.contact.enrichment

import com.zhiban.rebuild.data.contact.enrichment.CompanyShortNameDetector.Classification
import org.junit.Assert.assertEquals
import org.junit.Test

class CompanyShortNameDetectorTest {
    @Test
    fun `short brand names are completion candidates`() {
        assertEquals(Classification.SUSPECTED_SHORT, CompanyShortNameDetector.classify("星河科技"))
        assertEquals(Classification.SUSPECTED_SHORT, CompanyShortNameDetector.classify("华为"))
        assertEquals(Classification.SUSPECTED_SHORT, CompanyShortNameDetector.classify("平凯星辰"))
        assertEquals(Classification.SUSPECTED_SHORT, CompanyShortNameDetector.classify("小米科技"))
        // A generic "公司" tail is not a registered suffix, so this is still treated as a short name.
        assertEquals(Classification.SUSPECTED_SHORT, CompanyShortNameDetector.classify("小米公司"))
    }

    @Test
    fun `registered full names need no completion`() {
        assertEquals(Classification.FULL_NAME, CompanyShortNameDetector.classify("华为技术有限公司"))
        assertEquals(Classification.FULL_NAME, CompanyShortNameDetector.classify("北京小米科技有限责任公司"))
        assertEquals(Classification.FULL_NAME, CompanyShortNameDetector.classify("平凯星辰（北京）科技有限公司"))
        assertEquals(Classification.FULL_NAME, CompanyShortNameDetector.classify("中国某某设计研究院"))
    }

    @Test
    fun `obvious non companies are skipped`() {
        assertEquals(Classification.NOT_COMPANY, CompanyShortNameDetector.classify(null))
        assertEquals(Classification.NOT_COMPANY, CompanyShortNameDetector.classify(""))
        assertEquals(Classification.NOT_COMPANY, CompanyShortNameDetector.classify("  "))
        assertEquals(Classification.NOT_COMPANY, CompanyShortNameDetector.classify("无"))
        assertEquals(Classification.NOT_COMPANY, CompanyShortNameDetector.classify("个体"))
        assertEquals(Classification.NOT_COMPANY, CompanyShortNameDetector.classify("自由职业"))
        assertEquals(Classification.NOT_COMPANY, CompanyShortNameDetector.classify("A")) // too short
        assertEquals(Classification.NOT_COMPANY, CompanyShortNameDetector.classify("IBM")) // no CJK characters
    }

    @Test
    fun `city names containing a blocklist substring still classify as companies`() {
        // "无" is only a whole-field blocklist entry, never a substring match.
        assertEquals(Classification.SUSPECTED_SHORT, CompanyShortNameDetector.classify("无锡报业"))
    }
}
