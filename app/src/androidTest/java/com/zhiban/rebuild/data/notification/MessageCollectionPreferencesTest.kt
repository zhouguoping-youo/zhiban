package com.zhiban.rebuild.data.notification

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.zhiban.rebuild.data.notification.MessageCollectionPreferences
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class MessageCollectionPreferencesTest {
    private lateinit var context: Context
    private lateinit var preferences: MessageCollectionPreferences
    private val datastorePath = "datastore/message_collection.preferences_pb"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val prefsFile = File(context.filesDir, datastorePath)
        prefsFile.parentFile?.mkdirs()
        prefsFile.delete()
        preferences = MessageCollectionPreferences(context)
    }

    @Test
    fun markGapOnlyWhenGapExceedsThreshold() = runBlocking {
        val disconnectAt = 1_000L
        preferences.onNotificationListenerDisconnected(disconnectAt)

        preferences.markNotificationGapIfNeeded(
            nowEpochMs = disconnectAt + 10 * 60_000L,
            thresholdMs = 30 * 60_000L,
        )
        assertNull(preferences.consumeNotificationGapReason())

        preferences.onNotificationListenerDisconnected(disconnectAt)
        preferences.markNotificationGapIfNeeded(
            nowEpochMs = disconnectAt + 31 * 60_000L,
            thresholdMs = 30 * 60_000L,
        )
        assertNotNull(preferences.consumeNotificationGapReason())
    }

    @Test
    fun consumeGapReasonClearsAfterRead() = runBlocking {
        val disconnectAt = 1_000L
        preferences.onNotificationListenerDisconnected(disconnectAt)
        preferences.markNotificationGapIfNeeded(
            nowEpochMs = disconnectAt + 30 * 60_000L,
            thresholdMs = 30 * 60_000L,
        )
        val first = preferences.consumeNotificationGapReason()
        assertNotNull(first)
        assertNull(preferences.consumeNotificationGapReason())
        assertEquals(true, first!!.startsWith("notification_gap:"))
    }
}
