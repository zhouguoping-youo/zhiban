package com.zhiban.rebuild.ui.tabs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrmAskPromptsTest {
    @Test
    fun newOpportunityPromptCallsConfirmedWriteTool() {
        val prompt = newCrmOpportunityPrompt()
        assertTrue(prompt.contains("contact.search"))
        assertTrue(prompt.contains("crm.opportunity.create"))
        assertTrue(prompt.contains("确认"))
    }

    @Test
    fun realSuggestionPromptCallsCrmTools() {
        val prompt = crmSuggestionPrompt("opp-real", "推进建议", isDemo = false)
        assertTrue(prompt.contains("crm.opportunity.get"))
        assertTrue(prompt.contains("crm.nextAction.complete"))
        assertTrue(prompt.contains("逐项发起写入确认"))
    }

    @Test
    fun demoSuggestionPromptNeverCallsRealCrmTools() {
        val prompt = crmSuggestionPrompt("crm-demo-opp-data", "演示建议", isDemo = true)
        assertFalse(prompt.contains("crm.opportunity."))
        assertTrue(prompt.contains("只读演练"))
        assertTrue(prompt.contains("不要写入任何真实数据"))
    }
}
