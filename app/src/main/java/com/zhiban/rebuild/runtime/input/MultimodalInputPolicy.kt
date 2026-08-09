package com.zhiban.rebuild.runtime.input

internal class MultimodalInputPolicy(private val limits: InputLimits) {
    fun validate(items: List<AttachmentInspection>): InputValidation {
        if (items.size > limits.maxAttachmentItems) return InputValidation.Rejected(InputError.TOO_MANY_ITEMS)
        if (items.any {
                it.declaredMimeType !in limits.allowedMimeTypes ||
                    it.detectedMimeType !in limits.allowedMimeTypes
            }
        ) {
            return InputValidation.Rejected(InputError.MIME_NOT_ALLOWED)
        }
        if (items.any {
                it.declaredMimeType != it.detectedMimeType
            }
        ) {
            return InputValidation.Rejected(InputError.MIME_MISMATCH)
        }
        if (items.any {
                it.byteLength > limits.maxPerItemBytes
            }
        ) {
            return InputValidation.Rejected(InputError.ITEM_TOO_LARGE)
        }
        if (items.sumOf { it.byteLength } >
            limits.maxAggregateBytes
        ) {
            return InputValidation.Rejected(InputError.AGGREGATE_TOO_LARGE)
        }
        if (items.any {
                it.durationMs != null && it.durationMs > limits.maxAudioDurationMs
            }
        ) {
            return InputValidation.Rejected(InputError.DURATION_EXCEEDED)
        }
        return InputValidation.Accepted
    }
}
