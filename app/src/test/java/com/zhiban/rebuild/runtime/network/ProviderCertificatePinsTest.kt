package com.zhiban.rebuild.runtime.network

import com.zhiban.rebuild.runtime.provider.ProviderProfile
import com.zhiban.rebuild.runtime.provider.TrustedProviderRegistry
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCertificatePinsTest {
    @Test fun everyTrustedProviderHostHasTwoValidDistinctPins() {
        val registry = TrustedProviderRegistry()
        val trustedHosts = registry.presets().map { preset ->
            val endpoint = registry.resolve(
                ProviderProfile(preset.providerId, preset.endpointId, preset.defaultModel, "credential.test", 1),
            )
            URI(endpoint.chatUrl).host.also { assertEquals(it, URI(endpoint.probeUrl).host) }
        }.toSet()

        assertEquals(trustedHosts, ProviderCertificatePins.pinsByHost.keys)
        ProviderCertificatePins.pinsByHost.forEach { (host, pins) ->
            assertTrue("$host needs active and backup pins", pins.size >= 2)
            pins.forEach { pin ->
                assertTrue("invalid pin for $host", pin.matches(Regex("sha256/[A-Za-z0-9+/]{43}=")))
            }
        }
        ProviderCertificatePins.build()
    }
}
