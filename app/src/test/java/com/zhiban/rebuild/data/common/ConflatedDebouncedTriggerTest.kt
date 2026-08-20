package com.zhiban.rebuild.data.common

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConflatedDebouncedTriggerTest {
    @Test
    fun `signal waits for debounce and ordinary failure does not stop later sweep`() = runTest {
        var attempts = 0
        val failures = mutableListOf<String>()
        val trigger = ConflatedDebouncedTrigger(
            scope = backgroundScope,
            debounceMs = 100,
            onFailure = { failures += it.message.orEmpty() },
            action = {
                attempts += 1
                if (attempts == 1) error("first sweep failed")
            },
        )

        trigger.signal()
        advanceTimeBy(99)
        runCurrent()
        assertEquals(0, attempts)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, attempts)
        assertEquals(listOf("first sweep failed"), failures)

        trigger.signal()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(2, attempts)
        assertEquals(listOf("first sweep failed"), failures)
    }
}
