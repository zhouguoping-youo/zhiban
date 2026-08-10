package com.zhiban.rebuild.data.agent

import androidx.room.withTransaction
import com.zhiban.rebuild.data.calendar.SystemCalendarEvent
import com.zhiban.rebuild.data.contact.ContactAddressEntity
import com.zhiban.rebuild.data.contact.ContactAliasEntity
import com.zhiban.rebuild.data.contact.ContactEmploymentEntity
import com.zhiban.rebuild.data.contact.ContactEnrichmentCandidateEntity
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.ContactFacetEntity
import com.zhiban.rebuild.data.contact.ContactImportantDateEntity
import com.zhiban.rebuild.data.contact.ContactMergeLinkEntity
import com.zhiban.rebuild.data.contact.ContactMethodEntity
import com.zhiban.rebuild.data.contact.ContactPlatformIdentityEntity
import com.zhiban.rebuild.data.contact.ContactRoleEntity
import com.zhiban.rebuild.data.contact.OrganizationEntity
import com.zhiban.rebuild.data.contact.OwnerContactLinkEntity
import com.zhiban.rebuild.data.contact.RelationshipEdgeEntity
import com.zhiban.rebuild.data.contact.RelationshipEventEntity
import com.zhiban.rebuild.data.contact.RelationshipEventParticipantEntity
import com.zhiban.rebuild.data.contact.RelationshipEventWithParticipants
import com.zhiban.rebuild.data.contact.RelationshipPersonIds
import com.zhiban.rebuild.data.contact.SystemContactCandidate
import com.zhiban.rebuild.data.contact.buildLocalOrganizationSuggestions
import com.zhiban.rebuild.data.contact.normalizeContactPhone
import com.zhiban.rebuild.data.crm.CrmActionStatus
import com.zhiban.rebuild.data.crm.CrmActivityEntity
import com.zhiban.rebuild.data.crm.CrmAgentSuggestionEntity
import com.zhiban.rebuild.data.crm.CrmDemoCleanupAuditEntity
import com.zhiban.rebuild.data.crm.CrmLeadEntity
import com.zhiban.rebuild.data.crm.CrmLeadStatus
import com.zhiban.rebuild.data.crm.CrmNextActionEntity
import com.zhiban.rebuild.data.crm.CrmOpportunityEntity
import com.zhiban.rebuild.data.crm.CrmOpportunityStage
import com.zhiban.rebuild.data.crm.CrmOpportunityStakeholderEntity
import com.zhiban.rebuild.data.crm.CrmRecordStatus
import com.zhiban.rebuild.data.crm.CrmStageHistoryEntity
import com.zhiban.rebuild.data.crm.CrmSuggestionStatus
import com.zhiban.rebuild.data.notification.MessageCollectionPreferences
import com.zhiban.rebuild.data.notification.NotificationCandidateEntity
import com.zhiban.rebuild.data.notification.NotificationInsightAnalyzer
import com.zhiban.rebuild.data.notification.ScheduleInsight
import com.zhiban.rebuild.runtime.context.FactEntity
import com.zhiban.rebuild.runtime.context.FactIndex
import com.zhiban.rebuild.runtime.governance.ActionDecision
import com.zhiban.rebuild.runtime.governance.ActionPolicy
import com.zhiban.rebuild.runtime.governance.AutoWriteAuditDraft
import com.zhiban.rebuild.runtime.governance.AutoWriteToolNames
import com.zhiban.rebuild.runtime.governance.ReversibleWriteReadiness
import com.zhiban.rebuild.runtime.governance.canonicalChangeDigest
import com.zhiban.rebuild.runtime.governance.insertVisibleAutoWrite
import com.zhiban.rebuild.runtime.tool.RuntimeToolRisk
import com.zhiban.rebuild.runtime.tool.RuntimeToolSpec
import com.zhiban.rebuild.runtime.tool.sha256
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.json.JSONObject

internal class ContactAgentDataRepository(private val database: AgentDatabase) {
    private val enrichmentWriter = ContactEnrichmentDomainWriter(database)

    fun observeRawContacts(): Flow<List<ContactEntity>> = database.contactDao().observeAllActive()

    fun observeContactRoles(): Flow<List<ContactRoleEntity>> = database.contactDao().observeRoles()

    suspend fun confirmContactRole(contactId: String, roleType: String, skillId: String, nowEpochMs: Long = System.currentTimeMillis()) {
        require(database.contactDao().findById(contactId) != null) { "联系人不存在" }
        require(
            roleType in
                setOf("FAMILY", "FRIEND", "COLLEAGUE", "CUSTOMER", "SUPPLIER", "TEACHER", "CLASSMATE", "PROJECT_PARTNER", "OTHER"),
        ) {
            "不支持的联系人角色"
        }
        database.contactDao().upsertRole(
            ContactRoleEntity(contactId, skillId, roleType, 1.0, true, null, nowEpochMs, nowEpochMs),
        )
    }

    suspend fun removeContactRole(contactId: String, roleType: String, skillId: String): Boolean =
        database.contactDao().deleteRole(contactId, skillId, roleType) == 1

    fun observeContacts(): Flow<List<ContactEntity>> = combine(
        database.contactDao().observeAllActive(),
        database.contactIdentityDao().observeActiveMergeLinks(),
        database.contactKnowledgeDao().observeActiveOwnerContactLinks(),
    ) { contacts, links, ownerLinks ->
        val mergedSourceIds = links.mapTo(hashSetOf(), ContactMergeLinkEntity::sourceContactId)
        val ownerContactIds = ownerLinks.mapTo(hashSetOf(), OwnerContactLinkEntity::contactId)
        val sourcesByCanonical = links.groupBy(ContactMergeLinkEntity::canonicalContactId)
        val contactsById = contacts.associateBy(ContactEntity::contactId)
        contacts.filterNot { it.contactId in mergedSourceIds || it.contactId in ownerContactIds }
            .map { canonical ->
                sourcesByCanonical[canonical.contactId].orEmpty()
                    .mapNotNull { contactsById[it.sourceContactId] }
                    .fold(canonical, ::fillMissingContactFields)
            }
    }

    fun observeOwnerContactLinks(): Flow<List<OwnerContactLinkEntity>> = database.contactKnowledgeDao().observeActiveOwnerContactLinks()

    suspend fun confirmContactIsOwner(contactId: String, nowEpochMs: Long = System.currentTimeMillis()) {
        require(database.contactDao().findById(contactId) != null) { "联系人不存在" }
        database.contactKnowledgeDao().upsertOwnerContactLink(
            OwnerContactLinkEntity(
                contactId = contactId,
                reason = "用户确认这是本人通讯录资料",
                userConfirmed = true,
                createdAtEpochMs = nowEpochMs,
                undoneAtEpochMs = null,
            ),
        )
    }

    suspend fun undoContactIsOwner(contactId: String, nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        database.contactKnowledgeDao().undoOwnerContactLink(contactId, nowEpochMs) == 1

    fun observeContactAliases(): Flow<List<ContactAliasEntity>> = database.contactIdentityDao().observeAliases()

    fun observeContactPlatformIdentities(): Flow<List<ContactPlatformIdentityEntity>> = database.contactIdentityDao().observePlatformIdentities()

    fun observeContactMergeLinks(): Flow<List<ContactMergeLinkEntity>> = database.contactIdentityDao().observeActiveMergeLinks()

    suspend fun confirmContactMerge(canonicalContactId: String, sourceContactId: String, reason: String, nowEpochMs: Long = System.currentTimeMillis()) {
        require(canonicalContactId != sourceContactId) { "请选择两个不同的联系人" }
        require(reason.isNotBlank()) { "需要保留合并依据" }
        require(database.contactDao().findById(canonicalContactId) != null) { "主联系人不存在" }
        require(database.contactDao().findById(sourceContactId) != null) { "待合并联系人不存在" }
        require(database.contactIdentityDao().activeMergeLink(canonicalContactId) == null) {
            "主联系人已合并到其他联系人，请先撤销原合并"
        }
        // A source may be merged only once; re-merging it elsewhere must fail loudly instead of
        // REPLACE-overwriting the link (which would silently re-point it with no audit trail).
        require(database.contactIdentityDao().activeMergeLink(sourceContactId) == null) {
            "该联系人已合并到其他联系人，请先撤销原合并"
        }
        // The source must not already be a canonical with its own sources. Merging b (canonical
        // of a→b) away into c would strand a pointing at the now-hidden b. The caller must first
        // redirect b's sources to the final canonical, or undo them.
        val sourceHasSources = database.contactIdentityDao().hasActiveSources(sourceContactId)
        require(!sourceHasSources) {
            "该联系人是其他联系人的合并目标，请先处理其已有合并"
        }
        database.contactIdentityDao().upsertMergeLink(
            ContactMergeLinkEntity(
                sourceContactId = sourceContactId,
                canonicalContactId = canonicalContactId,
                reason = reason.trim().take(200),
                userConfirmed = true,
                createdAtEpochMs = nowEpochMs,
                undoneAtEpochMs = null,
            ),
        )
    }

    suspend fun undoContactMerge(sourceContactId: String, nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        database.contactIdentityDao().undoConfirmedMerge(sourceContactId, nowEpochMs) == 1

    suspend fun addContactAlias(contactId: String, alias: String, aliasType: String = "USER_ALIAS", nowEpochMs: Long = System.currentTimeMillis()): String {
        require(database.contactDao().findById(contactId) != null) { "联系人不存在" }
        val clean = alias.trim()
        require(clean.length in 1..60) { "称呼应为 1–60 个字" }
        val normalized = normalizeIdentityValue(clean)
        val id = "alias-$contactId-${normalized.hashCode().toUInt().toString(16)}"
        database.contactIdentityDao().upsertAlias(
            ContactAliasEntity(
                aliasId = id,
                contactId = contactId,
                alias = clean,
                normalizedAlias = normalized,
                aliasType = aliasType,
                source = "USER_CONFIRMED",
                userConfirmed = true,
                createdAtEpochMs = nowEpochMs,
            ),
        )
        return id
    }

    suspend fun addContactPlatformIdentity(contactId: String, platform: String, handle: String, nowEpochMs: Long = System.currentTimeMillis()): String {
        require(database.contactDao().findById(contactId) != null) { "联系人不存在" }
        val canonicalPlatform = platform.trim().uppercase()
        require(
            canonicalPlatform in setOf(
                "WECHAT", "DOUYIN", "XIAOHONGSHU", "QQ", "TIM", "FEISHU", "LARK",
                "WEWORK", "DINGTALK", "SMS", "WEIBO", "OTHER",
            ),
        ) {
            "平台类型无效"
        }
        val clean = handle.trim()
        require(clean.length in 1..100) { "账号应为 1–100 个字" }
        val normalized = normalizeIdentityValue(clean)
        val id = "identity-$contactId-$canonicalPlatform-${normalized.hashCode().toUInt().toString(16)}"
        database.contactIdentityDao().upsertPlatformIdentity(
            ContactPlatformIdentityEntity(
                identityId = id,
                contactId = contactId,
                platform = canonicalPlatform,
                handle = clean,
                normalizedHandle = normalized,
                platformUserId = null,
                source = "USER_CONFIRMED",
                userConfirmed = true,
                createdAtEpochMs = nowEpochMs,
                updatedAtEpochMs = nowEpochMs,
            ),
        )
        return id
    }

    suspend fun deleteContactAlias(aliasId: String): Boolean = database.contactIdentityDao().deleteConfirmedAlias(aliasId) == 1

    suspend fun deleteContactPlatformIdentity(identityId: String): Boolean = database.contactIdentityDao().deleteConfirmedPlatformIdentity(identityId) == 1

    suspend fun saveUserContact(
        contactId: String?,
        displayName: String,
        phone: String?,
        wechatId: String?,
        company: String?,
        title: String?,
        tag: String?,
        note: String?,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): String = database.withTransaction {
        require(displayName.isNotBlank()) { "联系人姓名不能为空" }
        val dao = database.contactDao()
        val existing = contactId?.let { dao.findById(it) }
        val id = existing?.contactId ?: "user-${UUID.randomUUID()}"
        val value = ContactEntity(
            contactId = id,
            displayName = displayName.trim(),
            normalizedName = displayName.trim().lowercase(),
            phone = phone.cleanContactField(),
            email = existing?.email,
            wechatId = wechatId.cleanContactField(),
            company = company.cleanContactField(),
            title = title.cleanContactField(),
            aliasesJson = existing?.aliasesJson ?: "[]",
            tagsJson = tag.cleanContactField()?.let { cleanTag ->
                buildJsonArray { add(JsonPrimitive(cleanTag)) }.toString()
            } ?: "[]",
            note = note.cleanContactField(),
            avatarUri = existing?.avatarUri,
            source = existing?.source ?: "USER",
            deletedAtEpochMs = null,
            createdAtEpochMs = existing?.createdAtEpochMs ?: nowEpochMs,
            updatedAtEpochMs = nowEpochMs,
        )
        if (existing == null) dao.insert(value) else check(dao.update(value) == 1)
        val knowledge = database.contactKnowledgeDao()
        replaceUserContactMethods(knowledge, id, value, existing, nowEpochMs)
        upsertUserContactEmployment(knowledge, id, value, existing, nowEpochMs)
        upsertUserContactSceneFacet(knowledge, id, tag, existing, nowEpochMs)
        id
    }

    private suspend fun replaceUserContactMethods(
        knowledge: com.zhiban.rebuild.data.contact.ContactKnowledgeDao,
        id: String,
        value: ContactEntity,
        existing: ContactEntity?,
        nowEpochMs: Long,
    ) {
        // A user edit replaces the user-owned primary values. Without this reconciliation,
        // cleared or changed phone/WeChat values remain searchable forever as stale identities.
        knowledge.deleteUserMethods(id, "PHONE")
        knowledge.deleteUserMethods(id, "WECHAT")
        knowledge.endCurrentUserEmployments(id, nowEpochMs)
        knowledge.deleteUserFacets(id, "SCENE")
        val methods = buildList {
            value.phone?.let { phoneValue ->
                val normalized = normalizeContactPhone(phoneValue) ?: phoneValue
                add(
                    ContactMethodEntity(
                        methodId = stableContactKnowledgeId(id, "PHONE", normalized),
                        contactId = id,
                        kind = "PHONE",
                        value = phoneValue,
                        normalizedValue = normalized,
                        label = null,
                        isPrimary = true,
                        source = "USER",
                        evidenceRef = "USER_PROFILE",
                        confidence = 1.0,
                        userConfirmed = true,
                        verifiedAtEpochMs = nowEpochMs,
                        createdAtEpochMs = existing?.createdAtEpochMs ?: nowEpochMs,
                        updatedAtEpochMs = nowEpochMs,
                    ),
                )
            }
            value.wechatId?.let { handle ->
                add(
                    ContactMethodEntity(
                        methodId = stableContactKnowledgeId(id, "WECHAT", handle.lowercase()),
                        contactId = id,
                        kind = "WECHAT",
                        value = handle,
                        normalizedValue = handle.lowercase(),
                        label = null,
                        isPrimary = true,
                        source = "USER",
                        evidenceRef = "USER_PROFILE",
                        confidence = 1.0,
                        userConfirmed = true,
                        verifiedAtEpochMs = nowEpochMs,
                        createdAtEpochMs = existing?.createdAtEpochMs ?: nowEpochMs,
                        updatedAtEpochMs = nowEpochMs,
                    ),
                )
            }
        }
        if (methods.isNotEmpty()) knowledge.upsertMethods(methods)
    }

    private suspend fun upsertUserContactEmployment(
        knowledge: com.zhiban.rebuild.data.contact.ContactKnowledgeDao,
        id: String,
        value: ContactEntity,
        existing: ContactEntity?,
        nowEpochMs: Long,
    ) {
        value.company?.let { companyName ->
            val organizationId = stableContactKnowledgeId("organization", "NAME", companyName.lowercase())
            knowledge.upsertOrganization(
                OrganizationEntity(
                    organizationId = organizationId,
                    canonicalName = companyName,
                    normalizedName = companyName.lowercase(),
                    creditCode = null,
                    status = null,
                    registeredAddress = null,
                    longitude = null,
                    latitude = null,
                    source = "USER",
                    sourceRef = "USER_PROFILE",
                    userConfirmed = true,
                    verifiedAtEpochMs = nowEpochMs,
                    createdAtEpochMs = existing?.createdAtEpochMs ?: nowEpochMs,
                    updatedAtEpochMs = nowEpochMs,
                ),
            )
            knowledge.upsertEmployment(
                ContactEmploymentEntity(
                    employmentId = stableContactKnowledgeId(id, "EMPLOYMENT", organizationId),
                    contactId = id,
                    organizationId = organizationId,
                    companyNameSnapshot = companyName,
                    department = null,
                    title = value.title,
                    jobDescription = null,
                    officeLocation = null,
                    isCurrent = true,
                    source = "USER",
                    evidenceRef = "USER_PROFILE",
                    confidence = 1.0,
                    userConfirmed = true,
                    createdAtEpochMs = existing?.createdAtEpochMs ?: nowEpochMs,
                    updatedAtEpochMs = nowEpochMs,
                ),
            )
        }
    }

    private suspend fun upsertUserContactSceneFacet(
        knowledge: com.zhiban.rebuild.data.contact.ContactKnowledgeDao,
        id: String,
        tag: String?,
        existing: ContactEntity?,
        nowEpochMs: Long,
    ) {
        tag.cleanContactField()?.let { facetValue ->
            knowledge.upsertFacet(
                ContactFacetEntity(
                    facetId = stableContactKnowledgeId(id, "SCENE", facetValue),
                    contactId = id,
                    dimension = "SCENE",
                    value = facetValue,
                    source = "USER",
                    evidenceRef = "USER_PROFILE",
                    confidence = 1.0,
                    userConfirmed = true,
                    createdAtEpochMs = existing?.createdAtEpochMs ?: nowEpochMs,
                    updatedAtEpochMs = nowEpochMs,
                ),
            )
        }
    }

    /**
     * Persists only the rows explicitly selected in the import preview.
     *
     * Re-import is idempotent by Android lookup key, then by normalized phone.
     * A phone matching the user's encrypted profile is never created as a
     * regular contact.
     */
    suspend fun importConfirmedSystemContacts(
        contacts: List<SystemContactCandidate>,
        ownerPhone: String?,
        ownerWechatId: String?,
        ownerName: String?,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): ContactImportSummary = database.withTransaction {
        val dao = database.contactDao()
        val normalizedOwnerPhone = normalizeContactPhone(ownerPhone)
        val normalizedOwnerWechat = ownerWechatId
            ?.trim()
            ?.trimStart('@')
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
        val normalizedOwnerName = ownerName
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
        val ctx = SystemContactImportContext(
            dao = dao,
            normalizedOwnerPhone = normalizedOwnerPhone,
            normalizedOwnerWechat = normalizedOwnerWechat,
            normalizedOwnerName = normalizedOwnerName,
            nowEpochMs = nowEpochMs,
        )
        contacts.distinctBy(SystemContactCandidate::sourceId).forEach { candidate ->
            processSystemContactCandidate(ctx, candidate)
        }
        stageLocalOrganizationSuggestions(nowEpochMs)
        ContactImportSummary(
            created = ctx.created,
            updated = ctx.updated,
            skippedSelf = ctx.skippedSelf,
            skippedInvalid = ctx.skippedInvalid,
            skippedSelfName = ctx.skippedSelfName,
            skippedSelfPhone = ctx.skippedSelfPhone,
            skippedSelfWechat = ctx.skippedSelfWechat,
            selfIdentityMissing = ctx.selfIdentityMissing,
        )
    }

    private data class SystemContactImportContext(
        val dao: com.zhiban.rebuild.data.contact.ContactDao,
        val normalizedOwnerPhone: String?,
        val normalizedOwnerWechat: String?,
        val normalizedOwnerName: String?,
        val nowEpochMs: Long,
        var created: Int = 0,
        var updated: Int = 0,
        var skippedSelf: Int = 0,
        var skippedInvalid: Int = 0,
        var skippedSelfName: String? = null,
        var skippedSelfPhone: String? = null,
        var skippedSelfWechat: String? = null,
        var selfIdentityMissing: Boolean = false,
    )

    private data class SystemContactEntityInput(
        val candidate: SystemContactCandidate,
        val name: String,
        val phones: List<String>,
        val wechats: List<String>,
        val source: String,
        val sourceMatch: ContactEntity?,
        val existing: ContactEntity?,
        val nowEpochMs: Long,
    )

    private suspend fun processSystemContactCandidate(ctx: SystemContactImportContext, candidate: SystemContactCandidate) {
        val name = candidate.displayName.trim().take(100)
        val phones = candidate.phones.mapNotNull(::normalizeContactPhone).distinct()
        val platformIdentities = candidate.platformIdentities
            .plus(candidate.wechatIds.map { com.zhiban.rebuild.data.contact.SystemContactPlatformIdentity("WECHAT", it) })
            .map { it.copy(handle = normalizeContactMethodHandle(it.handle)) }
            .distinctBy { it.platform to it.handle }
        val wechats = platformIdentities.filter { it.platform == "WECHAT" }.map { it.handle }
        if (name.isBlank()) {
            ctx.skippedInvalid++
            return
        }
        val matchesOwnerPhone = ctx.normalizedOwnerPhone != null && phones.contains(ctx.normalizedOwnerPhone)
        val matchesOwnerWechat = ctx.normalizedOwnerWechat != null && wechats.contains(ctx.normalizedOwnerWechat)
        if (matchesOwnerPhone || matchesOwnerWechat) {
            ctx.skippedSelf++
            if (ctx.skippedSelfName == null) ctx.skippedSelfName = name
            if (matchesOwnerPhone) ctx.skippedSelfPhone = ctx.normalizedOwnerPhone
            if (ctx.skippedSelfWechat == null) {
                ctx.skippedSelfWechat = wechats.firstOrNull() ?: ctx.normalizedOwnerWechat
            }
            ctx.selfIdentityMissing = ctx.selfIdentityMissing ||
                (ctx.normalizedOwnerName == null && name.isNotBlank()) ||
                (ctx.normalizedOwnerPhone == null && phones.isNotEmpty()) ||
                (ctx.normalizedOwnerWechat == null && wechats.isNotEmpty())
            return
        }

        val source = "SYSTEM_CONTACT:${candidate.sourceId.take(180)}"
        val sourceMatch = ctx.dao.findBySource(source)
        val existing = sourceMatch ?: phones.firstOrNull()?.let { normalized ->
            database.contactKnowledgeDao().findContactByMethod("PHONE", normalized)
                ?: ctx.dao.findByPhone(normalized)
        } ?: wechats.firstOrNull()?.let { normalized ->
            database.contactKnowledgeDao().findContactByMethod("WECHAT", normalized)
        } ?: platformIdentities.firstNotNullOfOrNull { identity ->
            database.contactIdentityDao().findContactByPlatformHandle(identity.platform, identity.handle)
        }
        val value = buildSystemContactEntity(
            SystemContactEntityInput(
                candidate = candidate,
                name = name,
                phones = phones,
                wechats = wechats,
                source = source,
                sourceMatch = sourceMatch,
                existing = existing,
                nowEpochMs = ctx.nowEpochMs,
            ),
        )
        if (existing == null) {
            ctx.dao.insert(value)
            ctx.created++
        } else {
            check(ctx.dao.update(value) == 1)
            ctx.updated++
        }

        val knowledge = database.contactKnowledgeDao()
        val sourceRef = "android-contact:${candidate.sourceId.take(180)}"
        upsertSystemContactMethods(knowledge, candidate, value, sourceRef, ctx.nowEpochMs)
        upsertSystemContactPlatformIdentities(candidate, value, sourceRef, ctx.nowEpochMs)
        upsertSystemContactOrganization(knowledge, candidate, value, sourceRef, ctx.nowEpochMs)
        upsertSystemContactAddressesDatesAndFacet(knowledge, candidate, value, sourceRef, ctx.nowEpochMs)
    }

    private fun buildSystemContactEntity(input: SystemContactEntityInput): ContactEntity = ContactEntity(
        contactId = input.existing?.contactId ?: "system-${UUID.randomUUID()}",
        displayName = if (input.sourceMatch != null) input.name else input.existing?.displayName ?: input.name,
        normalizedName = (if (input.sourceMatch != null) input.name else input.existing?.displayName ?: input.name).lowercase(),
        phone = if (input.sourceMatch != null) {
            input.phones.firstOrNull() ?: input.sourceMatch.phone
        } else {
            input.existing?.phone ?: input.phones.firstOrNull()
        },
        email = if (input.sourceMatch != null) {
            input.candidate.emails.firstOrNull()?.trim()?.lowercase() ?: input.sourceMatch.email
        } else {
            input.existing?.email ?: input.candidate.emails.firstOrNull()?.trim()?.lowercase()
        },
        wechatId = if (input.sourceMatch != null) {
            input.existing?.wechatId ?: input.wechats.firstOrNull()
        } else {
            input.existing?.wechatId ?: input.wechats.firstOrNull()
        },
        company = if (input.sourceMatch != null) {
            input.candidate.company.cleanContactField() ?: input.sourceMatch.company
        } else {
            input.existing?.company ?: input.candidate.company.cleanContactField()
        },
        title = if (input.sourceMatch != null) {
            input.candidate.title.cleanContactField() ?: input.sourceMatch.title
        } else {
            input.existing?.title ?: input.candidate.title.cleanContactField()
        },
        aliasesJson = input.existing?.aliasesJson ?: "[]",
        tagsJson = input.existing?.tagsJson?.takeIf { it != "[]" } ?: "[\"手机通讯录\"]",
        note = if (input.sourceMatch != null) {
            input.candidate.note.cleanContactField() ?: input.sourceMatch.note
        } else {
            input.existing?.note ?: input.candidate.note.cleanContactField()
        },
        avatarUri = input.existing?.avatarUri,
        source = if (input.existing == null || input.sourceMatch != null) input.source else input.existing.source,
        deletedAtEpochMs = null,
        createdAtEpochMs = input.existing?.createdAtEpochMs ?: input.nowEpochMs,
        updatedAtEpochMs = input.nowEpochMs,
    )

    private suspend fun upsertSystemContactMethods(
        knowledge: com.zhiban.rebuild.data.contact.ContactKnowledgeDao,
        candidate: SystemContactCandidate,
        value: ContactEntity,
        sourceRef: String,
        nowEpochMs: Long,
    ) {
        val methods = buildList {
            candidate.phones.mapNotNull(::normalizeContactPhone).distinct().forEachIndexed { index, normalized ->
                add(
                    ContactMethodEntity(
                        methodId = stableContactKnowledgeId(value.contactId, "PHONE", normalized),
                        contactId = value.contactId,
                        kind = "PHONE",
                        value = normalized,
                        normalizedValue = normalized,
                        label = null,
                        isPrimary = index == 0,
                        source = "SYSTEM_CONTACT",
                        evidenceRef = sourceRef,
                        confidence = 0.85,
                        userConfirmed = true,
                        verifiedAtEpochMs = null,
                        createdAtEpochMs = nowEpochMs,
                        updatedAtEpochMs = nowEpochMs,
                    ),
                )
            }
            candidate.platformIdentities
                .plus(candidate.wechatIds.map { com.zhiban.rebuild.data.contact.SystemContactPlatformIdentity("WECHAT", it) })
                .distinctBy { it.platform to normalizeContactMethodHandle(it.handle) }
                .forEachIndexed { index, identity ->
                    val normalized = normalizeContactMethodHandle(identity.handle)
                    add(
                        ContactMethodEntity(
                            methodId = stableContactKnowledgeId(value.contactId, identity.platform, normalized),
                            contactId = value.contactId,
                            kind = identity.platform,
                            value = normalized,
                            normalizedValue = normalized,
                            label = null,
                            isPrimary = index == 0,
                            source = "SYSTEM_CONTACT",
                            evidenceRef = sourceRef,
                            confidence = 0.85,
                            userConfirmed = true,
                            verifiedAtEpochMs = null,
                            createdAtEpochMs = nowEpochMs,
                            updatedAtEpochMs = nowEpochMs,
                        ),
                    )
                }
            candidate.emails.map { it.trim().lowercase() }.filter { it.contains('@') }.distinct()
                .forEachIndexed { index, normalized ->
                    add(
                        ContactMethodEntity(
                            methodId = stableContactKnowledgeId(value.contactId, "EMAIL", normalized),
                            contactId = value.contactId,
                            kind = "EMAIL",
                            value = normalized,
                            normalizedValue = normalized,
                            label = null,
                            isPrimary = index == 0,
                            source = "SYSTEM_CONTACT",
                            evidenceRef = sourceRef,
                            confidence = 0.85,
                            userConfirmed = true,
                            verifiedAtEpochMs = null,
                            createdAtEpochMs = nowEpochMs,
                            updatedAtEpochMs = nowEpochMs,
                        ),
                    )
                }
        }
        if (methods.isNotEmpty()) knowledge.upsertMethods(methods)
    }

    private suspend fun upsertSystemContactPlatformIdentities(candidate: SystemContactCandidate, value: ContactEntity, sourceRef: String, nowEpochMs: Long) {
        val identities = candidate.platformIdentities
            .plus(candidate.wechatIds.map { com.zhiban.rebuild.data.contact.SystemContactPlatformIdentity("WECHAT", it) })
            .distinctBy { it.platform to normalizeContactMethodHandle(it.handle) }
        identities.forEach { identity ->
            val normalized = normalizeContactMethodHandle(identity.handle)
            database.contactIdentityDao().upsertPlatformIdentity(
                ContactPlatformIdentityEntity(
                    identityId = stableContactKnowledgeId(value.contactId, identity.platform, normalized),
                    contactId = value.contactId,
                    platform = identity.platform,
                    handle = identity.handle.trim(),
                    normalizedHandle = normalized,
                    platformUserId = null,
                    source = "SYSTEM_CONTACT:$sourceRef",
                    userConfirmed = true,
                    createdAtEpochMs = nowEpochMs,
                    updatedAtEpochMs = nowEpochMs,
                ),
            )
        }
    }

    private suspend fun stageLocalOrganizationSuggestions(nowEpochMs: Long) {
        val contacts = database.contactDao().listActiveForIntelligence()
        buildLocalOrganizationSuggestions(contacts).forEach { suggestion ->
            val candidateId = "local-org-${sha256("${suggestion.contactId}|${suggestion.company}").take(24)}"
            database.contactKnowledgeDao().insertEnrichmentCandidateIfAbsent(
                ContactEnrichmentCandidateEntity(
                    candidateId = candidateId,
                    contactId = suggestion.contactId,
                    providerId = "local-contact-intelligence",
                    fieldKind = "ORGANIZATION",
                    proposedValueJson = buildJsonObject { put("company", JsonPrimitive(suggestion.company)) }.toString(),
                    sourceRef = "通讯录中另一位联系人的已存公司资料",
                    confidence = suggestion.confidence,
                    status = "PENDING",
                    observedAtEpochMs = nowEpochMs,
                    expiresAtEpochMs = nowEpochMs + LOCAL_ENRICHMENT_TTL_MS,
                    createdAtEpochMs = nowEpochMs,
                    updatedAtEpochMs = nowEpochMs,
                ),
            )
        }
    }

    suspend fun refreshLocalContactIntelligence(nowEpochMs: Long = System.currentTimeMillis()) = database.withTransaction {
        val contacts = database.contactDao().listActiveForIntelligence()
        contacts.forEach { contact ->
            contact.wechatId?.cleanContactField()?.let { handle ->
                val normalized = normalizeContactMethodHandle(handle)
                database.contactIdentityDao().insertPlatformIdentityIfAbsent(
                    ContactPlatformIdentityEntity(
                        identityId = stableContactKnowledgeId(contact.contactId, "WECHAT", normalized),
                        contactId = contact.contactId,
                        platform = "WECHAT",
                        handle = handle,
                        normalizedHandle = normalized,
                        platformUserId = null,
                        source = "CONTACT_PROFILE",
                        userConfirmed = true,
                        createdAtEpochMs = contact.createdAtEpochMs,
                        updatedAtEpochMs = nowEpochMs,
                    ),
                )
            }
        }
        stageLocalOrganizationSuggestions(nowEpochMs)
    }

    private suspend fun upsertSystemContactOrganization(
        knowledge: com.zhiban.rebuild.data.contact.ContactKnowledgeDao,
        candidate: SystemContactCandidate,
        value: ContactEntity,
        sourceRef: String,
        nowEpochMs: Long,
    ) {
        candidate.company.cleanContactField()?.let { companyName ->
            val organizationId = stableContactKnowledgeId("organization", "NAME", companyName.lowercase())
            knowledge.upsertOrganization(
                OrganizationEntity(
                    organizationId = organizationId,
                    canonicalName = companyName,
                    normalizedName = companyName.lowercase(),
                    creditCode = null,
                    status = null,
                    registeredAddress = null,
                    longitude = null,
                    latitude = null,
                    source = "SYSTEM_CONTACT",
                    sourceRef = sourceRef,
                    userConfirmed = true,
                    verifiedAtEpochMs = null,
                    createdAtEpochMs = nowEpochMs,
                    updatedAtEpochMs = nowEpochMs,
                ),
            )
            knowledge.upsertEmployment(
                ContactEmploymentEntity(
                    employmentId = stableContactKnowledgeId(value.contactId, "EMPLOYMENT", organizationId),
                    contactId = value.contactId,
                    organizationId = organizationId,
                    companyNameSnapshot = companyName,
                    department = candidate.department.cleanContactField(),
                    title = candidate.title.cleanContactField(),
                    jobDescription = candidate.jobDescription.cleanContactField(),
                    officeLocation = candidate.officeLocation.cleanContactField(),
                    isCurrent = true,
                    source = "SYSTEM_CONTACT",
                    evidenceRef = sourceRef,
                    confidence = 0.8,
                    userConfirmed = true,
                    createdAtEpochMs = nowEpochMs,
                    updatedAtEpochMs = nowEpochMs,
                ),
            )
        }
    }

    private suspend fun upsertSystemContactAddressesDatesAndFacet(
        knowledge: com.zhiban.rebuild.data.contact.ContactKnowledgeDao,
        candidate: SystemContactCandidate,
        value: ContactEntity,
        sourceRef: String,
        nowEpochMs: Long,
    ) {
        val addresses = candidate.addresses.distinct().map { address ->
            ContactAddressEntity(
                addressId = stableContactKnowledgeId(value.contactId, address.kind, address.formattedAddress),
                contactId = value.contactId,
                kind = address.kind,
                formattedAddress = address.formattedAddress,
                longitude = null,
                latitude = null,
                precision = null,
                source = "SYSTEM_CONTACT",
                evidenceRef = sourceRef,
                userConfirmed = true,
                verifiedAtEpochMs = null,
                createdAtEpochMs = nowEpochMs,
                updatedAtEpochMs = nowEpochMs,
            )
        }
        if (addresses.isNotEmpty()) knowledge.upsertAddresses(addresses)
        candidate.birthday?.let { birthday ->
            knowledge.upsertImportantDate(
                ContactImportantDateEntity(
                    dateId = stableContactKnowledgeId(value.contactId, "DATE", "BIRTHDAY"),
                    contactId = value.contactId,
                    kind = "BIRTHDAY",
                    year = birthday.year,
                    month = birthday.month,
                    day = birthday.day,
                    source = "SYSTEM_CONTACT",
                    evidenceRef = sourceRef,
                    userConfirmed = true,
                    createdAtEpochMs = nowEpochMs,
                    updatedAtEpochMs = nowEpochMs,
                ),
            )
        }
        knowledge.upsertFacet(
            ContactFacetEntity(
                facetId = stableContactKnowledgeId(value.contactId, "SCENE", "PHONE_CONTACTS"),
                contactId = value.contactId,
                dimension = "SCENE",
                value = "手机通讯录",
                source = "SYSTEM_CONTACT",
                evidenceRef = sourceRef,
                confidence = 1.0,
                userConfirmed = true,
                createdAtEpochMs = nowEpochMs,
                updatedAtEpochMs = nowEpochMs,
            ),
        )
    }

    suspend fun deleteUserContact(contactId: String): Boolean = database.withTransaction {
        val now = System.currentTimeMillis()
        val identityCluster = database.contactDao().activeIdentityClusterIds(contactId)
        if (identityCluster.isEmpty()) return@withTransaction false
        database.relationshipEdgeDao().deactivateForContacts(identityCluster, now)
        database.relationshipEventDao().deactivateForContacts(identityCluster, now)
        FactIndex(database).revokeByContactIds(identityCluster, now)
        database.crmDao().apply {
            detachLeadContacts(identityCluster, now)
            detachOpportunityContacts(identityCluster, now)
            deleteStakeholdersForContacts(identityCluster)
            detachActivityContacts(identityCluster)
            detachActionContacts(identityCluster, now)
            detachSuggestionContacts(identityCluster, now)
        }
        database.contactDao().softDeleteAll(identityCluster, now) > 0
    }

    fun observeContactMethods(contactId: String) = database.contactKnowledgeDao().observeMethods(contactId)
    fun observeContactEmployments(contactId: String) = database.contactKnowledgeDao().observeEmployments(contactId)
    fun observeContactAddresses(contactId: String) = database.contactKnowledgeDao().observeAddresses(contactId)
    fun observeContactImportantDates(contactId: String) = database.contactKnowledgeDao().observeImportantDates(contactId)
    fun observeAllContactImportantDates() = database.contactKnowledgeDao().observeAllImportantDates()
    fun observeContactFacets(contactId: String) = database.contactKnowledgeDao().observeFacets(contactId)
    fun observePendingContactEnrichment(contactId: String) = database.contactKnowledgeDao().observePendingEnrichment(contactId)
    fun observeAllPendingContactEnrichment(nowEpochMs: Long = System.currentTimeMillis()) =
        database.contactKnowledgeDao().observeAllPendingEnrichment(nowEpochMs)

    suspend fun stageContactEnrichmentCandidate(candidate: ContactEnrichmentCandidateEntity) =
        database.contactKnowledgeDao().insertEnrichmentCandidateIfAbsent(candidate.copy(status = "PENDING")) != -1L

    suspend fun resolveContactEnrichmentCandidate(candidateId: String, accepted: Boolean): Boolean = database.contactKnowledgeDao().resolveEnrichmentCandidate(
        candidateId = candidateId,
        status = if (accepted) "APPROVED" else "DISMISSED",
        nowEpochMs = System.currentTimeMillis(),
    ) == 1

    suspend fun applyContactEnrichmentCandidate(candidate: ContactEnrichmentCandidateEntity, nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        enrichmentWriter.apply(candidate.candidateId, nowEpochMs)

    private fun String?.cleanContactField(): String? = this?.trim()?.takeIf(String::isNotEmpty)
    private fun normalizeContactMethodHandle(raw: String): String = raw
        .trim()
        .trimStart('@')
        .lowercase()
        .filterNot(Char::isWhitespace)
    private fun normalizeIdentityValue(value: String): String = value.lowercase().filterNot(Char::isWhitespace).trimStart('@')

    private companion object {
        const val LOCAL_ENRICHMENT_TTL_MS = 30L * 24 * 60 * 60 * 1_000
    }
}
