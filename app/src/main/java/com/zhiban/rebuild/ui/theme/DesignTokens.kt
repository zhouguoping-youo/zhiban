package com.zhiban.rebuild.ui.theme

import androidx.compose.ui.unit.dp

/**
 * ZhiBan's single visual scale.
 *
 * Screens should consume these semantic values instead of inventing local
 * spacing, corner radii, icon sizes, or control heights. The scale follows the
 * quiet, compact rhythm used by ChatGPT's mobile surfaces while preserving
 * ZhiBan's terracotta brand accent.
 */
object ZhiBanSpacing {
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 12.dp
    val Lg = 16.dp
    val Xl = 20.dp
    val Xxl = 24.dp
    val Xxxl = 32.dp

    val PageHorizontal = Xl

    /**
     * The app shell already places page content immediately after the real
     * status-bar/cutout safe area. Pages must not add a second decorative row
     * above their header.
     */
    val PageTop = 0.dp
    val PageBottom = Xxl
    val Section = Xxl
    val ContentGap = Md
    val Related = Sm
    val SectionHeaderBottom = Md
}

object ZhiBanRadius {
    val ExtraSmall = 8.dp
    val Small = 10.dp
    val Medium = 14.dp
    val Card = 16.dp
    val Input = 18.dp
    val Dialog = 24.dp
    val Full = 999.dp
}

/**
 * Icon glyph sizes are named by role, not by visual adjectives.
 *
 * Icons in the same role share one 24 dp design canvas. Smaller inline and
 * disclosure glyphs are deliberate exceptions. Interactive containers remain
 * at least 48 dp even when the visible glyph is smaller.
 */
object ZhiBanIconSize {
    /** Status marks, chevrons and icons that sit inside a text line. */
    val Inline = 18.dp

    /** Search and other icons embedded inside form fields. */
    val Field = 20.dp

    /** Leading glyph inside a 40-44 dp list-row surface. */
    val Leading = 22.dp

    /** Toolbar, navigation and standard standalone action glyph. */
    val Action = 22.dp
    val Navigation = Action

    /** Deliberately larger non-interactive illustration for empty states. */
    val EmptyState = 28.dp
}

object ZhiBanIconContainer {
    /** Visual selection/leading surface. It is not the touch target by itself. */
    val Compact = 40.dp

    /** Minimum Android interactive target. */
    val TouchTarget = 48.dp

    /** Emphasized circular action such as the home composer microphone. */
    val Emphasized = 52.dp
}

object ZhiBanSize {
    // Compatibility aliases. New UI should use ZhiBanIconSize directly.
    val IconSmall = ZhiBanIconSize.Inline
    val Icon = ZhiBanIconSize.Action
    val IconLarge = ZhiBanIconSize.Action
    val TouchTarget = ZhiBanIconContainer.TouchTarget
    val Control = 48.dp
    val Input = 52.dp
    val Avatar = 44.dp
    val BottomBar = 64.dp
    val TopBar = 64.dp
    val ListRow = 64.dp
    val ListRowWithSubtitle = 72.dp
    val DialogAction = 48.dp

    /** Material 3 visible switch width; the surrounding touch target remains 48 dp high. */
    val SwitchWidth = 52.dp
}
