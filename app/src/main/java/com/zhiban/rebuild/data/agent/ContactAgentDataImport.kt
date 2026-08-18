package com.zhiban.rebuild.data.agent

import androidx.room.withTransaction
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
import com.zhiban.rebuild.data.notification.MessageCollectionPreferences
import com.zhiban.rebuild.data.notification.NotificationCandidateEntity
import com.zhiban.rebuild.data.notification.NotificationInsightAnalyzer
import com.zhiban.rebuild.data.notification.ScheduleInsight
import com.zhiban.rebuild.relationship.RelationshipTaxonomy
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
import com.zhiban.rebuild.runtime.tool.changeIdFor
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

/**
 * 系统联系人导入与本地情报刷新的实现,从 ContactAgentDataRepository 拆出以守住 1000 有效行红线。
 * 全部以 ContactAgentDataRepository 扩展函数形式存在,内部状态经 internal val database 访问。
 */
private const val LOCAL_ENRICHMENT_TTL_MS = 30L * 24 * 60 * 60 * 1_000

internal suspend fun ContactAgentDataRepository.importConfirmedSystemContacts(
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
    val automaticallyMerged = applyDeterministicIdentityLinks(dao.listActiveForIntelligence(), nowEpochMs)
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
        automaticallyMerged = automaticallyMerged,
    )
}

internal data class SystemContactImportContext(
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

internal data class SystemContactEntityInput(
    val candidate: SystemContactCandidate,
    val name: String,
    val phones: List<String>,
    val wechats: List<String>,
    val source: String,
    val sourceMatch: ContactEntity?,
    val existing: ContactEntity?,
    val nowEpochMs: Long,
)

internal suspend fun ContactAgentDataRepository.processSystemContactCandidate(ctx: SystemContactImportContext, candidate: SystemContactCandidate) {
    val name = candidate.displayName.trim().take(100)
    val phones = candidate.phones.mapNotNull(::normalizeContactPhone).distinct()
    val emails = candidate.emails.mapNotNull { value ->
        value.trim().lowercase().takeIf { '@' in it && it.length <= 254 }
    }.distinct()
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
    } ?: emails.firstOrNull()?.let { normalized ->
        database.contactKnowledgeDao().findContactByMethod("EMAIL", normalized)
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
    database.upsertObservedSystemContactIntelligence(candidate, value, sourceRef, ctx.nowEpochMs)
}

internal fun ContactAgentDataRepository.buildSystemContactEntity(input: SystemContactEntityInput): ContactEntity = ContactEntity(
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

internal suspend fun ContactAgentDataRepository.upsertSystemContactMethods(
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
                    userConfirmed = false,
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
                        userConfirmed = false,
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
                        userConfirmed = false,
                        verifiedAtEpochMs = null,
                        createdAtEpochMs = nowEpochMs,
                        updatedAtEpochMs = nowEpochMs,
                    ),
                )
            }
    }
    if (methods.isNotEmpty()) knowledge.upsertMethods(methods)
}

internal suspend fun ContactAgentDataRepository.upsertSystemContactPlatformIdentities(
    candidate: SystemContactCandidate,
    value: ContactEntity,
    sourceRef: String,
    nowEpochMs: Long,
) {
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
                userConfirmed = false,
                createdAtEpochMs = nowEpochMs,
                updatedAtEpochMs = nowEpochMs,
            ),
        )
    }
}

internal suspend fun ContactAgentDataRepository.stageLocalOrganizationSuggestions(nowEpochMs: Long) {
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

internal suspend fun ContactAgentDataRepository.refreshLocalContactIntelligence(nowEpochMs: Long = System.currentTimeMillis()) = database.withTransaction {
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
    applyDeterministicIdentityLinks(contacts, nowEpochMs)
    stageLocalOrganizationSuggestions(nowEpochMs)
}

internal suspend fun ContactAgentDataRepository.applyDeterministicIdentityLinks(contacts: List<ContactEntity>, nowEpochMs: Long): Int {
    val identityDao = database.contactIdentityDao()
    var createdCount = 0
    ContactIdentityResolver.resolve(
        contacts = contacts,
        aliases = identityDao.listAliases(),
        platformIdentities = identityDao.listPlatformIdentities(),
    ).filter { it.decision == IdentityResolutionDecision.AUTO_LINK }
        .forEach { resolution ->
            val (canonical, source) = chooseCanonicalContact(resolution.first, resolution.second)
            if (identityDao.activeMergeLink(canonical.contactId) != null ||
                identityDao.activeMergeLink(source.contactId) != null ||
                identityDao.hasActiveSources(source.contactId) ||
                identityDao.mergeHistory(source.contactId) != null
            ) {
                return@forEach
            }
            val link = ContactMergeLinkEntity(
                sourceContactId = source.contactId,
                canonicalContactId = canonical.contactId,
                reason = resolution.reason,
                userConfirmed = false,
                createdAtEpochMs = nowEpochMs,
                undoneAtEpochMs = null,
            )
            val idempotencyKey = sha256("identity-link:${source.contactId}:${canonical.contactId}")
            if (database.changeLogDao().findByIdempotencyKey(idempotencyKey) != null) return@forEach
            identityDao.upsertMergeLink(link)
            database.insertVisibleAutoWrite(
                AutoWriteAuditDraft(
                    changeId = changeIdFor(idempotencyKey),
                    runtimeRunId = null,
                    toolName = AutoWriteToolNames.CONTACT_IDENTITY_AUTO_LINK,
                    idempotencyKey = idempotencyKey,
                    targetDomain = "CONTACT_MERGE",
                    targetId = source.contactId,
                    operation = "LINK",
                    afterDigest = sha256("${link.sourceContactId}:${link.canonicalContactId}:${link.createdAtEpochMs}"),
                    inversePayloadJson = buildJsonObject {
                        put("sourceContactId", JsonPrimitive(source.contactId))
                        put("canonicalContactId", JsonPrimitive(canonical.contactId))
                    }.toString(),
                    originType = "SYSTEM_PERCEPTION",
                    subjectContactId = canonical.contactId,
                    sourceType = "IDENTITY_RESOLVER",
                    sourceRef = resolution.reason,
                    confidence = resolution.confidence,
                    presentationType = "CONTACT_IDENTITY_LINK",
                    correctionRoute = "RELATION",
                    createdAtEpochMs = nowEpochMs,
                ),
            )
            createdCount++
        }
    return createdCount
}

internal suspend fun ContactAgentDataRepository.upsertSystemContactOrganization(
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
                userConfirmed = false,
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
                isCurrent = false,
                source = "SYSTEM_CONTACT",
                evidenceRef = sourceRef,
                confidence = 0.6,
                userConfirmed = false,
                createdAtEpochMs = nowEpochMs,
                updatedAtEpochMs = nowEpochMs,
            ),
        )
    }
}

internal suspend fun ContactAgentDataRepository.upsertSystemContactAddressesDatesAndFacet(
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
            userConfirmed = false,
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
                userConfirmed = false,
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
            userConfirmed = false,
            createdAtEpochMs = nowEpochMs,
            updatedAtEpochMs = nowEpochMs,
        ),
    )
}
