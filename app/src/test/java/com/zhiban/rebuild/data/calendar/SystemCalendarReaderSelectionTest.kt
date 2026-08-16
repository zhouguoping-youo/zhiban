package com.zhiban.rebuild.data.calendar

import android.provider.CalendarContract
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemCalendarReaderSelectionTest {
    @Test fun instanceQueryExcludesCanceledRecurrenceOccurrences() {
        assertTrue(ACTIVE_INSTANCE_SELECTION.contains(CalendarContract.Instances.VISIBLE))
        assertTrue(ACTIVE_INSTANCE_SELECTION.contains(CalendarContract.Instances.STATUS))
        assertTrue(ACTIVE_INSTANCE_SELECTION.contains("!= ?"))
    }

    @Test fun instanceQueryKeepsEventsWhoseStatusIsNull() {
        assertTrue(ACTIVE_INSTANCE_SELECTION.contains("${CalendarContract.Instances.STATUS} IS NULL"))
        assertEquals(1, ACTIVE_INSTANCE_SELECTION.count { it == '?' })
    }

    @Test fun cancellationIsRethrownNotConvertedToErrorResult() {
        assertThrows(CancellationException::class.java) {
            queryFailureResult(CancellationException("job cancelled"))
        }
    }

    @Test fun genuineQueryFailureBecomesErrorResult() {
        val result = queryFailureResult(IllegalStateException("boom"))

        assertEquals("boom", result.errorMessage)
        assertTrue(result.events.isEmpty())
    }

    @Test fun failureWithoutMessageFallsBackToDefaultErrorText() {
        val result = queryFailureResult(IllegalStateException())

        assertEquals("读取系统日历失败", result.errorMessage)
    }
}
