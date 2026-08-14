package com.zhiban.rebuild

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalizationResourcesDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun contactCountUsesLocaleSpecificPluralResources() {
        assertEquals("1 contact", localizedContext(Locale.US).resources.getQuantityString(R.plurals.contact_count, 1, 1))
        assertEquals("2 contacts", localizedContext(Locale.US).resources.getQuantityString(R.plurals.contact_count, 2, 2))
        assertEquals("2 位联系人", localizedContext(Locale.CHINA).resources.getQuantityString(R.plurals.contact_count, 2, 2))
    }

    private fun localizedContext(locale: Locale): Context {
        val configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }
        return context.createConfigurationContext(configuration)
    }
}
