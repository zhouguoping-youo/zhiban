package com.zhiban.rebuild.data.calllog

import android.provider.CallLog
import org.junit.Assert.assertEquals
import org.junit.Test

class CallLogSourceTest {
    @Test
    fun mapsEverySupportedAndroidCallTypeWithoutGuessingUnknownValues() {
        assertEquals("INCOMING", callDirection(CallLog.Calls.INCOMING_TYPE))
        assertEquals("OUTGOING", callDirection(CallLog.Calls.OUTGOING_TYPE))
        assertEquals("MISSED", callDirection(CallLog.Calls.MISSED_TYPE))
        assertEquals("REJECTED", callDirection(CallLog.Calls.REJECTED_TYPE))
        assertEquals("BLOCKED", callDirection(CallLog.Calls.BLOCKED_TYPE))
        assertEquals("VOICEMAIL", callDirection(CallLog.Calls.VOICEMAIL_TYPE))
        assertEquals("OTHER", callDirection(Int.MAX_VALUE))
    }
}
