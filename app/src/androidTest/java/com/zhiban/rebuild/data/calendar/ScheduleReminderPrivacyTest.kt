package com.zhiban.rebuild.data.calendar

import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScheduleReminderPrivacyTest {
    @Test fun lockScreenVersionDoesNotContainScheduleDetails() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val notification = buildScheduleReminderNotification(context, "秘密客户会议", "14:30", 1_800_000L)

        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        val publicVersion = requireNotNull(notification.publicVersion)
        assertEquals("知伴日程提醒", publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals("解锁后查看详情", publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        requireNotNull(notification.contentIntent)
    }
}
