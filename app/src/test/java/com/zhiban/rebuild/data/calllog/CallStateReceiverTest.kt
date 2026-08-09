package com.zhiban.rebuild.data.calllog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallStateReceiverTest {
    @Test
    fun onlyIdlePhoneStateTriggersReconciliation() {
        assertTrue(shouldEnqueueCallReconcile("android.intent.action.PHONE_STATE", "IDLE"))
        assertFalse(shouldEnqueueCallReconcile("android.intent.action.PHONE_STATE", "OFFHOOK"))
        assertFalse(shouldEnqueueCallReconcile("other", "IDLE"))
    }
}
