package com.zhiban.rebuild.data.suggestion

import android.app.NotificationManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSuggestionNotifierTest {
    @Test
    fun daytimeWithNormalInterruptionFilterPublishes() {
        assertTrue(shouldPublishSuggestionNotification(12, NotificationManager.INTERRUPTION_FILTER_ALL))
    }

    @Test
    fun quietHoursDoNotPublish() {
        assertFalse(shouldPublishSuggestionNotification(22, NotificationManager.INTERRUPTION_FILTER_ALL))
        assertFalse(shouldPublishSuggestionNotification(6, NotificationManager.INTERRUPTION_FILTER_ALL))
    }

    @Test
    fun systemDoNotDisturbDoesNotPublish() {
        assertFalse(shouldPublishSuggestionNotification(12, NotificationManager.INTERRUPTION_FILTER_NONE))
        assertFalse(shouldPublishSuggestionNotification(12, NotificationManager.INTERRUPTION_FILTER_PRIORITY))
    }

    @Test
    fun optedOutContactDoesNotPublish() {
        assertFalse(
            shouldPublishSuggestionNotification(
                hour = 12,
                interruptionFilter = NotificationManager.INTERRUPTION_FILTER_ALL,
                contactOptedOut = true,
            ),
        )
    }
}
