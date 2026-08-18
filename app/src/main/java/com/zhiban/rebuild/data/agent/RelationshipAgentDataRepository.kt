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
import com.zhiban.rebuild.data.contact.ContactImportantDateEntity
import com.zhiban.rebuild.data.contact.ContactMergeLinkEntity
import com.zhiban.rebuild.data.contact.ContactMethodEntity
import com.zhiban.rebuild.data.contact.ContactPlatformIdentityEntity
import com.zhiban.rebuild.data.contact.ContactRoleEntity
import com.zhiban.rebuild.data.contact.OrganizationEntity
import com.zhiban.rebuild.data.contact.OwnerContactLinkEntity
import com.zhiban.rebuild.data.contact.RelationshipEdgeEntity
import com.zhiban.rebuild.data.contact.RelationshipEpisodeEntity
import com.zhiban.rebuild.data.contact.RelationshipEventEntity
import com.zhiban.rebuild.data.contact.RelationshipEventParticipantEntity
import com.zhiban.rebuild.data.contact.RelationshipEventWithParticipants
import com.zhiban.rebuild.data.contact.RelationshipPersonIds
import com.zhiban.rebuild.data.contact.SystemContactCandidate
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
import com.zhiban.rebuild.foundation.sha256
import com.zhiban.rebuild.relationship.RelationshipTaxonomy
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import org.json.JSONObject

internal class RelationshipAgentDataRepository(private val database: AgentDatabase) {
    private val temporalRelationships = TemporalRelationshipWriter(database)

    fun observeRelationships(): Flow<List<RelationshipEdgeEntity>> = database.relationshipEdgeDao().observeActive()

    fun observeTemporalRelationships(): Flow<List<RelationshipEpisodeEntity>> = database.contactIntelligenceDao().observeAllRelationships()

    suspend fun saveConfirmedRelationship(
        fromContactId: String,
        toContactId: String,
        relationType: String,
        temporalState: String = "CURRENT",
        nowEpochMs: Long = System.currentTimeMillis(),
    ): String = database.withTransaction {
        require(fromContactId != toContactId) { "请选择两个不同的联系人" }
        require(relationType in RelationshipTaxonomy.selectableCodes) { "关系类型无效" }
        RelationshipTaxonomy.requireAllowedTemporalState(relationType, temporalState)
        require(
            fromContactId == RelationshipPersonIds.SELF ||
                database.contactDao().findById(fromContactId) != null,
        ) { "关系中的第一个人不存在" }
        require(
            toContactId == RelationshipPersonIds.SELF ||
                database.contactDao().findById(toContactId) != null,
        ) { "关系中的第二个人不存在" }
        val canonical = listOf(fromContactId, toContactId).sorted()
        val id = "user-edge-${canonical[0]}-${canonical[1]}-$relationType".take(220)
        database.relationshipEdgeDao().upsert(
            RelationshipEdgeEntity(
                edgeId = id,
                fromContactId = fromContactId,
                toContactId = toContactId,
                relationType = relationType,
                evidenceDigest = "USER_CONFIRMED",
                evidenceRefsJson = "[\"USER_PROFILE\"]",
                confidence = 1.0,
                userConfirmed = true,
                skillId = null,
                status = if (temporalState == "PAST") "HISTORICAL" else "ACTIVE",
                createdAtEpochMs = nowEpochMs,
                updatedAtEpochMs = nowEpochMs,
            ),
        )
        temporalRelationships.replaceEpisode(
            episodeKey = id,
            fromPersonId = fromContactId,
            toPersonId = toContactId,
            relationshipType = relationType,
            temporalState = temporalState,
            evidenceRefsJson = "[\"USER_PROFILE\"]",
            confidence = 1.0,
            verificationState = "USER_CONFIRMED",
            nowEpochMs = nowEpochMs,
        )
        id
    }

    suspend fun deleteConfirmedRelationship(edgeId: String, nowEpochMs: Long = System.currentTimeMillis()): Boolean = database.withTransaction {
        val current = database.relationshipEdgeDao().find(edgeId) ?: return@withTransaction false
        val deleted = database.relationshipEdgeDao().deleteConfirmed(edgeId) == 1
        if (deleted) {
            database.contactIntelligenceDao().closeOpenUserRelationships(
                current.fromContactId,
                current.toContactId,
                current.relationType,
                nowEpochMs,
            )
        }
        deleted
    }

    suspend fun updateConfirmedRelationship(edgeId: String, relationType: String, nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        database.withTransaction {
            require(relationType in RelationshipTaxonomy.selectableCodes) { "关系类型无效" }
            val current = database.relationshipEdgeDao().find(edgeId) ?: return@withTransaction false
            require(current.userConfirmed) { "只有你确认的关系可以修改" }
            database.relationshipEdgeDao().upsert(
                current.copy(
                    relationType = relationType,
                    evidenceDigest = "USER_CONFIRMED",
                    evidenceRefsJson = "[\"USER_PROFILE\"]",
                    confidence = 1.0,
                    updatedAtEpochMs = nowEpochMs,
                ),
            )
            database.contactIntelligenceDao().closeOpenUserRelationships(
                current.fromContactId,
                current.toContactId,
                current.relationType,
                nowEpochMs,
            )
            temporalRelationships.replaceEpisode(
                episodeKey = edgeId,
                fromPersonId = current.fromContactId,
                toPersonId = current.toContactId,
                relationshipType = relationType,
                temporalState = "CURRENT",
                evidenceRefsJson = "[\"USER_PROFILE\"]",
                confidence = 1.0,
                verificationState = "USER_CONFIRMED",
                nowEpochMs = nowEpochMs,
            )
            true
        }

    fun observeRelationshipEvents(): Flow<List<RelationshipEventWithParticipants>> = combine(
        database.relationshipEventDao().observeActive(),
        database.contactIdentityDao().observeActiveMergeLinks(),
        ::canonicalizeRelationshipEvents,
    )

    fun observeRelationshipEventsForContact(contactId: String): Flow<List<RelationshipEventWithParticipants>> = combine(
        database.relationshipEventDao().observeForContact(contactId),
        database.contactIdentityDao().observeActiveMergeLinks(),
        ::canonicalizeRelationshipEvents,
    )

    suspend fun saveConfirmedRelationshipEvent(
        eventId: String?,
        eventType: String,
        title: String,
        note: String?,
        occurredAtEpochMs: Long?,
        participants: List<RelationshipEventParticipantInput>,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): String = database.withTransaction {
        require(eventType in setOf("INTRODUCTION", "SHARED_PROJECT", "SHARED_EVENT", "FAMILY_MILESTONE", "OTHER")) {
            "经历类型无效"
        }
        require(title.isNotBlank() && title.trim().length <= 100) { "标题应为 1-100 个字" }
        require(participants.size >= 2) { "至少需要两位参与者" }
        require(participants.any { it.participantKind == "CONTACT" }) { "至少需要一位联系人" }
        require(participants.any { it.participantKind == "USER" }) { "经历需要包含你" }
        participants.filter { it.participantKind == "CONTACT" }.forEach {
            require(!it.contactId.isNullOrBlank() && database.contactDao().findById(it.contactId) != null) {
                "参与联系人不存在"
            }
        }
        val id = eventId ?: "relationship-event-${UUID.randomUUID()}"
        val current = eventId?.let { database.relationshipEventDao().findEvent(it) }
        database.relationshipEventDao().upsertEvent(
            RelationshipEventEntity(
                eventId = id,
                eventType = eventType,
                title = title.trim(),
                note = note?.trim()?.takeIf(String::isNotEmpty),
                occurredAtEpochMs = occurredAtEpochMs,
                evidenceDigest = "USER_CONFIRMED",
                evidenceRefsJson = "[\"USER_PROFILE\"]",
                userConfirmed = true,
                status = "ACTIVE",
                createdAtEpochMs = current?.createdAtEpochMs ?: nowEpochMs,
                updatedAtEpochMs = nowEpochMs,
            ),
        )
        database.relationshipEventDao().deleteParticipants(id)
        database.relationshipEventDao().upsertParticipants(
            participants.distinctBy {
                "${it.participantKind}:${it.contactId}:${it.participantRole}"
            }.mapIndexed { index, participant ->
                RelationshipEventParticipantEntity(
                    participantId = "$id-participant-$index",
                    eventId = id,
                    participantKind = participant.participantKind,
                    contactId = participant.contactId,
                    participantRole = participant.participantRole,
                    displayNameSnapshot = participant.displayName.trim().ifBlank {
                        if (participant.participantKind ==
                            "USER"
                        ) {
                            "我"
                        } else {
                            "联系人"
                        }
                    },
                    createdAtEpochMs = nowEpochMs,
                )
            },
        )
        id
    }

    suspend fun deleteConfirmedRelationshipEvent(eventId: String): Boolean = database.relationshipEventDao().deleteConfirmed(eventId) == 1
}
