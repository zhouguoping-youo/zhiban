package com.zhiban.rebuild.ui.components

import android.content.Context
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
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import com.zhiban.rebuild.ui.icons.AskIcon
import com.zhiban.rebuild.ui.icons.CalendarIcon
import com.zhiban.rebuild.ui.icons.ProfileIcon
import com.zhiban.rebuild.ui.icons.RelationIcon
import com.zhiban.rebuild.ui.icons.SkillGridIcon
import com.zhiban.rebuild.ui.theme.ZhiBanIconContainer
import com.zhiban.rebuild.ui.theme.ZhiBanIconSize
import com.zhiban.rebuild.ui.theme.ZhiBanRadius
import com.zhiban.rebuild.ui.theme.ZhiBanSize
import com.zhiban.rebuild.ui.theme.ZhiBanSpacing
import com.zhiban.rebuild.ui.theme.ZhiBanTerracotta
import com.zhiban.rebuild.ui.theme.ZhiBanTerracottaSoft

private val BottomBarReservedHeight = 104.dp
private val BottomBarHeight = ZhiBanSize.BottomBar
private val BottomBarBottomPadding = ZhiBanSpacing.Md
private val BottomBarContainerHeight = ZhiBanSize.BottomBar + BottomBarBottomPadding
private val BottomTabIconSize = ZhiBanIconSize.Navigation
private val BottomBarShape = RoundedCornerShape(ZhiBanRadius.Full)
private val NavigationRailWidth = 64.dp
private val NavigationRailOuterPadding = ZhiBanSpacing.Md
private const val NAVIGATION_HINTS_PREFERENCES = "navigation_hints"
private const val FIRST_USE_HINT_SHOWN_KEY = "bottom_tabs_first_use_hint_shown"

@Composable
fun ZhiBanScaffold(
    showBottomBar: Boolean,
    currentDestination: NavDestination?,
    onTabSelected: (Any) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val navigationHintPreferences = remember(context) {
        context.getSharedPreferences(NAVIGATION_HINTS_PREFERENCES, Context.MODE_PRIVATE)
    }
    var firstUseHintPending by remember {
        mutableStateOf(!navigationHintPreferences.getBoolean(FIRST_USE_HINT_SHOWN_KEY, false))
    }
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
                            bottom = if (usesBottomNavigation) BottomBarReservedHeight else 0.dp,
                        ),
                ) { content(PaddingValues()) }
                if (usesBottomNavigation) {
                    ZhiBanBottomBar(
                        currentDestination = currentDestination,
                        onTabSelected = onTabSelected,
                        showFirstUseHint = firstUseHintPending,
                        onFirstUseHintShown = {
                            navigationHintPreferences.edit().putBoolean(FIRST_USE_HINT_SHOWN_KEY, true).apply()
                            firstUseHintPending = false
                        },
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
            CalendarIcon(selected = selected, color = navigationIconColor(selected))
        }
        RailNavItem(
            tabs.getValue("relation").label,
            currentDestination?.hasRoute(Relation::class) == true,
            { onTabSelected(Relation) },
        ) { selected ->
            RelationIcon(selected = selected, color = navigationIconColor(selected))
        }
        RailNavItem(
            tabs.getValue("home").label,
            currentDestination?.hasRoute(Home::class) == true,
            { onTabSelected(Home) },
        ) { selected ->
            AskIcon(selected = selected, color = navigationIconColor(selected))
        }
        RailNavItem(
            tabs.getValue("skill").label,
            currentDestination?.hasRoute(Skill::class) == true,
            { onTabSelected(Skill) },
        ) { selected ->
            SkillGridIcon(selected = selected, color = navigationIconColor(selected))
        }
        RailNavItem(
            tabs.getValue("profile").label,
            currentDestination?.hasRoute(Profile::class) == true,
            { onTabSelected(Profile) },
        ) { selected ->
            ProfileIcon(selected = selected, color = navigationIconColor(selected))
        }
    }
}

@Composable
fun ZhiBanBottomBar(
    currentDestination: NavDestination?,
    onTabSelected: (Any) -> Unit,
    modifier: Modifier = Modifier,
    showFirstUseHint: Boolean = false,
    onFirstUseHintShown: () -> Unit = {},
) {
    val tabs = MainTabContract.tabs.associateBy { it.key }
    Box(
        modifier = modifier
            .navigationBarsPadding()
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .padding(horizontal = ZhiBanSpacing.Xxl)
            .height(BottomBarContainerHeight),
    ) {
        Box(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().height(BottomBarHeight)
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
                Alignment.TopCenter,
            ).fillMaxWidth().height(BottomBarHeight).padding(horizontal = ZhiBanSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val calendarSelected = currentDestination?.hasRoute(Calendar::class) == true
            TabNavItem(tabs.getValue("calendar").label, calendarSelected, {
                onTabSelected(Calendar())
            }, Modifier.weight(1f), showFirstUseHint && calendarSelected, onFirstUseHintShown) { selected ->
                CalendarIcon(
                    selected = selected,
                    color = navigationIconColor(selected),
                    modifier = Modifier.size(BottomTabIconSize),
                )
            }
            TabNavItem(tabs.getValue("relation").label, currentDestination?.hasRoute(Relation::class) == true, {
                onTabSelected(Relation)
            }, Modifier.weight(1f)) { selected ->
                RelationIcon(
                    selected = selected,
                    color = navigationIconColor(selected),
                    modifier = Modifier.size(BottomTabIconSize),
                )
            }
            TabNavItem(tabs.getValue("home").label, currentDestination?.hasRoute(Home::class) == true, {
                onTabSelected(Home)
            }, Modifier.weight(1f)) { selected ->
                AskIcon(
                    selected = selected,
                    color = navigationIconColor(selected),
                    modifier = Modifier.size(BottomTabIconSize),
                )
            }
            TabNavItem(tabs.getValue("skill").label, currentDestination?.hasRoute(Skill::class) == true, {
                onTabSelected(Skill)
            }, Modifier.weight(1f)) { selected ->
                SkillGridIcon(
                    selected = selected,
                    color = navigationIconColor(selected),
                    modifier = Modifier.size(BottomTabIconSize),
                )
            }
            TabNavItem(tabs.getValue("profile").label, currentDestination?.hasRoute(Profile::class) == true, {
                onTabSelected(Profile)
            }, Modifier.weight(1f)) { selected ->
                ProfileIcon(
                    selected = selected,
                    color = navigationIconColor(selected),
                    modifier = Modifier.size(BottomTabIconSize),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabNavItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showInitialHint: Boolean = false,
    onInitialHintShown: () -> Unit = {},
    iconContent: @Composable (Boolean) -> Unit,
) {
    val tooltipState = rememberTooltipState()
    LaunchedEffect(showInitialHint) {
        if (showInitialHint) {
            onInitialHintShown()
            tooltipState.show()
        }
    }
    Box(modifier = modifier.requiredHeightIn(min = ZhiBanSize.TouchTarget)) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text(label) } },
            state = tooltipState,
            focusable = false,
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier.fillMaxSize().semantics {
                    contentDescription = label
                }.selectable(selected = selected, role = Role.Tab, onClick = onClick),
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
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RailNavItem(label: String, selected: Boolean, onClick: () -> Unit, iconContent: @Composable (Boolean) -> Unit) {
    val tooltipState = rememberTooltipState()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = tooltipState,
        focusable = false,
    ) {
        Box(
            modifier = Modifier
                .size(ZhiBanSize.TouchTarget)
                .semantics { contentDescription = label }
                .selectable(selected = selected, role = Role.Tab, onClick = onClick),
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
}

@Composable
private fun navigationIconColor(selected: Boolean): Color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
