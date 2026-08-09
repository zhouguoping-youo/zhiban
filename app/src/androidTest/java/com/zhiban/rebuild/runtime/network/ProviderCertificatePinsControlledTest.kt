package com.zhiban.rebuild.runtime.network

import androidx.test.platform.app.InstrumentationRegistry
import com.zhiban.rebuild.runtime.provider.ProviderProfile
import com.zhiban.rebuild.runtime.provider.TrustedProviderRegistry
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in external-network acceptance; normal CI remains deterministic. */
class ProviderCertificatePinsControlledTest {
    @Test fun liveAndroidHandshakeAcceptsEveryPinnedProviderChain() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("runProviderTlsControlled") == "true")
        val client = OkHttpClient.Builder()
            .certificatePinner(ProviderCertificatePins.build())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        val registry = TrustedProviderRegistry()

        registry.presets().forEach { preset ->
            val endpoint = registry.resolve(
                ProviderProfile(preset.providerId, preset.endpointId, preset.defaultModel, "credential.test", 1),
            )
            client.newCall(Request.Builder().url(endpoint.probeUrl).get().build()).execute().use { response ->
                assertTrue(
                    "${preset.providerId} (${URI(endpoint.probeUrl).host}) did not complete HTTPS",
                    response.code in 100..599,
                )
            }
        }
    }
}
