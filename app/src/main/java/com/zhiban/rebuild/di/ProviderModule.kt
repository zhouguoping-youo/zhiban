package com.zhiban.rebuild.di

import android.content.Context
import com.zhiban.rebuild.provider.AndroidProviderHealthCache
import com.zhiban.rebuild.provider.AndroidProviderProfileStore
import com.zhiban.rebuild.provider.AppPrivateProviderAttachmentResolver
import com.zhiban.rebuild.provider.CredentialProvisioner
import com.zhiban.rebuild.provider.CredentialResolver
import com.zhiban.rebuild.provider.DefaultOutboundDataPolicy
import com.zhiban.rebuild.provider.KeystoreCredentialVault
import com.zhiban.rebuild.provider.OpenAiCompatibleProviderAdapter
import com.zhiban.rebuild.provider.OutboundExportGate
import com.zhiban.rebuild.provider.PolicyEnforcingProviderAdapter
import com.zhiban.rebuild.provider.ProviderAdapter
import com.zhiban.rebuild.provider.ProviderConfigurationManager
import com.zhiban.rebuild.provider.ProviderEnvironmentManager
import com.zhiban.rebuild.provider.ProviderProfileStore
import com.zhiban.rebuild.provider.ResilientProviderAdapter
import com.zhiban.rebuild.provider.StepFunWebSearchGateway
import com.zhiban.rebuild.provider.WebSearchGateway
import com.zhiban.rebuild.runtime.context.EmbeddingGateway
import com.zhiban.rebuild.runtime.embedding.EmbeddingConfiguration
import com.zhiban.rebuild.runtime.embedding.VolcEmbeddingEnvironment
import com.zhiban.rebuild.runtime.embedding.VolcEmbeddingTransport
import com.zhiban.rebuild.runtime.governance.AppPrivateOutboundAuditStore
import com.zhiban.rebuild.runtime.governance.OutboundDataPreferences
import com.zhiban.rebuild.runtime.input.asr.CloudAsrGateway
import com.zhiban.rebuild.runtime.input.asr.ProviderCloudAsrGateway
import com.zhiban.rebuild.runtime.input.asr.StepFunCloudAsrTransport
import com.zhiban.rebuild.runtime.mcp.McpConnectionFactory
import com.zhiban.rebuild.runtime.mcp.McpRemoteEnvironment
import com.zhiban.rebuild.runtime.mcp.ProductionMcpConnectionFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object ProviderModule {
    @Provides @Singleton
    fun provideCredentialVault(@ApplicationContext context: Context): KeystoreCredentialVault = KeystoreCredentialVault(context)

    @Provides @Singleton
    fun provideSecretRedactor(): com.zhiban.rebuild.provider.SecretRedactor = com.zhiban.rebuild.provider.SecretRedactor()

    @Provides @Singleton
    fun provideAndroidProviderProfileStore(@ApplicationContext context: Context): AndroidProviderProfileStore = AndroidProviderProfileStore(context)

    @Provides @Singleton
    fun provideCredentialProvisioner(vault: KeystoreCredentialVault): CredentialProvisioner = vault

    @Provides @Singleton
    fun provideProviderProfileStore(store: AndroidProviderProfileStore): ProviderProfileStore = store

    @Provides @Singleton
    fun provideCredentialResolver(vault: KeystoreCredentialVault): CredentialResolver = vault

    @Provides @Singleton
    fun provideProviderAdapter(
        @ApplicationContext context: Context,
        client: OkHttpClient,
        credentials: CredentialResolver,
        outboundAudit: AppPrivateOutboundAuditStore,
        outboundPreferences: OutboundDataPreferences,
    ): ProviderAdapter = PolicyEnforcingProviderAdapter(
        delegate = ResilientProviderAdapter(
            OpenAiCompatibleProviderAdapter(
                client,
                credentials,
                attachments = AppPrivateProviderAttachmentResolver(context),
            ),
        ),
        policy = DefaultOutboundDataPolicy(outboundPreferences::snapshot),
        auditSink = outboundAudit,
    )

    @Provides @Singleton
    fun provideOutboundExportGate(outboundPreferences: OutboundDataPreferences, outboundAudit: AppPrivateOutboundAuditStore): OutboundExportGate =
        OutboundExportGate(outboundPreferences::snapshot, outboundAudit)

    @Provides @Singleton
    fun provideProviderConfigurationManager(vault: CredentialProvisioner, profiles: ProviderProfileStore): ProviderConfigurationManager =
        ProviderConfigurationManager(vault, profiles)

    @Provides @Singleton
    fun provideProviderEnvironmentManager(
        @ApplicationContext context: Context,
        configuration: ProviderConfigurationManager,
        adapter: ProviderAdapter,
    ): ProviderEnvironmentManager = ProviderEnvironmentManager(
        configuration,
        adapter,
        healthCache = AndroidProviderHealthCache(context),
    )

    @Provides @Singleton
    internal fun provideWebSearchGateway(
        environment: ProviderEnvironmentManager,
        credentials: CredentialResolver,
        client: OkHttpClient,
        outboundGate: OutboundExportGate,
    ): WebSearchGateway = StepFunWebSearchGateway(
        environment,
        credentials,
        client,
        outboundGate,
    )

    @Provides @Singleton
    internal fun provideMcpConnectionFactory(client: OkHttpClient, credentials: CredentialResolver): McpConnectionFactory =
        ProductionMcpConnectionFactory(client, credentials)

    @Provides @Singleton
    internal fun provideMcpRemoteEnvironment(
        @ApplicationContext context: Context,
        vault: KeystoreCredentialVault,
        connections: McpConnectionFactory,
        outboundGate: OutboundExportGate,
    ): McpRemoteEnvironment = McpRemoteEnvironment(context, vault, connections, outboundGate)

    @Provides @Singleton
    internal fun provideVolcEmbeddingEnvironment(
        @ApplicationContext context: Context,
        vault: KeystoreCredentialVault,
        client: OkHttpClient,
        outboundGate: OutboundExportGate,
    ): VolcEmbeddingEnvironment = VolcEmbeddingEnvironment(
        context,
        vault,
        VolcEmbeddingTransport(client),
        outboundGate,
    )

    @Provides @Singleton
    internal fun provideEmbeddingGateway(environment: VolcEmbeddingEnvironment): EmbeddingGateway = environment

    @Provides @Singleton
    internal fun provideEmbeddingConfiguration(environment: VolcEmbeddingEnvironment): EmbeddingConfiguration = environment

    @Provides @Singleton
    internal fun provideCloudAsrGateway(
        environment: ProviderEnvironmentManager,
        credentials: CredentialResolver,
        client: OkHttpClient,
        outboundGate: OutboundExportGate,
    ): CloudAsrGateway = ProviderCloudAsrGateway(
        environment,
        credentials,
        StepFunCloudAsrTransport(client),
        outboundGate,
    )
}
