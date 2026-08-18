package com.zhiban.rebuild.runtime.tool

import com.zhiban.rebuild.data.contact.ContactDao
import com.zhiban.rebuild.data.crm.CrmDao
import com.zhiban.rebuild.foundation.RuntimeToolSpec
import com.zhiban.rebuild.provider.ProviderFailure
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class CrmOpportunityListToolBinding(override val spec: RuntimeToolSpec, private val crm: CrmDao) : RuntimeToolBinding {
    override suspend fun requestApproval(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): Boolean =
        throw ToolPolicyRejectedException("crm.opportunity.list is read-only")

    override suspend fun executeReadOnly(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): RoutedToolResult {
        val args = parseCrmArguments(request.argumentsJson, setOf("query", "stage", "status", "limit"))
        val query = args["query"]?.jsonPrimitive?.content?.trim().orEmpty()
        val stage = args["stage"]?.jsonPrimitive?.content?.trim()?.uppercase()
        val status = args["status"]?.jsonPrimitive?.content?.trim()?.uppercase()
        val limit = args["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 20
        if (
            query.length > 100 ||
            (stage != null && stage !in CRM_STAGES) ||
            (status != null && status !in CRM_STATUSES) ||
            limit !in 1..50
        ) {
            throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
        }
        val normalizedQuery = query.lowercase()
        val rows = crm.observeOpportunities().first()
            .asSequence()
            .filter { stage == null || it.stage == stage }
            .filter { status == null || it.status == status }
            .filter {
                normalizedQuery.isBlank() ||
                    it.title.lowercase().contains(normalizedQuery) ||
                    it.accountNameSnapshot.lowercase().contains(normalizedQuery)
            }
            .take(limit)
            .toList()
        val safe = buildJsonObject {
            put("count", rows.size)
            put(
                "opportunities",
                buildJsonArray {
                    rows.forEach { row ->
                        add(
                            buildJsonObject {
                                put("opportunityId", row.opportunityId)
                                put("title", row.title)
                                put("accountName", row.accountNameSnapshot)
                                put("stage", row.stage)
                                put("status", row.status)
                                row.valueMinor?.let { put("valueMinor", it) }
                                put("currencyCode", row.currencyCode)
                                put("probabilityPercent", row.probabilityPercent)
                                row.expectedCloseAtEpochMs?.let { put("expectedCloseAtEpochMs", it) }
                                row.primaryContactId?.let { put("primaryContactId", it) }
                                put("sourceType", row.sourceType)
                                put("updatedAtEpochMs", row.updatedAtEpochMs)
                            },
                        )
                    }
                },
            )
        }.toString()
        return RoutedToolResult(spec.name, request.providerCallId, safe)
    }
}

internal class CrmOpportunityDetailToolBinding(override val spec: RuntimeToolSpec, private val crm: CrmDao, private val contacts: ContactDao) :
    RuntimeToolBinding {
    override suspend fun requestApproval(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): Boolean =
        throw ToolPolicyRejectedException("crm.opportunity.get is read-only")

    override suspend fun executeReadOnly(request: RuntimeToolCallRequest, context: RuntimeToolRouteContext): RoutedToolResult {
        val args = parseCrmArguments(request.argumentsJson, setOf("opportunityId"))
        val opportunityId = args["opportunityId"]?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() && it.length <= 128 }
            ?: throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
        val opportunity = crm.findOpportunity(opportunityId)
        if (opportunity == null) {
            return RoutedToolResult(
                spec.name,
                request.providerCallId,
                buildJsonObject {
                    put("found", false)
                    put("opportunityId", opportunityId)
                }.toString(),
            )
        }
        val stakeholders = crm.observeStakeholders(opportunityId).first()
        val activities = crm.observeActivities(opportunityId).first().take(20)
        val actions = crm.observeActions(opportunityId).first().take(20)
        val suggestions = crm.observeSuggestions(opportunityId).first().take(10)
        val history = crm.observeStageHistory(opportunityId).first().take(20)
        val contactNames = stakeholders.map { it.contactId }.distinct().associateWith { id ->
            contacts.findById(id)?.displayName ?: "未知联系人"
        }
        val safe = buildJsonObject {
            put("found", true)
            put(
                "opportunity",
                buildJsonObject {
                    put("opportunityId", opportunity.opportunityId)
                    put("title", opportunity.title)
                    put("accountName", opportunity.accountNameSnapshot)
                    put("stage", opportunity.stage)
                    put("status", opportunity.status)
                    opportunity.valueMinor?.let { put("valueMinor", it) }
                    put("currencyCode", opportunity.currencyCode)
                    put("probabilityPercent", opportunity.probabilityPercent)
                    opportunity.expectedCloseAtEpochMs?.let { put("expectedCloseAtEpochMs", it) }
                    opportunity.productSummary?.let { put("productSummary", it.take(500)) }
                    opportunity.needSummary?.let { put("needSummary", it.take(1000)) }
                    opportunity.lossReason?.let { put("lossReason", it.take(500)) }
                    opportunity.primaryContactId?.let { put("primaryContactId", it) }
                    put("sourceType", opportunity.sourceType)
                },
            )
            put("stakeholders", stakeholdersJsonArray(stakeholders, contactNames))
            put("nextActions", actionsJsonArray(actions))
            put("activities", activitiesJsonArray(activities))
            put("agentSuggestions", suggestionsJsonArray(suggestions))
            put("stageHistory", stageHistoryJsonArray(history))
        }.toString()
        return RoutedToolResult(spec.name, request.providerCallId, safe)
    }

    private fun stakeholdersJsonArray(
        rows: List<com.zhiban.rebuild.data.crm.CrmOpportunityStakeholderEntity>,
        contactNames: Map<String, String>,
    ): kotlinx.serialization.json.JsonArray = buildJsonArray {
        rows.forEach { row ->
            add(
                buildJsonObject {
                    put("contactId", row.contactId)
                    put("displayName", contactNames.getValue(row.contactId))
                    put("roleType", row.roleType)
                    put("influenceLevel", row.influenceLevel)
                    put("userConfirmed", row.userConfirmed)
                },
            )
        }
    }

    private fun actionsJsonArray(rows: List<com.zhiban.rebuild.data.crm.CrmNextActionEntity>): kotlinx.serialization.json.JsonArray = buildJsonArray {
        rows.forEach { row ->
            add(
                buildJsonObject {
                    put("actionId", row.actionId)
                    put("title", row.title)
                    put("actionType", row.actionType)
                    put("status", row.status)
                    put("priority", row.priority)
                    row.dueAtEpochMs?.let { put("dueAtEpochMs", it) }
                    row.scheduleId?.let { put("scheduleId", it) }
                    row.rationale?.let { put("rationale", it.take(500)) }
                },
            )
        }
    }

    private fun activitiesJsonArray(rows: List<com.zhiban.rebuild.data.crm.CrmActivityEntity>): kotlinx.serialization.json.JsonArray = buildJsonArray {
        rows.forEach { row ->
            add(
                buildJsonObject {
                    put("activityId", row.activityId)
                    put("activityType", row.activityType)
                    put("title", row.title)
                    put("summary", row.summary.take(1000))
                    put("occurredAtEpochMs", row.occurredAtEpochMs)
                    put("sourceType", row.sourceType)
                    row.evidenceSummary?.let { put("evidenceSummary", it.take(500)) }
                    put("userConfirmed", row.userConfirmed)
                },
            )
        }
    }

    private fun suggestionsJsonArray(rows: List<com.zhiban.rebuild.data.crm.CrmAgentSuggestionEntity>): kotlinx.serialization.json.JsonArray = buildJsonArray {
        rows.forEach { row ->
            add(
                buildJsonObject {
                    put("suggestionId", row.suggestionId)
                    put("suggestionType", row.suggestionType)
                    put("title", row.title)
                    put("summary", row.summary.take(1000))
                    put("rationale", row.rationale.take(1000))
                    put("confidence", row.confidence)
                    put("status", row.status)
                },
            )
        }
    }

    private fun stageHistoryJsonArray(rows: List<com.zhiban.rebuild.data.crm.CrmStageHistoryEntity>): kotlinx.serialization.json.JsonArray = buildJsonArray {
        rows.forEach { row ->
            add(
                buildJsonObject {
                    put("historyId", row.historyId)
                    row.fromStage?.let { put("fromStage", it) }
                    put("toStage", row.toStage)
                    row.reason?.let { put("reason", it.take(500)) }
                    put("sourceType", row.sourceType)
                    put("userConfirmed", row.userConfirmed)
                    put("changedAtEpochMs", row.changedAtEpochMs)
                },
            )
        }
    }
}

private val CRM_STAGES = setOf("LEAD", "CONTACTED", "QUALIFIED", "PROPOSAL", "NEGOTIATION", "WON", "LOST")
private val CRM_STATUSES = setOf("OPEN", "WON", "LOST")

private fun parseCrmArguments(value: String, allowedKeys: Set<String>) = runCatching { Json.parseToJsonElement(value).jsonObject }
    .getOrElse { throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false) }
    .also { args ->
        if (args.keys.any { it !in allowedKeys }) throw ProviderFailure("INVALID_TOOL_ARGUMENTS", false)
    }
