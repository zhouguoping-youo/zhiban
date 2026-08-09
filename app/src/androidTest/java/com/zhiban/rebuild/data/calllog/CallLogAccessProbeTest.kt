package com.zhiban.rebuild.data.calllog

import android.Manifest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CallLogAccessProbeTest {
    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.READ_CALL_LOG)

    @Test
    fun grantedPermissionCanQueryProviderWithoutReadingPhoneNumbers() = runBlocking {
        val status = CallLogAccessProbe.probe(ApplicationProvider.getApplicationContext())

        assertEquals(CallLogAccessStatus.AVAILABLE, status)
    }
}
