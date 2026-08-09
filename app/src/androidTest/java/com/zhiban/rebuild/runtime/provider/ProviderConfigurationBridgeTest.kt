package com.zhiban.rebuild.runtime.provider

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.ui.chat.PreferencesManager
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderConfigurationBridgeTest {
    @Test fun providerHealthCachePersistsOnlyNonSecretSnapshotAndExpiresAfterOneHour() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cache = AndroidProviderHealthCache(context)
        cache.clear()
        val digest = "a".repeat(64)
        val health = ProviderHealth(
            true,
            1_000,
            CapabilitySnapshot(
                digest,
                setOf("text"),
                setOf("stream", "tools"),
                100_000,
                2_048,
                1_000,
                3_601_000,
            ),
            null,
        )
        cache.save(digest, health)
        val loaded = requireNotNull(cache.load(digest, 2_000))
        assertTrue(loaded.available)
        assertEquals(setOf("stream", "tools"), loaded.capability?.features)
        assertNull(cache.load(digest, 3_601_001))
        val raw = context.getSharedPreferences("agent_provider_health_v1", Context.MODE_PRIVATE).all.toString()
        assertFalse(raw.contains("api_key", ignoreCase = true))
        cache.clear()
    }

    @Test fun fiveProviderSwitchPublishesOnlyVerifiedKeyAndFailedRotationKeepsPrevious() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = KeystoreCredentialVault(context)
        val profiles = AndroidProviderProfileStore(context)
        val configuration = ProviderConfigurationManager(vault, profiles)
        configuration.clear()
        val healthyAdapter = object : ProviderAdapter {
            override suspend fun probe(profile: ProviderProfile) = CapabilitySnapshot(
                TrustedProviderRegistry().digest(profile),
                setOf("text"),
                setOf("stream", "tools"),
                100_000,
                2_048,
                10,
                100,
            )
            override fun stream(request: ModelRequest) = emptyFlow<ModelEvent>()
            override fun cancel(requestId: String) = true
        }
        val healthy = ProviderEnvironmentManager(configuration, healthyAdapter) { 10 }
        TrustedProviderRegistry.PRESETS.forEach { preset ->
            assertTrue(
                healthy.configure(
                    preset.providerId,
                    "device-${preset.providerId}".encodeToByteArray(),
                    preset.defaultModel,
                ).available,
            )
            assertEquals(preset.providerId, healthy.activeProfile()?.providerId)
        }
        val previous = requireNotNull(healthy.activeProfile())
        val failing = ProviderEnvironmentManager(
            configuration,
            object : ProviderAdapter {
                override suspend fun probe(profile: ProviderProfile): CapabilitySnapshot = throw ProviderFailure("AUTHENTICATION_FAILED", false)
                override fun stream(request: ModelRequest) = emptyFlow<ModelEvent>()
                override fun cancel(requestId: String) = true
            },
        )
        assertTrue(
            runCatching {
                failing.configure(previous.providerId, "rejected".encodeToByteArray(), previous.modelId)
            }.isFailure,
        )
        assertEquals(previous, failing.activeProfile())
        assertFalse(vault.contains(previous.credentialRef, previous.keyVersion + 1))
        configuration.clear()
    }

    @Test fun legacyEncryptedPreferenceMigratesOnceIntoRuntimeVaultAndProfile() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val legacy = PreferencesManager(context)
        val vault = KeystoreCredentialVault(context)
        val profiles = AndroidProviderProfileStore(context)
        val manager = ProviderConfigurationManager(vault, profiles)
        manager.clear()
        legacy.clearLegacyApiKey()
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        val oldStore = EncryptedSharedPreferences.create(
            context,
            "zhiban_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        check(oldStore.edit().putString("api_key", "test-only-migration-key").commit())

        val migrated = legacy.consumeLegacyApiKey { bytes ->
            manager.provisionStepFun(bytes, "step-3.5-flash")
        }

        assertTrue(migrated)
        assertEquals(null, oldStore.getString("api_key", null))
        assertEquals("step-3.5-flash", manager.activeProfile()?.modelId)
        val resolved = vault.withCredential("stepfun.primary", 1) { it.copyOf() }
        assertEquals("test-only-migration-key", resolved.decodeToString())
        resolved.fill(0)
        manager.clear()
        Unit
    }
}
