package com.zhiban.rebuild.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.zhiban.rebuild.ui.agent.AgentConversationRoute
import com.zhiban.rebuild.ui.agent.settings.AgentBehaviorSecurityPage
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
import com.zhiban.rebuild.ui.settings.LanguageSettingsPage
import com.zhiban.rebuild.ui.settings.ModelConfigPage
import com.zhiban.rebuild.ui.settings.NotificationSettingsPage
import com.zhiban.rebuild.ui.settings.PrivacySecurityPage
import com.zhiban.rebuild.ui.settings.ReportErrorSettingsPage
import com.zhiban.rebuild.ui.settings.StorageSettingsPage
import com.zhiban.rebuild.ui.tabs.CalendarTab
import com.zhiban.rebuild.ui.tabs.CrmCapabilityPage
import com.zhiban.rebuild.ui.tabs.CrmLeadListPage
import com.zhiban.rebuild.ui.tabs.CrmOpportunityBoardPage
import com.zhiban.rebuild.ui.tabs.CrmOpportunityDetailPage
import com.zhiban.rebuild.ui.tabs.CrmOpportunityListPage
import com.zhiban.rebuild.ui.tabs.HomeTab
import com.zhiban.rebuild.ui.tabs.ProfileTab
import com.zhiban.rebuild.ui.tabs.RelationTab
import com.zhiban.rebuild.ui.tabs.SkillTab

@Composable
fun ZhiBanNavHost(modifier: Modifier = Modifier, relationInboxRequest: Long = 0L, callNoteRequest: Long = 0L, calendarFocusRequest: Long = 0L) {
    val navController = rememberNavController()
    var internalRelationInboxRequest by remember { mutableLongStateOf(0L) }
    var lastHandledRelationInboxRequest by rememberSaveable { mutableLongStateOf(0L) }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

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
        NavHost(
            navController = navController,
            startDestination = Calendar(),
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
        ) {
            // Per architect 818 派单 V: tap 问问 TAB (Home route) → 直接
            // AssistantChat 主屏 (无 HomeTab 之前置: 晚上好 greeting + ⚡
            // 大按钮 + "还没有对话" hint). HomeTab 之前是 "chat 引导页",
            // 老周 A+B 反馈: 直接对话界面, 不需 entry hero.
            composable<Home> {
                AgentConversationRoute(
                    initialDraft = "",
                    onBackToHome = {
                        navController.navigate(Calendar()) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = false }
                            launchSingleTop = true
                        }
                    },
                    onNavigateToSettings = { navController.navigate(Profile) { launchSingleTop = true } },
                    onManagePlugins = { navController.navigate(Skill) { launchSingleTop = true } },
                )
            }
            composable<Calendar> { entry ->
                val route = entry.toRoute<Calendar>()
                CalendarTab(focusDateEpochMs = route.focusDateEpochMs.takeIf { it > 0L })
            }
            composable<Relation> {
                val latestInboxRequest = maxOf(relationInboxRequest, internalRelationInboxRequest)
                RelationTab(
                    openInboxRequest = latestInboxRequest.takeIf { it > lastHandledRelationInboxRequest } ?: 0L,
                    onInboxRequestHandled = { request ->
                        lastHandledRelationInboxRequest = maxOf(lastHandledRelationInboxRequest, request)
                    },
                    openCallNoteRequest = callNoteRequest,
                    onOwnerClick = { navController.navigate(ProfileEdit) },
                    onOpenAutoWrites = { navController.navigate(AutoWrites) },
                )
            }
            composable<Skill> {
                SkillTab(
                    onOpenCrm = { navController.navigate(CrmCapability) },
                )
            }
            composable<Profile> {
                ProfileTab(
                    onNavigateToAgentSettings = { navController.navigate(AgentSettings) },
                    onNavigateToAutoWrites = { navController.navigate(AutoWrites) },
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

            composable<AgentSettings> {
                AgentSettingsPage(
                    onBack = { navController.popBackStack() },
                    onPersonalization = { navController.navigate(ConversationStyle) },
                    onMemory = { navController.navigate(MemoryConfig) },
                    onModel = { navController.navigate(ModelConfig) },
                    onTools = { navController.navigate(AgentTools) },
                    onSkills = { navController.navigate(AgentSkills) },
                    onBehavior = { navController.navigate(AgentBehaviorSecurity) },
                    onFeedback = { navController.navigate(AgentFeedbackImprovement) },
                    onRunHistory = { navController.navigate(AgentRunHistory) },
                )
            }
            composable<ProfileEdit> { UserProfilePage(onBack = { navController.popBackStack() }) }
            composable<ConversationStyle> { AgentPersonalizationPage(onBack = { navController.popBackStack() }) }
            composable<MemoryConfig> { AgentMemoryPage(onBack = { navController.popBackStack() }) }
            composable<AgentTools> { AgentToolsPage(onBack = { navController.popBackStack() }) }
            composable<AgentSkills> { AgentSkillsPage(onBack = { navController.popBackStack() }) }
            composable<AgentBehaviorSecurity> { AgentBehaviorSecurityPage(onBack = { navController.popBackStack() }) }
            composable<AgentFeedbackImprovement> {
                AgentFeedbackImprovementPage(onBack = { navController.popBackStack() })
            }
            composable<AgentRunHistory> { AgentRunHistoryPage(onBack = { navController.popBackStack() }) }
            composable<AutoWrites> { AutoWritePage(onBack = { navController.popBackStack() }) }
            composable<LanguageSettings> { LanguageSettingsPage(onBack = { navController.popBackStack() }) }
            composable<NotificationSettings> { NotificationSettingsPage(onBack = { navController.popBackStack() }) }
            composable<PrivacySecurity> {
                PrivacySecurityPage(
                    onBack = { navController.popBackStack() },
                    onOpenMemory = { navController.navigate(MemoryConfig) },
                    onOpenTools = { navController.navigate(AgentTools) },
                )
            }
            composable<StorageSettings> { StorageSettingsPage(onBack = { navController.popBackStack() }) }
            composable<DataSettings> {
                DataSettingsPage(
                    onBack = { navController.popBackStack() },
                    onMemory = { navController.navigate(MemoryConfig) },
                    onRunHistory = { navController.navigate(AgentRunHistory) },
                )
            }
            composable<ReportErrorSettings> {
                ReportErrorSettingsPage(
                    onBack = { navController.popBackStack() },
                    onDiagnostics = { navController.navigate(AgentRunHistory) },
                )
            }
            composable<AboutZhiBan> { AboutZhiBanPage(onBack = { navController.popBackStack() }) }
            composable<Appearance> { AppearanceSettingsPage(onBack = { navController.popBackStack() }) }

            composable<CrmCapability> {
                CrmCapabilityPage(
                    onBack = { navController.popBackStack() },
                    onOpenLeads = { navController.navigate(CrmLeads) },
                    onOpenOpportunityList = { stage -> navController.navigate(CrmOpportunityList(stage)) },
                    onOpenOpportunity = { opportunityId ->
                        navController.navigate(CrmOpportunityDetail(opportunityId))
                    },
                    onOpenCalendar = { epochMs ->
                        navController.navigate(Calendar(epochMs ?: 0L)) { launchSingleTop = true }
                    },
                    onAskAgent = { draft ->
                        navController.navigate(AssistantChat(draft = draft, returnTarget = "BACK", workContext = true))
                    },
                )
            }

            composable<CrmLeads> {
                CrmLeadListPage(
                    onBack = { navController.popBackStack() },
                    onOpenOpportunity = { opportunityId ->
                        navController.navigate(CrmOpportunityDetail(opportunityId))
                    },
                )
            }

            composable<CrmOpportunityList> { entry ->
                val route = entry.toRoute<CrmOpportunityList>()
                CrmOpportunityListPage(
                    initialStage = route.stage,
                    onBack = { navController.popBackStack() },
                    onOpenOpportunity = { opportunityId ->
                        navController.navigate(CrmOpportunityDetail(opportunityId))
                    },
                    onOpenBoard = { navController.navigate(CrmOpportunityBoard) },
                )
            }

            composable<CrmOpportunityBoard> {
                CrmOpportunityBoardPage(
                    onBack = { navController.popBackStack() },
                    onOpenOpportunity = { opportunityId ->
                        navController.navigate(CrmOpportunityDetail(opportunityId))
                    },
                )
            }

            composable<CrmOpportunityDetail> { entry ->
                val route = entry.toRoute<CrmOpportunityDetail>()
                CrmOpportunityDetailPage(
                    opportunityId = route.opportunityId,
                    onBack = { navController.popBackStack() },
                    onOpenCalendar = { epochMs -> navController.navigate(Calendar(epochMs ?: 0L)) },
                    onOpenRelation = { navController.navigate(Relation) { launchSingleTop = true } },
                    onAskAgent = { draft ->
                        navController.navigate(AssistantChat(draft = draft, returnTarget = "BACK", workContext = true))
                    },
                )
            }

            composable<AssistantChat> { entry ->
                val route = entry.toRoute<AssistantChat>()
                AgentConversationRoute(
                    initialDraft = route.draft,
                    initialMode = if (route.workContext) "Work" else "Chat",
                    onManagePlugins = { navController.navigate(Skill) { launchSingleTop = true } },
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
}
