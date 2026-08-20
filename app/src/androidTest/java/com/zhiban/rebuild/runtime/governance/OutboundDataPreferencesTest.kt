package com.zhiban.rebuild.runtime.governance

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboundDataPreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun cleanup() {
        context.getSharedPreferences("outbound_data_preferences", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun cloudChannelsRemainFailClosedUntilTheUserExplicitlyEnablesThem() {
        cleanup()

        val settings = OutboundDataPreferences(context).snapshot()

        assertTrue(settings.allowRedactedAutomaticPersonalContext)
        assertFalse(settings.allowCloudSpeech)
        assertFalse(settings.allowRemoteMcp)
        assertFalse(settings.allowRemoteEmbedding)
    }
}
