package com.zhiban.rebuild.data.interaction

import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.config.AgentControlStore
import com.zhiban.rebuild.data.config.SilenceContactThresholds
import com.zhiban.rebuild.data.contact.ContactEntity
import com.zhiban.rebuild.data.contact.RelationshipEdgeEntity
import com.zhiban.rebuild.data.suggestion.AgentSuggestionEntity
import com.zhiban.rebuild.data.suggestion.AgentSuggestionNotifier
import com.zhiban.rebuild.data.suggestion.AgentSuggestionStatus
import com.zhiban.rebuild.data.suggestion.AgentSuggestionType
import com.zhiban.rebuild.relationship.RelationshipGroup
import com.zhiban.rebuild.relationship.RelationshipTaxonomy
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Produces at most one daily, aggregate reminder for contacts whose observed interaction has gone quiet. */
@Singleton
class SilentContactSuggestionScanner @Inject internal constructor(
    private val database: AgentDatabase,
    private val controls: AgentControlStore,
    private val notifier: AgentSuggestionNotifier,
) {
    suspend fun scan(nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        val matches = findSilentContacts(nowEpochMs, controls.silenceContactThresholds())
        if (matches.isEmpty()) return false
        val date = Instant.ofEpochMilli(nowEpochMs).atZone(ZoneId.systemDefault()).toLocalDate()
        val names = matches.take(MAX_NAMES_IN_SUMMARY).joinToString("、") { it.contact.displayName }
        val remaining = matches.size - MAX_NAMES_IN_SUMMARY
        val suffix = if (remaining > 0) "等 ${matches.size} 人" else ""
        val suggestion = AgentSuggestionEntity(
            suggestionId = UUID.randomUUID().toString(),
            type = AgentSuggestionType.SILENT_CONTACTS,
            title = "有些人值得联系一下",
            body = "$names$suffix，最近联系得比较少。可以看看是否需要问候。",
            contactId = null,
            candidateId = null,
            sourceEvent = SOURCE_EVENT,
            dedupeKey = "silent-contacts:$date",
            status = AgentSuggestionStatus.PENDING,
            createdAtEpochMs = nowEpochMs,
            updatedAtEpochMs = nowEpochMs,
        )
        val inserted = database.agentSuggestionDao().insert(suggestion) != -1L
        if (inserted) notifier.publish(database.agentSuggestionDao().pendingCount(), null, nowEpochMs)
        return inserted
    }

    private suspend fun findSilentContacts(nowEpochMs: Long, thresholds: SilenceContactThresholds): List<SilentContact> {
        val matches = mutableListOf<SilentContact>()
        var offset = 0
        while (true) {
            val page = database.contactInteractionDao().contactRecencyPage(nowEpochMs, PAGE_SIZE, offset)
            if (page.isEmpty()) break
            val ids = page.map(ContactInteractionRecency::contactId)
            val relationships = database.relationshipEdgeDao().ownerRelationships(ids)
                .groupBy { it.otherContactId() }
            val contacts = database.contactDao().findByIds(ids).associateBy(ContactEntity::contactId)
            page.forEach { recency ->
                val days = recency.silenceDays ?: return@forEach
                val threshold = thresholdDays(relationships[recency.contactId].orEmpty(), thresholds)
                val contact = contacts[recency.contactId] ?: return@forEach
                if (days >= threshold) matches += SilentContact(contact, days, threshold)
            }
            if (page.size < PAGE_SIZE) break
            offset += PAGE_SIZE
        }
        return matches.sortedWith(
            compareByDescending<SilentContact> { it.silenceDays - it.thresholdDays }
                .thenByDescending(SilentContact::silenceDays)
                .thenBy { it.contact.normalizedName },
        )
    }

    private fun thresholdDays(relationships: List<RelationshipEdgeEntity>, thresholds: SilenceContactThresholds): Int {
        val relationCodes = relationships.mapTo(linkedSetOf()) { it.relationType }
        return when {
            "CUSTOMER" in relationCodes -> thresholds.customerDays

            "CLOSE_FRIEND" in relationCodes || relationCodes.any {
                RelationshipTaxonomy.find(it)?.group == RelationshipGroup.FAMILY
            } -> thresholds.familyOrCloseFriendDays

            else -> thresholds.generalDays
        }
    }

    private fun RelationshipEdgeEntity.otherContactId(): String = if (fromContactId == SELF_CONTACT_ID) toContactId else fromContactId

    private data class SilentContact(val contact: ContactEntity, val silenceDays: Long, val thresholdDays: Int)

    private companion object {
        const val SELF_CONTACT_ID = "user:self"
        const val SOURCE_EVENT = "MAINTENANCE_SILENCE_SCAN"
        const val PAGE_SIZE = 250
        const val MAX_NAMES_IN_SUMMARY = 5
    }
}
