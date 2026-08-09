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
    fun provideOkHttpClient(@ApplicationContext context: Context): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            redactHeader("Authorization")
            level = loggingLevel(
                isDebuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            )
        }
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .certificatePinner(ProviderCertificatePins.build())
            .addInterceptor(logging)
            .build()
    }

    internal fun loggingLevel(isDebuggable: Boolean): HttpLoggingInterceptor.Level = if (isDebuggable) {
        HttpLoggingInterceptor.Level.BASIC
    } else {
        HttpLoggingInterceptor.Level.NONE
    }
}
