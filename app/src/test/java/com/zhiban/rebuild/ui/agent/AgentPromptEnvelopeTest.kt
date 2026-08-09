package com.zhiban.rebuild.ui.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPromptEnvelopeTest {
    @Test
    fun hidesInternalContextWithoutRemovingItFromRuntimePrompt() {
        val prompt = AgentPromptEnvelope.wrap(
            displayText = "帮我创建机会",
            internalContext = "调用 crm.opportunity.create 并等待确认",
        )

        assertEquals("帮我创建机会", AgentPromptEnvelope.displayText(prompt))
        assertTrue(prompt.contains("crm.opportunity.create"))
    }

    @Test
    fun hidesInternalContextAfterPersistenceFlattensWhitespace() {
        val prompt = AgentPromptEnvelope.wrap(
            displayText = "帮我创建机会",
            internalContext = "调用 crm.opportunity.create 并等待确认",
        ).replace('\n', ' ')

        assertEquals("帮我创建机会", AgentPromptEnvelope.displayText(prompt))
    }

    @Test
    fun leavesOrdinaryUserInputUntouched() {
        assertEquals("明天下午提醒我开会", AgentPromptEnvelope.displayText("明天下午提醒我开会"))
    }

    @Test
    fun legacyCrmCreateInstructionIsPresentedAsNaturalLanguage() {
        val legacy = "请使用个人 CRM 工具创建机会。主要联系人可选：我明确提到联系人时先调用 contact.search 确认"

        assertEquals("帮我新建一个个人 CRM 机会", AgentPromptEnvelope.displayText(legacy))
    }

    @Test
    fun legacyCrmSuggestionKeepsOnlyItsUserFacingTitle() {
        val legacy = "请先调用 crm.opportunity.get 查询机会，再分析建议“联系采购负责人”的依据和下一步。"

        assertEquals("帮我分析：联系采购负责人", AgentPromptEnvelope.displayText(legacy))
    }
}
