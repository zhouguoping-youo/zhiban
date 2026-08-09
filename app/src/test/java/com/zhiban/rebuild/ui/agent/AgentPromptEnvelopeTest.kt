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
    fun leavesOrdinaryUserInputUntouched() {
        assertEquals("明天下午提醒我开会", AgentPromptEnvelope.displayText("明天下午提醒我开会"))
    }
}
