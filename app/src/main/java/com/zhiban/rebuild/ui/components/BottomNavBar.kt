package com.zhiban.rebuild.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import com.zhiban.rebuild.navigation.Calendar
import com.zhiban.rebuild.navigation.Home
import com.zhiban.rebuild.navigation.MainTabContract
import com.zhiban.rebuild.navigation.Profile
import com.zhiban.rebuild.navigation.Relation
import com.zhiban.rebuild.navigation.Skill
import com.zhiban.rebuild.ui.theme.ZhiBanIconContainer
import com.zhiban.rebuild.ui.theme.ZhiBanIconSize
import com.zhiban.rebuild.ui.theme.ZhiBanRadius
import com.zhiban.rebuild.ui.theme.ZhiBanSize
import com.zhiban.rebuild.ui.theme.ZhiBanSpacing
import com.zhiban.rebuild.ui.theme.ZhiBanTerracotta
import com.zhiban.rebuild.ui.theme.ZhiBanTerracottaSoft

private val BottomBarReservedHeight = 104.dp
private val BottomBarHeight = ZhiBanSize.BottomBar
private val BottomBarContainerHeight = ZhiBanSize.BottomBar
private val BottomBarBottomPadding = ZhiBanSpacing.Md
private val BottomTabIconSize = ZhiBanIconSize.Navigation
private val BottomBarShape = RoundedCornerShape(ZhiBanRadius.Full)
private val NavigationRailWidth = 64.dp
private val NavigationRailOuterPadding = ZhiBanSpacing.Md

@Composable
fun ZhiBanScaffold(
    showBottomBar: Boolean,
    currentDestination: NavDestination?,
    onTabSelected: (Any) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    val density = LocalDensity.current
    val isKeyboardVisible = WindowInsets.ime.getBottom(density) > 0
    val showBottomBarNow = showBottomBar && !isKeyboardVisible
    BoxWithConstraints(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val navigationMode = if (showBottomBarNow) {
            zhiBanNavigationModeForWidth(maxWidth.value)
        } else {
            null
        }
        val usesBottomNavigation = navigationMode == ZhiBanNavigationMode.BottomBar
        val contentInsets = if (usesBottomNavigation) {
            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
        } else {
            WindowInsets.safeDrawing
        }

        Row(Modifier.fillMaxSize()) {
            if (navigationMode == ZhiBanNavigationMode.Rail) {
                ZhiBanNavigationRail(
                    currentDestination = currentDestination,
                    onTabSelected = onTabSelected,
                )
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(max = ZHIBAN_CONTENT_MAX_WIDTH_DP.dp)
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .windowInsetsPadding(contentInsets)
                        .padding(
                            top = ZhiBanSpacing.Sm,
                            bottom = if (usesBottomNavigation) BottomBarReservedHeight else 0.dp,
                        ),
                ) { content(PaddingValues()) }
                if (usesBottomNavigation) {
                    ZhiBanBottomBar(
                        currentDestination = currentDestination,
                        onTabSelected = onTabSelected,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }
}

@Composable
private fun ZhiBanNavigationRail(currentDestination: NavDestination?, onTabSelected: (Any) -> Unit) {
    val tabs = MainTabContract.tabs.associateBy { it.key }
    Column(
        modifier = Modifier
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical),
            )
            .padding(start = NavigationRailOuterPadding, top = ZhiBanSpacing.Sm, bottom = ZhiBanSpacing.Sm)
            .width(NavigationRailWidth)
            .fillMaxHeight()
            .shadow(
                14.dp,
                BottomBarShape,
                ambientColor = Color.Black.copy(alpha = 0.08f),
                spotColor = Color.Black.copy(alpha = 0.12f),
            )
            .clip(BottomBarShape)
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = ZhiBanSpacing.Sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        RailNavItem(
            tabs.getValue("calendar").label,
            currentDestination?.hasRoute(Calendar::class) == true,
            { onTabSelected(Calendar()) },
        ) { selected ->
            NavigationIcon(Icons.Outlined.CalendarMonth, selected)
        }
        RailNavItem(
            tabs.getValue("relation").label,
            currentDestination?.hasRoute(Relation::class) == true,
            { onTabSelected(Relation) },
        ) { selected ->
            NavigationIcon(Icons.Outlined.Hub, selected)
        }
        RailNavItem(
            tabs.getValue("home").label,
            currentDestination?.hasRoute(Home::class) == true,
            { onTabSelected(Home) },
        ) { selected ->
            NavigationIcon(Icons.Outlined.ChatBubbleOutline, selected)
        }
        RailNavItem(
            tabs.getValue("skill").label,
            currentDestination?.hasRoute(Skill::class) == true,
            { onTabSelected(Skill) },
        ) { selected ->
            NavigationIcon(Icons.Outlined.Extension, selected)
        }
        RailNavItem(
            tabs.getValue("profile").label,
            currentDestination?.hasRoute(Profile::class) == true,
            { onTabSelected(Profile) },
        ) { selected ->
            NavigationIcon(Icons.Outlined.PersonOutline, selected)
        }
    }
}

@Composable
fun ZhiBanBottomBar(currentDestination: NavDestination?, onTabSelected: (Any) -> Unit, modifier: Modifier = Modifier) {
    val tabs = MainTabContract.tabs.associateBy { it.key }
    Box(
        modifier = modifier
            .navigationBarsPadding()
            .padding(bottom = BottomBarBottomPadding)
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .padding(horizontal = ZhiBanSpacing.Xxl)
            .height(BottomBarContainerHeight),
    ) {
        Box(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(BottomBarHeight)
                .shadow(
                    14.dp,
                    BottomBarShape,
                    ambientColor = Color.Black.copy(alpha = 0.08f),
                    spotColor = Color.Black.copy(alpha = 0.12f),
                )
                .clip(BottomBarShape).background(MaterialTheme.colorScheme.surface),
        )
        Row(
            modifier = Modifier.align(
                Alignment.BottomCenter,
            ).fillMaxWidth().height(BottomBarHeight).padding(horizontal = ZhiBanSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TabNavItem(tabs.getValue("calendar").label, currentDestination?.hasRoute(Calendar::class) == true, {
                onTabSelected(Calendar())
            }, Modifier.weight(1f)) { selected ->
                Icon(
                    Icons.Outlined.CalendarMonth,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(BottomTabIconSize),
                )
            }
            TabNavItem(tabs.getValue("relation").label, currentDestination?.hasRoute(Relation::class) == true, {
                onTabSelected(Relation)
            }, Modifier.weight(1f)) { selected ->
                Icon(
                    Icons.Outlined.Hub,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(BottomTabIconSize),
                )
            }
            TabNavItem(tabs.getValue("home").label, currentDestination?.hasRoute(Home::class) == true, {
                onTabSelected(Home)
            }, Modifier.weight(1f)) { selected ->
                Icon(
                    Icons.Outlined.ChatBubbleOutline,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(BottomTabIconSize),
                )
            }
            TabNavItem(tabs.getValue("skill").label, currentDestination?.hasRoute(Skill::class) == true, {
                onTabSelected(Skill)
            }, Modifier.weight(1f)) { selected ->
                Icon(
                    Icons.Outlined.Extension,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(BottomTabIconSize),
                )
            }
            TabNavItem(tabs.getValue("profile").label, currentDestination?.hasRoute(Profile::class) == true, {
                onTabSelected(Profile)
            }, Modifier.weight(1f)) { selected ->
                Icon(
                    Icons.Outlined.PersonOutline,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(BottomTabIconSize),
                )
            }
        }
    }
}

@Composable
private fun TabNavItem(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, iconContent: @Composable (Boolean) -> Unit) {
    Box(
        modifier = modifier.height(ZhiBanSize.TouchTarget).semantics {
            contentDescription = label
        }.clickable(role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(ZhiBanIconContainer.Compact).clip(CircleShape)
                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            iconContent(selected)
        }
    }
}

@Composable
private fun RailNavItem(label: String, selected: Boolean, onClick: () -> Unit, iconContent: @Composable (Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .size(ZhiBanSize.TouchTarget)
            .semantics { contentDescription = label }
            .clickable(role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(ZhiBanIconContainer.Compact)
                .clip(CircleShape)
                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            iconContent(selected)
        }
    }
}

@Composable
private fun NavigationIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean) {
    Icon(
        icon,
        contentDescription = null,
        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(BottomTabIconSize),
    )
}
