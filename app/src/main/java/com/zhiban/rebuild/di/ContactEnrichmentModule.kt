package com.zhiban.rebuild.di

import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.contact.enrichment.CompanyEnrichmentCoordinator
import com.zhiban.rebuild.data.contact.enrichment.CompanyEnrichmentRefresher
import com.zhiban.rebuild.data.contact.enrichment.CompanyRegistryGateway
import com.zhiban.rebuild.data.contact.enrichment.WebSearchCompanyRegistryGateway
import com.zhiban.rebuild.provider.WebSearchGateway
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ContactEnrichmentModule {
    // Company full-name completion runs on public web search (no private registry proxy to deploy). The
    // gateway self-degrades to "no candidates" when the active LLM provider is not StepFun or search fails.
    @Provides
    @Singleton
    fun provideCompanyRegistryGateway(webSearch: WebSearchGateway): CompanyRegistryGateway = WebSearchCompanyRegistryGateway(webSearch)

    @Provides
    @Singleton
    internal fun provideCompanyEnrichmentRefresher(database: AgentDatabase, gateway: CompanyRegistryGateway): CompanyEnrichmentRefresher =
        CompanyEnrichmentCoordinator(database, gateway)
}
