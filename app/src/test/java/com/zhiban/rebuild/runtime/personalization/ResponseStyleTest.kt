package com.zhiban.rebuild.runtime.personalization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseStyleTest {

    @Test fun exposesSevenStylesIncludingNewAndCustom() {
        assertEquals(7, ResponseStyle.entries.size)
        // Feedback-improvement loop depends on CONCISE surviving (see AgentFeedbackViewModel.accept).
        assertTrue(ResponseStyle.entries.contains(ResponseStyle.CONCISE))
        assertTrue(ResponseStyle.entries.contains(ResponseStyle.DETAILED))
        assertTrue(ResponseStyle.entries.contains(ResponseStyle.CASUAL))
        assertTrue(ResponseStyle.entries.contains(ResponseStyle.PROFESSIONAL))
        assertTrue(ResponseStyle.entries.contains(ResponseStyle.PLAYFUL))
        assertTrue(ResponseStyle.entries.contains(ResponseStyle.CUSTOM))
    }

    @Test fun everyStyleHasLabelAndHint() {
        ResponseStyle.entries.forEach { style ->
            assertTrue("${style.name} label blank", style.label.isNotBlank())
            assertTrue("${style.name} hint blank", style.hint.isNotBlank())
        }
    }

    @Test fun presetStylesHavePromptFragmentCustomDoesNot() {
        ResponseStyle.entries.filter { it != ResponseStyle.CUSTOM }.forEach { style ->
            assertTrue("${style.name} promptFragment blank", style.promptFragment.isNotBlank())
        }
        // CUSTOM relies on the user's own instructions (injected via user.md), so it has no preset fragment.
        assertEquals("", ResponseStyle.CUSTOM.promptFragment)
    }

    @Test fun labelsAndHintsAreDistinct() {
        assertEquals(7, ResponseStyle.entries.map { it.label }.distinct().size)
        assertEquals(7, ResponseStyle.entries.map { it.hint }.distinct().size)
    }

    @Test fun legacyStoredNamesStillResolve() {
        // Persisted SharedPreferences values must keep resolving (no migration).
        assertEquals(ResponseStyle.CONCISE, ResponseStyle.valueOf("CONCISE"))
        assertEquals(ResponseStyle.BALANCED, ResponseStyle.valueOf("BALANCED"))
        assertEquals(ResponseStyle.DETAILED, ResponseStyle.valueOf("DETAILED"))
    }
}
