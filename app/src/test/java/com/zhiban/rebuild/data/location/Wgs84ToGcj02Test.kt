package com.zhiban.rebuild.data.location

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the WGS-84 → GCJ-02 transform against the published evilttransform reference vector so a
 * future edit cannot silently swap the argument order or a sign and start misplacing markers by
 * hundreds of metres. All expectations were computed from the algorithm itself (see commit notes).
 */
class Wgs84ToGcj02Test {
    @Test fun `beijing matches the published gcj02 reference vector`() {
        val (lat, lon) = wgs84ToGcj02(39.915, 116.404)
        assertEquals(39.916404, lat, 1e-4)
        assertEquals(116.410244, lon, 1e-4)
    }

    @Test fun `wuhan fix moves into the gcj02 datum`() {
        val (lat, lon) = wgs84ToGcj02(30.5928, 114.3055)
        assertTrue(abs(lat - 30.590391) < 1e-4)
        assertTrue(abs(lon - 114.310945) < 1e-4)
    }

    @Test fun `coordinates outside mainland china pass through unchanged`() {
        val (lat, lon) = wgs84ToGcj02(40.7128, -74.0060)
        assertEquals(40.7128, lat, 0.0)
        assertEquals(-74.0060, lon, 0.0)
    }

    @Test fun `offset stays well under one kilometre across china`() {
        listOf(39.9 to 116.4, 22.5 to 114.0, 31.2 to 121.5, 30.6 to 104.0, 45.8 to 126.5).forEach { (lat, lon) ->
            val (gcjLat, gcjLon) = wgs84ToGcj02(lat, lon)
            assertTrue(abs(gcjLat - lat) < 0.01)
            assertTrue(abs(gcjLon - lon) < 0.01)
        }
    }

    @Test fun `conversion is deterministic`() {
        assertEquals(wgs84ToGcj02(30.5928, 114.3055), wgs84ToGcj02(30.5928, 114.3055))
    }
}
