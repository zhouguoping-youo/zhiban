package com.zhiban.rebuild.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import android.os.Process
import androidx.core.content.ContextCompat
import com.zhiban.rebuild.runtime.provider.LocationGateway
import com.zhiban.rebuild.runtime.provider.LocationSnapshot
import com.zhiban.rebuild.runtime.provider.LocationUnavailable
import java.util.function.Consumer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * On-demand, single-shot device location built directly on [LocationManager] rather than the Play
 * Services fused provider, because the fused client silently yields no fix on the non-GMS Chinese
 * handsets ZhiBan targets. Only ever requests ONE update (no listener stays registered), honoring the
 * no-background-trajectory boundary. Coordinates are converted to GCJ-02 here so every downstream
 * consumer (map deep-link, POI search) matches Chinese basemaps without each re-deriving the offset.
 */
internal class SystemLocationGateway(private val context: Context) : LocationGateway {
    override suspend fun currentLocation(): LocationSnapshot {
        if (!hasLocationPermission()) throw LocationUnavailable(LocationUnavailable.PERMISSION_DENIED)
        val manager = context.getSystemService(LocationManager::class.java)
            ?: throw LocationUnavailable(LocationUnavailable.UNAVAILABLE)
        // getProviders(enabledOnly=true) is empty when location is off; isLocationEnabled() needs API 28.
        if (manager.getProviders(true).isEmpty()) throw LocationUnavailable(LocationUnavailable.SERVICE_DISABLED)
        // Fresh cached fix → instant; else one live single shot; else any recent-enough cached fix. Failing
        // only when nothing exists beats timing out indoors, where a cached network fix is almost always present.
        val fix = freshestCachedFix(manager, MAX_FRESH_FIX_AGE_MS, MAX_FRESH_ACCURACY_METERS)
            ?: requestLiveFixOrNull(manager)
            ?: freshestCachedFix(manager, MAX_FALLBACK_FIX_AGE_MS, MAX_FALLBACK_ACCURACY_METERS)
            ?: throw LocationUnavailable(LocationUnavailable.TIMEOUT)
        val (latitude, longitude) = wgs84ToGcj02(fix.latitude, fix.longitude)
        return LocationSnapshot(latitude, longitude, fix.accuracy, fix.time)
    }

    private fun hasLocationPermission(): Boolean = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        .any { context.checkPermission(it, Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED }

    /** Freshest cached fix within [maxAgeMs] and [maxAccuracyMeters], or null when none qualifies. */
    @SuppressLint("MissingPermission")
    private fun freshestCachedFix(manager: LocationManager, maxAgeMs: Long, maxAccuracyMeters: Float): Location? = manager.getProviders(true)
        .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
        .filter { it.hasAccuracy() && it.accuracy <= maxAccuracyMeters }
        .filter { System.currentTimeMillis() - it.time <= maxAgeMs }
        .maxByOrNull(Location::getTime)

    // One live fix. On API 30+ getCurrentLocation is the reliable one-shot (it reaches the fused provider
    // third-party last-known lookups can't see); below that, requestSingleUpdate is the only single-shot API.
    // These are framework LocationManager calls, not the GMS FusedLocationProviderClient library, so they still
    // work on non-GMS handsets — there the fused provider is simply absent and we fall through to network/GPS.
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private suspend fun requestLiveFixOrNull(manager: LocationManager): Location? {
        val provider = liveProviders().firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) } ?: return null
        return withTimeoutOrNull(FIX_TIMEOUT) {
            suspendCancellableCoroutine<Location?> { continuation ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val signal = CancellationSignal()
                    val consumer = Consumer<Location?> { location ->
                        if (continuation.isActive) continuation.resumeWith(Result.success(location))
                    }
                    continuation.invokeOnCancellation { signal.cancel() }
                    runCatching { manager.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(context), consumer) }
                        .onFailure { if (continuation.isActive) continuation.resumeWith(Result.success(null)) }
                } else {
                    lateinit var listener: LocationListener
                    listener = LocationListener { location ->
                        manager.removeUpdates(listener)
                        if (continuation.isActive) continuation.resumeWith(Result.success(location))
                    }
                    continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                    runCatching { manager.requestSingleUpdate(provider, listener, Looper.getMainLooper()) }
                        .onFailure { if (continuation.isActive) continuation.resumeWith(Result.success(null)) }
                }
            }
        }
    }

    private fun liveProviders(): List<String> = liveLocationProviders(Build.VERSION.SDK_INT)

    private companion object {
        const val MAX_FRESH_FIX_AGE_MS = 15L * 60 * 1_000
        const val MAX_FRESH_ACCURACY_METERS = 250f
        const val MAX_FALLBACK_FIX_AGE_MS = 24L * 60 * 60 * 1_000
        const val MAX_FALLBACK_ACCURACY_METERS = 2_000f
        val FIX_TIMEOUT = 10.seconds
    }
}

/**
 * Live-fix provider preference. `LocationManager.FUSED_PROVIDER` only exists from API 31 (S) — the
 * guard must be S, not R (on API 30 the constant is merely absent and lookups fall back to
 * network/GPS, so the old guard never crashed, but it stated the wrong API level).
 */
internal fun liveLocationProviders(sdkInt: Int): List<String> = if (sdkInt >= Build.VERSION_CODES.S) {
    listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
} else {
    listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
}

/**
 * WGS-84 → GCJ-02 ("火星坐标"), the public-domain transform Chinese basemaps expect. Raw GNSS is
 * WGS-84; dropping it onto a 高德/百度 map unconverted shifts the marker ~100–700m. Pure math, no
 * network, so it is safe to run on-device and unit-test against known benchmark points. Locations
 * outside mainland China are returned unchanged (the GCJ-02 datum only applies there).
 */
internal fun wgs84ToGcj02(latitude: Double, longitude: Double): Pair<Double, Double> {
    if (gcjOutOfChina(latitude, longitude)) return latitude to longitude
    var deltaLat = gcjTransformLat(longitude - GCJ_LON_OFFSET, latitude - GCJ_LAT_OFFSET)
    var deltaLon = gcjTransformLon(longitude - GCJ_LON_OFFSET, latitude - GCJ_LAT_OFFSET)
    val radLat = latitude / 180.0 * PI
    var magic = sin(radLat)
    magic = 1.0 - GCJ_EE * magic * magic
    val sqrtMagic = sqrt(magic)
    deltaLat = deltaLat * 180.0 / (GCJ_A * (1.0 - GCJ_EE) / (magic * sqrtMagic) * PI)
    deltaLon = deltaLon * 180.0 / (GCJ_A / sqrtMagic * cos(radLat) * PI)
    return (latitude + deltaLat) to (longitude + deltaLon)
}

private fun gcjOutOfChina(latitude: Double, longitude: Double): Boolean =
    longitude < GCJ_LON_MIN || longitude > GCJ_LON_MAX || latitude < GCJ_LAT_MIN || latitude > GCJ_LAT_MAX

private fun gcjTransformLat(x: Double, y: Double): Double {
    var result = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
    result += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
    result += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
    result += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
    return result
}

private fun gcjTransformLon(x: Double, y: Double): Double {
    var result = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
    result += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
    result += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
    result += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
    return result
}

private const val GCJ_A = 6378245.0
private const val GCJ_EE = 0.00669342162296594323
private const val GCJ_LON_OFFSET = 105.0
private const val GCJ_LAT_OFFSET = 35.0
private const val GCJ_LON_MIN = 72.004
private const val GCJ_LON_MAX = 137.8347
private const val GCJ_LAT_MIN = 0.8293
private const val GCJ_LAT_MAX = 55.8271
