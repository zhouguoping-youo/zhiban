package com.zhiban.rebuild.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zhiban.rebuild.ui.agent.AgentTopBar
import com.zhiban.rebuild.ui.theme.ZhiBanTerracotta
import com.zhiban.rebuild.ui.theme.ZhiBanTheme
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
    fun secondaryAndConversationTitlesShareOneTopBaseline() {
        compose.setContent {
            ZhiBanTheme {
                Box {
                    ZhiBanTopBar(title = "二级标题", onBack = {})
                    AgentTopBar()
                }
            }
        }

        val secondaryTop = compose.onNodeWithText("二级标题").getUnclippedBoundsInRoot().top
        val conversationTop = compose.onNodeWithText("问问").getUnclippedBoundsInRoot().top
        assertDpClose("secondary and conversation titles share one top baseline", secondaryTop, conversationTop)
    }

    @Test
    fun secondaryTopBarAlignsBackArrowWithTitleRow() {
        compose.setContent {
            ZhiBanTheme {
                ZhiBanTopBar(title = "个人资料", onBack = {})
            }
        }

        val backCenter = compose.onNodeWithContentDescription("返回").verticalCenterDp()
        val titleCenter = compose.onNodeWithText("个人资料").verticalCenterDp()
        assertRowAligned("secondary header back arrow and title share one row", backCenter, titleCenter)
    }

    @Test
    fun conversationTopBarAlignsBackArrowWithTitleRow() {
        compose.setContent {
            ZhiBanTheme {
                AgentTopBar()
            }
        }

        val backCenter = compose.onNodeWithContentDescription("返回").verticalCenterDp()
        val titleCenter = compose.onNodeWithText("问问").verticalCenterDp()
        assertRowAligned("conversation header back arrow and title share one row", backCenter, titleCenter)
    }

    private fun SemanticsNodeInteraction.verticalCenterDp(): Float {
        val bounds = getUnclippedBoundsInRoot()
        return (bounds.top.value + bounds.bottom.value) / 2f
    }

    private fun assertRowAligned(message: String, expected: Float, actual: Float) {
        assertTrue("$message: expectedCenterY=$expected actualCenterY=$actual", abs(expected - actual) < 1.0f)
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
