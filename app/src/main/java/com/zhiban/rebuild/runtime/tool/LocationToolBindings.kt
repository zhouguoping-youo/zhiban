package com.zhiban.rebuild.runtime.tool

import com.zhiban.rebuild.runtime.provider.LocationGateway
import com.zhiban.rebuild.runtime.provider.LocationUnavailable
import com.zhiban.rebuild.runtime.provider.ProviderFailure
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Read-only binding for `location.current`. The snapshot is the user's own device data (never
 * external), so it does not trip the auto-write provenance gate; only coordinates and their
 * accuracy/age cross to the model — no address, no contact, nothing else. Any inability to fix is
 * surfaced as a machine-readable ProviderFailure so the Agent can tell the user exactly what to do
 * (grant permission / enable location / retry outdoors) instead of guessing.
 */
internal class LocationCurrentToolBinding(override val spec: RuntimeToolSpec, private val gateway: LocationGateway) : RuntimeToolBinding {
    override suspend fun requestApproval(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): Boolean =
        throw ToolPolicyRejectedException("read-only tools do not request approval")

    override suspend fun executeReadOnly(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): RoutedToolResult {
        // The tool takes no arguments; reject any the model invents.
        parseToolArgs(request.argumentsJson, emptySet()) { ProviderFailure("INVALID_TOOL_ARGUMENTS", false) }
        val snapshot = try {
            gateway.currentLocation()
        } catch (unavailable: LocationUnavailable) {
            throw ProviderFailure(unavailable.reasonCode, retryable = unavailable.reasonCode != LocationUnavailable.PERMISSION_DENIED)
        }
        val safeResult = buildJsonObject {
            put("coordinateSystem", "GCJ-02")
            put("latitude", snapshot.latitude)
            put("longitude", snapshot.longitude)
            put("accuracyMeters", snapshot.accuracyMeters)
            put("capturedAtEpochMs", snapshot.capturedAtEpochMs)
        }.toString()
        return RoutedToolResult(spec.name, request.providerCallId, safeResult)
    }

    companion object {
        const val TOOL_NAME = "location.current"
    }
}

/**
 * Location tools are injected via CapabilityRouter.dynamicBindings so ProviderExecutionEngine's static
 * binding list (already at the file-size audit ceiling) stays untouched. Returns empty when no gateway
 * is wired (e.g. a profile that should not expose location), which unregisters the tool entirely.
 */
internal fun locationToolBindings(catalog: RuntimeToolCatalog, gateway: LocationGateway?): List<RuntimeToolBinding> {
    val spec = catalog.specs[LocationCurrentToolBinding.TOOL_NAME] ?: return emptyList()
    return gateway?.let { listOf(LocationCurrentToolBinding(spec, it)) } ?: emptyList()
}
