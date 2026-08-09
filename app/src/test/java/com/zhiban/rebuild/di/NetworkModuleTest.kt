package com.zhiban.rebuild.di

import okhttp3.logging.HttpLoggingInterceptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NetworkModuleTest {

    @Test
    fun `debug logging is basic without headers or body`() {
        val level = NetworkModule.loggingLevel(isDebuggable = true)

        assertEquals(HttpLoggingInterceptor.Level.BASIC, level)
        assertFalse(level == HttpLoggingInterceptor.Level.HEADERS)
        assertFalse(level == HttpLoggingInterceptor.Level.BODY)
    }

    @Test
    fun `release logging is disabled`() {
        assertEquals(
            HttpLoggingInterceptor.Level.NONE,
            NetworkModule.loggingLevel(isDebuggable = false),
        )
    }
}
