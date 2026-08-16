package com.zhiban.rebuild.di

import android.content.Context
import android.content.pm.ApplicationInfo
import com.zhiban.rebuild.runtime.network.ProviderCertificatePins
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(@ApplicationContext context: Context): OkHttpClient = buildClient(
        isDebuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
    )

    internal fun buildClient(isDebuggable: Boolean): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            redactHeader("Authorization")
            level = loggingLevel(isDebuggable)
        }
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .certificatePinner(ProviderCertificatePins.build())
            // A certificate-pinned API client has no legitimate cross-host redirect use case.
            // Following a 30x off a pinned host would silently drop the pin (and the request
            // body) onto an unpinned host, so disable redirect-following outright.
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(logging)
            .build()
    }

    internal fun loggingLevel(isDebuggable: Boolean): HttpLoggingInterceptor.Level = if (isDebuggable) {
        HttpLoggingInterceptor.Level.BASIC
    } else {
        HttpLoggingInterceptor.Level.NONE
    }
}
