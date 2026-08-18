package com.zhiban.rebuild.runtime.tool

import com.zhiban.rebuild.provider.LocationGateway
import com.zhiban.rebuild.provider.LocationSnapshot
import com.zhiban.rebuild.provider.LocationUnavailable
import com.zhiban.rebuild.provider.ProviderFailure
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationCurrentToolBindingTest {
    private val catalog = RuntimeToolCatalog.production()
    private val spec = catalog.requireRegistered(LocationCurrentToolBinding.TOOL_NAME)

    @Test fun `returns a GCJ-02 snapshot without confirmation`() = runTest {
        val binding = LocationCurrentToolBinding(spec, gatewayReturning(LocationSnapshot(30.590391, 114.310945, 12.5f, 1_700_000_000_000L)), consent = { true })
        val result = binding.executeReadOnly(RuntimeToolCallRequest("call-1", spec.name, """{}"""), context())
        assertEquals(spec.name, result.canonicalName)
        assertTrue(result.safeResultJson.contains("\"coordinateSystem\":\"GCJ-02\""))
        assertTrue(result.safeResultJson.contains("30.590391"))
        assertTrue(result.safeResultJson.contains("114.310945"))
        assertTrue(result.safeResultJson.contains("\"accuracyMeters\":12.5"))
    }

    @Test fun `consent off rejects the call before touching the gateway`() = runTest {
        var calls = 0
        val binding = LocationCurrentToolBinding(
            spec,
            LocationGateway {
                calls += 1
                LocationSnapshot(1.0, 2.0, 1f, 1L)
            },
            consent = { false },
        )
        val failure = runCatching {
            binding.executeReadOnly(RuntimeToolCallRequest("c", spec.name, """{}"""), context())
        }.exceptionOrNull()
        assertTrue(failure is ProviderFailure)
        failure as ProviderFailure
        assertEquals("LOCATION_CONSENT_REQUIRED", failure.code)
        assertFalse(failure.retryable)
        assertEquals(0, calls) // 未同意:连系统定位都不碰
    }

    @Test fun `rejects unexpected arguments`() = runTest {
        val binding = LocationCurrentToolBinding(spec, gatewayReturning(LocationSnapshot(1.0, 2.0, 1f, 1L)), consent = { true })
        val failure = runCatching {
            binding.executeReadOnly(RuntimeToolCallRequest("c", spec.name, """{"query":"nearby"}"""), context())
        }.exceptionOrNull()
        assertTrue(failure is ProviderFailure)
        assertEquals("INVALID_TOOL_ARGUMENTS", (failure as ProviderFailure).code)
    }

    @Test fun `permission denial is surfaced as non-retryable`() = runTest {
        val binding = LocationCurrentToolBinding(spec, gatewayFailing(LocationUnavailable.PERMISSION_DENIED), consent = { true })
        val failure = runCatching {
            binding.executeReadOnly(RuntimeToolCallRequest("c", spec.name, """{}"""), context())
        }.exceptionOrNull()
        assertTrue(failure is ProviderFailure)
        failure as ProviderFailure
        assertEquals(LocationUnavailable.PERMISSION_DENIED, failure.code)
        assertFalse(failure.retryable)
    }

    @Test fun `transient unavailability is retryable`() = runTest {
        val binding = LocationCurrentToolBinding(spec, gatewayFailing(LocationUnavailable.TIMEOUT), consent = { true })
        val failure = runCatching {
            binding.executeReadOnly(RuntimeToolCallRequest("c", spec.name, """{}"""), context())
        }.exceptionOrNull()
        assertTrue(failure is ProviderFailure)
        failure as ProviderFailure
        assertEquals(LocationUnavailable.TIMEOUT, failure.code)
        assertTrue(failure.retryable)
    }

    @Test fun `factory omits the tool when no gateway is wired`() {
        assertTrue(locationToolBindings(catalog, null).isEmpty())
        assertEquals(1, locationToolBindings(catalog, gatewayReturning(LocationSnapshot(1.0, 2.0, 1f, 1L)), consent = { true }).size)
    }

    @Test fun `factory defaults to consent denied`() = runTest {
        val bindings = locationToolBindings(catalog, gatewayReturning(LocationSnapshot(1.0, 2.0, 1f, 1L)))
        val failure = runCatching {
            bindings.single().executeReadOnly(RuntimeToolCallRequest("c", spec.name, """{}"""), context())
        }.exceptionOrNull()
        assertTrue(failure is ProviderFailure)
        assertEquals("LOCATION_CONSENT_REQUIRED", (failure as ProviderFailure).code)
    }

    private fun gatewayReturning(snapshot: LocationSnapshot) = LocationGateway { snapshot }

    private fun gatewayFailing(reasonCode: String) = LocationGateway { throw LocationUnavailable(reasonCode) }

    private fun context() = RuntimeToolRouteContext("run", "session", "attempt", "owner", 1, 1, 1)
}
