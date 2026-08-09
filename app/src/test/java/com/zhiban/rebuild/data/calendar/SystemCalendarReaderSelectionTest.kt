package com.zhiban.rebuild.data.calendar

import android.provider.CalendarContract
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemCalendarReaderSelectionTest {
    @Test fun instanceQueryExcludesCanceledRecurrenceOccurrences() {
        assertTrue(ACTIVE_INSTANCE_SELECTION.contains(CalendarContract.Instances.VISIBLE))
        assertTrue(ACTIVE_INSTANCE_SELECTION.contains(CalendarContract.Instances.STATUS))
        assertTrue(ACTIVE_INSTANCE_SELECTION.contains("!= ?"))
    }
}
