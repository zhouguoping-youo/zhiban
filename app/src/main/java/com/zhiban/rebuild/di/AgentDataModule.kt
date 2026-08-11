package com.zhiban.rebuild.di

import android.content.Context
import androidx.room.Room
import com.zhiban.rebuild.BuildConfig
import com.zhiban.rebuild.data.agent.AgentDataRepository
import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.agent.AgentDatabaseEncryption
import com.zhiban.rebuild.data.agent.AgentDatabaseKeyManager
import com.zhiban.rebuild.data.agent.MigratingSqlCipherOpenHelperFactory
import com.zhiban.rebuild.runtime.governance.insertVisibleAutoWrite
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
                )
                .addCallback(AgentDatabase.CALLBACK).build()
        }
    }

    @Provides
    @Singleton
    internal fun provideAgentDataRepository(
        daos: com.zhiban.rebuild.data.agent.AgentDataDaos,
        transactions: com.zhiban.rebuild.data.agent.AgentTransactionRunner,
        factIndex: com.zhiban.rebuild.runtime.context.FactIndex,
        autoWriteSink: com.zhiban.rebuild.data.agent.AutoWriteSink,
        calendar: com.zhiban.rebuild.data.agent.CalendarAgentDataRepository,
        crm: com.zhiban.rebuild.data.agent.CrmAgentDataRepository,
        contacts: com.zhiban.rebuild.data.agent.ContactAgentDataRepository,
        relationships: com.zhiban.rebuild.data.agent.RelationshipAgentDataRepository,
        systemCalendarReader: com.zhiban.rebuild.data.calendar.SystemCalendarReader,
        reminderScheduler: com.zhiban.rebuild.data.calendar.ScheduleReminderScheduler,
    ): AgentDataRepository = AgentDataRepository(
        daos,
        transactions,
        factIndex,
        autoWriteSink,
        calendar,
        crm,
        contacts,
        relationships,
        externalCalendarConflicts = systemCalendarReader,
        scheduleReminderSink = com.zhiban.rebuild.data.agent.ScheduleReminderSink { schedule ->
            reminderScheduler.replace(
                schedule.id,
                schedule.startAtEpochMs,
                schedule.reminderMinutesBefore,
            )
        },
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
    )

    @Provides
    @Singleton
    internal fun provideTransactionRunner(database: AgentDatabase): com.zhiban.rebuild.data.agent.AgentTransactionRunner =
        com.zhiban.rebuild.data.agent.RoomAgentTransactionRunner(database)

    @Provides
    internal fun provideFactIndex(database: AgentDatabase): com.zhiban.rebuild.runtime.context.FactIndex =
        com.zhiban.rebuild.runtime.context.FactIndex(database)

    @Provides
    internal fun provideAutoWriteSink(database: AgentDatabase): com.zhiban.rebuild.data.agent.AutoWriteSink =
        com.zhiban.rebuild.data.agent.AutoWriteSink { draft ->
            database.insertVisibleAutoWrite(draft)
        }

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
        provider: com.zhiban.rebuild.runtime.provider.ProviderAdapter,
        profiles: com.zhiban.rebuild.runtime.provider.ProviderProfileStore,
        personalization: AgentPersonalizationStore,
        userProfile: com.zhiban.rebuild.runtime.personalization.UserProfileStore,
        controls: com.zhiban.rebuild.runtime.config.AgentControlStore,
        mcpEnvironment: com.zhiban.rebuild.runtime.mcp.McpRemoteEnvironment,
        collectionPreferences: com.zhiban.rebuild.data.notification.MessageCollectionPreferences,
        embeddingGateway: com.zhiban.rebuild.runtime.context.EmbeddingGateway,
        networkQuality: com.zhiban.rebuild.runtime.network.AndroidNetworkQualityGateway,
        dynamicConfig: com.zhiban.rebuild.runtime.config.AgentDynamicConfigStore,
        reminderScheduler: com.zhiban.rebuild.data.calendar.ScheduleReminderScheduler,
        skillPackages: com.zhiban.rebuild.runtime.skills.SkillPackageManager,
        communicationHandoffLauncher: com.zhiban.rebuild.data.communication.CommunicationHandoffLauncher,
        systemCalendarReader: com.zhiban.rebuild.data.calendar.SystemCalendarReader,
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
        ),
        infrastructure = com.zhiban.rebuild.runtime.kernel.ProviderEngineInfrastructure(
            mcpEnvironment = mcpEnvironment,
            embeddingGateway = embeddingGateway,
            messageCollectionPreferences = collectionPreferences,
            communicationHandoffLauncher = communicationHandoffLauncher,
            externalCalendarConflicts = systemCalendarReader,
        ),
    )

    @Provides
    @Singleton
    fun provideRuntimeUiClient(commandGateway: RuntimeCommandGateway, projectionGateway: RuntimeProjectionGateway): RuntimeUiClient =
        GatewayRuntimeUiClient(commandGateway, projectionGateway)
}
