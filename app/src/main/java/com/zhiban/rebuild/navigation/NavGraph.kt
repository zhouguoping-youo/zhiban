package com.zhiban.rebuild.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.zhiban.rebuild.ui.agent.AgentConversationRoute
import com.zhiban.rebuild.ui.agent.settings.AgentFeedbackImprovementPage
import com.zhiban.rebuild.ui.agent.settings.AgentMemoryPage
import com.zhiban.rebuild.ui.agent.settings.AgentPersonalizationPage
import com.zhiban.rebuild.ui.agent.settings.AgentRunHistoryPage
import com.zhiban.rebuild.ui.agent.settings.AgentSettingsPage
import com.zhiban.rebuild.ui.agent.settings.AgentSkillsPage
import com.zhiban.rebuild.ui.agent.settings.AgentToolsPage
import com.zhiban.rebuild.ui.agent.settings.UserProfilePage
import com.zhiban.rebuild.ui.components.ZhiBanScaffold
import com.zhiban.rebuild.ui.debug.debugAcceptanceRoute
import com.zhiban.rebuild.ui.settings.AboutZhiBanPage
import com.zhiban.rebuild.ui.settings.AppearanceSettingsPage
import com.zhiban.rebuild.ui.settings.AutoWritePage
import com.zhiban.rebuild.ui.settings.DataSettingsPage
import com.zhiban.rebuild.ui.settings.ModelConfigPage
import com.zhiban.rebuild.ui.settings.NotificationSettingsPage
import com.zhiban.rebuild.ui.settings.PrivacySecurityPage
import com.zhiban.rebuild.ui.settings.ReportErrorSettingsPage
import com.zhiban.rebuild.ui.settings.StorageSettingsPage
import com.zhiban.rebuild.ui.tabs.CalendarTab
import com.zhiban.rebuild.ui.tabs.ContactMaintenancePage
import com.zhiban.rebuild.ui.tabs.CrmCapabilityPage
import com.zhiban.rebuild.ui.tabs.CrmLeadListPage
import com.zhiban.rebuild.ui.tabs.CrmOpportunityBoardPage
import com.zhiban.rebuild.ui.tabs.CrmOpportunityDetailPage
import com.zhiban.rebuild.ui.tabs.CrmOpportunityListPage
import com.zhiban.rebuild.ui.tabs.EventPlanningDetailPage
import com.zhiban.rebuild.ui.tabs.EventPlanningListPage
import com.zhiban.rebuild.ui.tabs.EventPlanningPage
import com.zhiban.rebuild.ui.tabs.LifeAssistantDetailPage
import com.zhiban.rebuild.ui.tabs.LifeAssistantListPage
import com.zhiban.rebuild.ui.tabs.LifeAssistantPage
import com.zhiban.rebuild.ui.tabs.ProfileTab
import com.zhiban.rebuild.ui.tabs.RelationTab
import com.zhiban.rebuild.ui.tabs.SkillTab

@Composable
fun ZhiBanNavHost(modifier: Modifier = Modifier, relationInboxRequest: Long = 0L, callNoteRequest: Long = 0L, calendarFocusRequest: Long = 0L) {
    val navController = rememberNavController()
    var lastHandledRelationInboxRequest by rememberSaveable { mutableLongStateOf(0L) }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    ExternalRequestEffects(navController, relationInboxRequest, callNoteRequest, calendarFocusRequest)
    val showBottomBar = TAB_ROUTES.any { routeClass ->
        currentDestination?.hasRoute(routeClass) == true
    }

    ZhiBanScaffold(
        showBottomBar = showBottomBar,
        currentDestination = currentDestination,
        onTabSelected = { route ->
            if (route == Home) {
                // 问问进入独立全屏对话，不恢复之前离开时保存的设置子页面。
                // Home 不属于 TAB_ROUTES，因此对话页不会显示底部 TabBar。
                navController.navigate(Home) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                }
            } else {
                navController.navigate(route) {
                    // The five main tabs are flat destinations rather than nested tab graphs.
                    // Saving/restoring a flat destination also restores routes opened from it
                    // (for example Skill -> CRM -> Relation), which made tapping Ability reopen
                    // Relation. A tab tap must always resolve to that tab's root destination.
                    popUpTo(navController.graph.findStartDestination().id) { saveState = false }
                    launchSingleTop = true
                }
            }
        },
        modifier = modifier,
    ) { _ ->
        ZhiBanNavContent(
            navController = navController,
            relationInboxRequest = relationInboxRequest,
            lastHandledRelationInboxRequest = lastHandledRelationInboxRequest,
            setLastHandledRelationInboxRequest = { lastHandledRelationInboxRequest = it },
            callNoteRequest = callNoteRequest,
        )
    }
}

@Composable
private fun ExternalRequestEffects(navController: NavHostController, relationInboxRequest: Long, callNoteRequest: Long, calendarFocusRequest: Long) {
    LaunchedEffect(relationInboxRequest) {
        if (relationInboxRequest > 0L) {
            navController.navigate(Relation) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
            }
        }
    }
    LaunchedEffect(callNoteRequest) {
        if (callNoteRequest > 0L) {
            navController.navigate(Relation) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
            }
        }
    }
    LaunchedEffect(calendarFocusRequest) {
        if (calendarFocusRequest > 0L) {
            navController.navigate(Calendar(calendarFocusRequest)) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = false }
                launchSingleTop = true
            }
        }
    }
}

@Composable
private fun ZhiBanNavContent(
    navController: NavHostController,
    relationInboxRequest: Long,
    lastHandledRelationInboxRequest: Long,
    setLastHandledRelationInboxRequest: (Long) -> Unit,
    callNoteRequest: Long,
) {
    var lastHandled = lastHandledRelationInboxRequest
    NavHost(
        navController = navController,
        startDestination = Calendar(),
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
    ) {
        // 问问 TAB 直接进入全屏对话，不经过独立引导页。
        composable<Home> {
            AgentConversationRoute(
                initialDraft = "",
                onBackToHome = {
                    navController.navigate(Calendar()) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = false }
                        launchSingleTop = true
                    }
                },
                onNavigateToSettings = { navController.navigate(ModelConfig) { launchSingleTop = true } },
                onManagePlugins = { navController.navigate(Skill) { launchSingleTop = true } },
            )
        }
        composable<Calendar> { entry ->
            val route = entry.toRoute<Calendar>()
            CalendarTab(focusDateEpochMs = route.focusDateEpochMs.takeIf { it > 0L })
        }
        composable<Relation> {
            RelationTab(
                openInboxRequest = relationInboxRequest.takeIf { it > lastHandled } ?: 0L,
                onInboxRequestHandled = { request ->
                    lastHandled = maxOf(lastHandled, request)
                    setLastHandledRelationInboxRequest(lastHandled)
                },
                openCallNoteRequest = callNoteRequest,
                onOwnerClick = { navController.navigate(ProfileEdit) },
                onOpenAutoWrites = { navController.navigate(AutoWrites) },
                onOpenContactMaintenance = { navController.navigate(ContactMaintenance) },
            )
        }
        composable<Skill> {
            SkillTab(
                onOpenCrm = { navController.navigate(CrmCapability) },
                onOpenLifeAssistant = { navController.navigate(LifeAssistant) },
                onOpenEventPlanning = { navController.navigate(EventPlanning) },
            )
        }
        composable<Profile> {
            ProfileTab(
                onNavigateToAgentSettings = { navController.navigate(AgentSettings) },
                onNavigateToAutoWrites = { navController.navigate(AutoWrites) },
                onNavigateToAgentSuggestions = { navController.navigate(AgentSuggestions) },
                onNavigateToProfileEdit = { navController.navigate(ProfileEdit) },
                onNavigateToPrivacySecurity = { navController.navigate(PrivacySecurity) },
                onNavigateToAppearance = { navController.navigate(Appearance) },
                onNavigateToNotificationSettings = { navController.navigate(NotificationSettings) },
                onNavigateToStorage = { navController.navigate(StorageSettings) },
                onNavigateToData = { navController.navigate(DataSettings) },
                onNavigateToReportError = { navController.navigate(ReportErrorSettings) },
                onNavigateToAbout = { navController.navigate(AboutZhiBan) },
            )
        }

        agentSettingsRoutes(navController)
        featureRoutes(navController)
        composable<AssistantChat> { entry ->
            val route = entry.toRoute<AssistantChat>()
            AgentConversationRoute(
                initialDraft = route.draft,
                initialMode = if (route.workContext) "Work" else "Chat",
                onManagePlugins = { navController.navigate(Skill) { launchSingleTop = true } },
                onNavigateToSettings = { navController.navigate(ModelConfig) { launchSingleTop = true } },
                onBackToHome = {
                    if (route.returnTarget == "BACK" && navController.popBackStack()) {
                        Unit
                    } else {
                        navController.navigate(Calendar()) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = false }
                            launchSingleTop = true
                        }
                    }
                },
            )
        }

        composable<ModelConfig> {
            ModelConfigPage(
                onBack = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Profile) { launchSingleTop = true }
                    }
                },
            )
        }
        debugAcceptanceRoute(navController)
    }
}
