package com.zhiban.rebuild.runtime.context

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class RetrievalAttemptTest {
    @Test fun failureReturnsFixedReasonWithoutExceptionText() = runTest {
        val result = attemptRetrieval<List<String>>("memory_fts") {
            throw IOException("private SQL and user content")
        }

        assertNull(result.value)
        assertEquals("memory_fts:failure", result.degradation)
    }

    @Test fun timeoutHasDistinctFixedReason() = runTest {
        val result = attemptRetrieval<List<String>>("memory_fts", timeoutMs = 10) {
            delay(20)
            emptyList()
        }

        assertNull(result.value)
        assertEquals("memory_fts:timeout", result.degradation)
    }

    @Test fun cancellationIsRethrown() = runTest {
        try {
            attemptRetrieval<List<String>>("memory_fts") {
                throw CancellationException("caller cancelled")
            }
            fail("CancellationException should be rethrown")
        } catch (_: CancellationException) {
            // Expected: cancellation is control flow, not retrieval degradation.
        }
    }
}
