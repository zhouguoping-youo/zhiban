package com.zhiban.rebuild.ui.agent

import com.zhiban.rebuild.navigation.AssistantChat
import com.zhiban.rebuild.navigation.Home
import com.zhiban.rebuild.navigation.TAB_ROUTES
import org.junit.Assert.*
import org.junit.Test

class AgentConversationUiStateTest {
    @Test fun `ten production stages remain exhaustive`() {
        assertEquals(10, AgentConversationStage.entries.size)
    }

    @Test fun `executing always disables input`() {
        assertFalse(AgentConversationUiState(stage = AgentConversationStage.EXECUTING).isInputEnabled)
    }

    @Test fun `plan is visible only while awaiting or executing confirmation`() {
        val plan = AgentPlanUi("执行计划")
        assertFalse(AgentConversationUiState(stage = AgentConversationStage.PLANNING, plan = plan).showPlan)
        assertTrue(AgentConversationUiState(stage = AgentConversationStage.AWAITING_CONFIRMATION, plan = plan).showPlan)
        assertTrue(AgentConversationUiState(stage = AgentConversationStage.EXECUTING, plan = plan).showPlan)
        // Terminal stages must not keep the card: once confirmed/rejected/cancelled it resolves to a
        // result, not a lingering plan (#16).
        assertFalse(AgentConversationUiState(stage = AgentConversationStage.SUCCEEDED, plan = plan).showPlan)
        assertFalse(AgentConversationUiState(stage = AgentConversationStage.FAILED_FINAL, plan = plan).showPlan)
    }

    @Test fun `ask conversation and nested assistant chat are full screen`() {
        assertEquals(4, TAB_ROUTES.size)
        assertFalse(TAB_ROUTES.contains(Home::class))
        assertFalse(TAB_ROUTES.contains(AssistantChat::class))
    }
}
