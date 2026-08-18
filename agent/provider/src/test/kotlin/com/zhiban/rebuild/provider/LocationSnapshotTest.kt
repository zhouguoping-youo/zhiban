package com.zhiban.rebuild.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocationSnapshotTest {
    @Test fun `valid snapshot is accepted`() {
        val snapshot = LocationSnapshot(latitude = 30.590391, longitude = 114.310945, accuracyMeters = 12.5f, capturedAtEpochMs = 1_700_000_000_000L)
        assertEquals(30.590391, snapshot.latitude, 0.0)
        assertEquals(114.310945, snapshot.longitude, 0.0)
    }

    @Test fun `out of range coordinates are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            LocationSnapshot(latitude = 91.0, longitude = 0.0, accuracyMeters = 1f, capturedAtEpochMs = 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocationSnapshot(latitude = 0.0, longitude = 181.0, accuracyMeters = 1f, capturedAtEpochMs = 1L)
        }
    }

    @Test fun `negative accuracy or non-positive timestamp is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            LocationSnapshot(latitude = 0.0, longitude = 0.0, accuracyMeters = -1f, capturedAtEpochMs = 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocationSnapshot(latitude = 0.0, longitude = 0.0, accuracyMeters = 1f, capturedAtEpochMs = 0L)
        }
    }

    @Test fun `unavailability carries a stable reason code`() {
        assertEquals(LocationUnavailable.PERMISSION_DENIED, LocationUnavailable(LocationUnavailable.PERMISSION_DENIED).reasonCode)
        assertEquals(LocationUnavailable.TIMEOUT, LocationUnavailable(LocationUnavailable.TIMEOUT).message)
    }
}
