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

    @Test fun `confirmation copy describes user action instead of runtime internals`() {
        assertEquals("需要你确认", planCardStatusLabel(AgentConversationStage.AWAITING_CONFIRMATION))
        assertEquals("正在处理", planCardStatusLabel(AgentConversationStage.EXECUTING))
        assertEquals("确认", planConfirmLabel(AgentPlanUi("创建日程")))
        assertEquals(
            "确认并打开微信",
            planConfirmLabel(AgentPlanUi(title = "发送邀请", platform = "WECHAT", message = "明晚见")),
        )
    }
}
