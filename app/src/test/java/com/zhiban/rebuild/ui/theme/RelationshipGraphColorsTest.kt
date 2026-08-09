package com.zhiban.rebuild.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class RelationshipGraphColorsTest {
    @Test
    fun `node labels retain accessible contrast in light and dark themes`() {
        listOf(LightRelationshipGraphColors, DarkRelationshipGraphColors).forEach { palette ->
            val pairs = listOf(
                palette.focusNode to palette.onFocusNode,
                palette.contactNode to palette.onContactNode,
                palette.workNode to palette.onWorkNode,
            )
            pairs.forEach { (background, foreground) ->
                assertTrue(contrastRatio(background, foreground) >= 4.5f)
            }
        }
    }

    private fun contrastRatio(first: Color, second: Color): Float {
        val lighter = maxOf(first.luminance(), second.luminance())
        val darker = minOf(first.luminance(), second.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }
}
