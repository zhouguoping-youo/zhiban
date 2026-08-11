package com.zhiban.rebuild.runtime.governance

import com.zhiban.rebuild.data.agent.AgentDatabase
import com.zhiban.rebuild.data.agent.ScheduleEntity
import com.zhiban.rebuild.runtime.context.FactEntity
import com.zhiban.rebuild.runtime.context.FactIndex
import com.zhiban.rebuild.runtime.runSuspendCatching
import com.zhiban.rebuild.runtime.tool.sha256
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Central undo dispatcher. Add each new reversible domain here instead of letting UI touch DAOs. */
internal class ChangeUndoCoordinator(private val database: AgentDatabase) {
    suspend fun undoInTransaction(changeId: String, runId: String, nowEpochMs: Long): ChangeLogEntity? {
        val change = database.changeLogDao().find(changeId) ?: return null
        if (change.runtimeRunId != runId || change.undoState != "AVAILABLE") return null
        val changed = applyInverse(change, nowEpochMs)
        if (!changed || database.changeLogDao().markUndone(changeId, nowEpochMs) != 1) return null
        return change
    }

    suspend fun undoVisibleInTransaction(changeId: String, nowEpochMs: Long): ChangeLogEntity? {
        if (database.changeLogDao().findAutoWriteReceipt(changeId) == null) return null
        val change = database.changeLogDao().find(changeId) ?: return null
        if (change.undoState != "AVAILABLE" ||
            change.originType !in setOf("SYSTEM_PERCEPTION", "RUNTIME_TOOL")
        ) {
            return null
        }
        if (!applyInverse(change, nowEpochMs) ||
            database.changeLogDao().markUndone(changeId, nowEpochMs) != 1
        ) {
            return null
        }
        return change
    }

    private suspend fun applyInverse(change: ChangeLogEntity, nowEpochMs: Long): Boolean = when (change.toolName) {
        "contact.createCandidate" -> {
            val deleted = database.contactDao().deleteAgentCandidate(change.targetId) == 1
            deleted && FactIndex(database).delete("contact:${change.targetId}")
        }

        "relationship.createCandidate" -> undoRelationshipCandidate(change, nowEpochMs)

        ContactProfileDomainWriter.TOOL_NAME -> restoreContactProfile(change, nowEpochMs)

        ContactIdentityResolutionDomainWriter.TOOL_NAME -> undoSourceIdentityResolution(change, nowEpochMs)

        "calendar.schedule.create" -> {
            val deleted = database.scheduleDao().deleteById(change.targetId) == 1
            deleted && FactIndex(database).delete("schedule:${change.targetId}")
        }

        "calendar.schedule.update" -> restoreSchedule(change, insert = false, nowEpochMs)

        "calendar.schedule.delete" -> restoreSchedule(change, insert = true, nowEpochMs)

        AutoWriteToolNames.INTERACTION_SUMMARY -> undoInteractionSummary(change)

        AutoWriteToolNames.CONTACT_TAG_ADD -> undoContactTag(change, nowEpochMs)

        AutoWriteToolNames.CONTACT_IDENTITY_AUTO_LINK -> undoAutomaticIdentityLink(change, nowEpochMs)

        AutoWriteToolNames.SCHEDULE_CREATE -> undoAutomaticSchedule(change)

        AutoWriteToolNames.CRM_LEAD_CANDIDATE -> undoCrmLeadCandidate(change)

        AutoWriteToolNames.CRM_ACTIVITY_APPEND -> undoCrmActivity(change)

        AutoWriteToolNames.CRM_NEXT_ACTION_CREATE -> undoCrmNextAction(change)

        AutoWriteToolNames.CRM_SUGGESTION_ACCEPT_ACTIVITY -> undoAcceptedSuggestionActivity(change, nowEpochMs)

        AutoWriteToolNames.CRM_SUGGESTION_ACCEPT_LEAD -> undoAcceptedSuggestionLead(change, nowEpochMs)

        else -> false
    }

    private suspend fun undoRelationshipCandidate(change: ChangeLogEntity, nowEpochMs: Long): Boolean {
        val edge = database.relationshipEdgeDao().find(change.targetId) ?: return false
        if (database.relationshipEdgeDao().deleteConfirmed(change.targetId) != 1) return false
        database.contactIntelligenceDao().closeOpenUserRelationships(
            edge.fromContactId,
            edge.toContactId,
            edge.relationType,
            nowEpochMs,
        )
        return true
    }

    private suspend fun undoSourceIdentityResolution(change: ChangeLogEntity, nowEpochMs: Long): Boolean {
        val inverse = parseInverse(change.inversePayloadJson) ?: return false
        val sourceIdentityId = inverse["sourceIdentityId"]?.jsonPrimitive?.content ?: return false
        val contactId = inverse["contactId"]?.jsonPrimitive?.content ?: return false
        val previousStatus = inverse["previousStatus"]?.jsonPrimitive?.content ?: return false
        val previousConfidence = inverse["previousConfidence"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return false
        val current = database.contactIntelligenceDao().findSourceIdentity(sourceIdentityId) ?: return false
        if (current.personId != contactId || current.resolutionStatus != "RESOLVED") return false
        if (sourceIdentityResolutionDigest(sourceIdentityId, contactId, current.confidence) != change.afterDigest) return false
        if (
            database.contactIntelligenceDao().restoreSourceIdentityResolution(
                sourceIdentityId,
                contactId,
                previousStatus,
                previousConfidence,
                nowEpochMs,
            ) != 1
        ) {
            return false
        }
        inverse["deletePlatformIdentityId"]?.jsonPrimitive?.content?.let { identityId ->
            database.contactIdentityDao().deleteConfirmedPlatformIdentity(identityId)
        }
        return true
    }

    private suspend fun undoInteractionSummary(change: ChangeLogEntity): Boolean {
        val current = database.factDao().find(change.targetId) ?: return false
        if (!changeDigestMatches(change.afterDigest, canonicalChangeDigest(current), current)) return false
        return FactIndex(database).delete(change.targetId)
    }

    private suspend fun undoContactTag(change: ChangeLogEntity, nowEpochMs: Long): Boolean {
        val current = database.contactDao().findRawById(change.targetId) ?: return false
        if (sha256(current.tagsJson) != change.afterDigest) return false
        val inverse =
            runSuspendCatching { Json.parseToJsonElement(change.inversePayloadJson).jsonObject }.getOrNull() ?: return false
        val removeTag = inverse["removeTag"]?.jsonPrimitive?.content ?: return false
        val tags = runSuspendCatching { Json.parseToJsonElement(current.tagsJson).jsonArray.map { it.jsonPrimitive.content } }
            .getOrNull() ?: return false
        if (removeTag !in tags) return false
        val updated = current.copy(
            tagsJson = buildJsonArray {
                tags.filterNot { it == removeTag }.forEach { add(JsonPrimitive(it)) }
            }.toString(),
            updatedAtEpochMs = nowEpochMs,
        )
        return database.contactDao().update(updated) == 1
    }

    private suspend fun undoAutomaticIdentityLink(change: ChangeLogEntity, nowEpochMs: Long): Boolean {
        val inverse = parseInverse(change.inversePayloadJson) ?: return false
        val sourceId = inverse["sourceContactId"]?.jsonPrimitive?.content ?: return false
        val canonicalId = inverse["canonicalContactId"]?.jsonPrimitive?.content ?: return false
        if (sourceId != change.targetId) return false
        return database.contactIdentityDao().undoAutomaticMerge(sourceId, canonicalId, nowEpochMs) == 1
    }

    private suspend fun undoAutomaticSchedule(change: ChangeLogEntity): Boolean {
        val current = database.scheduleDao().findById(change.targetId) ?: return false
        if (!changeDigestMatches(change.afterDigest, canonicalChangeDigest(current), current)) return false
        return database.scheduleDao().deleteById(change.targetId) == 1
    }

    private suspend fun undoCrmLeadCandidate(change: ChangeLogEntity): Boolean {
        val current = database.crmDao().findLead(change.targetId) ?: return false
        if (!changeDigestMatches(change.afterDigest, canonicalChangeDigest(current), current)) return false
        return database.crmDao().deleteAutoCandidateLead(change.targetId) == 1
    }

    private suspend fun undoCrmActivity(change: ChangeLogEntity): Boolean {
        val current = database.crmDao().findActivity(change.targetId) ?: return false
        if (!changeDigestMatches(change.afterDigest, canonicalChangeDigest(current), current)) return false
        return database.crmDao().deleteAutoActivity(change.targetId) == 1
    }

    private suspend fun undoCrmNextAction(change: ChangeLogEntity): Boolean {
        val current = database.crmDao().findAction(change.targetId) ?: return false
        if (!changeDigestMatches(change.afterDigest, canonicalChangeDigest(current), current)) return false
        return database.crmDao().deleteAutoAction(change.targetId) == 1
    }

    /** Inverse of accepting a CALL_FOLLOW_UP suggestion: restore the suggestion to PENDING and delete the activity it wrote. */
    private suspend fun undoAcceptedSuggestionActivity(change: ChangeLogEntity, nowEpochMs: Long): Boolean {
        val suggestion = database.crmDao().findSuggestion(change.targetId) ?: return false
        if (suggestion.status != com.zhiban.rebuild.data.crm.CrmSuggestionStatus.ACCEPTED) return false
        val inverse = parseInverse(change.inversePayloadJson) ?: return false
        val activityId = inverse["deleteActivityId"]?.jsonPrimitive?.content ?: return false
        val activity = database.crmDao().findActivity(activityId) ?: return false
        if (!changeDigestMatches(change.afterDigest, canonicalChangeDigest(activity), activity)) return false
        database.crmDao().deleteActivityById(activityId)
        return database.crmDao().transitionSuggestionStatus(
            change.targetId,
            com.zhiban.rebuild.data.crm.CrmSuggestionStatus.ACCEPTED,
            com.zhiban.rebuild.data.crm.CrmSuggestionStatus.PENDING,
            nowEpochMs,
        ) == 1
    }

    /** Inverse of accepting a NEW_LEAD suggestion: restore the suggestion to PENDING and delete the lead it created. */
    private suspend fun undoAcceptedSuggestionLead(change: ChangeLogEntity, nowEpochMs: Long): Boolean {
        val suggestion = database.crmDao().findSuggestion(change.targetId) ?: return false
        if (suggestion.status != com.zhiban.rebuild.data.crm.CrmSuggestionStatus.ACCEPTED) return false
        val inverse = parseInverse(change.inversePayloadJson) ?: return false
        val leadId = inverse["deleteLeadId"]?.jsonPrimitive?.content ?: return false
        val lead = database.crmDao().findLead(leadId) ?: return false
        if (!changeDigestMatches(change.afterDigest, canonicalChangeDigest(lead), lead)) return false
        database.crmDao().deleteLeadById(leadId)
        return database.crmDao().transitionSuggestionStatus(
            change.targetId,
            com.zhiban.rebuild.data.crm.CrmSuggestionStatus.ACCEPTED,
            com.zhiban.rebuild.data.crm.CrmSuggestionStatus.PENDING,
            nowEpochMs,
        ) == 1
    }

    private suspend fun parseInverse(inversePayloadJson: String) = runSuspendCatching { Json.parseToJsonElement(inversePayloadJson).jsonObject }.getOrNull()

    companion object {
        val AUTO_TOOL_NAMES: Set<String> = AutoWriteToolNames.all
    }

    private suspend fun restoreSchedule(change: ChangeLogEntity, insert: Boolean, nowEpochMs: Long): Boolean {
        val json =
            runSuspendCatching { Json.parseToJsonElement(change.inversePayloadJson).jsonObject }.getOrNull() ?: return false
        fun text(name: String): String? = json[name]?.jsonPrimitive?.content
        val restored = ScheduleEntity(
            id = text("id") ?: return false, title = text("title") ?: return false,
            startAtEpochMs = text("startAtEpochMs")?.toLongOrNull() ?: return false,
            durationMinutes = text("durationMinutes")?.toIntOrNull() ?: return false,
            note = json["note"]?.jsonPrimitive?.content,
            createdByRunId = json["createdByRunId"]?.jsonPrimitive?.content,
            createdByRuntimeRunId = json["createdByRuntimeRunId"]?.jsonPrimitive?.content,
            createdByRuntimeAttemptId = json["createdByRuntimeAttemptId"]?.jsonPrimitive?.content,
            createdAtEpochMs = text("createdAtEpochMs")?.toLongOrNull() ?: return false,
            updatedAtEpochMs = nowEpochMs,
        )
        val changed = if (insert) {
            runSuspendCatching {
                database.scheduleDao().insert(restored)
                true
            }.getOrDefault(false)
        } else {
            database.scheduleDao().update(restored) == 1
        }
        if (!changed) return false
        FactIndex(database).upsert(
            FactEntity(
                "schedule:${restored.id}", "CALENDAR_EVENT",
                "${restored.title}，开始时间=${restored.startAtEpochMs}，时长=${restored.durationMinutes}分钟",
                null, "UNDO", change.runtimeRunId, null, null, 1.0, "NORMAL", "ACTIVE", 0, null,
                restored.createdAtEpochMs, nowEpochMs,
            ),
        )
        return true
    }

    private suspend fun restoreContactProfile(change: ChangeLogEntity, nowEpochMs: Long): Boolean {
        val json =
            runSuspendCatching { Json.parseToJsonElement(change.inversePayloadJson).jsonObject }.getOrNull() ?: return false
        val clearFields = json["clearFields"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
        if (clearFields.any { it !in ContactProfileDomainWriter.PROFILE_FIELDS }) return false
        val contact = database.contactDao().findById(change.targetId) ?: return false
        if (contactProfileFieldsDigest(contact, clearFields) != change.afterDigest) return false
        val restored = contact.copy(
            phone = contact.phone.takeUnless { "phone" in clearFields },
            email = contact.email.takeUnless { "email" in clearFields },
            wechatId = contact.wechatId.takeUnless { "wechatId" in clearFields },
            company = contact.company.takeUnless { "company" in clearFields },
            title = contact.title.takeUnless { "title" in clearFields },
            note = contact.note.takeUnless { "note" in clearFields },
            updatedAtEpochMs = nowEpochMs,
        )
        if (clearFields.isNotEmpty() && database.contactDao().update(restored) != 1) return false
        val factId = json["deleteFactId"]?.jsonPrimitive?.content
        if (factId != null && !FactIndex(database).delete(factId)) return false
        return clearFields.isNotEmpty() || factId != null
    }
}
