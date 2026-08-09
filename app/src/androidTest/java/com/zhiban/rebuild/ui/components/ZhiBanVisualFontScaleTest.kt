package com.zhiban.rebuild.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.zhiban.rebuild.ui.theme.ZhiBanTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ZhiBanVisualFontScaleTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun primaryHeaderExpandsAtDoubleFontScale() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                ZhiBanTheme {
                    ZhiBanPrimaryTabHeader(
                        title = "关系",
                        subtitle = "128 位联系人",
                        modifier = Modifier.testTag("primary-header"),
                    )
                }
            }
        }

        compose.onNodeWithText("关系").assertIsDisplayed()
        compose.onNodeWithText("128 位联系人").assertIsDisplayed()
        val bounds = compose.onNodeWithTag("primary-header").getUnclippedBoundsInRoot()
        val height = bounds.bottom - bounds.top
        assertTrue("header must grow beyond its 64dp minimum", height > 64.dp)
    }

    @Test
    fun singleLineInputRemainsVisibleAtDoubleFontScale() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                ZhiBanTheme {
                    ZhiBanTextInput(
                        value = "两倍字体仍然完整显示",
                        onValueChange = {},
                        placeholder = "请输入",
                        modifier = Modifier.testTag("text-input"),
                    )
                }
            }
        }

        compose.onNodeWithText("两倍字体仍然完整显示").assertIsDisplayed()
        val bounds = compose.onNodeWithTag("text-input").getUnclippedBoundsInRoot()
        val height = bounds.bottom - bounds.top
        assertTrue("input must preserve its 52dp minimum", height >= 52.dp)
    }

    @Test
    fun secondaryHeaderKeepsLongContextAtDoubleFontScale() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                ZhiBanTheme {
                    ZhiBanTopBar(
                        title = "隐私与权限",
                        subtitle = "查看知伴可以使用的手机能力和数据发送范围",
                        onBack = {},
                        modifier = Modifier.testTag("secondary-header"),
                    )
                }
            }
        }

        compose.onNodeWithText("隐私与权限").assertIsDisplayed()
        compose.onNodeWithText("查看知伴可以使用的手机能力和数据发送范围").assertIsDisplayed()
        val bounds = compose.onNodeWithTag("secondary-header").getUnclippedBoundsInRoot()
        assertTrue("secondary header must expand for two-line context", bounds.bottom - bounds.top > 72.dp)
    }

    @Test
    fun saveActionShowsCommittedState() {
        compose.setContent {
            ZhiBanTheme {
                ZhiBanSaveButton(state = ZhiBanSaveState.SAVED, onClick = {})
            }
        }

        compose.onNodeWithText("已保存").assertIsDisplayed()
    }
}
