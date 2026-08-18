package com.zhiban.rebuild.data.agent

import androidx.room.withTransaction
import com.zhiban.rebuild.data.autowrite.ActionDecision
import com.zhiban.rebuild.data.autowrite.ActionPolicy
import com.zhiban.rebuild.data.autowrite.AutoWriteAuditDraft
import com.zhiban.rebuild.data.autowrite.AutoWriteToolNames
import com.zhiban.rebuild.data.autowrite.ReversibleWriteReadiness
import com.zhiban.rebuild.data.autowrite.canonicalChangeDigest
import com.zhiban.rebuild.data.autowrite.insertVisibleAutoWrite
import com.zhiban.rebuild.data.calendar.SystemCalendarEvent
import com.zhiban.rebuild.data.contact.ContactAddressEntity
import com.zhiban.rebuild.data.contact.ContactAliasEntity
import com.zhiban.rebuild.data.contact.ContactEmploymentEntity
import com.zhiban.rebuild.data.contact.ContactEnrichmentCandidateEntity
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.ContactFacetEntity
import com.zhiban.rebuild.data.contact.ContactIdentityResolver
import com.zhiban.rebuild.data.contact.ContactImportantDateEntity
import com.zhiban.rebuild.data.contact.ContactMergeLinkEntity
import com.zhiban.rebuild.data.contact.ContactMethodEntity
import com.zhiban.rebuild.data.contact.ContactPlatformIdentityEntity
import com.zhiban.rebuild.data.contact.ContactRoleEntity
import com.zhiban.rebuild.data.contact.IdentityClaimEntity
import com.zhiban.rebuild.data.contact.IdentityResolutionDecision
import com.zhiban.rebuild.data.contact.OrganizationEntity
import com.zhiban.rebuild.data.contact.OwnerContactLinkEntity
import com.zhiban.rebuild.data.contact.PersonEmploymentEpisodeEntity
import com.zhiban.rebuild.data.contact.PersonEntity
import com.zhiban.rebuild.data.contact.RelationshipEdgeEntity
import com.zhiban.rebuild.data.contact.RelationshipEventEntity
import com.zhiban.rebuild.data.contact.RelationshipEventParticipantEntity
import com.zhiban.rebuild.data.contact.RelationshipEventWithParticipants
import com.zhiban.rebuild.data.contact.RelationshipPersonIds
import com.zhiban.rebuild.data.contact.SourceIdentityEntity
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
import com.zhiban.rebuild.data.facts.FactEntity
import com.zhiban.rebuild.data.facts.FactIndex
import com.zhiban.rebuild.data.notification.MessageCollectionPreferences
import com.zhiban.rebuild.data.notification.NotificationCandidateEntity
import com.zhiban.rebuild.data.notification.NotificationInsightAnalyzer
import com.zhiban.rebuild.data.notification.ScheduleInsight
import com.zhiban.rebuild.foundation.RuntimeToolRisk
import com.zhiban.rebuild.foundation.RuntimeToolSpec
import com.zhiban.rebuild.foundation.changeIdFor
import com.zhiban.rebuild.foundation.sha256
import com.zhiban.rebuild.relationship.RelationshipTaxonomy
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.json.JSONObject

internal class ContactAgentDataRepository(internal val database: AgentDatabase) {
    private val enrichmentWriter = ContactEnrichmentDomainWriter(database)

    fun observeRawContacts(): Flow<List<ContactEntity>> = database.contactDao().observeAllActive()

    fun observeContactRoles(): Flow<List<ContactRoleEntity>> = database.contactDao().observeRoles()

    suspend fun confirmContactRole(contactId: String, roleType: String, skillId: String, nowEpochMs: Long = System.currentTimeMillis()) {
        require(database.contactDao().findById(contactId) != null) { "联系人不存在" }
        require(roleType in RelationshipTaxonomy.selectableCodes) {
            "不支持的联系人角色"
        }
        database.contactDao().upsertRole(
            ContactRoleEntity(contactId, skillId, roleType, 1.0, true, null, nowEpochMs, nowEpochMs),
        )
    }

    suspend fun removeContactRole(contactId: String, roleType: String, skillId: String): Boolean =
        database.contactDao().deleteRole(contactId, skillId, roleType) == 1

    fun observeContacts(): Flow<List<ContactEntity>> = combine(
        // observeActive 已在 SQL 里排除合并源联系人,省掉内存 filterNot 一步(P1-性能4)。
        database.contactDao().observeActive(),
        database.contactIdentityDao().observeActiveMergeLinks(),
        database.contactKnowledgeDao().observeActiveOwnerContactLinks(),
    ) { contacts, links, ownerLinks ->
        val ownerContactIds = ownerLinks.mapTo(hashSetOf(), OwnerContactLinkEntity::contactId)
        val sourcesByCanonical = links.groupBy(ContactMergeLinkEntity::canonicalContactId)
        val contactsById = contacts.associateBy(ContactEntity::contactId)
        contacts.filterNot { it.contactId in ownerContactIds }
            .map { canonical ->
                sourcesByCanonical[canonical.contactId].orEmpty()
                    .mapNotNull { contactsById[it.sourceContactId] }
                    .fold(canonical, ::fillMissingContactFields)
            }
    }.distinctUntilChanged() // 任一表写触发的整表重 fold 若结果未变,不再向下游发射

    fun observeOwnerContactLinks(): Flow<List<OwnerContactLinkEntity>> = database.contactKnowledgeDao().observeActiveOwnerContactLinks()

    suspend fun saveOwnerCurrentEmployment(company: String, title: String?, nowEpochMs: Long = System.currentTimeMillis()): PersonEmploymentEpisodeEntity =
        database.withTransaction {
            val normalizedCompany = normalizeOrganizationFullName(company)
            require(normalizedCompany.isNotBlank()) { "公司全称不能为空" }
            val organization = database.upsertUserConfirmedOrganization(
                fullName = normalizedCompany,
                sourceRef = "USER_PROFILE",
                nowEpochMs = nowEpochMs,
            )
            val normalizedTitle = title?.trim()?.takeIf(String::isNotBlank)
            val intelligence = database.contactIntelligenceDao()
            val ownerPersonIds = database.contactKnowledgeDao().listActiveOwnerContactLinks()
                .mapTo(linkedSetOf()) { it.contactId }
                .apply { add(RelationshipPersonIds.SELF) }
            val currentEmployments = ownerPersonIds.mapNotNull { intelligence.findCurrentUserEmployment(it) }
            val current = currentEmployments.maxByOrNull { it.updatedAtEpochMs }
            if (intelligence.findPerson(RelationshipPersonIds.SELF) == null) {
                intelligence.upsertPerson(
                    PersonEntity(
                        personId = RelationshipPersonIds.SELF,
                        canonicalContactId = null,
                        displayName = "我",
                        normalizedName = "我",
                        kind = "SELF",
                        status = "ACTIVE",
                        createdAtEpochMs = nowEpochMs,
                        updatedAtEpochMs = nowEpochMs,
                    ),
                )
            }
            val sameCompany = current?.companyNameSnapshot
                ?.trim()
                ?.replace(Regex("\\s+"), " ")
                ?.equals(normalizedCompany, ignoreCase = true) == true
            if (current != null && sameCompany) {
                ownerPersonIds.forEach { intelligence.endCurrentUserEmployments(it, nowEpochMs) }
                return@withTransaction current.copy(
                    organizationId = organization.organizationId,
                    companyNameSnapshot = organization.canonicalName,
                    title = normalizedTitle,
                    validToEpochMs = null,
                    currentState = "CURRENT",
                    updatedAtEpochMs = nowEpochMs,
                ).also { intelligence.upsertEmployment(it) }
            }
            if (currentEmployments.isNotEmpty()) {
                ownerPersonIds.forEach { intelligence.endCurrentUserEmployments(it, nowEpochMs) }
            }
            PersonEmploymentEpisodeEntity(
                episodeId = stableContactKnowledgeId(
                    RelationshipPersonIds.SELF,
                    "USER_EMPLOYMENT",
                    "$normalizedCompany:$nowEpochMs",
                ),
                personId = RelationshipPersonIds.SELF,
                organizationId = organization.organizationId,
                companyNameSnapshot = normalizedCompany,
                department = null,
                title = normalizedTitle,
                validFromEpochMs = null,
                validToEpochMs = null,
                temporalPrecision = "UNKNOWN",
                currentState = "CURRENT",
                sourceRef = "USER_PROFILE",
                confidence = 1.0,
                verificationState = "USER_CONFIRMED",
                status = "ACTIVE",
                recordedAtEpochMs = nowEpochMs,
                updatedAtEpochMs = nowEpochMs,
            ).also { intelligence.upsertEmployment(it) }
        }

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
        email: String? = null,
        responsibilities: String? = null,
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
            // fill-only：表单可补可改，但不传则保留既有值（非表单调用方如确认新建不得清空）。
            email = email.cleanContactField() ?: existing?.email,
            wechatId = wechatId.cleanContactField(),
            company = company.cleanContactField()?.let(::normalizeOrganizationFullName),
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
            responsibilities = responsibilities.cleanContactField() ?: existing?.responsibilities,
        )
        if (existing == null) dao.insert(value) else check(dao.update(value) == 1)
        val knowledge = database.contactKnowledgeDao()
        replaceUserContactMethods(knowledge, id, value, existing, nowEpochMs)
        upsertUserContactEmployment(knowledge, id, value, existing, nowEpochMs)
        upsertUserTemporalIntelligence(id, value, existing, nowEpochMs)
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

    private suspend fun upsertUserTemporalIntelligence(id: String, value: ContactEntity, existing: ContactEntity?, nowEpochMs: Long) {
        val intelligence = database.contactIntelligenceDao()
        intelligence.upsertPerson(
            PersonEntity(
                personId = id,
                canonicalContactId = id,
                displayName = value.displayName,
                normalizedName = value.normalizedName,
                kind = "CONTACT",
                status = "ACTIVE",
                createdAtEpochMs = existing?.createdAtEpochMs ?: nowEpochMs,
                updatedAtEpochMs = nowEpochMs,
            ),
        )
        intelligence.supersedeUserClaims(id, nowEpochMs)
        intelligence.supersedeUserSourceIdentities(id, nowEpochMs)
        val values = userIdentityValues(value)
        values.filter(UserIdentityValue::isAddressableIdentity).forEach { identity ->
            val sourceIdentityId = identity.sourceIdentityId(id)
            val previous = intelligence.findSourceIdentity(sourceIdentityId)
            intelligence.upsertSourceIdentity(identity.toSourceIdentity(id, previous?.firstObservedAtEpochMs ?: nowEpochMs, nowEpochMs))
        }
        values.forEach { identity ->
            intelligence.upsertClaim(identity.toClaim(id, nowEpochMs))
        }
        upsertUserTemporalEmployment(intelligence, id, value, existing, nowEpochMs)
    }

    private suspend fun upsertUserTemporalEmployment(
        intelligence: com.zhiban.rebuild.data.contact.ContactIntelligenceDao,
        id: String,
        value: ContactEntity,
        existing: ContactEntity?,
        nowEpochMs: Long,
    ) {
        val previous = intelligence.findCurrentUserEmployment(id)
        if (existing?.company != value.company) intelligence.endCurrentUserEmployments(id, nowEpochMs)
        value.company?.let { company ->
            val organizationId = stableContactKnowledgeId("organization", "NAME", company.lowercase())
            val episodeId = previous?.episodeId.takeIf { existing?.company == company }
                ?: stableContactKnowledgeId(id, "USER_EMPLOYMENT", "$organizationId:$nowEpochMs")
            intelligence.upsertEmployment(
                PersonEmploymentEpisodeEntity(
                    episodeId = episodeId,
                    personId = id,
                    organizationId = organizationId,
                    companyNameSnapshot = company,
                    department = null,
                    title = value.title,
                    validFromEpochMs = previous?.validFromEpochMs.takeIf { existing?.company == company } ?: nowEpochMs,
                    validToEpochMs = null,
                    temporalPrecision = "DAY",
                    currentState = "CURRENT",
                    sourceRef = "USER_PROFILE",
                    confidence = 1.0,
                    verificationState = "USER_CONFIRMED",
                    status = "ACTIVE",
                    recordedAtEpochMs = previous?.recordedAtEpochMs.takeIf { existing?.company == company } ?: nowEpochMs,
                    updatedAtEpochMs = nowEpochMs,
                ),
            )
        }
    }

    private fun userIdentityValues(value: ContactEntity): List<UserIdentityValue> = buildList {
        add(UserIdentityValue("NAME", value.displayName, value.normalizedName))
        value.phone?.let { phone -> add(UserIdentityValue("PHONE", phone, normalizeContactPhone(phone) ?: phone)) }
        value.email?.let { email -> add(UserIdentityValue("EMAIL", email, email.trim().lowercase())) }
        value.wechatId?.let { handle -> add(UserIdentityValue("WECHAT", handle, handle.trim().lowercase())) }
        value.company?.let { company -> add(UserIdentityValue("COMPANY", company, company.trim().lowercase())) }
        value.title?.let { title -> add(UserIdentityValue("TITLE", title, title.trim().lowercase())) }
    }

    private fun UserIdentityValue.sourceIdentityId(personId: String) = stableContactKnowledgeId("user-source", personId, type, normalized)

    private fun UserIdentityValue.toSourceIdentity(personId: String, firstObservedAtEpochMs: Long, nowEpochMs: Long) = SourceIdentityEntity(
        sourceIdentityId = sourceIdentityId(personId),
        personId = personId,
        sourceType = type,
        accountScope = "USER_CONFIRMED",
        tenantId = null,
        stableExternalId = normalized.takeIf { type in ADDRESSABLE_IDENTITY_TYPES },
        visibleHandle = display,
        normalizedHandle = normalized,
        conversationScopeId = null,
        resolutionStatus = "RESOLVED",
        confidence = 1.0,
        sourceRef = "USER_PROFILE",
        firstObservedAtEpochMs = firstObservedAtEpochMs,
        lastObservedAtEpochMs = nowEpochMs,
    )

    private fun UserIdentityValue.toClaim(personId: String, nowEpochMs: Long) = IdentityClaimEntity(
        claimId = stableContactKnowledgeId("user-claim", personId, type, normalized, nowEpochMs.toString()),
        personId = personId,
        fieldType = type,
        displayValue = display,
        normalizedValue = normalized,
        validFromEpochMs = nowEpochMs,
        validToEpochMs = null,
        temporalPrecision = "DAY",
        recordedAtEpochMs = nowEpochMs,
        sourceIdentityId = sourceIdentityId(personId).takeIf { isAddressableIdentity() },
        sourceRef = "USER_PROFILE",
        confidence = 1.0,
        verificationState = "USER_CONFIRMED",
        supersedesClaimId = null,
        status = "ACTIVE",
    )

    private data class UserIdentityValue(val type: String, val display: String, val normalized: String) {
        fun isAddressableIdentity(): Boolean = type in ADDRESSABLE_IDENTITY_TYPES
    }

    /**
     * Persists only the rows explicitly selected in the import preview.
     *
     * Re-import is idempotent by Android lookup key, then by normalized phone.
     * A phone matching the user's encrypted profile is never created as a
     * regular contact.
     */
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
    fun observeAllTemporalEmployments() = database.contactIntelligenceDao().observeAllEmployments()
    fun observeUnresolvedSourceIdentities() = database.contactIntelligenceDao().observeUnresolvedIdentities()
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

    internal fun chooseCanonicalContact(first: ContactEntity, second: ContactEntity): Pair<ContactEntity, ContactEntity> {
        val ordered = listOf(first, second).sortedWith(
            compareByDescending<ContactEntity> { it.canonicalPreferenceScore() }
                .thenBy(ContactEntity::createdAtEpochMs)
                .thenBy(ContactEntity::contactId),
        )
        return ordered.first() to ordered.last()
    }

    private fun ContactEntity.canonicalPreferenceScore(): Int {
        val sourceScore = when {
            source == "USER" -> 20
            source.startsWith("SYSTEM_CONTACT") -> 10
            else -> 0
        }
        return sourceScore + listOf(phone, email, wechatId, company, title, note, avatarUri).count { !it.isNullOrBlank() }
    }

    internal fun String?.cleanContactField(): String? = this?.trim()?.takeIf(String::isNotEmpty)
    internal fun normalizeContactMethodHandle(raw: String): String = raw
        .trim()
        .trimStart('@')
        .lowercase()
        .filterNot(Char::isWhitespace)
    private fun normalizeIdentityValue(value: String): String = value.lowercase().filterNot(Char::isWhitespace).trimStart('@')

    private companion object {
        val ADDRESSABLE_IDENTITY_TYPES = setOf("PHONE", "EMAIL", "WECHAT")
    }
}
