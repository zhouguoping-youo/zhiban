package com.zhiban.rebuild.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainTabContractTest {
    @Test
    fun `tab labels and order stay frozen`() {
        assertEquals(listOf("日历", "关系", "问问", "能力", "我的"), MainTabContract.tabs.map { it.label })
    }

    @Test
    fun `every tab has a unique meaningful empty state`() {
        val tabs = MainTabContract.tabs
        assertEquals(5, tabs.map { it.emptyTitle }.distinct().size)
        assertTrue(tabs.all { it.pageTitle.isNotBlank() && it.emptyDescription.length >= 10 })
    }
}
