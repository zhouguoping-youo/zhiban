package com.zhiban.rebuild.runtime.provider

/**
 * A single on-demand device fix. Deliberately carries no reverse-geocoded address: resolving a
 * coordinate to a place name is a separate (outbound) step, not part of reading the sensor.
 */
data class LocationSnapshot(val latitude: Double, val longitude: Double, val accuracyMeters: Float, val capturedAtEpochMs: Long) {
    init {
        require(latitude in -MAX_LATITUDE..MAX_LATITUDE && longitude in -MAX_LONGITUDE..MAX_LONGITUDE) { "INVALID_COORDINATES" }
        require(accuracyMeters >= 0f) { "INVALID_ACCURACY" }
        require(capturedAtEpochMs > 0) { "INVALID_CAPTURE_TIME" }
    }

    private companion object {
        const val MAX_LATITUDE = 90.0
        const val MAX_LONGITUDE = 180.0
    }
}

/**
 * Reads the device's own location, one shot at a time. There is intentionally no watch/tracking
 * method — ZhiBan never collects a background trajectory (PRODUCT.md 克制采集). The contract lives in
 * agent:provider beside WebSearchGateway so it stays pure JVM and mockable; the Android implementation
 * lives in the app module.
 */
fun interface LocationGateway {
    /** Single fix. Throws [LocationUnavailable] (never a bare failure) when it cannot produce one. */
    suspend fun currentLocation(): LocationSnapshot
}

/** A location read that could not be satisfied, with a machine-readable reason the Agent can relay. */
class LocationUnavailable(val reasonCode: String) : IllegalStateException(reasonCode) {
    companion object {
        const val PERMISSION_DENIED = "PERMISSION_DENIED"
        const val SERVICE_DISABLED = "SERVICE_DISABLED"
        const val TIMEOUT = "TIMEOUT"
        const val UNAVAILABLE = "UNAVAILABLE"
    }
}
