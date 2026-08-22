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
import com.zhiban.rebuild.ui.settings.AgentSuggestionPage
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

internal fun NavGraphBuilder.agentSettingsRoutes(navController: NavHostController) {
    composable<AgentSettings> {
        AgentSettingsPage(
            onBack = { navController.popBackStack() },
            onPersonalization = { navController.navigate(ConversationStyle) },
            onMemory = { navController.navigate(MemoryConfig) },
            onModel = { navController.navigate(ModelConfig) },
            onTools = { navController.navigate(AgentTools) },
            onSkills = { navController.navigate(AgentSkills) },
            onFeedback = { navController.navigate(AgentFeedbackImprovement) },
            onRunHistory = { navController.navigate(AgentRunHistory) },
        )
    }
    composable<ProfileEdit> { UserProfilePage(onBack = { navController.popBackStack() }) }
    composable<ConversationStyle> { AgentPersonalizationPage(onBack = { navController.popBackStack() }) }
    composable<MemoryConfig> { AgentMemoryPage(onBack = { navController.popBackStack() }) }
    composable<AgentTools> { AgentToolsPage(onBack = { navController.popBackStack() }) }
    composable<AgentSkills> { AgentSkillsPage(onBack = { navController.popBackStack() }) }
    composable<AgentFeedbackImprovement> {
        AgentFeedbackImprovementPage(onBack = { navController.popBackStack() })
    }
    composable<AgentRunHistory> { AgentRunHistoryPage(onBack = { navController.popBackStack() }) }
    composable<AutoWrites> { AutoWritePage(onBack = { navController.popBackStack() }) }
    composable<AgentSuggestions> { AgentSuggestionPage(onBack = { navController.popBackStack() }) }
    composable<ContactMaintenance> {
        ContactMaintenancePage(
            onBack = { navController.popBackStack() },
            onAsk = { draft ->
                navController.navigate(
                    AssistantChat(draft = draft, returnTarget = "RELATION"),
                )
            },
        )
    }
    composable<NotificationSettings> { NotificationSettingsPage(onBack = { navController.popBackStack() }) }
    composable<PrivacySecurity> {
        PrivacySecurityPage(
            onBack = { navController.popBackStack() },
        )
    }
    composable<StorageSettings> { StorageSettingsPage(onBack = { navController.popBackStack() }) }
    composable<DataSettings> {
        DataSettingsPage(
            onBack = { navController.popBackStack() },
            onStorage = { navController.navigate(StorageSettings) },
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
}

internal fun NavGraphBuilder.featureRoutes(navController: NavHostController) {
    composable<CrmCapability> {
        CrmCapabilityPage(
            onBack = { navController.popBackStack() },
            onOpenLeads = { navController.navigate(CrmLeads) },
            onOpenOpportunityList = { stage -> navController.navigate(CrmOpportunityList(stage)) },
            onOpenOpportunity = { opportunityId ->
                navController.navigate(CrmOpportunityDetail(opportunityId))
            },
            onAskAgent = { draft ->
                navController.navigate(AssistantChat(draft = draft, returnTarget = "BACK"))
            },
        )
    }

    composable<LifeAssistant> {
        LifeAssistantPage(
            onBack = { navController.popBackStack() },
            onOpenAll = { navController.navigate(LifeAssistantList) },
            onOpenItem = { itemId -> navController.navigate(LifeAssistantDetail(itemId)) },
            onOpenRelations = { navController.navigate(Relation) { launchSingleTop = true } },
            onAskAgent = { draft ->
                navController.navigate(AssistantChat(draft = draft, returnTarget = "BACK"))
            },
        )
    }

    composable<LifeAssistantList> {
        LifeAssistantListPage(
            onBack = { navController.popBackStack() },
            onOpenItem = { itemId -> navController.navigate(LifeAssistantDetail(itemId)) },
        )
    }

    composable<LifeAssistantDetail> { entry ->
        val route = entry.toRoute<LifeAssistantDetail>()
        LifeAssistantDetailPage(
            itemId = route.itemId,
            onBack = { navController.popBackStack() },
            onAskAgent = { draft ->
                navController.navigate(AssistantChat(draft = draft, returnTarget = "BACK"))
            },
        )
    }

    composable<EventPlanning> {
        EventPlanningPage(
            onBack = { navController.popBackStack() },
            onOpenAll = { navController.navigate(EventPlanningList) },
            onOpenPlan = { planId -> navController.navigate(EventPlanningDetail(planId)) },
        )
    }

    composable<EventPlanningList> {
        EventPlanningListPage(
            onBack = { navController.popBackStack() },
            onOpenPlan = { planId -> navController.navigate(EventPlanningDetail(planId)) },
        )
    }

    composable<EventPlanningDetail> { entry ->
        val route = entry.toRoute<EventPlanningDetail>()
        EventPlanningDetailPage(
            planId = route.planId,
            onBack = { navController.popBackStack() },
            onAskAgent = { draft ->
                navController.navigate(AssistantChat(draft = draft, returnTarget = "BACK"))
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
                navController.navigate(AssistantChat(draft = draft, returnTarget = "BACK"))
            },
        )
    }
}
