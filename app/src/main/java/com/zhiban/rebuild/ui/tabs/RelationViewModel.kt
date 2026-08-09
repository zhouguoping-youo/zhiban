package com.zhiban.rebuild.ui.tabs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhiban.rebuild.data.agent.AgentDataRepository
import com.zhiban.rebuild.data.agent.RelationshipEventParticipantInput
import com.zhiban.rebuild.data.calllog.CallLogRepository
import com.zhiban.rebuild.data.calllog.CallRecordEntity
import com.zhiban.rebuild.data.contact.ContactAliasEntity
import com.zhiban.rebuild.data.contact.ContactEnrichmentCandidateEntity
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.ContactMergeLinkEntity
import com.zhiban.rebuild.data.contact.ContactPlatformIdentityEntity
import com.zhiban.rebuild.data.contact.OwnerContactLinkEntity
import com.zhiban.rebuild.data.contact.RelationshipEdgeEntity
import com.zhiban.rebuild.data.contact.RelationshipEventWithParticipants
import com.zhiban.rebuild.data.contact.SystemContactCandidate
import com.zhiban.rebuild.data.contact.SystemContactReader
import com.zhiban.rebuild.data.contact.normalizeContactPhone
import com.zhiban.rebuild.data.crm.CrmOpportunityEntity
import com.zhiban.rebuild.data.notification.MessageCollectionPreferences
import com.zhiban.rebuild.data.notification.NotificationCandidateEntity
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

@HiltViewModel
class RelationViewModel @Inject constructor(
    private val repository: AgentDataRepository,
    private val systemContactReader: SystemContactReader,
    private val userProfileStore: UserProfileStore,
    private val messageCollectionPreferences: MessageCollectionPreferences,
    private val callLogRepository: CallLogRepository,
    private val cloudAsrGateway: CloudAsrGateway,
    private val outboundDataPreferences: OutboundDataPreferences,
) : ViewModel() {
    val contacts: StateFlow<List<ContactEntity>> = repository.observeContacts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val rawContacts: StateFlow<List<ContactEntity>> = repository.observeRawContacts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val relationships: StateFlow<List<RelationshipEdgeEntity>> = repository.observeRelationships()
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
    val mergeSuggestions: StateFlow<List<ContactMergeSuggestion>> = combine(
        repository.observeRawContacts(),
        repository.observeContactAliases(),
        repository.observeContactPlatformIdentities(),
        repository.observeContactMergeLinks(),
    ) { contacts, aliases, identities, links ->
        buildMergeSuggestions(contacts, aliases, identities, links)
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
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
    val pendingCallNotes: StateFlow<List<CallRecordEntity>> = callLogRepository.observePendingNotes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val mutableCloudAsrAvailability = MutableStateFlow(CloudAsrAvailability.CONSENT_REQUIRED)
    val cloudAsrAvailability = mutableCloudAsrAvailability.asStateFlow()

    init {
        viewModelScope.launch { repository.purgeNonPersonalSmsCandidates() }
        viewModelScope.launch { repository.refreshLocalContactIntelligence() }
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

    fun importSystemContacts(sourceIds: Set<String>) {
        if (sourceIds.isEmpty() || mutableImportState.value.isImporting) return
        viewModelScope.launch {
            val current = mutableImportState.value
            mutableImportState.value = current.copy(isImporting = true, error = null, resultMessage = null)
            runSuspendCatching {
                repository.importConfirmedSystemContacts(
                    contacts = current.contacts.filter { it.sourceId in sourceIds },
                    ownerPhone = userProfileStore.profile.value.phone,
                    ownerWechatId = userProfileStore.profile.value.wechatId,
                    ownerName = userProfileStore.profile.value.name,
                    nowEpochMs = System.currentTimeMillis(),
                )
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
        onResult: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            runSuspendCatching {
                repository.saveUserContact(id, name, phone, wechat, company, title, tag, note)
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

    fun saveRelationship(fromId: String, toId: String, type: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            runSuspendCatching { repository.saveConfirmedRelationship(fromId, toId, type) }
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
    val contactsById = active.associateBy(ContactEntity::contactId)
    val suggestions = linkedMapOf<String, ContactMergeSuggestion>()

    fun addPair(first: ContactEntity, second: ContactEntity, reason: String, confidence: Double) {
        if (first.contactId == second.contactId) return
        val ordered = if (first.contactId < second.contactId) first to second else second to first
        val key = "${ordered.first.contactId}\u001f${ordered.second.contactId}"
        val previous = suggestions[key]
        if (previous == null || confidence > previous.confidence) {
            suggestions[key] = ContactMergeSuggestion(ordered.first, ordered.second, reason, confidence)
        }
    }

    fun addGroups(groups: Collection<List<ContactEntity>>, reason: String, confidence: Double) {
        groups.forEach { group ->
            for (firstIndex in 0 until group.lastIndex) {
                for (secondIndex in firstIndex + 1 until group.size) {
                    addPair(group[firstIndex], group[secondIndex], reason, confidence)
                }
            }
        }
    }

    addGroups(
        active.mapNotNull { contact -> normalizeContactPhone(contact.phone)?.let { it to contact } }
            .groupBy({ it.first }, { it.second }).values,
        "手机号相同",
        1.0,
    )
    addGroups(
        active.mapNotNull { contact ->
            contact.email?.trim()?.lowercase()?.takeIf(String::isNotBlank)?.let {
                it to
                    contact
            }
        }
            .groupBy({ it.first }, { it.second }).values,
        "邮箱相同",
        1.0,
    )
    addGroups(
        active.mapNotNull { contact ->
            contact.wechatId?.trim()?.lowercase()?.takeIf(String::isNotBlank)?.let {
                it to
                    contact
            }
        }
            .groupBy({ it.first }, { it.second }).values,
        "微信号相同",
        1.0,
    )
    addGroups(
        identities.mapNotNull { identity ->
            contactsById[identity.contactId]?.let { "${identity.platform}\u001f${identity.normalizedHandle}" to it }
        }.groupBy({ it.first }, { it.second }).values,
        "社交账号相同",
        1.0,
    )

    val contactsByName = active.groupBy(ContactEntity::normalizedName)
    aliases.forEach { alias ->
        val aliasOwner = contactsById[alias.contactId] ?: return@forEach
        contactsByName[alias.normalizedAlias].orEmpty().forEach { namedContact ->
            addPair(aliasOwner, namedContact, "常用称呼与姓名吻合", 0.9)
        }
    }
    addGroups(
        active.mapNotNull { contact ->
            contact.company?.trim()?.lowercase()?.takeIf(String::isNotBlank)
                ?.let { "${contact.normalizedName}\u001f$it" to contact }
        }.groupBy({ it.first }, { it.second }).values,
        "姓名和公司相同",
        0.82,
    )
    // 同名 + 一方是 agent 据对话先建的占位（source = AGENT_CANDIDATE，通常只有名字）：随后从手机
    // 通讯录导入了同一个真人。纯同名会把同名的不同人误凑，所以用"一方是 agent 占位"约束提高精度——
    // 关键是不只看"无联系方式"：一个真实的、但恰好没存手机号/邮箱的导入联系人不是占位，把两个这样的
    // 同名真人凑成一对就是误合并。低置信，用户确认后才合并。手机通讯录导入不去重（只按手机号/微信/
    // 来源），这条建议是兜底，让这类重复可被发现并合并。
    fun ContactEntity.isAgentStub() = source == "AGENT_CANDIDATE"
    addGroups(
        active.filter { it.normalizedName.isNotBlank() }
            .groupBy(ContactEntity::normalizedName)
            .values
            .filter { group -> group.size > 1 && group.any { it.isAgentStub() } },
        "同名且一方是待确认联系人",
        0.6,
    )
    return suggestions.values.sortedByDescending(ContactMergeSuggestion::confidence)
}

internal fun sameNormalizedPhone(first: String?, second: String?): Boolean {
    val normalizedFirst = normalizeContactPhone(first) ?: return false
    val normalizedSecond = normalizeContactPhone(second) ?: return false
    return normalizedFirst == normalizedSecond
}
