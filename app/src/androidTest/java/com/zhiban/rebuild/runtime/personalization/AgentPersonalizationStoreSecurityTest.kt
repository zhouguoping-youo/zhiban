package com.zhiban.rebuild.runtime.personalization

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AgentPersonalizationStoreSecurityTest {
    @Test
    fun legacyPreferredNameMigratesWithoutPlaintextResidue() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = System.nanoTime().toString()
        val legacyStoreName = "agent_personalization_test_legacy_$suffix"
        val secureStoreName = "agent_personalization_test_secure_$suffix"
        val secretName = "迁移称呼-林小姐-745921"
        val legacy = context.getSharedPreferences(legacyStoreName, Context.MODE_PRIVATE)

        try {
            check(
                legacy.edit()
                    .putString("preferred_name", secretName)
                    .putString("response_style", ResponseStyle.PROFESSIONAL.name)
                    .commit(),
            )

            val loaded = AgentPersonalizationStore(context, legacyStoreName, secureStoreName).load()

            assertEquals(secretName, loaded.preferredName)
            assertEquals(ResponseStyle.PROFESSIONAL, loaded.style)
            assertFalse(legacy.contains("preferred_name"))
            val persistedSettings = File(context.applicationInfo.dataDir, "shared_prefs")
                .walkTopDown()
                .filter(File::isFile)
                .filter { it.name.contains(suffix) }
            assertFalse(persistedSettings.any { it.readBytes().decodeToString().contains(secretName) })
        } finally {
            context.deleteSharedPreferences(legacyStoreName)
            context.deleteSharedPreferences(secureStoreName)
        }
    }
}
