package com.zhiban.rebuild.navigation

import kotlinx.serialization.Serializable

@Serializable data object Home

@Serializable data class Calendar(val focusDateEpochMs: Long = 0L)

@Serializable data object Relation

@Serializable data object Skill

@Serializable data object CrmCapability

@Serializable data object LifeAssistant

@Serializable data object LifeAssistantList

@Serializable data class LifeAssistantDetail(val itemId: String)

@Serializable data object EventPlanning

@Serializable data object EventPlanningList

@Serializable data class EventPlanningDetail(val planId: String)

@Serializable data object CrmLeads

@Serializable data class CrmOpportunityList(val stage: String? = null)

@Serializable data object CrmOpportunityBoard

@Serializable data class CrmOpportunityDetail(val opportunityId: String)

@Serializable data object Profile

@Serializable data class AssistantChat(
    val draft: String = "",
    val openAttachment: Boolean = false,
    val startVoice: Boolean = false,
    val returnTarget: String = "CALENDAR",
    val workContext: Boolean = false,
)

@Serializable data object ModelConfig

@Serializable data object AgentSettings

@Serializable data object DebugAcceptance

@Serializable data class AgentVisualPrototype(val state: String = "empty")

@Serializable data object MemoryConfig

@Serializable data object ProfileEdit

@Serializable data object ConversationStyle

@Serializable data object AgentTools

@Serializable data object AgentSkills

@Serializable data object AgentFeedbackImprovement

@Serializable data object AgentRunHistory

@Serializable data object AutoWrites

@Serializable data object ContactMaintenance

@Serializable data object AboutZhiBan

@Serializable data object WechatChannel

@Serializable data object NotificationSettings

@Serializable data object PrivacySecurity

@Serializable data object Appearance

@Serializable data object StorageSettings

@Serializable data object DataSettings

@Serializable data object ReportErrorSettings

// Per v3.1 spec §3.1: the 问问 (Home) tab is a conversation surface.
// Ask is an entry action: after it opens the Agent conversation, that screen
// becomes full screen and uses its own back button. The four browse tabs keep
// the icon-only bottom navigation.
val TAB_ROUTES = listOf(Calendar::class, Relation::class, Skill::class, Profile::class)
