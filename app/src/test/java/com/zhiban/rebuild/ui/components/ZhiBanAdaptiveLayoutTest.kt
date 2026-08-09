package com.zhiban.rebuild.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ZhiBanAdaptiveLayoutTest {
    @Test
    fun compactWindowsUseBottomNavigation() {
        assertEquals(ZhiBanNavigationMode.BottomBar, zhiBanNavigationModeForWidth(320f))
        assertEquals(ZhiBanNavigationMode.BottomBar, zhiBanNavigationModeForWidth(599f))
    }

    @Test
    fun expandedWindowsUseNavigationRail() {
        assertEquals(ZhiBanNavigationMode.Rail, zhiBanNavigationModeForWidth(600f))
        assertEquals(ZhiBanNavigationMode.Rail, zhiBanNavigationModeForWidth(840f))
    }
}
