package com.zhiban.rebuild.data.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityTraversalBudgetTest {
    @Test
    fun traversalStopsAtNodeDepthAndVisibleLineLimits() {
        val nodeLimited = AccessibilityTraversalBudget(maxNodes = 2, maxDepth = 10, maxVisibleLines = 10)
        assertTrue(nodeLimited.enter(depth = 0, visibleLineCount = 0))
        assertTrue(nodeLimited.enter(depth = 1, visibleLineCount = 0))
        assertFalse(nodeLimited.enter(depth = 2, visibleLineCount = 0))

        val depthLimited = AccessibilityTraversalBudget(maxNodes = 10, maxDepth = 1, maxVisibleLines = 10)
        assertFalse(depthLimited.enter(depth = 2, visibleLineCount = 0))

        val lineLimited = AccessibilityTraversalBudget(maxNodes = 10, maxDepth = 10, maxVisibleLines = 1)
        assertFalse(lineLimited.enter(depth = 0, visibleLineCount = 1))
    }
}
