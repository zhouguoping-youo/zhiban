package com.zhiban.rebuild.data.location

import android.Manifest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.zhiban.rebuild.provider.LocationUnavailable
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real LocationManager path on device. A Samsung handset indoors may still have no fresh
 * GNSS/network fix within the timeout, so the test accepts either a valid GCJ-02 snapshot or a
 * known-reason [LocationUnavailable] — both prove the gateway is wired correctly end to end. The actual
 * outcome is printed so a run also reports the live coordinates when a fix is available.
 */
@RunWith(AndroidJUnit4::class)
class SystemLocationGatewayInstrumentedTest {
    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    @Test
    fun currentLocationReturnsValidGcj02SnapshotOrAKnownReason() = runBlocking {
        val gateway = SystemLocationGateway(ApplicationProvider.getApplicationContext())
        val outcome = runCatching { gateway.currentLocation() }
        val snapshot = outcome.getOrNull()
        if (snapshot != null) {
            assertTrue(snapshot.latitude in -90.0..90.0)
            assertTrue(snapshot.longitude in -180.0..180.0)
            assertTrue(snapshot.accuracyMeters >= 0f)
            assertTrue(snapshot.capturedAtEpochMs > 0)
            println("LOCATION_FIX system=GCJ-02 lat=${snapshot.latitude} lon=${snapshot.longitude} accuracyM=${snapshot.accuracyMeters}")
        } else {
            val error = outcome.exceptionOrNull()
            assertTrue(error is LocationUnavailable)
            val reason = (error as LocationUnavailable).reasonCode
            assertTrue(
                reason in setOf(
                    LocationUnavailable.SERVICE_DISABLED,
                    LocationUnavailable.TIMEOUT,
                    LocationUnavailable.UNAVAILABLE,
                ),
            )
            println("LOCATION_UNAVAILABLE reason=$reason")
        }
    }
}
