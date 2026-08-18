package com.zhiban.rebuild.runtime.input.asr

import com.zhiban.rebuild.provider.CapabilitySnapshot
import com.zhiban.rebuild.provider.CredentialProvisioner
import com.zhiban.rebuild.provider.CredentialResolver
import com.zhiban.rebuild.provider.ModelEvent
import com.zhiban.rebuild.provider.ModelRequest
import com.zhiban.rebuild.provider.OutboundExportGate
import com.zhiban.rebuild.provider.OutboundPolicySettings
import com.zhiban.rebuild.provider.ProviderAdapter
import com.zhiban.rebuild.provider.ProviderConfigurationManager
import com.zhiban.rebuild.provider.ProviderEnvironmentManager
import com.zhiban.rebuild.provider.ProviderProfile
import com.zhiban.rebuild.provider.ProviderProfileStore
import com.zhiban.rebuild.provider.TrustedProviderRegistry
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProviderCloudAsrGatewayTest {
    @Test fun `stepfun profile uses its bound credential and cloud transport`() = runTest {
        val fixture = fixture("stepfun")
        var observedKey = ""
        val gateway = ProviderCloudAsrGateway(
            fixture.environment,
            fixture.vault,
            object : CloudAsrTransport {
                override suspend fun stepFun(credential: ByteArray, audio: File): CloudAsrResult {
                    observedKey = credential.decodeToString()
                    return CloudAsrResult.Success("明天下午三点开会")
                }
            },
            outboundGate(allowCloudSpeech = true),
        )

        assertEquals(CloudAsrAvailability.AVAILABLE, gateway.availability())
        assertEquals(CloudAsrResult.Success("明天下午三点开会"), gateway.transcribe(File("voice.ogg")))
        assertEquals("key-stepfun", observedKey)
    }

    @Test fun `missing provider falls back without calling cloud`() = runTest {
        val fixture = unconfiguredFixture()
        var calls = 0
        val gateway = ProviderCloudAsrGateway(
            fixture.environment,
            fixture.vault,
            object : CloudAsrTransport {
                override suspend fun stepFun(credential: ByteArray, audio: File): CloudAsrResult {
                    calls++
                    return CloudAsrResult.Success("bad")
                }
            },
            outboundGate(allowCloudSpeech = true),
        )

        assertEquals(CloudAsrAvailability.PROVIDER_NOT_CONFIGURED, gateway.availability())
        val failure = gateway.transcribe(File("voice.ogg"))
        assertTrue(failure is CloudAsrResult.Failure && failure.safeCode == "ASR_PROVIDER_NOT_CONFIGURED")
        assertEquals(0, calls)
    }

    @Test fun `cloud speech is blocked before credential and transport without consent`() = runTest {
        val fixture = fixture("stepfun")
        var calls = 0
        val gateway = ProviderCloudAsrGateway(
            fixture.environment,
            fixture.vault,
            object : CloudAsrTransport {
                override suspend fun stepFun(credential: ByteArray, audio: File): CloudAsrResult {
                    calls++
                    return CloudAsrResult.Success("bad")
                }
            },
            outboundGate(allowCloudSpeech = false),
        )

        assertEquals(CloudAsrAvailability.CONSENT_REQUIRED, gateway.availability())
        assertEquals(
            CloudAsrResult.Failure("ASR_CLOUD_CONSENT_REQUIRED", false),
            gateway.transcribe(File("voice.ogg")),
        )
        assertEquals(0, calls)
    }

    @Test fun `unexpected transport failure becomes retryable result instead of escaping to UI`() = runTest {
        val fixture = fixture("stepfun")
        val gateway = ProviderCloudAsrGateway(
            fixture.environment,
            fixture.vault,
            object : CloudAsrTransport {
                override suspend fun stepFun(credential: ByteArray, audio: File): CloudAsrResult = throw IOException("socket reset")
            },
            outboundGate(allowCloudSpeech = true),
        )

        assertEquals(CloudAsrResult.Failure("ASR_NETWORK_FAILURE", true), gateway.transcribe(File("voice.ogg")))
    }

    @Test fun `blank transport success is rejected at the gateway boundary`() = runTest {
        val fixture = fixture("stepfun")
        val gateway = ProviderCloudAsrGateway(
            fixture.environment,
            fixture.vault,
            object : CloudAsrTransport {
                override suspend fun stepFun(credential: ByteArray, audio: File): CloudAsrResult = CloudAsrResult.Success("  \n ")
            },
            outboundGate(allowCloudSpeech = true),
        )

        assertEquals(CloudAsrResult.Failure("ASR_EMPTY_RESULT", false), gateway.transcribe(File("voice.ogg")))
    }

    @Test fun `transcription cancellation propagates through credential scope`() = runTest {
        val fixture = fixture("stepfun")
        val gateway = ProviderCloudAsrGateway(
            fixture.environment,
            fixture.vault,
            object : CloudAsrTransport {
                override suspend fun stepFun(credential: ByteArray, audio: File): CloudAsrResult = throw CancellationException("cancelled")
            },
            outboundGate(allowCloudSpeech = true),
        )

        try {
            gateway.transcribe(File("voice.ogg"))
            fail("cancellation must propagate")
        } catch (_: CancellationException) {
            // Expected control flow.
        }
    }

    private suspend fun fixture(providerId: String): Fixture {
        val vault = FakeVault()
        val profiles = FakeProfiles()
        val manager = ProviderConfigurationManager(vault, profiles)
        val environment = ProviderEnvironmentManager(
            manager,
            object : ProviderAdapter {
                override suspend fun probe(profile: ProviderProfile) = CapabilitySnapshot(
                    TrustedProviderRegistry().digest(profile),
                    setOf("text", "asr"),
                    emptySet(),
                    1000,
                    100,
                    1,
                    100,
                )
                override fun stream(request: ModelRequest) = emptyFlow<ModelEvent>()
                override fun cancel(requestId: String) = true
            },
            clock = { 1 },
        )
        val preset = TrustedProviderRegistry().preset(providerId)
        environment.configure(providerId, "key-$providerId".encodeToByteArray(), preset.defaultModel)
        return Fixture(environment, vault)
    }

    private fun unconfiguredFixture(): Fixture {
        val vault = FakeVault()
        val environment = ProviderEnvironmentManager(
            ProviderConfigurationManager(vault, FakeProfiles()),
            object : ProviderAdapter {
                override suspend fun probe(profile: ProviderProfile) = error("not called")
                override fun stream(request: ModelRequest) = emptyFlow<ModelEvent>()
                override fun cancel(requestId: String) = true
            },
        )
        return Fixture(environment, vault)
    }

    private data class Fixture(val environment: ProviderEnvironmentManager, val vault: FakeVault)

    private fun outboundGate(allowCloudSpeech: Boolean) = OutboundExportGate(
        settings = { OutboundPolicySettings(allowCloudSpeech = allowCloudSpeech) },
    )
}

private class FakeVault :
    CredentialProvisioner,
    CredentialResolver {
    private val values = mutableMapOf<Pair<String, Int>, ByteArray>()
    override suspend fun provision(credentialRef: String, keyVersion: Int, credential: ByteArray) {
        values[
            credentialRef to
                keyVersion,
        ] =
            credential.copyOf()
    }
    override suspend fun delete(credentialRef: String, keyVersion: Int) {
        values.remove(credentialRef to keyVersion)?.fill(0)
    }
    override suspend fun contains(credentialRef: String, keyVersion: Int) = credentialRef to keyVersion in values
    override suspend fun <T> withCredential(credentialRef: String, keyVersion: Int, block: suspend (ByteArray) -> T): T {
        val value = requireNotNull(values[credentialRef to keyVersion]).copyOf()
        return try {
            block(value)
        } finally {
            value.fill(0)
        }
    }
}

private class FakeProfiles : ProviderProfileStore {
    private var profile: ProviderProfile? = null
    override suspend fun load() = profile
    override suspend fun save(profile: ProviderProfile) {
        this.profile = profile
    }
    override suspend fun clear() {
        profile = null
    }
}
