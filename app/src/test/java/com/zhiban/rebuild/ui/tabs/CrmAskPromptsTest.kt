package com.zhiban.rebuild.ui.tabs

import com.zhiban.rebuild.ui.agent.AgentPromptEnvelope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrmAskPromptsTest {
    @Test
    fun promptsKeepToolConstraintsOutOfUserFacingCopy() {
        val prompts = listOf(
            newCrmOpportunityPrompt(),
            crmSuggestionPrompt("opportunity-1", "准备下一次沟通", isDemo = false),
            crmSuggestionPrompt(null, "补充联系人信息", isDemo = false),
            crmSuggestionPrompt("demo-1", "准备下一次沟通", isDemo = true),
            crmOpportunityCoachPrompt("opp-1", "续约", "确认下一步", "当前没有待办", isDemo = false),
        )

        prompts.forEach { prompt ->
            val displayText = AgentPromptEnvelope.displayText(prompt)
            assertFalse(displayText.contains("contact.search"))
            assertFalse(displayText.contains("crm."))
            assertFalse(displayText.contains("工具"))
        }
        assertTrue(AgentPromptEnvelope.displayText(newCrmOpportunityPrompt()).contains("新建"))
        assertTrue(newCrmOpportunityPrompt().contains("crm.opportunity.create"))
        assertTrue(newCrmOpportunityPrompt().contains("确认"))
    }

    @Test
    fun opportunityCoachUsesVerifiedOpportunityAndProtectsDemoData() {
        val real = crmOpportunityCoachPrompt("opp-1", "续约", "确认下一步", "当前没有待办", isDemo = false)
        val demo = crmOpportunityCoachPrompt("demo-1", "演示机会", "准备沟通", "演示依据", isDemo = true)

        assertTrue(AgentPromptEnvelope.displayText(real).contains("确认下一步"))
        assertTrue(real.contains("crm.opportunity.get"))
        assertTrue(real.contains("逐项确认"))
        assertTrue(real.contains("对外发送必须由用户最后确认"))
        assertTrue(demo.contains("只读"))
        assertFalse(demo.contains("crm.nextAction.create"))
    }
}
