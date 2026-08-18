package com.zhiban.rebuild.ui.tabs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhiban.rebuild.data.agent.AgentDataRepository
import com.zhiban.rebuild.data.agent.AndroidContactSyncPreview
import com.zhiban.rebuild.data.agent.AndroidContactSyncRepository
import com.zhiban.rebuild.data.agent.AndroidContactSyncResult
import com.zhiban.rebuild.data.agent.RelationshipEventParticipantInput
import com.zhiban.rebuild.data.calllog.CallLogRepository
import com.zhiban.rebuild.data.calllog.CallRecordEntity
import com.zhiban.rebuild.data.contact.ContactAliasEntity
import com.zhiban.rebuild.data.contact.ContactEnrichmentCandidateEntity
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.ContactIdentityResolver
import com.zhiban.rebuild.data.contact.ContactMaintenanceEvaluator
import com.zhiban.rebuild.data.contact.ContactMaintenanceOverview
import com.zhiban.rebuild.data.contact.ContactMergeLinkEntity
import com.zhiban.rebuild.data.contact.ContactPlatformIdentityEntity
import com.zhiban.rebuild.data.contact.IdentityResolutionDecision
import com.zhiban.rebuild.data.contact.OwnerContactLinkEntity
import com.zhiban.rebuild.data.contact.PersonEmploymentEpisodeEntity
import com.zhiban.rebuild.data.contact.RelationshipEdgeEntity
import com.zhiban.rebuild.data.contact.RelationshipEpisodeEntity
import com.zhiban.rebuild.data.contact.RelationshipEventWithParticipants
import com.zhiban.rebuild.data.contact.SourceIdentityEntity
import com.zhiban.rebuild.data.contact.SystemContactCandidate
import com.zhiban.rebuild.data.contact.SystemContactReader
import com.zhiban.rebuild.data.contact.enrichment.CompanyEnrichmentRefresher
import com.zhiban.rebuild.data.contact.normalizeContactPhone
import com.zhiban.rebuild.data.crm.CrmOpportunityEntity
import com.zhiban.rebuild.data.notification.MessageCollectionPreferences
import com.zhiban.rebuild.data.notification.NotificationCandidateEntity
import com.zhiban.rebuild.data.reply.ReplySuggestionRepository
import com.zhiban.rebuild.runtime.context.FactEntity
import com.zhiban.rebuild.runtime.governance.OutboundDataPreferences
import com.zhiban.rebuild.runtime.input.asr.CloudAsrAvailability
import com.zhiban.rebuild.runtime.input.asr.CloudAsrGateway
import com.zhiban.rebuild.runtime.input.asr.CloudAsrResult
import com.zhiban.rebuild.runtime.personalization.UserProfileStore
import com.zhiban.rebuild.runtime.runSuspendCatching
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ContactImportUiState(
    val isLoading: Boolean = false,
    val isImporting: Boolean = false,
    val contacts: List<SystemContactCandidate> = emptyList(),
    val rowsRead: Int = 0,
    val blankRows: Int = 0,
    val error: String? = null,
    val resultMessage: String? = null,
)

data class ContactMergeSuggestion(val first: ContactEntity, val second: ContactEntity, val reason: String, val confidence: Double)

data class RelationPageSnapshot(
    val contacts: List<ContactEntity> = emptyList(),
    val relationships: List<RelationshipEdgeEntity> = emptyList(),
    val temporalRelationships: List<RelationshipEpisodeEntity> = emptyList(),
    val relationshipEvents: List<RelationshipEventWithParticipants> = emptyList(),
    val rawContacts: List<ContactEntity> = emptyList(),
    val ownerContactLinks: List<OwnerContactLinkEntity> = emptyList(),
    val temporalEmployments: List<PersonEmploymentEpisodeEntity> = emptyList(),
    val maintenanceOverview: ContactMaintenanceOverview = ContactMaintenanceOverview(emptyList(), 0, 0),
    val unresolvedSourceIdentities: List<SourceIdentityEntity> = emptyList(),
    val notificationCandidates: List<NotificationCandidateEntity> = emptyList(),
    val pendingCallNotes: List<CallRecordEntity> = emptyList(),
    val importState: ContactImportUiState = ContactImportUiState(),
    val cloudAsrAvailability: CloudAsrAvailability = CloudAsrAvailability.CONSENT_REQUIRED,
    val enabledMessagePlatforms: Set<String> = MessageCollectionPreferences.DEFAULT_PLATFORMS,
    val outgoingMessageCollectionEnabled: Boolean = false,
)

private data class RelationInboxSnapshot(
    val unresolvedSourceIdentities: List<SourceIdentityEntity>,
    val notificationCandidates: List<NotificationCandidateEntity>,
    val pendingCallNotes: List<CallRecordEntity>,
    val importState: ContactImportUiState,
    val cloudAsrAvailability: CloudAsrAvailability,
)

class RelationContactServices @Inject constructor(
    val systemContactReader: SystemContactReader,
    val companyEnrichment: CompanyEnrichmentRefresher,
    val androidContactSync: AndroidContactSyncRepository,
)

class RelationCollectionServices @Inject constructor(
    val messageCollectionPreferences: MessageCollectionPreferences,
    val callLogRepository: CallLogRepository,
    val cloudAsrGateway: CloudAsrGateway,
    val outboundDataPreferences: OutboundDataPreferences,
)

@HiltViewModel
class RelationViewModel @Inject constructor(
    private val repository: AgentDataRepository,
    private val userProfileStore: UserProfileStore,
    private val replySuggestionRepository: ReplySuggestionRepository,
    contactServices: RelationContactServices,
    collectionServices: RelationCollectionServices,
) : ViewModel() {
    private val systemContactReader = contactServices.systemContactReader
    private val companyEnrichment = contactServices.companyEnrichment
    private val androidContactSync = contactServices.androidContactSync
    private val messageCollectionPreferences = collectionServices.messageCollectionPreferences
    private val callLogRepository = collectionServices.callLogRepository
    private val cloudAsrGateway = collectionServices.cloudAsrGateway
    private val outboundDataPreferences = collectionServices.outboundDataPreferences
    val contacts: StateFlow<List<ContactEntity>> = repository.observeContacts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val rawContacts: StateFlow<List<ContactEntity>> = repository.observeRawContacts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val relationships: StateFlow<List<RelationshipEdgeEntity>> = repository.observeRelationships()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val temporalRelationships: StateFlow<List<RelationshipEpisodeEntity>> = repository.observeTemporalRelationships()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val relationshipEvents: StateFlow<List<RelationshipEventWithParticipants>> = repository.observeRelationshipEvents()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val aliases: StateFlow<List<ContactAliasEntity>> = repository.observeContactAliases()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val platformIdentities: StateFlow<List<ContactPlatformIdentityEntity>> = repository.observeContactPlatformIdentities()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val mergeLinks: StateFlow<List<ContactMergeLinkEntity>> = repository.observeContactMergeLinks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val ownerContactLinks: StateFlow<List<OwnerContactLinkEntity>> = repository.observeOwnerContactLinks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val pendingEnrichment: StateFlow<List<ContactEnrichmentCandidateEntity>> =
        repository.observeAllPendingContactEnrichment()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val temporalEmployments: StateFlow<List<PersonEmploymentEpisodeEntity>> =
        repository.observeAllTemporalEmployments()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val unresolvedSourceIdentities: StateFlow<List<SourceIdentityEntity>> =
        repository.observeUnresolvedSourceIdentities()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val mergeSuggestions: StateFlow<List<ContactMergeSuggestion>> = combine(
        repository.observeRawContacts(),
        repository.observeContactAliases(),
        repository.observeContactPlatformIdentities(),
        repository.observeContactMergeLinks(),
    ) { contacts, aliases, identities, links ->
        buildMergeSuggestions(contacts, aliases, identities, links)
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val maintenanceOverview: StateFlow<ContactMaintenanceOverview> = combine(
        contacts,
        temporalEmployments,
        platformIdentities,
        mergeSuggestions,
        pendingEnrichment,
    ) { contacts, employments, identities, mergeReviews, enrichmentReviews ->
        ContactMaintenanceEvaluator.evaluate(
            contacts = contacts,
            employments = employments,
            platformIdentities = identities,
            duplicateReviewCount = mergeReviews.size,
            enrichmentReviewCount = enrichmentReviews.size,
            nowEpochMs = System.currentTimeMillis(),
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            ContactMaintenanceOverview(emptyList(), 0, 0),
        )
    private val mutableImportState = MutableStateFlow(ContactImportUiState())
    val importState = mutableImportState.asStateFlow()
    val notificationCandidates: StateFlow<List<NotificationCandidateEntity>> =
        repository.observeNotificationCandidates()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val enabledMessagePlatforms: StateFlow<Set<String>> =
        messageCollectionPreferences.enabledPlatforms
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                MessageCollectionPreferences.DEFAULT_PLATFORMS,
            )
    val outgoingMessageCollectionEnabled: StateFlow<Boolean> =
        messageCollectionPreferences.outgoingCollectionEnabled
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val userProfile = userProfileStore.profile
    val replySuggestions: StateFlow<List<ReplySuggestionCardModel>> = replySuggestionRepository.observePending()
        .map { rows ->
            rows.groupBy { it.candidateId }.map { (candidateId, group) ->
                val sorted = group.sortedBy { it.draftIndex }
                val first = sorted.first()
                ReplySuggestionCardModel(
                    candidateId = candidateId,
                    contactId = first.contactId,
                    platform = first.threadKey.substringBefore('|'),
                    contactName = first.contactName
                        ?: first.threadKey.substringAfter('|', "").ifBlank { "对方" },
                    incomingExcerpt = first.incomingExcerpt,
                    drafts = sorted.map { it.draft },
                    createdAtEpochMs = first.createdAtEpochMs,
                )
            }.sortedByDescending { it.createdAtEpochMs }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val pendingCallNotes: StateFlow<List<CallRecordEntity>> = callLogRepository.observePendingNotes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val mutableCloudAsrAvailability = MutableStateFlow(CloudAsrAvailability.CONSENT_REQUIRED)
    val cloudAsrAvailability = mutableCloudAsrAvailability.asStateFlow()
    val pageSnapshot: StateFlow<RelationPageSnapshot> = combine(
        combine(contacts, relationships, temporalRelationships, relationshipEvents, rawContacts) {
                contacts,
                relationships,
                temporalRelationships,
                relationshipEvents,
                rawContacts,
            ->
            RelationPageSnapshot(
                contacts = contacts,
                relationships = relationships,
                temporalRelationships = temporalRelationships,
                relationshipEvents = relationshipEvents,
                rawContacts = rawContacts,
            )
        },
        combine(ownerContactLinks, temporalEmployments, maintenanceOverview) { ownerContactLinks, temporalEmployments, maintenanceOverview ->
            Triple(ownerContactLinks, temporalEmployments, maintenanceOverview)
        },
        combine(
            unresolvedSourceIdentities,
            notificationCandidates,
            pendingCallNotes,
            importState,
            cloudAsrAvailability,
        ) { unresolvedSourceIdentities, notificationCandidates, pendingCallNotes, importState, cloudAsr ->
            RelationInboxSnapshot(
                unresolvedSourceIdentities,
                notificationCandidates,
                pendingCallNotes,
                importState,
                cloudAsr,
            )
        },
        combine(enabledMessagePlatforms, outgoingMessageCollectionEnabled) { enabled, outgoing -> enabled to outgoing },
    ) { base, owner, inbox, collection ->
        base.copy(
            ownerContactLinks = owner.first,
            temporalEmployments = owner.second,
            maintenanceOverview = owner.third,
            unresolvedSourceIdentities = inbox.unresolvedSourceIdentities,
            notificationCandidates = inbox.notificationCandidates,
            pendingCallNotes = inbox.pendingCallNotes,
            importState = inbox.importState,
            cloudAsrAvailability = inbox.cloudAsrAvailability,
            enabledMessagePlatforms = collection.first,
            outgoingMessageCollectionEnabled = collection.second,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RelationPageSnapshot())

    init {
        viewModelScope.launch { repository.purgeNonPersonalSmsCandidates() }
        viewModelScope.launch {
            repository.refreshLocalContactIntelligence()
            companyEnrichment.refresh()
        }
        refreshCloudAsrAvailability()
    }

    fun dismissCallNote(callRecordId: String) {
        viewModelScope.launch { callLogRepository.dismissPendingNote(callRecordId) }
    }

    fun saveCallNote(callRecordId: String, text: String, source: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            runSuspendCatching {
                callLogRepository.saveTypedNote(
                    callRecordId,
                    text,
                    source,
                    source.takeIf {
                        it ==
                            "CLOUD_ASR"
                    }?.let { "stepfun" },
                )
            }
                .onSuccess { onResult(null) }
                .onFailure { onResult(it.message ?: "保存通话备注失败") }
        }
    }

    fun allowCloudSpeech() {
        outboundDataPreferences.setAllowCloudSpeech(true)
        refreshCloudAsrAvailability()
    }

    fun transcribeCallNote(audio: File, onResult: (String?, String?) -> Unit) {
        viewModelScope.launch {
            when (val result = cloudAsrGateway.transcribe(audio)) {
                is CloudAsrResult.Success -> {
                    audio.delete()
                    onResult(result.text, null)
                }

                is CloudAsrResult.Failure -> onResult(
                    null,
                    when (result.safeCode) {
                        "ASR_CLOUD_CONSENT_REQUIRED" -> "需要先允许语音识别上云"
                        "ASR_PROVIDER_NOT_CONFIGURED" -> "请先配置模型服务"
                        else -> "语音识别失败，请手动输入"
                    },
                )
            }
        }
    }

    private fun refreshCloudAsrAvailability() {
        viewModelScope.launch { mutableCloudAsrAvailability.value = cloudAsrGateway.availability() }
    }

    fun setMessagePlatformEnabled(platform: String, enabled: Boolean) {
        viewModelScope.launch { messageCollectionPreferences.setEnabled(platform, enabled) }
    }

    fun setOutgoingMessageCollectionEnabled(enabled: Boolean) {
        viewModelScope.launch { messageCollectionPreferences.setOutgoingCollectionEnabled(enabled) }
    }

    fun dismissNotificationCandidate(candidateId: String) {
        viewModelScope.launch { repository.dismissNotificationCandidate(candidateId) }
    }

    fun confirmNotificationCandidate(candidateId: String, contactId: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            runSuspendCatching { repository.confirmNotificationCandidate(candidateId, contactId) }
                .onSuccess { saved ->
                    if (saved) {
                        // 用户已确认这条消息属于该联系人；若他还没有线索则补建一条 NEW 线索。
                        repository.createLeadForContactIfAbsent(contactId, candidateId)
                    }
                    onResult(if (saved) null else "候选或联系人已不存在")
                }
                .onFailure { onResult(it.message ?: "保存失败，请重试") }
        }
    }

    fun createContactFromNotification(candidateId: String, displayName: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            runSuspendCatching { repository.confirmNotificationAsNewContact(candidateId, displayName) }
                .onSuccess { onResult(null) }
                .onFailure { onResult(it.message ?: "创建联系人失败，请重试") }
        }
    }

    fun confirmNotificationSchedule(candidateId: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            runSuspendCatching { repository.confirmNotificationSchedule(candidateId) }
                .onSuccess { onResult(null) }
                .onFailure { onResult(it.message ?: "添加日程失败，请重试") }
        }
    }

    fun forwardReplySuggestion(model: ReplySuggestionCardModel, draft: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            runSuspendCatching {
                replySuggestionRepository.forward(model.candidateId, model.platform, model.contactName, draft)
            }
                .onSuccess { result -> onResult(if (result.launched) null else "无法拉起${model.platform}，请稍后再试") }
                .onFailure { onResult(it.message ?: "转发失败，请重试") }
        }
    }

    fun dismissReplySuggestion(candidateId: String) {
        viewModelScope.launch { replySuggestionRepository.dismiss(candidateId) }
    }

    fun optOutReplySuggestion(contactId: String) {
        viewModelScope.launch { replySuggestionRepository.optOutContact(contactId) }
    }

    fun loadSystemContacts() {
        if (mutableImportState.value.isLoading) return
        viewModelScope.launch {
            mutableImportState.value = ContactImportUiState(isLoading = true)
            val result = systemContactReader.readAll()
            mutableImportState.value = ContactImportUiState(
                contacts = result.contacts,
                rowsRead = result.rowsRead,
                blankRows = result.blankRows,
                error = result.errorMessage,
            )
        }
    }

    fun prepareSystemContactSync(contact: ContactEntity, onResult: (AndroidContactSyncPreview?, String?) -> Unit) {
        viewModelScope.launch {
            runSuspendCatching { androidContactSync.prepare(contact) }
                .onSuccess { onResult(it, null) }
                .onFailure { onResult(null, it.message ?: "暂时无法安全写入手机通讯录") }
        }
    }

    fun applySystemContactSync(preview: AndroidContactSyncPreview, onResult: (AndroidContactSyncResult?, String?) -> Unit) {
        viewModelScope.launch {
            runSuspendCatching { androidContactSync.apply(preview) }
                .onSuccess { onResult(it, null) }
                .onFailure { onResult(null, it.message ?: "手机通讯录更新失败") }
        }
    }

    fun undoSystemContactSync(operationId: String, onResult: (String) -> Unit) {
        if (operationId.isBlank()) return
        viewModelScope.launch {
            runSuspendCatching { androidContactSync.undo(operationId) }
                .onSuccess(onResult)
                .onFailure { onResult(it.message ?: "无法撤销这次更新") }
        }
    }

    fun importSystemContacts(sourceIds: Set<String>) {
        if (sourceIds.isEmpty() || mutableImportState.value.isImporting) return
        viewModelScope.launch {
            val current = mutableImportState.value
            mutableImportState.value = current.copy(isImporting = true, error = null, resultMessage = null)
            runSuspendCatching {
                val summary = repository.importConfirmedSystemContacts(
                    contacts = current.contacts.filter { it.sourceId in sourceIds },
                    ownerPhone = userProfileStore.profile.value.phone,
                    ownerWechatId = userProfileStore.profile.value.wechatId,
                    ownerName = userProfileStore.profile.value.name,
                    nowEpochMs = System.currentTimeMillis(),
                )
                companyEnrichment.refresh()
                summary
            }.onSuccess { summary ->
                if (summary.selfIdentityMissing) {
                    userProfileStore.mergeMissingIdentity(
                        name = summary.skippedSelfName,
                        phone = summary.skippedSelfPhone,
                        wechatId = summary.skippedSelfWechat,
                    )
                }
                mutableImportState.value = current.copy(
                    isImporting = false,
                    resultMessage = buildString {
                        append("已导入 ${summary.imported} 位联系人")
                        if (summary.updated > 0) append("（其中 ${summary.updated} 位已更新）")
                        if (summary.automaticallyMerged > 0) append("，自动整理 ${summary.automaticallyMerged} 组重复资料")
                        if (summary.skippedSelf > 0) append("；已跳过你的本人资料")
                        if (summary.skippedInvalid > 0) append("；${summary.skippedInvalid} 条资料不完整")
                    },
                )
            }.onFailure {
                mutableImportState.value = current.copy(isImporting = false, error = it.message ?: "导入失败，请重试")
            }
        }
    }

    fun clearImportState() {
        mutableImportState.value = ContactImportUiState()
    }

    fun save(
        id: String?,
        name: String,
        phone: String?,
        wechat: String?,
        company: String?,
        title: String?,
        tag: String?,
        note: String?,
        email: String?,
        responsibilities: String?,
        onResult: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            runSuspendCatching {
                repository.saveUserContact(
                    id, name, phone, wechat, company, title, tag, note,
                    email = email, responsibilities = responsibilities,
                )
            }.onSuccess { onResult(null) }.onFailure { onResult(it.message ?: "保存失败") }
        }
    }

    fun delete(id: String, onDone: () -> Unit) {
        viewModelScope.launch {
            repository.deleteUserContact(id)
            onDone()
        }
    }

    fun confirmContactIsOwner(contactId: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            runSuspendCatching { repository.confirmContactIsOwner(contactId) }
                .onSuccess { onResult(null) }
                .onFailure { onResult(it.message ?: "识别本人资料失败") }
        }
    }

    fun undoContactIsOwner(contactId: String) {
        viewModelScope.launch { repository.undoContactIsOwner(contactId) }
    }

    fun saveOwnerCurrentEmployment(company: String, title: String?, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            runSuspendCatching { repository.saveOwnerCurrentEmployment(company, title) }
                .onSuccess { onResult(null) }
                .onFailure { onResult(it.message ?: "保存当前工作失败") }
        }
    }

    fun saveRelationship(fromId: String, toId: String, type: String, temporalState: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            runSuspendCatching { repository.saveConfirmedRelationship(fromId, toId, type, temporalState) }
                .onSuccess { onResult(null) }
                .onFailure { onResult(it.message ?: "保存关系失败") }
        }
    }

    fun deleteRelationship(edgeId: String, onDone: () -> Unit) {
        viewModelScope.launch {
            repository.deleteConfirmedRelationship(edgeId)
            onDone()
        }
    }

    fun updateRelationship(edgeId: String, type: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            runSuspendCatching { repository.updateConfirmedRelationship(edgeId, type) }
                .onSuccess { changed -> onResult(if (changed) null else "关系不存在") }
                .onFailure { onResult(it.message ?: "修改关系失败") }
        }
    }

    fun saveRelationshipEvent(
        eventId: String?,
        type: String,
        title: String,
        note: String?,
        participants: List<RelationshipEventParticipantInput>,
        onResult: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            runSuspendCatching {
                repository.saveConfirmedRelationshipEvent(eventId, type, title, note, null, participants)
            }.onSuccess { onResult(null) }
                .onFailure { onResult(it.message ?: "保存经历失败") }
        }
    }

    fun deleteRelationshipEvent(eventId: String, onDone: () -> Unit) {
        viewModelScope.launch {
            repository.deleteConfirmedRelationshipEvent(eventId)
            onDone()
        }
    }

    fun contactFacts(contactId: String): Flow<List<FactEntity>> = repository.observeContactFacts(contactId)
    fun contactCalls(contactId: String): Flow<List<CallRecordEntity>> = callLogRepository.observeForContact(contactId)
    fun contactOpportunities(contactId: String): Flow<List<CrmOpportunityEntity>> = repository.observeCrmOpportunitiesByContact(contactId)

    fun contactEnrichment(contactId: String): Flow<List<ContactEnrichmentCandidateEntity>> = repository.observePendingContactEnrichment(contactId)

    fun confirmContactEnrichment(candidate: ContactEnrichmentCandidateEntity, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            runSuspendCatching { repository.applyContactEnrichmentCandidate(candidate) }
                .onSuccess { onResult(null) }
                .onFailure { onResult(it.message ?: "应用失败") }
        }
    }

    fun rejectContactEnrichment(candidate: ContactEnrichmentCandidateEntity) {
        viewModelScope.launch { repository.resolveContactEnrichmentCandidate(candidate.candidateId, accepted = false) }
    }

    fun saveContactFact(contactId: String, text: String, type: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            runSuspendCatching { repository.saveConfirmedContactFact(contactId, text, type) }
                .onSuccess { onResult(null) }
                .onFailure { onResult(it.message ?: "保存失败") }
        }
    }

    fun deleteContactFact(factId: String) {
        viewModelScope.launch {
            if (!callLogRepository.deleteNoteFact(factId)) repository.deleteContactFact(factId)
        }
    }

    fun addAlias(contactId: String, alias: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            runSuspendCatching { repository.addContactAlias(contactId, alias) }
                .onSuccess { onResult(null) }
                .onFailure { onResult(it.message ?: "保存称呼失败") }
        }
    }

    fun addPlatformIdentity(contactId: String, platform: String, handle: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            runSuspendCatching { repository.addContactPlatformIdentity(contactId, platform, handle) }
                .onSuccess { onResult(null) }
                .onFailure { onResult(it.message ?: "保存账号失败") }
        }
    }

    fun deleteAlias(aliasId: String) {
        viewModelScope.launch { repository.deleteContactAlias(aliasId) }
    }

    fun deletePlatformIdentity(identityId: String) {
        viewModelScope.launch { repository.deleteContactPlatformIdentity(identityId) }
    }

    fun confirmMerge(canonicalContactId: String, sourceContactId: String, reason: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            runSuspendCatching { repository.confirmContactMerge(canonicalContactId, sourceContactId, reason) }
                .onSuccess { onResult(null) }
                .onFailure { onResult(it.message ?: "合并失败，请重试") }
        }
    }

    fun undoMerge(sourceContactId: String, onResult: (String?) -> Unit = {}) {
        viewModelScope.launch {
            runSuspendCatching { repository.undoContactMerge(sourceContactId) }
                .onSuccess { changed -> onResult(if (changed) null else "这条合并已经撤销") }
                .onFailure { onResult(it.message ?: "恢复失败，请重试") }
        }
    }
}

internal fun buildMergeSuggestions(
    contacts: List<ContactEntity>,
    aliases: List<ContactAliasEntity>,
    identities: List<ContactPlatformIdentityEntity>,
    links: List<ContactMergeLinkEntity>,
): List<ContactMergeSuggestion> {
    val mergedSources = links.mapTo(hashSetOf(), ContactMergeLinkEntity::sourceContactId)
    val active = contacts.filterNot { it.contactId in mergedSources }
    return ContactIdentityResolver.resolve(active, aliases, identities)
        .filter { it.decision == IdentityResolutionDecision.REVIEW }
        .map { ContactMergeSuggestion(it.first, it.second, it.reason, it.confidence) }
}

internal fun sameNormalizedPhone(first: String?, second: String?): Boolean {
    val normalizedFirst = normalizeContactPhone(first) ?: return false
    val normalizedSecond = normalizeContactPhone(second) ?: return false
    return normalizedFirst == normalizedSecond
}
