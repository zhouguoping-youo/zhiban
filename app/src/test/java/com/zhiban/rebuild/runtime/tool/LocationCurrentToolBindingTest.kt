package com.zhiban.rebuild.runtime.tool

import com.zhiban.rebuild.runtime.provider.LocationGateway
import com.zhiban.rebuild.runtime.provider.LocationSnapshot
import com.zhiban.rebuild.runtime.provider.LocationUnavailable
import com.zhiban.rebuild.runtime.provider.ProviderFailure
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationCurrentToolBindingTest {
    private val catalog = RuntimeToolCatalog.production()
    private val spec = catalog.requireRegistered(LocationCurrentToolBinding.TOOL_NAME)

    @Test fun `returns a GCJ-02 snapshot without confirmation`() = runTest {
        val binding = LocationCurrentToolBinding(spec, gatewayReturning(LocationSnapshot(30.590391, 114.310945, 12.5f, 1_700_000_000_000L)))
        val result = binding.executeReadOnly(RuntimeToolCallRequest("call-1", spec.name, """{}"""), context())
        assertEquals(spec.name, result.canonicalName)
        assertTrue(result.safeResultJson.contains("\"coordinateSystem\":\"GCJ-02\""))
        assertTrue(result.safeResultJson.contains("30.590391"))
        assertTrue(result.safeResultJson.contains("114.310945"))
        assertTrue(result.safeResultJson.contains("\"accuracyMeters\":12.5"))
    }

    @Test fun `rejects unexpected arguments`() = runTest {
        val binding = LocationCurrentToolBinding(spec, gatewayReturning(LocationSnapshot(1.0, 2.0, 1f, 1L)))
        val failure = runCatching {
            binding.executeReadOnly(RuntimeToolCallRequest("c", spec.name, """{"query":"nearby"}"""), context())
        }.exceptionOrNull()
        assertTrue(failure is ProviderFailure)
        assertEquals("INVALID_TOOL_ARGUMENTS", (failure as ProviderFailure).code)
    }

    @Test fun `permission denial is surfaced as non-retryable`() = runTest {
        val binding = LocationCurrentToolBinding(spec, gatewayFailing(LocationUnavailable.PERMISSION_DENIED))
        val failure = runCatching {
            binding.executeReadOnly(RuntimeToolCallRequest("c", spec.name, """{}"""), context())
        }.exceptionOrNull()
        assertTrue(failure is ProviderFailure)
        failure as ProviderFailure
        assertEquals(LocationUnavailable.PERMISSION_DENIED, failure.code)
        assertFalse(failure.retryable)
    }

    @Test fun `transient unavailability is retryable`() = runTest {
        val binding = LocationCurrentToolBinding(spec, gatewayFailing(LocationUnavailable.TIMEOUT))
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
        assertEquals(1, locationToolBindings(catalog, gatewayReturning(LocationSnapshot(1.0, 2.0, 1f, 1L))).size)
    }

    private fun gatewayReturning(snapshot: LocationSnapshot) = LocationGateway { snapshot }

    private fun gatewayFailing(reasonCode: String) = LocationGateway { throw LocationUnavailable(reasonCode) }

    private fun context() = RuntimeToolRouteContext("run", "session", "attempt", "owner", 1, 1, 1)
}
