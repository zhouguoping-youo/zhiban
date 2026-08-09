package com.zhiban.rebuild.runtime.input

import org.junit.Assert.assertEquals
import org.junit.Test

class MultimodalInputModuleTest {
    private val limits = InputLimits(
        maxAttachmentItems = 2,
        maxPerItemBytes = 8,
        maxAggregateBytes = 12,
        maxAudioDurationMs = 5_000,
        allowedMimeTypes = setOf("image/png", "audio/wav", "text/plain"),
    )

    @Test fun metadataAndMagicMustBothMatchFailClosedLimits() {
        val valid = AttachmentInspection("a", "image/png", "image/png", 8, null, "digest-a")
        assertEquals(InputValidation.Accepted, MultimodalInputPolicy(limits).validate(listOf(valid)))
        val spoofed = valid.copy(detectedMimeType = "text/plain")
        assertEquals(
            InputValidation.Rejected(InputError.MIME_MISMATCH),
            MultimodalInputPolicy(limits).validate(listOf(spoofed)),
        )
        assertEquals(
            InputValidation.Rejected(InputError.ITEM_TOO_LARGE),
            MultimodalInputPolicy(limits).validate(listOf(valid.copy(byteLength = 9))),
        )
    }

    @Test fun countAggregateAndDurationAreBoundedBeforeStaging() {
        val audio = AttachmentInspection("a", "audio/wav", "audio/wav", 6, 5_001, "digest-a")
        assertEquals(
            InputValidation.Rejected(InputError.DURATION_EXCEEDED),
            MultimodalInputPolicy(limits).validate(listOf(audio)),
        )
        val items = listOf(
            AttachmentInspection("a", "text/plain", "text/plain", 7, null, "a"),
            AttachmentInspection("b", "text/plain", "text/plain", 6, null, "b"),
        )
        assertEquals(
            InputValidation.Rejected(InputError.AGGREGATE_TOO_LARGE),
            MultimodalInputPolicy(limits).validate(items),
        )
        assertEquals(
            InputValidation.Rejected(InputError.TOO_MANY_ITEMS),
            MultimodalInputPolicy(limits).validate(items + items.first().copy(attachmentId = "c")),
        )
    }
}
