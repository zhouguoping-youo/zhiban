package com.zhiban.rebuild.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zhiban.rebuild.ui.theme.ZhiBanTerracotta
import com.zhiban.rebuild.ui.theme.ZhiBanTheme
import com.zhiban.rebuild.ui.agent.AgentTopBar
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ZhiBanPageChromeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun primaryHeaderStartsImmediatelyAfterTheSafeArea() {
        var safeTop = 0.dp
        compose.setContent {
            val density = LocalDensity.current
            safeTop = with(density) { WindowInsets.safeDrawing.getTop(density).toDp() }
            ZhiBanTheme {
                ZhiBanScaffold(
                    showBottomBar = false,
                    currentDestination = null,
                    onTabSelected = {},
                ) {
                    ZhiBanPrimaryTabHeader(
                        title = "关系",
                        subtitle = "联系人",
                        modifier = Modifier.testTag("primary-header"),
                    )
                }
            }
        }

        val top = compose.onNodeWithTag("primary-header").getUnclippedBoundsInRoot().top
        assertDpClose("primary header has no decorative row above it", safeTop, top)
    }

    @Test
    fun secondaryHeaderStartsImmediatelyAfterTheSafeArea() {
        var safeTop = 0.dp
        compose.setContent {
            val density = LocalDensity.current
            safeTop = with(density) { WindowInsets.safeDrawing.getTop(density).toDp() }
            ZhiBanTheme {
                ZhiBanScaffold(
                    showBottomBar = false,
                    currentDestination = null,
                    onTabSelected = {},
                ) {
                    ZhiBanTopBar(
                        title = "个人 CRM",
                        onBack = {},
                        modifier = Modifier.testTag("secondary-header"),
                    )
                }
            }
        }

        val top = compose.onNodeWithTag("secondary-header").getUnclippedBoundsInRoot().top
        assertDpClose("secondary header has no decorative row above it", safeTop, top)
    }

    @Test
    fun primarySecondaryAndConversationTitlesShareOneTopBaseline() {
        compose.setContent {
            ZhiBanTheme {
                Box {
                    ZhiBanPrimaryTabHeader(title = "一级标题", subtitle = "一级说明")
                    ZhiBanTopBar(title = "二级标题", onBack = {})
                    AgentTopBar()
                }
            }
        }

        val primaryTop = compose.onNodeWithText("一级标题").getUnclippedBoundsInRoot().top
        val secondaryTop = compose.onNodeWithText("二级标题").getUnclippedBoundsInRoot().top
        val conversationTop = compose.onNodeWithText("问问").getUnclippedBoundsInRoot().top
        assertDpClose("primary and secondary title baselines match", primaryTop, secondaryTop)
        assertDpClose("primary and conversation title baselines match", primaryTop, conversationTop)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun themeUsesQuietBrandTouchFeedbackInsteadOfDefaultGrey() {
        var color = androidx.compose.ui.graphics.Color.Unspecified
        var pressedAlpha = 1f
        compose.setContent {
            ZhiBanTheme {
                val ripple = checkNotNull(LocalRippleConfiguration.current)
                color = ripple.color
                pressedAlpha = checkNotNull(ripple.rippleAlpha).pressedAlpha
            }
        }

        compose.runOnIdle {
            assertEquals(ZhiBanTerracotta, color)
            assertTrue("pressed feedback should stay subtle", pressedAlpha in 0.06f..0.12f)
        }
    }

    private fun assertDpClose(message: String, expected: Dp, actual: Dp) {
        assertTrue("$message: expected=$expected actual=$actual", abs(expected.value - actual.value) < 0.6f)
    }
}
