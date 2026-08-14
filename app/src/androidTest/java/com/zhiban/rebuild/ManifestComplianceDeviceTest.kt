package com.zhiban.rebuild

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhiban.rebuild.data.notification.OutgoingMessageAccessibilityService
import com.zhiban.rebuild.data.notification.ZhiBanNotificationListenerService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManifestComplianceDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Suppress("DEPRECATION")
    @Test fun sensitiveServicesExposeAccurateLabelsAndDescriptions() {
        val packageManager = context.packageManager
        val notificationService = packageManager.getServiceInfo(
            ComponentName(context, ZhiBanNotificationListenerService::class.java),
            PackageManager.GET_META_DATA,
        )
        val outgoingService = packageManager.getServiceInfo(
            ComponentName(context, OutgoingMessageAccessibilityService::class.java),
            PackageManager.GET_META_DATA,
        )

        assertEquals(R.string.notification_listener_service_label, notificationService.labelRes)
        assertEquals(R.string.notification_listener_description, notificationService.descriptionRes)
        assertEquals(R.string.outgoing_accessibility_service_label, outgoingService.labelRes)
        assertEquals(R.string.outgoing_message_accessibility_description, outgoingService.descriptionRes)
        assertTrue(context.getString(notificationService.descriptionRes).contains("不代发消息"))
        assertTrue(context.getString(outgoingService.descriptionRes).contains("不会点击、代发"))
    }
}
