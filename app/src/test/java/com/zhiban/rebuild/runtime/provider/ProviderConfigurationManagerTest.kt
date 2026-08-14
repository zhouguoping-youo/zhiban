package com.zhiban.rebuild.runtime.provider

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConfigurationManagerTest {
    @Test fun `configuration failure codes distinguish offline timeout and provider failures`() {
        assertEquals("NETWORK_OFFLINE", ProviderEnvironmentManager.safeConfigurationFailureCode(UnknownHostException()))
        assertEquals("TIMEOUT", ProviderEnvironmentManager.safeConfigurationFailureCode(SocketTimeoutException()))
        assertEquals(
            "AUTHENTICATION_FAILED",
            ProviderEnvironmentManager.safeConfigurationFailureCode(ProviderFailure("AUTHENTICATION_FAILED", false)),
        )
    }

    @Test fun `legacy and foreign providers are rejected by the agent boundary`() = runTest {
        val manager = ProviderConfigurationManager(FakeCredentialProvisioner(), FakeProviderProfileStore())
        listOf("minimax", "aliyun", "volc", "zhipu", "tencent").forEach { providerId ->
            val failure = runCatching {
                manager.provisionCandidate(providerId, "foreign-key".encodeToByteArray(), "foreign-model")
            }.exceptionOrNull()
            assertEquals("UNSUPPORTED_PROVIDER", failure?.message)
        }
    }

    @Test fun `the sole stepfun preset is trusted and probed before publication`() = runTest {
        val vault = FakeCredentialProvisioner()
        val profiles = FakeProviderProfileStore()
        val configuration = ProviderConfigurationManager(vault, profiles)
        val environment = ProviderEnvironmentManager(
            configuration,
            object : ProviderAdapter {
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
            },
            clock = { 10 },
        )

        TrustedProviderRegistry.PRESETS.forEach { preset ->
            val health = environment.configure(
                preset.providerId,
                "key-${preset.providerId}".encodeToByteArray(),
                preset.defaultModel,
            )
            assertTrue(health.available)
            assertEquals(preset.providerId, environment.activeProfile()?.providerId)
            assertEquals(preset.endpointId, environment.activeProfile()?.endpointId)
        }
        assertEquals(listOf("stepfun"), TrustedProviderRegistry.PRESETS.map { it.providerId })
    }

    @Test fun `agent environment health checks new key and retains verified configuration after failed rotation`() = runTest {
        val vault = FakeCredentialProvisioner()
        val profiles = FakeProviderProfileStore()
        val configuration = ProviderConfigurationManager(vault, profiles)
        val healthy = ProviderEnvironmentManager(
            configuration,
            object : ProviderAdapter {
                override suspend fun probe(profile: ProviderProfile): CapabilitySnapshot = CapabilitySnapshot(
                    TrustedProviderRegistry().digest(profile),
                    setOf("text"),
                    setOf("stream"),
                    1000,
                    100,
                    10,
                    100,
                )
                override fun stream(request: ModelRequest) = emptyFlow<ModelEvent>()
                override fun cancel(requestId: String) = true
            },
            clock = { 10 },
        )

        assertTrue(healthy.configureStepFun("valid-key".encodeToByteArray(), "step-3.5-flash").available)
        assertTrue(healthy.isConfigured())

        val failing = ProviderEnvironmentManager(
            configuration,
            object : ProviderAdapter {
                override suspend fun probe(profile: ProviderProfile): CapabilitySnapshot = throw ProviderFailure("AUTHENTICATION_FAILED", false)
                override fun stream(request: ModelRequest) = emptyFlow<ModelEvent>()
                override fun cancel(requestId: String) = true
            },
        )
        assertTrue(runCatching { failing.configureStepFun("bad-key".encodeToByteArray(), "step-3.5-flash") }.isFailure)
        assertTrue(failing.isConfigured())
        assertEquals(1, profiles.profile?.keyVersion)
    }

    @Test fun `failed health cache never masks a recovered provider`() = runTest {
        val configuration = ProviderConfigurationManager(FakeCredentialProvisioner(), FakeProviderProfileStore())
        configuration.provisionStepFun("test-only-key".encodeToByteArray(), "step-3.5-flash")
        val staleFailure = ProviderHealth(false, 10, null, "NETWORK_OFFLINE")
        val cache = FakeProviderHealthCache(staleFailure)
        var probes = 0
        val environment = ProviderEnvironmentManager(
            configuration,
            healthyAdapter { probes += 1 },
            healthCache = cache,
            clock = { 20 },
        )

        val health = environment.healthCheck()

        assertTrue(health.available)
        assertEquals(1, probes)
        assertNotEquals(staleFailure, cache.saved)
        assertTrue(requireNotNull(cache.saved).available)
    }

    @Test fun `explicit health refresh bypasses a positive snapshot`() = runTest {
        val configuration = ProviderConfigurationManager(FakeCredentialProvisioner(), FakeProviderProfileStore())
        val profile = configuration.provisionStepFun("test-only-key".encodeToByteArray(), "step-3.5-flash")
        val digest = TrustedProviderRegistry().digest(profile)
        val cached = ProviderHealth(true, 10, capability(digest, 10), null)
        var probes = 0
        val environment = ProviderEnvironmentManager(
            configuration,
            healthyAdapter { probes += 1 },
            healthCache = FakeProviderHealthCache(cached),
            clock = { 20 },
        )

        val health = environment.healthCheck(forceRefresh = true)

        assertTrue(health.available)
        assertEquals(20, health.checkedAtEpochMs)
        assertEquals(1, probes)
    }

    @Test fun `provision binds secret to trusted profile without retaining caller bytes`() = runTest {
        val vault = FakeCredentialProvisioner()
        val profiles = FakeProviderProfileStore()
        val manager = ProviderConfigurationManager(vault, profiles)
        val secret = "test-only-key".encodeToByteArray()

        val profile = manager.provisionStepFun(secret, "step-3.5-flash")
        secret.fill(0)

        assertEquals("stepfun", profile.providerId)
        assertEquals("stepfun-cn-openai-v1", profile.endpointId)
        assertEquals("step-3.5-flash", profile.modelId)
        assertEquals("test-only-key", vault.lastProvisioned?.decodeToString())
        assertEquals(profile, profiles.profile)
        assertTrue(manager.isConfigured())
    }

    @Test fun `clear removes profile and bound credential`() = runTest {
        val vault = FakeCredentialProvisioner()
        val profiles = FakeProviderProfileStore()
        val manager = ProviderConfigurationManager(vault, profiles)
        manager.provisionStepFun("test-only-key".encodeToByteArray(), "step-3.5-flash")

        manager.clear()

        assertFalse(manager.isConfigured())
        assertEquals(null, profiles.profile)
        assertEquals("stepfun.primary" to 1, vault.deleted)
    }

    @Test fun `clear removes published profile before deleting its credential`() = runTest {
        val operations = mutableListOf<String>()
        val vault = FakeCredentialProvisioner(operations)
        val profiles = FakeProviderProfileStore(operations)
        val manager = ProviderConfigurationManager(vault, profiles)
        manager.provisionStepFun("test-only-key".encodeToByteArray(), "step-3.5-flash")
        operations.clear()

        manager.clear()

        assertEquals(listOf("profile.clear", "vault.delete"), operations)
    }

    @Test fun `unsupported legacy model is normalized to current trusted default`() = runTest {
        val manager = ProviderConfigurationManager(FakeCredentialProvisioner(), FakeProviderProfileStore())

        val profile = manager.provisionStepFun("test-only-key".encodeToByteArray(), "MiniMax-M1")

        assertEquals("step-3.5-flash", profile.modelId)
    }

    @Test fun `installed legacy stepfun profile migrates without replacing its key`() = runTest {
        val vault = FakeCredentialProvisioner()
        val profiles = FakeProviderProfileStore()
        val legacy = ProviderProfile("stepfun", "stepfun-cn-openai-v1", "step-3.7-flash", "stepfun.primary", 3)
        vault.provision(legacy.credentialRef, legacy.keyVersion, "existing-key".encodeToByteArray())
        profiles.save(legacy)

        val active = ProviderConfigurationManager(vault, profiles).activeProfile()

        assertEquals("step-3.5-flash", active?.modelId)
        assertEquals(3, active?.keyVersion)
        assertEquals("existing-key", vault.lastProvisioned?.decodeToString())
    }

    @Test fun `unsupported model selection remains on the single current model without reprovisioning key`() = runTest {
        val vault = FakeCredentialProvisioner()
        val profiles = FakeProviderProfileStore()
        val manager = ProviderConfigurationManager(vault, profiles)
        manager.provisionStepFun("test-only-key".encodeToByteArray(), "step-3.5-flash")

        val changed = manager.selectModel("MiniMax-M2.5")

        assertEquals("step-3.5-flash", changed.modelId)
        assertEquals("test-only-key", vault.lastProvisioned?.decodeToString())
    }
}

private class FakeCredentialProvisioner(private val operations: MutableList<String>? = null) : CredentialProvisioner {
    var lastProvisioned: ByteArray? = null
    var deleted: Pair<String, Int>? = null
    private val values = mutableMapOf<Pair<String, Int>, ByteArray>()
    override suspend fun provision(credentialRef: String, keyVersion: Int, credential: ByteArray) {
        lastProvisioned = credential.copyOf()
        values[credentialRef to keyVersion] = credential.copyOf()
    }
    override suspend fun delete(credentialRef: String, keyVersion: Int) {
        operations?.add("vault.delete")
        deleted = credentialRef to keyVersion
        values.remove(credentialRef to keyVersion)?.fill(0)
        lastProvisioned = values.values.lastOrNull()?.copyOf()
    }
    override suspend fun contains(credentialRef: String, keyVersion: Int) = credentialRef to keyVersion in values
}

private class FakeProviderProfileStore(private val operations: MutableList<String>? = null) : ProviderProfileStore {
    var profile: ProviderProfile? = null
    override suspend fun load(): ProviderProfile? = profile
    override suspend fun save(profile: ProviderProfile) {
        this.profile = profile
    }
    override suspend fun clear() {
        operations?.add("profile.clear")
        profile = null
    }
}

private class FakeProviderHealthCache(initial: ProviderHealth?) : ProviderHealthCache {
    private var loaded = initial
    var saved: ProviderHealth? = null

    override fun load(profileDigest: String, nowEpochMs: Long): ProviderHealth? = loaded

    override fun save(profileDigest: String, health: ProviderHealth) {
        saved = health
        loaded = health
    }

    override fun clear() {
        loaded = null
    }
}

private fun healthyAdapter(onProbe: () -> Unit): ProviderAdapter = object : ProviderAdapter {
    override suspend fun probe(profile: ProviderProfile): CapabilitySnapshot {
        onProbe()
        return capability(TrustedProviderRegistry().digest(profile), 20)
    }

    override fun stream(request: ModelRequest) = emptyFlow<ModelEvent>()

    override fun cancel(requestId: String) = true
}

private fun capability(profileDigest: String, checkedAtEpochMs: Long) = CapabilitySnapshot(
    profileDigest,
    setOf("text"),
    setOf("stream"),
    100_000,
    2_048,
    checkedAtEpochMs,
    checkedAtEpochMs + 60_000,
)
