package com.zhiban.rebuild.ui.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultimodalUiStateTest {
    @Test fun `contract enums remain independent and exhaustive`() {
        assertEquals(5, DevicePermissionState.entries.size)
        assertEquals(4, ProviderCapabilityState.entries.size)
        assertEquals(10, AttachmentPhase.entries.size)
        assertEquals(7, TranscriptionPhase.entries.size)
    }

    @Test fun `expired uri requires reselection or deletion`() {
        assertEquals(
            setOf(AttachmentAction.RESELECT, AttachmentAction.DELETE),
            AttachmentUiState.defaultActions(AttachmentPhase.URI_EXPIRED, retryable = false),
        )
    }

    @Test fun `batch progress aggregates bytes`() {
        val batch = AttachmentBatchUiState(
            listOf(
                AttachmentUiState("1", "a", InputModality.IMAGE, bytesSent = 50, totalBytes = 100),
                AttachmentUiState("2", "b", InputModality.FILE, bytesSent = 25, totalBytes = 100),
            ),
        )
        assertEquals(.375f, batch.progress)
    }

    @Test fun `retry is exposed only for retryable failure`() {
        assertTrue(AttachmentUiState.defaultActions(AttachmentPhase.FAILED, true).contains(AttachmentAction.RETRY))
        assertFalse(AttachmentUiState.defaultActions(AttachmentPhase.FAILED, false).contains(AttachmentAction.RETRY))
    }

    @Test fun `partial transcription cannot be confirmed`() {
        assertFalse(TranscriptionUiState(TranscriptionPhase.TRANSCRIBING, partialText = "临时").canConfirmFinal)
        assertTrue(TranscriptionUiState(TranscriptionPhase.FINAL, finalText = "确认文本").canConfirmFinal)
    }
}
