package com.zhiban.rebuild.ui.chat

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreferencesManagerSecurityTest {
    @Test
    fun customSystemPromptIsNotPersistedAsPlaintext() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val manager = PreferencesManager(context)
        val originalPrompt = manager.getSystemPrompt()
        val secretPrompt = "仅测试-客户报价底线-938471"

        try {
            manager.saveSystemPrompt(secretPrompt)

            assertEquals(secretPrompt, manager.getSystemPrompt())
            val persistedSettings = listOf(
                File(context.filesDir, "datastore/zhiban_prefs.preferences_pb"),
                File(context.applicationInfo.dataDir, "shared_prefs/zhiban_secure_prefs.xml"),
            ).filter(File::isFile)
            val plaintextFound = persistedSettings.any { file ->
                file.readBytes().decodeToString().contains(secretPrompt)
            }
            assertFalse(plaintextFound)
        } finally {
            manager.saveSystemPrompt(originalPrompt)
        }
    }
}
