package com.zhiban.rebuild.data.agent

import androidx.room.withTransaction
import com.zhiban.rebuild.data.calendar.ExternalCalendarConflictSource
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
import com.zhiban.rebuild.data.contact.GroupConversationEntity
import com.zhiban.rebuild.data.contact.GroupMembershipEpisodeEntity
import com.zhiban.rebuild.data.contact.OrganizationEntity
import com.zhiban.rebuild.data.contact.OwnerContactLinkEntity
import com.zhiban.rebuild.data.contact.PersonEmploymentEpisodeEntity
import com.zhiban.rebuild.data.contact.RelationshipEdgeEntity
import com.zhiban.rebuild.data.contact.RelationshipEpisodeEntity
import com.zhiban.rebuild.data.contact.RelationshipEventEntity
import com.zhiban.rebuild.data.contact.RelationshipEventParticipantEntity
import com.zhiban.rebuild.data.contact.RelationshipEventWithParticipants
import com.zhiban.rebuild.data.contact.RelationshipPersonIds
import com.zhiban.rebuild.data.contact.SourceIdentityEntity
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
import com.zhiban.rebuild.data.notification.MessageCollectionPreferences
import com.zhiban.rebuild.data.notification.NotificationCandidateEntity
import com.zhiban.rebuild.data.notification.NotificationInsightAnalyzer
import com.zhiban.rebuild.data.notification.ScheduleInsight
import com.zhiban.rebuild.data.notification.SocialNotificationParser
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import org.json.JSONObject

data class RelationshipEventParticipantInput(val participantKind: String, val contactId: String?, val participantRole: String, val displayName: String)

data class ContactImportSummary(
    val created: Int,
    val updated: Int,
    val skippedSelf: Int,
    val skippedInvalid: Int,
    val skippedSelfName: String? = null,
    val skippedSelfPhone: String? = null,
    val skippedSelfWechat: String? = null,
    val selfIdentityMissing: Boolean = false,
    val automaticallyMerged: Int = 0,
) {
    val imported: Int get() = created + updated
}

class AgentDataRepository internal constructor(
    infrastructure: AgentRepositoryInfrastructure,
    domains: AgentRepositoryDomains,
    private val externalCalendarConflicts: ExternalCalendarConflictSource = ExternalCalendarConflictSource { _, _, _, _ -> emptyList() },
    private val scheduleReminderSink: ScheduleReminderSink = ScheduleReminderSink { },
) {
    private val daos = infrastructure.daos
    private val transactions = infrastructure.transactions
    private val factIndex = infrastructure.factIndex
    private val autoWriteSink = infrastructure.autoWriteSink
    private val calendar = domains.calendar
    private val crm = domains.crm
    private val contacts = domains.contacts
    private val relationships = domains.relationships
    fun observeNotificationCandidates(): Flow<List<NotificationCandidateEntity>> = combine(
        daos.notificationCandidateDao.observePending(),
        daos.contactIdentityDao.observeActiveMergeLinks(),
    ) { candidates, mergeLinks ->
        val canonicalBySource = mergeLinks.associate { it.sourceContactId to it.canonicalContactId }
        candidates.map { candidate ->
            candidate.copy(
                suggestedContactId = candidate.suggestedContactId?.let { canonicalBySource[it] ?: it },
                linkedContactId = candidate.linkedContactId?.let { canonicalBySource[it] ?: it },
            )
        }
    }

    suspend fun stageNotificationCandidate(candidate: NotificationCandidateEntity) {
        val nowEpochMs = System.currentTimeMillis()
        val externalConflict = hasExternalConflictForAutomaticSchedule(candidate)
        val automaticSchedule = transactions.runInTransaction {
            val existing = daos.notificationCandidateDao.findBySourceKey(candidate.sourceKey)
            if (existing?.status in setOf("CONFIRMED", "DISMISSED")) return@runInTransaction null
            var enriched = enrichNotificationCandidate(candidate)
            var automaticallyProcessed = false
            if (isLikelyReplyContext(enriched, nowEpochMs) &&
                recordInferredInteractionEvidence(enriched, nowEpochMs)
            ) {
                enriched = enriched.copy(linkedContactId = enriched.linkedContactId ?: enriched.suggestedContactId)
                automaticallyProcessed = true
            } else if (enriched.suggestedContactId != null && enriched.suggestedContactConfidence >= AUTO_LINK_CONFIDENCE &&
                recordAutoInteractionEvidence(enriched, enriched.suggestedContactId, nowEpochMs)
            ) {
                enriched = enriched.copy(linkedContactId = enriched.suggestedContactId)
                automaticallyProcessed = true
            }
            val createdSchedule = if (!externalConflict) {
                recordAutomaticSchedule(enriched, nowEpochMs)
            } else {
                null
            }
            if (createdSchedule != null) {
                enriched = enriched.copy(createdScheduleId = createdSchedule.id)
                automaticallyProcessed = true
            }
            if (automaticallyProcessed) {
                enriched = enriched.copy(status = enriched.completionStatus())
            }
            persistObservedCommunicationIdentity(enriched, nowEpochMs)
            daos.notificationCandidateDao.upsert(enriched)
            // A matched contact may become a CRM lead candidate, but never a formal lead without confirmation.
            enriched.suggestedContactId?.let { matchedContactId ->
                crm.suggestNewLeadFromNotification(matchedContactId, enriched.candidateId, nowEpochMs)
            }
            daos.notificationCandidateDao.clearExpiredOrDismissed(
                nowEpochMs - 30L * 24 * 60 * 60 * 1_000,
            )
            createdSchedule
        }
        automaticSchedule?.let(scheduleReminderSink::replace)
    }

    suspend fun dismissNotificationCandidate(candidateId: String): Boolean = daos.notificationCandidateDao.dismiss(candidateId) == 1

    suspend fun purgeNonPersonalSmsCandidates(): Int = transactions.runInTransaction {
        daos.notificationCandidateDao.deleteNonPersonalSmsCandidates() +
            daos.notificationCandidateDao.deleteUnsupportedLegacyNotificationCandidates()
    }

    suspend fun confirmNotificationCandidate(candidateId: String, contactId: String, nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        transactions.runInTransaction {
            val candidate = daos.notificationCandidateDao.find(candidateId) ?: return@runInTransaction false
            if (candidate.status != "PENDING" ||
                daos.contactDao.findById(contactId) == null
            ) {
                return@runInTransaction false
            }
            val originalEvidence = buildNotificationFactText(candidate)
            factIndex.upsert(
                FactEntity(
                    factId = "notification-evidence:${candidate.candidateId}",
                    factType = "CURRENT_MATTER",
                    // Evidence is immutable source data. Normalize only when projecting it for UI or
                    // retrieval so a display heuristic can never destroy the user's original message.
                    textContent = originalEvidence,
                    structuredDataJson = JSONObject()
                        .put("packageName", candidate.packageName)
                        .put("platform", candidate.platform)
                        .put("direction", candidate.direction)
                        .put("conversationTitle", candidate.conversationTitle)
                        .put("isGroupChat", candidate.isGroupChat)
                        .put("postedAtEpochMs", candidate.postedAtEpochMs)
                        .toString(),
                    sourceType = "USER_CONFIRMED_NOTIFICATION",
                    sourceRef = candidate.candidateId,
                    contactId = contactId,
                    skillId = null,
                    confidence = 1.0,
                    sensitivity = "PERSONAL",
                    status = "ACTIVE",
                    ttlDays = 0,
                    expiresAtEpochMs = null,
                    createdAtEpochMs = nowEpochMs,
                    updatedAtEpochMs = nowEpochMs,
                ),
            )
            persistConfirmedPlatformIdentity(candidate, contactId, nowEpochMs)
            persistObservedCommunicationIdentity(candidate.copy(linkedContactId = contactId), nowEpochMs)
            val updated = candidate.copy(
                linkedContactId = contactId,
                status = candidate.completionStatus(linkedContactId = contactId),
            )
            daos.notificationCandidateDao.upsert(updated)
            true
        }

    suspend fun confirmNotificationAsNewContact(candidateId: String, displayName: String, nowEpochMs: Long = System.currentTimeMillis()): String =
        transactions.runInTransaction {
            val candidate = daos.notificationCandidateDao.find(candidateId)
                ?: error("候选内容已不存在")
            require(candidate.status == "PENDING") { "这条内容已经处理" }
            val cleanName = displayName.trim().take(100)
            require(cleanName.isNotBlank()) { "联系人姓名不能为空" }
            val phone = candidate.senderName?.takeIf { candidate.platform == "SMS" }?.let(::normalizeContactPhone)
            val contactId = saveUserContact(
                contactId = null,
                displayName = cleanName,
                phone = phone,
                wechatId = null,
                company = null,
                title = null,
                tag = candidate.appLabel,
                note = "由你确认的${candidate.appLabel}消息建立",
                nowEpochMs = nowEpochMs,
            )
            check(confirmNotificationCandidate(candidateId, contactId, nowEpochMs))
            contactId
        }

    suspend fun confirmNotificationSchedule(candidateId: String, nowEpochMs: Long = System.currentTimeMillis()): String {
        val schedule = transactions.runInTransaction {
            val candidate = daos.notificationCandidateDao.find(candidateId)
                ?: error("候选内容已不存在")
            candidate.createdScheduleId?.let { existingId ->
                return@runInTransaction calendar.findSchedule(existingId) ?: error("已创建的日程不存在")
            }
            require(candidate.status == "PENDING") { "这条内容已经处理" }
            val insight = ScheduleInsight.from(candidate) ?: error("这条内容没有完整的日期和时间")
            require(insight.confidence >= 0.85) { "日程判断还不够明确" }
            require(insight.startAtEpochMs >= nowEpochMs - 5 * 60_000L) { "这个时间已经过去" }
            val scheduleId = "notification-schedule-${candidate.sourceKey.take(32)}"
            val source = candidate.senderName ?: candidate.conversationTitle
            val cleanTitle = resolveScheduleTitleFromCandidate(candidate, insight, source)
            saveUserSchedule(
                scheduleId = scheduleId,
                title = cleanTitle,
                startAtEpochMs = insight.startAtEpochMs,
                durationMinutes = insight.durationMinutes,
                note = buildString {
                    append("由").append(candidate.appLabel).append("消息确认添加")
                    candidate.senderName?.let { append(" · ").append(it) }
                },
                reminderMinutesBefore = insight.reminderMinutesBefore,
                nowEpochMs = nowEpochMs,
            )
            daos.notificationCandidateDao.upsert(
                candidate.copy(
                    createdScheduleId = scheduleId,
                    status = candidate.completionStatus(createdScheduleId = scheduleId),
                ),
            )
            calendar.findSchedule(scheduleId) ?: error("日程没有保存成功")
        }
        scheduleReminderSink.replace(schedule)
        return schedule.id
    }

    private fun resolveScheduleTitleFromCandidate(candidate: NotificationCandidateEntity, insight: ScheduleInsight, source: String?): String {
        val candidates = listOf(
            insight.title,
            candidate.body,
            candidate.title,
            candidate.conversationTitle,
        ).filterNotNull()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        for (candidateTitle in candidates) {
            val cleaned = NotificationInsightAnalyzer.sanitizeScheduleTitle(candidateTitle, source)
                .ifBlank { null }
            if (!cleaned.isNullOrBlank()) {
                return cleaned
            }
        }

        return "待确认安排"
    }

    private suspend fun enrichNotificationCandidate(candidate: NotificationCandidateEntity): NotificationCandidateEntity {
        if (candidate.suggestedContactId != null) return candidate
        val sender = candidate.senderName?.trim()?.takeIf(String::isNotBlank) ?: return candidate
        val normalized = normalizeIdentityValue(sender)
        val (contact, confidence) = if (candidate.platform == "SMS") {
            normalizeContactPhone(sender)?.let { phone ->
                daos.contactKnowledgeDao.findContactByMethod("PHONE", phone)
                    ?: daos.contactDao.findByPhone(phone)
            }?.let { it to 1.0 }
                ?: return candidate
        } else {
            daos.contactIdentityDao.findContactByPlatformHandle(candidate.platform, normalized)?.let { it to 1.0 }
                ?: daos.contactIdentityDao.findContactByAlias(normalized)?.takeIf {
                    daos.contactIdentityDao.countConfirmedContactsByAlias(normalized) == 1
                }?.let { it to AUTO_LINK_CONFIDENCE }
                ?: daos.contactDao.findByNormalizedName(normalized)?.takeIf {
                    daos.contactDao.countActiveByNormalizedName(normalized) == 1
                }?.let { it to AUTO_LINK_CONFIDENCE }
                ?: return candidate
        }
        return candidate.copy(
            suggestedContactId = contact.contactId,
            suggestedContactConfidence = confidence,
        )
    }

    private suspend fun hasExternalConflictForAutomaticSchedule(candidate: NotificationCandidateEntity): Boolean {
        val insight = ScheduleInsight.from(candidate) ?: return false
        if (!candidate.isEligibleForAutomaticSchedule(insight)) return false
        return try {
            externalCalendarConflicts.findConflicts(
                insight.startAtEpochMs,
                insight.startAtEpochMs + insight.durationMinutes * 60_000L,
                null,
                1,
            ).isNotEmpty()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Fail closed: inability to inspect the device calendar must never create a silent conflict.
            true
        }
    }

    private suspend fun recordAutomaticSchedule(candidate: NotificationCandidateEntity, nowEpochMs: Long): ScheduleEntity? {
        val insight = ScheduleInsight.from(candidate) ?: return null
        if (candidate.linkedContactId == null || !candidate.isEligibleForAutomaticSchedule(insight)) return null
        val policySpec = RuntimeToolSpec(
            AutoWriteToolNames.SCHEDULE_CREATE,
            1,
            RuntimeToolRisk.REVERSIBLE_AUTO_WRITE,
            "{}",
            1,
        )
        if (ActionPolicy().evaluate(
                policySpec,
                reversibleWriteReadiness = ReversibleWriteReadiness(true, true, true),
            ) != ActionDecision.AutoExecuteReversibleWrite
        ) {
            return null
        }
        val idempotencyKey = sha256("notification-schedule:${candidate.sourceKey}")
        daos.changeLogDao.findByIdempotencyKey(idempotencyKey)?.let { existing ->
            return calendar.findSchedule(existing.targetId)
        }
        if (calendar.findScheduleConflicts(insight.startAtEpochMs, insight.durationMinutes).isNotEmpty()) return null
        val scheduleId = "notification-schedule-${sha256(candidate.sourceKey).take(32)}"
        val source = candidate.senderName ?: candidate.conversationTitle
        calendar.saveUserSchedule(
            scheduleId = scheduleId,
            title = resolveScheduleTitleFromCandidate(candidate, insight, source),
            startAtEpochMs = insight.startAtEpochMs,
            durationMinutes = insight.durationMinutes,
            note = "知伴根据${candidate.appLabel}消息自动整理，可撤销",
            reminderMinutesBefore = insight.reminderMinutesBefore,
            nowEpochMs = nowEpochMs,
        )
        val schedule = calendar.findSchedule(scheduleId) ?: error("自动日程没有保存成功")
        autoWriteSink.insertVisible(
            AutoWriteAuditDraft(
                changeId = changeIdFor(idempotencyKey),
                runtimeRunId = null,
                toolName = AutoWriteToolNames.SCHEDULE_CREATE,
                idempotencyKey = idempotencyKey,
                targetDomain = "SCHEDULE",
                targetId = schedule.id,
                operation = "CREATE",
                afterDigest = canonicalChangeDigest(schedule),
                inversePayloadJson = "{\"deleteScheduleId\":\"${schedule.id}\"}",
                originType = "SYSTEM_PERCEPTION",
                subjectContactId = candidate.linkedContactId,
                sourceType = candidate.platform,
                sourceRef = candidate.sourceKey,
                confidence = insight.confidence,
                presentationType = "SCHEDULE_CREATE",
                correctionRoute = "CALENDAR",
                createdAtEpochMs = nowEpochMs,
            ),
        )
        return schedule
    }

    private fun NotificationCandidateEntity.isEligibleForAutomaticSchedule(insight: ScheduleInsight): Boolean = direction == "INCOMING" &&
        !isGroupChat &&
        insight.confidence >= AUTO_SCHEDULE_CONFIDENCE &&
        insight.startAtEpochMs >= postedAtEpochMs - 5 * 60_000L

    private suspend fun recordAutoInteractionEvidence(candidate: NotificationCandidateEntity, contactId: String, nowEpochMs: Long): Boolean {
        if (candidate.platform !in MessageCollectionPreferences.SUPPORTED_PLATFORMS) return false
        val sender = candidate.senderName.orEmpty().filter(Char::isDigit)
        val serviceSender = candidate.platform == "SMS" &&
            (sender.startsWith("106") || sender.startsWith("95") || sender.startsWith("96"))
        if (serviceSender || candidate.isGroupChat) return false
        val policySpec = RuntimeToolSpec(
            AutoWriteToolNames.INTERACTION_SUMMARY,
            1,
            RuntimeToolRisk.REVERSIBLE_AUTO_WRITE,
            "{}",
            1,
        )
        if (ActionPolicy().evaluate(
                policySpec,
                reversibleWriteReadiness = ReversibleWriteReadiness(true, true, true),
            ) != ActionDecision.AutoExecuteReversibleWrite
        ) {
            return false
        }
        val idempotencyKey = sha256("interaction:${candidate.sourceKey}")
        if (daos.changeLogDao.findByIdempotencyKey(idempotencyKey) != null) return true
        val factId = "notification-evidence:${sha256(candidate.sourceKey).take(32)}"
        val excerpt = normalizeMessageSnapshot(candidate.body.orEmpty()).take(120)
        val fact = FactEntity(
            factId = factId,
            factType = "INTERACTION_SUMMARY",
            textContent = buildString {
                append(candidate.appLabel).append("互动")
                candidate.senderName?.let { append(" · ").append(it) }
                append(if (candidate.direction == "OUTGOING") " · 我发出" else " · 对方发来")
                if (excerpt.isNotBlank()) append(" · 最近提到：").append(excerpt)
            }.take(240),
            structuredDataJson = JSONObject()
                .put("platform", candidate.platform)
                .put("conversationTitle", candidate.conversationTitle)
                .put("direction", candidate.direction)
                .put("isGroupChat", candidate.isGroupChat)
                .put("messageCount", 1)
                .put("firstObservedAtEpochMs", candidate.postedAtEpochMs)
                .put("lastObservedAtEpochMs", candidate.postedAtEpochMs)
                .toString(),
            sourceType = "OBSERVED_NOTIFICATION",
            sourceRef = candidate.candidateId,
            contactId = contactId,
            skillId = null,
            confidence = 1.0,
            sensitivity = "PERSONAL",
            status = "ACTIVE",
            ttlDays = 90,
            expiresAtEpochMs = nowEpochMs + 90L * 24 * 60 * 60 * 1_000,
            createdAtEpochMs = nowEpochMs,
            updatedAtEpochMs = nowEpochMs,
        )
        factIndex.upsert(fact)
        autoWriteSink.insertVisible(
            AutoWriteAuditDraft(
                changeId = changeIdFor(idempotencyKey),
                runtimeRunId = null,
                toolName = AutoWriteToolNames.INTERACTION_SUMMARY,
                idempotencyKey = idempotencyKey,
                targetDomain = "FACT",
                targetId = factId,
                operation = "CREATE",
                afterDigest = canonicalChangeDigest(fact),
                inversePayloadJson = "{\"deleteFactId\":\"$factId\"}",
                originType = "SYSTEM_PERCEPTION",
                subjectContactId = contactId,
                sourceType = candidate.platform,
                sourceRef = candidate.sourceKey,
                confidence = candidate.suggestedContactConfidence,
                presentationType = "INTERACTION_SUMMARY",
                correctionRoute = "CONTACT_PICKER",
                createdAtEpochMs = nowEpochMs,
            ),
        )
        return true
    }

    private suspend fun isLikelyReplyContext(candidate: NotificationCandidateEntity, nowEpochMs: Long): Boolean {
        val contactId = candidate.linkedContactId ?: candidate.suggestedContactId ?: return false
        if (candidate.platform !in MessageCollectionPreferences.SUPPORTED_PLATFORMS) return false
        if (candidate.direction != "INCOMING") {
            return false
        }
        if (candidate.isGroupChat) {
            return false
        }
        if (!SocialNotificationParser.likelyReplySignal(candidate.body)) {
            return false
        }
        val afterEpochMs = candidate.postedAtEpochMs - SocialNotificationParser.REPLY_FOCUS_WINDOW_MS
        val byContact = daos.notificationCandidateDao.hasRecentOutgoingByContact(
            candidate.platform,
            contactId,
            afterEpochMs,
        )
        if (byContact) {
            return true
        }
        val conversationTitle = candidate.conversationTitle?.trim()?.takeIf(String::isNotBlank) ?: return false
        val byConversation = daos.notificationCandidateDao.hasRecentOutgoingByConversation(
            candidate.platform,
            conversationTitle,
            afterEpochMs,
        )
        return byConversation
    }

    private suspend fun recordInferredInteractionEvidence(candidate: NotificationCandidateEntity, nowEpochMs: Long): Boolean {
        val contactId = candidate.linkedContactId ?: candidate.suggestedContactId ?: return false
        val counterParty = daos.contactDao.findRawById(contactId)?.displayName
            ?: candidate.senderName
            ?: candidate.conversationTitle
            ?: "对方"
        val policySpec = RuntimeToolSpec(
            AutoWriteToolNames.INTERACTION_SUMMARY,
            1,
            RuntimeToolRisk.REVERSIBLE_AUTO_WRITE,
            "{}",
            1,
        )
        if (ActionPolicy().evaluate(
                policySpec,
                reversibleWriteReadiness = ReversibleWriteReadiness(true, true, true),
            ) != ActionDecision.AutoExecuteReversibleWrite
        ) {
            return false
        }
        val idempotencyKey = sha256("inferred-interaction:${candidate.sourceKey}")
        if (daos.changeLogDao.findByIdempotencyKey(idempotencyKey) != null) {
            return true
        }
        val factId = "notification-inferred:${sha256(candidate.sourceKey).take(32)}"
        val fact = FactEntity(
            factId = factId,
            factType = "INTERACTION_SUMMARY",
            textContent = "推断你与 $counterParty 在 ${
                candidate.conversationTitle ?: "该会话"
            } 中有一次回复沟通".take(80),
            structuredDataJson = JSONObject()
                .put("platform", candidate.platform)
                .put("conversationTitle", candidate.conversationTitle)
                .put("direction", candidate.direction)
                .put("isGroupChat", candidate.isGroupChat)
                .put("messageCount", 1)
                .put("inferred", true)
                .put("firstObservedAtEpochMs", candidate.postedAtEpochMs)
                .put("lastObservedAtEpochMs", candidate.postedAtEpochMs)
                .toString(),
            sourceType = "INFERRED_NOTIFICATION",
            sourceRef = candidate.candidateId,
            contactId = contactId,
            skillId = null,
            confidence = 0.6,
            sensitivity = "PERSONAL",
            status = "ACTIVE",
            ttlDays = 90,
            expiresAtEpochMs = nowEpochMs + 90L * 24 * 60 * 60 * 1_000,
            createdAtEpochMs = nowEpochMs,
            updatedAtEpochMs = nowEpochMs,
        )
        factIndex.upsert(fact)
        autoWriteSink.insertVisible(
            AutoWriteAuditDraft(
                changeId = changeIdFor(idempotencyKey),
                runtimeRunId = null,
                toolName = AutoWriteToolNames.INTERACTION_SUMMARY,
                idempotencyKey = idempotencyKey,
                targetDomain = "FACT",
                targetId = factId,
                operation = "CREATE",
                afterDigest = canonicalChangeDigest(fact),
                inversePayloadJson = "{\"deleteFactId\":\"$factId\"}",
                originType = "SYSTEM_PERCEPTION",
                subjectContactId = contactId,
                sourceType = candidate.platform,
                sourceRef = candidate.sourceKey,
                confidence = 0.6,
                presentationType = "INTERACTION_SUMMARY",
                correctionRoute = "CONTACT_PICKER",
                createdAtEpochMs = nowEpochMs,
            ),
        )
        return true
    }

    private fun normalizeContactFactForDisplay(sourceType: String, factType: String, value: String): String =
        ContactFactDisplayNormalizer.normalize(sourceType, factType, value)

    private fun buildNotificationFactText(candidate: NotificationCandidateEntity): String {
        val speaker = if (candidate.direction == "OUTGOING") "你" else "对方"
        val counterpart = candidate.senderName ?: candidate.conversationTitle
        val source = candidate.appLabel.ifBlank { "消息记录" }
        val rawMessage = (candidate.body?.ifBlank { null } ?: candidate.title).orEmpty().trim()
        return buildString {
            append(speaker)
            if (!counterpart.isNullOrBlank()) {
                append("（").append(counterpart).append("）")
            }
            append(" · ").append(source).append("说")
            if (rawMessage.isNotBlank()) {
                append("：").append(rawMessage)
            } else {
                append("：").append("未提取到结构化内容")
            }
        }
    }

    private fun normalizeMessageSnapshot(raw: String): String = raw
        .trim()
        .replace(Regex("""^\s*(?:请|帮我|麻烦|先|先给我)\s*"""), "")
        .let { rawMessage: String ->
            NotificationInsightAnalyzer.normalizeConversationSnippet(rawMessage)
        }
        .replace(Regex("""^\s*和(?:我|你|他|她|它|对方)\s*"""), "")
        .replace(Regex("""\s*[:：]\s*"""), "：")
        .replace(Regex("""^([“"'])|([“"'])$"""), "")
        .replace(Regex("""\s{2,}"""), " ")
        .trim()
        .let { if (it.isBlank()) "未提取到具体内容" else it }

    private suspend fun persistConfirmedPlatformIdentity(candidate: NotificationCandidateEntity, contactId: String, nowEpochMs: Long) {
        val sender = candidate.senderName?.trim()?.takeIf(String::isNotBlank) ?: return
        val normalized = normalizeIdentityValue(sender)
        if (candidate.platform == "SMS") return
        val id = "identity-$contactId-${candidate.platform}-${normalized.hashCode().toUInt().toString(16)}"
        daos.contactIdentityDao.upsertPlatformIdentity(
            ContactPlatformIdentityEntity(
                identityId = id,
                contactId = contactId,
                platform = candidate.platform,
                handle = sender,
                normalizedHandle = normalized,
                platformUserId = null,
                source = "USER_CONFIRMED_MESSAGE",
                userConfirmed = true,
                createdAtEpochMs = nowEpochMs,
                updatedAtEpochMs = nowEpochMs,
            ),
        )
    }

    private suspend fun persistObservedCommunicationIdentity(candidate: NotificationCandidateEntity, nowEpochMs: Long) {
        if (candidate.platform == "OTHER") return
        val visibleHandle = candidate.senderName?.trim()?.takeIf(String::isNotBlank)
            ?: candidate.conversationTitle?.trim()?.takeIf(String::isNotBlank)
            ?: return
        val normalizedHandle = if (candidate.platform == "SMS") {
            normalizeContactPhone(visibleHandle) ?: return
        } else {
            normalizeIdentityValue(visibleHandle)
        }
        if (normalizedHandle.isBlank()) return
        val personId = candidate.linkedContactId?.takeIf { daos.contactIntelligenceDao.findPerson(it) != null }
        val groupScope = candidate.conversationTitle?.takeIf { candidate.isGroupChat }?.let(::normalizeIdentityValue)
        val sourceIdentityId = stableContactKnowledgeId(
            "communication-source",
            candidate.platform,
            groupScope ?: "DIRECT",
            normalizedHandle,
        )
        val existing = daos.contactIntelligenceDao.findSourceIdentity(sourceIdentityId)
        daos.contactIntelligenceDao.upsertSourceIdentity(
            SourceIdentityEntity(
                sourceIdentityId = sourceIdentityId,
                personId = personId ?: existing?.personId,
                sourceType = candidate.platform,
                accountScope = "DEVICE_OBSERVED",
                tenantId = null,
                stableExternalId = normalizedHandle.takeIf { candidate.platform == "SMS" },
                visibleHandle = visibleHandle,
                normalizedHandle = normalizedHandle,
                conversationScopeId = groupScope,
                resolutionStatus = if (personId != null || existing?.personId != null) "RESOLVED" else "UNRESOLVED",
                confidence = if (personId != null) {
                    1.0
                } else if (candidate.platform == "SMS") {
                    0.9
                } else {
                    0.55
                },
                sourceRef = candidate.candidateId,
                firstObservedAtEpochMs = existing?.firstObservedAtEpochMs ?: nowEpochMs,
                lastObservedAtEpochMs = nowEpochMs,
            ),
        )
        if (candidate.isGroupChat) {
            persistObservedGroupMembership(candidate, sourceIdentityId, visibleHandle, nowEpochMs)
        }
    }

    private suspend fun persistObservedGroupMembership(
        candidate: NotificationCandidateEntity,
        sourceIdentityId: String,
        visibleHandle: String,
        nowEpochMs: Long,
    ) {
        val title = candidate.conversationTitle?.trim()?.takeIf(String::isNotBlank) ?: return
        val groupId = stableContactKnowledgeId("observed-group", candidate.platform, normalizeIdentityValue(title))
        val existing = daos.contactIntelligenceDao.findGroup(groupId)
        daos.contactIntelligenceDao.upsertGroup(
            GroupConversationEntity(
                groupId = groupId,
                platform = candidate.platform,
                accountScope = "DEVICE_OBSERVED",
                stableGroupId = null,
                displayName = title,
                sourceRef = candidate.candidateId,
                firstObservedAtEpochMs = existing?.firstObservedAtEpochMs ?: nowEpochMs,
                lastObservedAtEpochMs = nowEpochMs,
            ),
        )
        daos.contactIntelligenceDao.upsertGroupMembership(
            GroupMembershipEpisodeEntity(
                membershipId = stableContactKnowledgeId("group-member", groupId, sourceIdentityId),
                groupId = groupId,
                sourceIdentityId = sourceIdentityId,
                groupAlias = visibleHandle,
                validFromEpochMs = null,
                validToEpochMs = null,
                status = "ACTIVE",
                confidence = 0.55,
                sourceRef = candidate.candidateId,
                recordedAtEpochMs = nowEpochMs,
            ),
        )
    }

    private fun NotificationCandidateEntity.completionStatus(
        linkedContactId: String? = this.linkedContactId,
        createdScheduleId: String? = this.createdScheduleId,
    ): String {
        val contactDone = senderName.isNullOrBlank() || linkedContactId != null
        val scheduleDone = ScheduleInsight.from(this) == null || createdScheduleId != null
        return if (contactDone && scheduleDone) "CONFIRMED" else "PENDING"
    }

    fun observeSchedules(fromEpochMs: Long, toEpochMs: Long): Flow<List<ScheduleProjection>> = calendar.observeSchedules(fromEpochMs, toEpochMs)
    suspend fun findSchedule(scheduleId: String): ScheduleEntity? = calendar.findSchedule(scheduleId)
    suspend fun saveUserSchedule(
        scheduleId: String?,
        title: String,
        startAtEpochMs: Long,
        durationMinutes: Int,
        note: String?,
        reminderMinutesBefore: Int?,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): String = calendar.saveUserSchedule(
        scheduleId,
        title,
        startAtEpochMs,
        durationMinutes,
        note,
        reminderMinutesBefore,
        nowEpochMs,
    )
    suspend fun deleteSchedule(scheduleId: String): Boolean = calendar.deleteSchedule(scheduleId)

    fun observeCrmLeads(): Flow<List<CrmLeadEntity>> = crm.observeCrmLeads()
    fun observeCrmCandidateLeads(): Flow<List<CrmLeadEntity>> = crm.observeCrmCandidateLeads()
    fun observeCrmOpportunities(): Flow<List<CrmOpportunityEntity>> = crm.observeCrmOpportunities()
    fun observeCrmOpportunity(opportunityId: String): Flow<CrmOpportunityEntity?> = crm.observeCrmOpportunity(opportunityId)
    fun observeCrmStakeholders(opportunityId: String): Flow<List<CrmOpportunityStakeholderEntity>> = crm.observeCrmStakeholders(opportunityId)
    fun observeCrmActivities(opportunityId: String): Flow<List<CrmActivityEntity>> = crm.observeCrmActivities(opportunityId)
    fun observeCrmPendingActions(): Flow<List<CrmNextActionEntity>> = crm.observeCrmPendingActions()
    fun observeCrmDashboardCounts(sinceEpochMs: Long): Flow<Pair<Int, Int>> = crm.observeCrmDashboardCounts(sinceEpochMs)
    fun observeCrmActions(opportunityId: String): Flow<List<CrmNextActionEntity>> = crm.observeCrmActions(opportunityId)
    fun observeCrmPendingSuggestions(): Flow<List<CrmAgentSuggestionEntity>> = crm.observeCrmPendingSuggestions()
    fun observeCrmSuggestions(opportunityId: String): Flow<List<CrmAgentSuggestionEntity>> = crm.observeCrmSuggestions(opportunityId)
    fun observeCrmStageHistory(opportunityId: String): Flow<List<CrmStageHistoryEntity>> = crm.observeCrmStageHistory(opportunityId)
    fun observeCrmOpportunitiesByContact(contactId: String): Flow<List<CrmOpportunityEntity>> = crm.observeCrmOpportunitiesByContact(contactId)
    suspend fun suggestCallFollowUpActivity(
        contactId: String,
        callRecordId: String,
        durationSeconds: Long,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Boolean = crm.suggestCallFollowUpActivity(contactId, callRecordId, durationSeconds, nowEpochMs)
    suspend fun acceptCallFollowUpSuggestion(suggestionId: String, nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        crm.acceptCallFollowUpSuggestion(suggestionId, nowEpochMs)
    suspend fun suggestNewLeadFromNotification(contactId: String, candidateId: String, nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        crm.suggestNewLeadFromNotification(contactId, candidateId, nowEpochMs)
    suspend fun acceptNewLeadSuggestion(suggestionId: String, nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        crm.acceptNewLeadSuggestion(suggestionId, nowEpochMs)
    suspend fun createLeadForContactIfAbsent(contactId: String, sourceRef: String?, nowEpochMs: Long = System.currentTimeMillis()): String? =
        crm.createLeadForContactIfAbsent(contactId, sourceRef, nowEpochMs)
    suspend fun setCrmActionCompleted(actionId: String, completed: Boolean): Boolean = crm.setCrmActionCompleted(actionId, completed)
    suspend fun setCrmSuggestionStatus(suggestionId: String, accepted: Boolean): Boolean = crm.setCrmSuggestionStatus(suggestionId, accepted)
    suspend fun updateCrmOpportunityStage(opportunityId: String, newStage: String, reason: String, nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        crm.updateCrmOpportunityStage(opportunityId, newStage, reason, nowEpochMs)
    suspend fun clearLegacyCrmDemoData(nowEpochMs: Long = System.currentTimeMillis()): CrmDemoCleanupSummary =
        crm.clearLegacyCrmDemoData(nowEpochMs = nowEpochMs)
    suspend fun qualifyCrmLead(leadId: String, nowEpochMs: Long = System.currentTimeMillis()): Boolean = crm.qualifyCrmLead(leadId, nowEpochMs)
    suspend fun disqualifyCrmLead(leadId: String, nowEpochMs: Long = System.currentTimeMillis()): Boolean = crm.disqualifyCrmLead(leadId, nowEpochMs)
    suspend fun convertLeadToOpportunity(leadId: String, input: CrmLeadConversionInput, nowEpochMs: Long = System.currentTimeMillis()): String? =
        crm.convertLeadToOpportunity(leadId, input, nowEpochMs)

    suspend fun importConfirmedSystemCalendarEvents(events: List<SystemCalendarEvent>, nowEpochMs: Long = System.currentTimeMillis()): CalendarImportSummary =
        calendar.importConfirmedSystemCalendarEvents(events, nowEpochMs)
    suspend fun findScheduleConflicts(startAtEpochMs: Long, durationMinutes: Int, excludeScheduleId: String? = null): List<ScheduleProjection> =
        calendar.findScheduleConflicts(startAtEpochMs, durationMinutes, excludeScheduleId)

    fun observeRawContacts(): Flow<List<ContactEntity>> = contacts.observeRawContacts()
    fun observeContactRoles(): Flow<List<ContactRoleEntity>> = contacts.observeContactRoles()
    suspend fun confirmContactRole(contactId: String, roleType: String, skillId: String, nowEpochMs: Long = System.currentTimeMillis()) =
        contacts.confirmContactRole(contactId, roleType, skillId, nowEpochMs)
    suspend fun removeContactRole(contactId: String, roleType: String, skillId: String): Boolean = contacts.removeContactRole(contactId, roleType, skillId)
    fun observeContacts(): Flow<List<ContactEntity>> = contacts.observeContacts()
    fun observeAllContactImportantDates() = contacts.observeAllContactImportantDates()
    fun observeAllTemporalEmployments() = contacts.observeAllTemporalEmployments()
    fun observeUnresolvedSourceIdentities() = contacts.observeUnresolvedSourceIdentities()
    fun observeOwnerContactLinks(): Flow<List<OwnerContactLinkEntity>> = contacts.observeOwnerContactLinks()
    suspend fun saveOwnerCurrentEmployment(company: String, title: String?, nowEpochMs: Long = System.currentTimeMillis()): PersonEmploymentEpisodeEntity =
        contacts.saveOwnerCurrentEmployment(company, title, nowEpochMs)
    suspend fun confirmContactIsOwner(contactId: String, nowEpochMs: Long = System.currentTimeMillis()) = contacts.confirmContactIsOwner(contactId, nowEpochMs)
    suspend fun undoContactIsOwner(contactId: String, nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        contacts.undoContactIsOwner(contactId, nowEpochMs)
    fun observeContactAliases(): Flow<List<ContactAliasEntity>> = contacts.observeContactAliases()
    fun observeContactPlatformIdentities(): Flow<List<ContactPlatformIdentityEntity>> = contacts.observeContactPlatformIdentities()
    fun observeContactMergeLinks(): Flow<List<ContactMergeLinkEntity>> = contacts.observeContactMergeLinks()
    suspend fun confirmContactMerge(canonicalContactId: String, sourceContactId: String, reason: String, nowEpochMs: Long = System.currentTimeMillis()) =
        contacts.confirmContactMerge(canonicalContactId, sourceContactId, reason, nowEpochMs)
    suspend fun undoContactMerge(sourceContactId: String, nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        contacts.undoContactMerge(sourceContactId, nowEpochMs)
    suspend fun addContactAlias(contactId: String, alias: String, aliasType: String = "USER_ALIAS", nowEpochMs: Long = System.currentTimeMillis()): String =
        contacts.addContactAlias(contactId, alias, aliasType, nowEpochMs)
    suspend fun addContactPlatformIdentity(contactId: String, platform: String, handle: String, nowEpochMs: Long = System.currentTimeMillis()): String =
        contacts.addContactPlatformIdentity(contactId, platform, handle, nowEpochMs)
    suspend fun deleteContactAlias(aliasId: String): Boolean = contacts.deleteContactAlias(aliasId)
    suspend fun deleteContactPlatformIdentity(identityId: String): Boolean = contacts.deleteContactPlatformIdentity(identityId)
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
    ): String = contacts.saveUserContact(contactId, displayName, phone, wechatId, company, title, tag, note, nowEpochMs)
    suspend fun importConfirmedSystemContacts(
        contacts: List<SystemContactCandidate>,
        ownerPhone: String?,
        ownerWechatId: String?,
        ownerName: String?,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): ContactImportSummary = this.contacts.importConfirmedSystemContacts(
        contacts,
        ownerPhone,
        ownerWechatId,
        ownerName,
        nowEpochMs,
    )
    suspend fun deleteUserContact(contactId: String): Boolean = contacts.deleteUserContact(contactId)
    fun observeContactMethods(contactId: String) = contacts.observeContactMethods(contactId)
    fun observeContactEmployments(contactId: String) = contacts.observeContactEmployments(contactId)
    fun observeContactAddresses(contactId: String) = contacts.observeContactAddresses(contactId)
    fun observeContactImportantDates(contactId: String) = contacts.observeContactImportantDates(contactId)
    fun observeContactFacets(contactId: String) = contacts.observeContactFacets(contactId)
    fun observePendingContactEnrichment(contactId: String) = contacts.observePendingContactEnrichment(contactId)
    fun observeAllPendingContactEnrichment() = contacts.observeAllPendingContactEnrichment()
    suspend fun refreshLocalContactIntelligence() = contacts.refreshLocalContactIntelligence()
    suspend fun stageContactEnrichmentCandidate(candidate: ContactEnrichmentCandidateEntity) = contacts.stageContactEnrichmentCandidate(candidate)
    suspend fun resolveContactEnrichmentCandidate(candidateId: String, accepted: Boolean): Boolean =
        contacts.resolveContactEnrichmentCandidate(candidateId, accepted)

    suspend fun applyContactEnrichmentCandidate(candidate: ContactEnrichmentCandidateEntity): Boolean = contacts.applyContactEnrichmentCandidate(candidate)

    fun observeRelationships(): Flow<List<RelationshipEdgeEntity>> = relationships.observeRelationships()
    fun observeTemporalRelationships(): Flow<List<RelationshipEpisodeEntity>> = relationships.observeTemporalRelationships()
    suspend fun saveConfirmedRelationship(
        fromContactId: String,
        toContactId: String,
        relationType: String,
        temporalState: String = "CURRENT",
        nowEpochMs: Long = System.currentTimeMillis(),
    ): String = relationships.saveConfirmedRelationship(fromContactId, toContactId, relationType, temporalState, nowEpochMs)
    suspend fun deleteConfirmedRelationship(edgeId: String, nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        relationships.deleteConfirmedRelationship(edgeId, nowEpochMs)
    suspend fun updateConfirmedRelationship(edgeId: String, relationType: String, nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        relationships.updateConfirmedRelationship(edgeId, relationType, nowEpochMs)
    fun observeRelationshipEvents(): Flow<List<RelationshipEventWithParticipants>> = relationships.observeRelationshipEvents()
    fun observeRelationshipEventsForContact(contactId: String): Flow<List<RelationshipEventWithParticipants>> =
        relationships.observeRelationshipEventsForContact(contactId)
    suspend fun saveConfirmedRelationshipEvent(
        eventId: String?,
        eventType: String,
        title: String,
        note: String?,
        occurredAtEpochMs: Long?,
        participants: List<RelationshipEventParticipantInput>,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): String = relationships.saveConfirmedRelationshipEvent(
        eventId,
        eventType,
        title,
        note,
        occurredAtEpochMs,
        participants,
        nowEpochMs,
    )
    suspend fun deleteConfirmedRelationshipEvent(eventId: String): Boolean = relationships.deleteConfirmedRelationshipEvent(eventId)

    private val contactFactSourceTypes = setOf(
        "USER_CONFIRMED",
        "USER_CONFIRMED_NOTIFICATION",
        "AGENT_DOMAIN_WRITE",
        "USER_CONFIRMED_MEMORY",
        "OBSERVED_NOTIFICATION",
        "INFERRED_NOTIFICATION",
    )
    private val contactFactTypes = setOf(
        "CONTACT_MEMORY",
        "IMPORTANT_DATE",
        "COMMUNICATION_PREFERENCE",
        "CURRENT_MATTER",
        "INTERACTION_SUMMARY",
    )

    fun observeContactFacts(contactId: String): Flow<List<FactEntity>> = daos.factDao.observeByContact(contactId, System.currentTimeMillis()).map { facts ->
        facts.filter { fact ->
            fact.sourceType in contactFactSourceTypes &&
                fact.factType in contactFactTypes &&
                fact.textContent.isNotBlank() &&
                fact.textContent != "待确认"
        }.map { fact ->
            fact.copy(
                textContent = normalizeContactFactForDisplay(fact.sourceType, fact.factType, fact.textContent),
            )
        }
    }

    suspend fun saveConfirmedContactFact(
        contactId: String,
        text: String,
        factType: String = "CONTACT_MEMORY",
        nowEpochMs: Long = System.currentTimeMillis(),
    ): String {
        require(daos.contactDao.findById(contactId) != null) { "联系人不存在" }
        require(text.isNotBlank() && text.trim().length <= 500) { "内容应为 1–500 个字" }
        val id = "contact-memory-${UUID.randomUUID()}"
        factIndex.upsert(
            FactEntity(
                factId = id,
                factType = factType,
                textContent = text.trim(),
                structuredDataJson = null,
                sourceType = "USER_CONFIRMED",
                sourceRef = "CONTACT_PROFILE",
                contactId = contactId,
                skillId = null,
                confidence = 1.0,
                sensitivity = "PERSONAL",
                status = "ACTIVE",
                ttlDays = 0,
                expiresAtEpochMs = null,
                createdAtEpochMs = nowEpochMs,
                updatedAtEpochMs = nowEpochMs,
            ),
        )
        return id
    }

    suspend fun deleteContactFact(factId: String): Boolean = transactions.runInTransaction {
        val deleted = factIndex.delete(factId)
        if (deleted && factId.startsWith(NOTIFICATION_EVIDENCE_PREFIX)) {
            daos.notificationCandidateDao.reopen(factId.removePrefix(NOTIFICATION_EVIDENCE_PREFIX))
        }
        deleted
    }

    private fun String?.cleanContactField(): String? = this?.trim()?.takeIf(String::isNotEmpty)

    private fun normalizeIdentityValue(value: String): String = value.lowercase().filterNot(Char::isWhitespace).trimStart('@')

    private companion object {
        const val NOTIFICATION_EVIDENCE_PREFIX = "notification-evidence:"
        const val AUTO_LINK_CONFIDENCE = 0.99
        const val AUTO_SCHEDULE_CONFIDENCE = 0.98
    }
}

internal fun stableContactKnowledgeId(vararg parts: String): String =
    UUID.nameUUIDFromBytes(parts.joinToString("\u001f").toByteArray(StandardCharsets.UTF_8)).toString()

internal fun fillMissingContactFields(canonical: ContactEntity, source: ContactEntity): ContactEntity = canonical.copy(
    phone = canonical.phone.ifNullOrBlank(source.phone),
    email = canonical.email.ifNullOrBlank(source.email),
    wechatId = canonical.wechatId.ifNullOrBlank(source.wechatId),
    company = canonical.company.ifNullOrBlank(source.company),
    title = canonical.title.ifNullOrBlank(source.title),
    aliasesJson = canonical.aliasesJson.takeUnless { it == "[]" } ?: source.aliasesJson,
    tagsJson = canonical.tagsJson.takeUnless { it == "[]" } ?: source.tagsJson,
    note = canonical.note.ifNullOrBlank(source.note),
    avatarUri = canonical.avatarUri.ifNullOrBlank(source.avatarUri),
    updatedAtEpochMs = maxOf(canonical.updatedAtEpochMs, source.updatedAtEpochMs),
)

private fun String?.ifNullOrBlank(fallback: String?): String? = this?.takeIf(String::isNotBlank) ?: fallback?.takeIf(String::isNotBlank)

internal fun canonicalizeRelationshipEvents(
    events: List<RelationshipEventWithParticipants>,
    links: List<ContactMergeLinkEntity>,
): List<RelationshipEventWithParticipants> {
    val canonicalBySource = links.associateBy(
        ContactMergeLinkEntity::sourceContactId,
        ContactMergeLinkEntity::canonicalContactId,
    )
    return events.map { value ->
        value.copy(
            participants = value.participants.map { participant ->
                participant.copy(contactId = participant.contactId?.let { canonicalBySource[it] ?: it })
            }.distinctBy { participant ->
                listOf(
                    participant.participantKind,
                    participant.contactId.orEmpty(),
                    participant.participantRole,
                    participant.displayNameSnapshot,
                ).joinToString("\u001f")
            },
        )
    }
}
