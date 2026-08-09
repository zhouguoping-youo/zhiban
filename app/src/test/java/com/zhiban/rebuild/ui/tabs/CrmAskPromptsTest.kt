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
}
