package com.zhiban.rebuild.di

import com.zhiban.rebuild.BuildConfig
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.contact.enrichment.CompanyEnrichmentCoordinator
import com.zhiban.rebuild.data.contact.enrichment.CompanyEnrichmentRefresher
import com.zhiban.rebuild.data.contact.enrichment.CompanyRegistryGateway
import com.zhiban.rebuild.data.contact.enrichment.HttpCompanyRegistryGateway
import com.zhiban.rebuild.data.contact.enrichment.UnavailableCompanyRegistryGateway
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object ContactEnrichmentModule {
    @Provides
    @Singleton
    fun provideCompanyRegistryGateway(client: OkHttpClient): CompanyRegistryGateway =
        BuildConfig.COMPANY_ENRICHMENT_BASE_URL.trim().takeIf(String::isNotEmpty)?.let {
            HttpCompanyRegistryGateway(client, it)
        } ?: UnavailableCompanyRegistryGateway

    @Provides
    @Singleton
    internal fun provideCompanyEnrichmentRefresher(database: AgentDatabase, gateway: CompanyRegistryGateway): CompanyEnrichmentRefresher =
        CompanyEnrichmentCoordinator(database, gateway)
}
