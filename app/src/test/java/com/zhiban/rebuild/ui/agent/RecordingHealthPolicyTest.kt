package com.zhiban.rebuild.ui.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingHealthPolicyTest {
    @Test
    fun revokedPermissionOrRecorderErrorTerminatesRecording() {
        assertEquals(RECORDING_INTERRUPTED_MESSAGE, recordingHealthFailureMessage(false, recorderError = false))
        assertEquals(RECORDING_INTERRUPTED_MESSAGE, recordingHealthFailureMessage(true, recorderError = true))
        assertNull(recordingHealthFailureMessage(true, recorderError = false))
    }
}
