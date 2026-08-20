package com.zhiban.rebuild.runtime.tool

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class RoomMemoryToolExecutorDegradationTest {
    @Test fun approvedMemoryFailureReturnsTraceableFixedReason() = runTest {
        val executor = RoomMemoryToolExecutor(database = { throw IOException("private database path") })

        val result = executor.recallApproved("查询")

        assertEquals(emptyList<String>(), result.items)
        assertEquals(listOf("memory_approved:failure"), result.degradationReasons)
    }

    @Test fun approvedMemoryCancellationIsRethrown() = runTest {
        val executor = RoomMemoryToolExecutor(database = { throw CancellationException("caller cancelled") })

        try {
            executor.recallApproved("查询")
            fail("CancellationException should be rethrown")
        } catch (_: CancellationException) {
            // Expected: ProviderExecutionEngine must observe cancellation directly.
        }
    }
}
