package com.zhiban.rebuild.ui.components

/**
 * Window-driven layout policy. The app adapts to the space it currently owns,
 * so split screen and foldable posture changes follow the same rules as phones.
 */
internal enum class ZhiBanNavigationMode {
    BottomBar,
    Rail,
}

internal const val ZHIBAN_EXPANDED_NAVIGATION_BREAKPOINT_DP = 600f
internal const val ZHIBAN_CONTENT_MAX_WIDTH_DP = 840f

internal fun zhiBanNavigationModeForWidth(windowWidthDp: Float): ZhiBanNavigationMode = if (windowWidthDp < ZHIBAN_EXPANDED_NAVIGATION_BREAKPOINT_DP) {
    ZhiBanNavigationMode.BottomBar
} else {
    ZhiBanNavigationMode.Rail
}
