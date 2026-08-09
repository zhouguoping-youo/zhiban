package com.zhiban.rebuild

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedImageDecodePolicyTest {
    @Test fun oversizedDimensionsAreReducedBeforeBitmapAllocation() {
        assertEquals(8, calculateSharedImageSampleSize(12_000, 8_000))
        assertEquals(1, calculateSharedImageSampleSize(1_024, 768))
    }

    @Test fun declaredPayloadOverTwentyMegabytesIsRejectedBeforeDecode() {
        assertTrue(isSharedImageDeclaredSizeAllowed(-1))
        assertTrue(isSharedImageDeclaredSizeAllowed(20L * 1024 * 1024))
        assertFalse(isSharedImageDeclaredSizeAllowed(20L * 1024 * 1024 + 1))
    }
}
