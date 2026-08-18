package com.zhiban.rebuild.data.location

import android.location.LocationManager
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P1 边界:FUSED_PROVIDER 守卫必须是 S(API 31),不是 R(30)。API 30 上该常量不存在,
 * 应只提供 network/GPS 回退。
 */
class SystemLocationProviderGuardTest {

    @Test fun fusedProviderOnlyIncludedFromApi31() {
        assertEquals(
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER),
            liveLocationProviders(Build.VERSION_CODES.R),
        )
        assertEquals(
            listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER),
            liveLocationProviders(Build.VERSION_CODES.S),
        )
    }
}
