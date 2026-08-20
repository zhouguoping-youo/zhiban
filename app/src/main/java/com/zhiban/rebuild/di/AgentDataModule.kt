package com.zhiban.rebuild.di

import android.content.Context
import androidx.room.Room
import com.zhiban.rebuild.BuildConfig
import com.zhiban.rebuild.data.agent.AgentDataRepository
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.agent.AgentDatabaseEncryption
import com.zhiban.rebuild.data.agent.AgentDatabaseKeyManager
import com.zhiban.rebuild.data.agent.MigratingSqlCipherOpenHelperFactory
import com.zhiban.rebuild.data.autowrite.insertVisibleAutoWrite
import com.zhiban.rebuild.data.config.AgentControlStore
import com.zhiban.rebuild.data.facts.FactIndex
import com.zhiban.rebuild.runtime.input.AndroidAttachmentContentSource
import com.zhiban.rebuild.runtime.input.AppPrivateAttachmentStager
import com.zhiban.rebuild.runtime.input.AttachmentContentSource
import com.zhiban.rebuild.runtime.input.AttachmentStagingGateway
import com.zhiban.rebuild.runtime.input.InputLimits
import com.zhiban.rebuild.runtime.kernel.KernelCommandProcessor
import com.zhiban.rebuild.runtime.personalization.AgentPersonalizationStore
import com.zhiban.rebuild.runtime.personalization.buildPersonalizationPrompt
import com.zhiban.rebuild.runtime.spi.RuntimeCommandGateway
import com.zhiban.rebuild.runtime.spi.RuntimeContextInputGateway
import com.zhiban.rebuild.runtime.spi.RuntimeProjectionGateway
import com.zhiban.rebuild.runtime.spi.RuntimeUiClient
import com.zhiban.rebuild.runtime.spi.RuntimeV2FeatureFlag
import com.zhiban.rebuild.runtime.spi.TextInputGateway
import com.zhiban.rebuild.runtime.store.ConversationHistoryGateway
import com.zhiban.rebuild.runtime.store.RoomContextInputGateway
import com.zhiban.rebuild.runtime.store.RoomConversationHistoryGateway
import com.zhiban.rebuild.runtime.store.RoomRuntimeGateways
import com.zhiban.rebuild.runtime.store.RoomTextInputGateway
import com.zhiban.rebuild.runtime.workspace.AppPrivateSessionWorkspaceGateway
import com.zhiban.rebuild.runtime.workspace.SessionWorkspaceGateway
import com.zhiban.rebuild.ui.agent.projection.GatewayRuntimeUiClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AgentDataModule {
    @Provides
    @Singleton
    internal fun provideAttachmentContentSource(@ApplicationContext context: Context): AttachmentContentSource = AndroidAttachmentContentSource(context)

    @Provides
    @Singleton
    internal fun provideInputLimits(): InputLimits = InputLimits(
        maxAttachmentItems = 5,
        maxPerItemBytes = 25L * 1024 * 1024,
        maxAggregateBytes = 50L * 1024 * 1024,
        maxAudioDurationMs = 10L * 60 * 1000,
        allowedMimeTypes = setOf("image/png", "image/jpeg", "image/gif", "image/webp", "audio/wav", "application/pdf"),
    )

    @Provides
    @Singleton
    internal fun provideAppPrivateAttachmentStager(
        @ApplicationContext context: Context,
        source: AttachmentContentSource,
        limits: InputLimits,
    ): AppPrivateAttachmentStager = AppPrivateAttachmentStager.forProduction(context, source, limits)

    @Provides
    @Singleton
    internal fun provideAttachmentStagingGateway(stager: AppPrivateAttachmentStager): AttachmentStagingGateway = stager

    @Provides
    @Singleton
    internal fun provideAgentDatabase(@ApplicationContext context: Context): AgentDatabase {
        AgentDatabaseEncryption.initializeLibrary()
        return AgentDatabaseKeyManager(context).withPassphrase { passphrase ->
            Room.databaseBuilder(context, AgentDatabase::class.java, AgentDatabase.NAME)
                .openHelperFactory(MigratingSqlCipherOpenHelperFactory(context, passphrase))
                .addMigrations(
                    AgentDatabase.MIGRATION_1_2, AgentDatabase.MIGRATION_2_3, AgentDatabase.MIGRATION_3_4,
                    AgentDatabase.MIGRATION_4_5, AgentDatabase.MIGRATION_5_6, AgentDatabase.MIGRATION_6_7,
                    AgentDatabase.MIGRATION_7_8, AgentDatabase.MIGRATION_8_9, AgentDatabase.MIGRATION_9_10,
                    AgentDatabase.MIGRATION_10_11, AgentDatabase.MIGRATION_11_12, AgentDatabase.MIGRATION_12_13,
                    AgentDatabase.MIGRATION_13_14, AgentDatabase.MIGRATION_14_15, AgentDatabase.MIGRATION_15_16,
                    AgentDatabase.MIGRATION_16_17, AgentDatabase.MIGRATION_17_18, AgentDatabase.MIGRATION_18_19,
                    AgentDatabase.MIGRATION_19_20, AgentDatabase.MIGRATION_20_21, AgentDatabase.MIGRATION_21_22,
                    AgentDatabase.MIGRATION_22_23, AgentDatabase.MIGRATION_23_24, AgentDatabase.MIGRATION_24_25,
                    AgentDatabase.MIGRATION_25_26, AgentDatabase.MIGRATION_26_27, AgentDatabase.MIGRATION_27_28,
                    AgentDatabase.MIGRATION_28_29, AgentDatabase.MIGRATION_29_30, AgentDatabase.MIGRATION_30_31, AgentDatabase.MIGRATION_31_32,
                    AgentDatabase.MIGRATION_32_33,
                    AgentDatabase.MIGRATION_33_34,
                    AgentDatabase.MIGRATION_34_35,
                    AgentDatabase.MIGRATION_35_36,
                    AgentDatabase.MIGRATION_36_37,
                    AgentDatabase.MIGRATION_37_38,
                    AgentDatabase.MIGRATION_38_39,
                    AgentDatabase.MIGRATION_39_40,
                    AgentDatabase.MIGRATION_40_41,
                    AgentDatabase.MIGRATION_41_42,
                    AgentDatabase.MIGRATION_42_43,
                    AgentDatabase.MIGRATION_43_44,
                    AgentDatabase.MIGRATION_44_45,
                    AgentDatabase.MIGRATION_45_46,
                    AgentDatabase.MIGRATION_46_47,
                    AgentDatabase.MIGRATION_47_48,
                    AgentDatabase.MIGRATION_48_49,
                    AgentDatabase.MIGRATION_49_50,
                    AgentDatabase.MIGRATION_50_51,
                    AgentDatabase.MIGRATION_51_52,
                )
                .addCallback(AgentDatabase.CALLBACK).build()
        }
    }

    @Provides
    internal fun provideMessageContactFieldExtraction(
        extractor: com.zhiban.rebuild.data.contact.enrichment.MessageContactInfoExtractor,
    ): com.zhiban.rebuild.data.contact.enrichment.MessageContactFieldExtraction = extractor

    @Provides
    internal fun provideRelationshipTypeExtraction(
        extractor: com.zhiban.rebuild.data.contact.enrichment.RelationshipTypeExtractor,
    ): com.zhiban.rebuild.data.contact.enrichment.RelationshipTypeExtraction = extractor

    @Provides
    @Singleton
    internal fun provideAgentDataRepository(
        infrastructure: com.zhiban.rebuild.data.agent.AgentRepositoryInfrastructure,
        domains: com.zhiban.rebuild.data.agent.AgentRepositoryDomains,
        systemCalendarReader: com.zhiban.rebuild.data.calendar.SystemCalendarReader,
        reminderScheduler: com.zhiban.rebuild.data.calendar.ScheduleReminderScheduler,
        replySuggestionCoordinator: com.zhiban.rebuild.data.reply.ReplySuggestionCoordinator,
        contactCompletionCoordinator: com.zhiban.rebuild.data.completion.ContactCompletionCoordinator,
        messageContactCompletionCoordinator: com.zhiban.rebuild.data.contact.enrichment.MessageContactCompletionCoordinator,
        relationshipInferenceCoordinator: com.zhiban.rebuild.data.contact.enrichment.RelationshipInferenceCoordinator,
        agentWakeupCoordinator: com.zhiban.rebuild.runtime.wakeup.AgentWakeupCoordinator,
    ): AgentDataRepository = AgentDataRepository(
        infrastructure,
        domains,
        externalCalendarConflicts = systemCalendarReader,
        scheduleReminderSink = com.zhiban.rebuild.data.agent.ScheduleReminderSink { schedule ->
            reminderScheduler.replace(
                schedule.id,
                schedule.startAtEpochMs,
                schedule.reminderMinutesBefore,
            )
        },
        replySuggestionSink = replySuggestionCoordinator::onIncomingActivity,
        contactCompletionSink = contactCompletionCoordinator::onIncomingWechatActivity,
        messageContactCompletionSink = messageContactCompletionCoordinator::onIncomingActivity,
        relationshipInferenceSink = relationshipInferenceCoordinator::onIncomingActivity,
        agentWakeupSink = agentWakeupCoordinator::onCandidateProcessed,
    )

    @Provides
    internal fun provideAgentDataDaos(database: AgentDatabase): com.zhiban.rebuild.data.agent.AgentDataDaos = com.zhiban.rebuild.data.agent.AgentDataDaos(
        notificationCandidateDao = database.notificationCandidateDao(),
        contactDao = database.contactDao(),
        contactIdentityDao = database.contactIdentityDao(),
        contactKnowledgeDao = database.contactKnowledgeDao(),
        contactIntelligenceDao = database.contactIntelligenceDao(),
        factDao = database.factDao(),
        changeLogDao = database.changeLogDao(),
        senderMuteDao = database.senderMuteDao(),
        contactInteractionDao = database.contactInteractionDao(),
    )

    @Provides
    @Singleton
    internal fun provideTransactionRunner(database: AgentDatabase): com.zhiban.rebuild.data.agent.AgentTransactionRunner =
        com.zhiban.rebuild.data.agent.RoomAgentTransactionRunner(database)

    @Provides
    internal fun provideFactIndex(database: AgentDatabase): com.zhiban.rebuild.data.facts.FactIndex = com.zhiban.rebuild.data.facts.FactIndex(database)

    @Provides
    @Singleton
    internal fun provideChangeUndoApplier(
        impl: com.zhiban.rebuild.runtime.governance.ChangeUndoApplierImpl,
    ): com.zhiban.rebuild.data.autowrite.ChangeUndoApplier = impl

    @Provides
    internal fun provideAutoWriteSink(database: AgentDatabase): com.zhiban.rebuild.data.agent.AutoWriteSink =
        com.zhiban.rebuild.data.agent.AutoWriteSink { draft ->
            database.insertVisibleAutoWrite(draft)
        }

    @Provides
    internal fun provideAgentRepositoryInfrastructure(
        daos: com.zhiban.rebuild.data.agent.AgentDataDaos,
        transactions: com.zhiban.rebuild.data.agent.AgentTransactionRunner,
        factIndex: com.zhiban.rebuild.data.facts.FactIndex,
        autoWriteSink: com.zhiban.rebuild.data.agent.AutoWriteSink,
    ): com.zhiban.rebuild.data.agent.AgentRepositoryInfrastructure =
        com.zhiban.rebuild.data.agent.AgentRepositoryInfrastructure(daos, transactions, factIndex, autoWriteSink)

    @Provides
    internal fun provideCalendarAgentDataRepository(database: AgentDatabase): com.zhiban.rebuild.data.agent.CalendarAgentDataRepository =
        com.zhiban.rebuild.data.agent.CalendarAgentDataRepository(database)

    @Provides
    internal fun provideCrmAgentDataRepository(database: AgentDatabase): com.zhiban.rebuild.data.agent.CrmAgentDataRepository =
        com.zhiban.rebuild.data.agent.CrmAgentDataRepository(database)

    @Provides
    internal fun provideContactAgentDataRepository(database: AgentDatabase): com.zhiban.rebuild.data.agent.ContactAgentDataRepository =
        com.zhiban.rebuild.data.agent.ContactAgentDataRepository(database)

    @Provides
    internal fun provideRelationshipAgentDataRepository(database: AgentDatabase): com.zhiban.rebuild.data.agent.RelationshipAgentDataRepository =
        com.zhiban.rebuild.data.agent.RelationshipAgentDataRepository(database)

    @Provides
    internal fun provideAgentRepositoryDomains(
        calendar: com.zhiban.rebuild.data.agent.CalendarAgentDataRepository,
        crm: com.zhiban.rebuild.data.agent.CrmAgentDataRepository,
        contacts: com.zhiban.rebuild.data.agent.ContactAgentDataRepository,
        relationships: com.zhiban.rebuild.data.agent.RelationshipAgentDataRepository,
    ): com.zhiban.rebuild.data.agent.AgentRepositoryDomains = com.zhiban.rebuild.data.agent.AgentRepositoryDomains(
        calendar,
        crm,
        contacts,
        relationships,
    )

    @Provides
    @Singleton
    internal fun provideRoomRuntimeGateways(database: AgentDatabase, flag: RuntimeV2FeatureFlag): RoomRuntimeGateways =
        RoomRuntimeGateways(database, producerVersion = "runtime-v2", featureFlag = flag)

    @Provides
    @Singleton
    internal fun provideRuntimeCommandGateway(gateways: RoomRuntimeGateways): RuntimeCommandGateway = gateways

    @Provides
    @Singleton
    internal fun provideRuntimeProjectionGateway(gateways: RoomRuntimeGateways): RuntimeProjectionGateway = gateways

    @Provides
    @Singleton
    fun provideRuntimeV2FeatureFlag(): RuntimeV2FeatureFlag = RuntimeV2FeatureFlag { BuildConfig.RUNTIME_V2_ENABLED }

    @Provides
    @Singleton
    internal fun provideTextInputGateway(database: AgentDatabase, flag: RuntimeV2FeatureFlag): TextInputGateway =
        RoomTextInputGateway(database, flag::isEnabled)

    @Provides
    @Singleton
    internal fun provideRuntimeContextInputGateway(database: AgentDatabase): RuntimeContextInputGateway = RoomContextInputGateway(database)

    @Provides @Singleton
    internal fun provideConversationHistoryGateway(database: AgentDatabase): ConversationHistoryGateway = RoomConversationHistoryGateway(database)

    @Provides
    @Singleton
    internal fun provideSessionWorkspaceGateway(gateway: AppPrivateSessionWorkspaceGateway): SessionWorkspaceGateway = gateway

    @Provides
    @Singleton
    internal fun provideKernelCommandProcessor(
        database: AgentDatabase,
        flag: RuntimeV2FeatureFlag,
        provider: com.zhiban.rebuild.provider.ProviderAdapter,
        profiles: com.zhiban.rebuild.provider.ProviderProfileStore,
        personalization: AgentPersonalizationStore,
        userProfile: com.zhiban.rebuild.runtime.personalization.UserProfileStore,
        controls: com.zhiban.rebuild.data.config.AgentControlStore,
        mcpEnvironment: com.zhiban.rebuild.runtime.mcp.McpRemoteEnvironment,
        collectionPreferences: com.zhiban.rebuild.data.notification.MessageCollectionPreferences,
        embeddingGateway: com.zhiban.rebuild.runtime.context.EmbeddingGateway,
        networkQuality: com.zhiban.rebuild.runtime.network.AndroidNetworkQualityGateway,
        dynamicConfig: com.zhiban.rebuild.runtime.config.AgentDynamicConfigStore,
        reminderScheduler: com.zhiban.rebuild.data.calendar.ScheduleReminderScheduler,
        skillPackages: com.zhiban.rebuild.runtime.skills.SkillPackageManager,
        communicationHandoffLauncher: com.zhiban.rebuild.data.communication.CommunicationHandoffLauncher,
        systemCalendarReader: com.zhiban.rebuild.data.calendar.SystemCalendarReader,
        webSearchGateway: com.zhiban.rebuild.provider.WebSearchGateway,
        locationGateway: com.zhiban.rebuild.provider.LocationGateway,
    ): KernelCommandProcessor = KernelCommandProcessor(
        database,
        ownerId = "app-process",
        enabled = flag::isEnabled,
        provider = provider,
        profiles = profiles,
        config = com.zhiban.rebuild.runtime.kernel.ProviderEngineConfig(
            personalization = {
                val value = personalization.load()
                val profile = userProfile.profile.value
                buildPersonalizationPrompt(value, profile)
            },
            ownerProfile = {
                val profile = userProfile.profile.value
                com.zhiban.rebuild.runtime.tool.ContactOwnerProfileSnapshot(
                    name = profile.name,
                    occupations = profile.occupations,
                    hasConfiguredIdentity = listOf(profile.name, profile.phone, profile.wechatId).any(String::isNotBlank),
                )
            },
            memoryPolicy = controls::memory,
            feedbackPolicy = controls::feedback,
            toolEnabled = controls::isToolAvailable,
            webSearchOptIn = controls::webSearchOptIn,
            networkQuality = networkQuality::current,
            dynamicConfig = dynamicConfig::snapshot,
            executionPreference = controls::execution,
            skillSpecs = {
                (com.zhiban.agent.skills.BuiltInSkills.all + skillPackages.activeSpecs())
                    .filter { controls.isSkillEnabled(it.id) }
            },
            onScheduleSaved = { schedule ->
                reminderScheduler.replace(
                    schedule.id,
                    schedule.startAtEpochMs,
                    schedule.reminderMinutesBefore,
                )
            },
            onScheduleUndo = { scheduleId, schedule ->
                if (schedule == null) {
                    reminderScheduler.cancel(scheduleId)
                } else {
                    reminderScheduler.replace(
                        schedule.id,
                        schedule.startAtEpochMs,
                        schedule.reminderMinutesBefore,
                    )
                }
            },
        ),
        infrastructure = com.zhiban.rebuild.runtime.kernel.ProviderEngineInfrastructure(
            mcpEnvironment = mcpEnvironment,
            embeddingGateway = embeddingGateway,
            messageCollectionPreferences = collectionPreferences,
            communicationHandoffLauncher = communicationHandoffLauncher,
            externalCalendarConflicts = systemCalendarReader,
            webSearchGateway = webSearchGateway,
            locationGateway = locationGateway,
            locationConsent = { controls.locationAccessEnabled() },
        ),
    )

    @Provides
    @Singleton
    internal fun provideLocationGateway(@ApplicationContext context: Context): com.zhiban.rebuild.provider.LocationGateway =
        com.zhiban.rebuild.data.location.SystemLocationGateway(context)

    @Provides
    @Singleton
    internal fun provideReplyDeliveryExecutor(
        impl: com.zhiban.rebuild.data.reply.HandoffDeliveryExecutor,
    ): com.zhiban.rebuild.data.reply.ReplyDeliveryExecutor = impl

    @Provides
    @Singleton
    internal fun provideReplySuggestionRepository(
        database: AgentDatabase,
        deliveryExecutor: com.zhiban.rebuild.data.reply.ReplyDeliveryExecutor,
        controls: com.zhiban.rebuild.data.config.AgentControlStore,
    ): com.zhiban.rebuild.data.reply.ReplySuggestionRepository = com.zhiban.rebuild.data.reply.ReplySuggestionRepository(database, deliveryExecutor, controls)

    @Provides
    @Singleton
    internal fun provideCompletionHandoff(
        handoffLauncher: com.zhiban.rebuild.data.communication.CommunicationHandoffLauncher,
    ): com.zhiban.rebuild.data.completion.CompletionHandoff = com.zhiban.rebuild.data.completion.CompletionHandoff { platform, recipient, message ->
        try {
            handoffLauncher.open(platform, recipient, message)
            true
        } catch (unavailable: IllegalStateException) {
            false // TARGET_APP_UNAVAILABLE — 目标应用未装/不可达，保持 DRAFTED 让 UI 提示而非崩溃。
        }
    }

    @Provides
    @Singleton
    internal fun provideContactCompletionRepository(
        database: AgentDatabase,
        handoff: com.zhiban.rebuild.data.completion.CompletionHandoff,
        outreachGenerator: com.zhiban.rebuild.data.completion.ContactCompletionOutreachGenerator,
        controls: com.zhiban.rebuild.data.config.AgentControlStore,
    ): com.zhiban.rebuild.data.completion.ContactCompletionRepository =
        com.zhiban.rebuild.data.completion.ContactCompletionRepository(database, handoff, outreachGenerator, controls)

    @Provides
    @Singleton
    fun provideRuntimeUiClient(commandGateway: RuntimeCommandGateway, projectionGateway: RuntimeProjectionGateway): RuntimeUiClient =
        GatewayRuntimeUiClient(commandGateway, projectionGateway)
}
