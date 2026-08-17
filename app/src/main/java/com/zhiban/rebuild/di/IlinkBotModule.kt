package com.zhiban.rebuild.di

import com.zhiban.rebuild.data.ilink.network.IlinkBotTransport
import com.zhiban.rebuild.data.ilink.network.OkHttpIlinkBotTransport
import com.zhiban.rebuild.data.ilink.network.WechatUinGenerator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import okhttp3.OkHttpClient

/**
 * Wiring for the WeChat iLink channel. The stateful singletons (credential store, cursor store,
 * context-token cache, resolver, sender, binding controller, fetch coordinator) are `@Inject
 * constructor` classes Hilt provides directly; only the transport and the UIN generator need
 * explicit providers.
 *
 * The shared `OkHttpClient` is reused: its 120s read timeout already covers the ~35s server
 * long-poll hold, and the certificate pinner only constrains the LLM host, so iLink traffic uses
 * normal CA trust.
 */
@Module
@InstallIn(SingletonComponent::class)
object IlinkBotModule {

    @Provides
    @Singleton
    fun provideWechatUinGenerator(): WechatUinGenerator = WechatUinGenerator()

    @Provides
    @Singleton
    internal fun provideIlinkBotTransport(client: OkHttpClient, uinGenerator: WechatUinGenerator): IlinkBotTransport =
        OkHttpIlinkBotTransport(client, uinGenerator)
}
